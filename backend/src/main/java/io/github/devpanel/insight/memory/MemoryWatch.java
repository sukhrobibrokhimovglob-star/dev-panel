package io.github.devpanel.insight.memory;

import com.sun.management.GarbageCollectionNotificationInfo;
import io.github.devpanel.insight.Trend;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;

/**
 * Memory leak detection. Heap usage alone always goes up and down; what tells a leak is the heap
 * left right after each garbage collection. The lowest such value per 10 minutes is the "floor";
 * a floor that keeps rising for hours means objects that are never freed. Also reports how much
 * time goes into garbage collection, which climbs as the heap fills up.
 */
public class MemoryWatch implements NotificationListener {

  private static final long WINDOW_MS = 10 * 60_000;
  private static final long KEEP_MS = 24 * 3_600_000L;
  private static final int MAX_EVENTS = 20_000;
  /** The trend is judged on this much floor history. */
  private static final long TREND_MS = 6 * 3_600_000L;

  private final Set<String> heapPools = new HashSet<>();
  private final Deque<GcEvent> events = new ArrayDeque<>();
  private final List<NotificationEmitter> emitters = new ArrayList<>();

  public void install() {
    for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
      if (pool.getType() == MemoryType.HEAP) {
        heapPools.add(pool.getName());
      }
    }
    for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
      if (gc instanceof NotificationEmitter emitter) {
        emitter.addNotificationListener(this, null, null);
        emitters.add(emitter);
      }
    }
  }

  public void uninstall() {
    for (NotificationEmitter emitter : emitters) {
      try {
        emitter.removeNotificationListener(this);
      } catch (Exception ignored) {
        // already gone
      }
    }
  }

  @Override
  public void handleNotification(Notification notification, Object handback) {
    if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION
        .equals(notification.getType())) {
      return;
    }
    GarbageCollectionNotificationInfo info =
        GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());
    long after = 0;
    for (Map.Entry<String, MemoryUsage> pool : info.getGcInfo().getMemoryUsageAfterGc()
        .entrySet()) {
      if (heapPools.contains(pool.getKey())) {
        after += pool.getValue().getUsed();
      }
    }
    long now = System.currentTimeMillis();
    synchronized (events) {
      events.addLast(new GcEvent(now, info.getGcName(), info.getGcAction(), info.getGcCause(),
          info.getGcInfo().getDuration(), after));
      while (events.size() > MAX_EVENTS
          || (!events.isEmpty() && events.peekFirst().time() < now - KEEP_MS)) {
        events.removeFirst();
      }
    }
  }

  public Analysis analysis() {
    long now = System.currentTimeMillis();
    long heapMax = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax();
    List<FloorPoint> floor = floor();
    List<Long> times = new ArrayList<>();
    List<Double> values = new ArrayList<>();
    for (FloorPoint point : floor) {
      if (point.time() >= now - TREND_MS) {
        times.add(point.time());
        values.add((double) point.bytes());
      }
    }
    Trend trend = Trend.of(times, values);
    double growth = values.size() < 2 ? 0 : values.get(values.size() - 1) - values.get(0);
    boolean leakSuspected = heapMax > 0 && values.size() >= 6 && trend.slopePerHour() > 0
        && trend.r2() >= 0.7 && growth >= heapMax * 0.10;

    long pauseMs = 0;
    long maxPauseMs = 0;
    int collections = 0;
    synchronized (events) {
      for (GcEvent event : events) {
        if (event.time() >= now - 5 * 60_000) {
          pauseMs += event.durationMs();
          maxPauseMs = Math.max(maxPauseMs, event.durationMs());
          collections++;
        }
      }
    }
    double gcShare = pauseMs / (5 * 60_000.0);
    double hoursToFull = leakSuspected ? trend.hoursUntil(heapMax) : -1;
    return new Analysis(heapMax, floor, trend.slopePerHour(), trend.r2(), leakSuspected,
        hoursToFull, gcShare, maxPauseMs, collections);
  }

  /** The lowest heap-after-GC per 10-minute window, oldest first. */
  private List<FloorPoint> floor() {
    List<FloorPoint> points = new ArrayList<>();
    synchronized (events) {
      long window = -1;
      long lowest = Long.MAX_VALUE;
      for (GcEvent event : events) {
        long eventWindow = event.time() / WINDOW_MS;
        if (eventWindow != window && window >= 0) {
          points.add(new FloorPoint(window * WINDOW_MS + WINDOW_MS / 2, lowest));
          lowest = Long.MAX_VALUE;
        }
        window = eventWindow;
        lowest = Math.min(lowest, event.heapAfter());
      }
      if (window >= 0) {
        points.add(new FloorPoint(window * WINDOW_MS + WINDOW_MS / 2, lowest));
      }
    }
    return points;
  }

  public List<GcEvent> recentCollections(int limit) {
    List<GcEvent> recent = new ArrayList<>();
    synchronized (events) {
      events.descendingIterator().forEachRemaining(event -> {
        if (recent.size() < limit) {
          recent.add(event);
        }
      });
    }
    return recent;
  }

  public record GcEvent(long time, String name, String action, String cause, long durationMs,
                        long heapAfter) {

    public Instant at() {
      return Instant.ofEpochMilli(time);
    }
  }

  public record FloorPoint(long time, long bytes) {
  }

  /**
   * {@code floorGrowthPerHour} in bytes; {@code hoursToFull} -1 unless a leak is suspected;
   * {@code gcShare}: share of the last 5 minutes spent in GC pauses (0..1).
   */
  public record Analysis(long heapMax, List<FloorPoint> floor, double floorGrowthPerHour,
                         double confidence, boolean leakSuspected, double hoursToFull,
                         double gcShare, long maxPauseMs, int collectionsLast5Min) {
  }
}
