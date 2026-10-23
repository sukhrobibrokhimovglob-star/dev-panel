package io.github.devpanel.metrics;

import io.github.devpanel.metrics.DevMetricsService.Disk;
import io.github.devpanel.metrics.HikariPools.PoolState;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The last {@code devpanel.metrics.retention} of samples, kept in memory (24 h at the default
 * 15 s interval is 5,760 small points). Lost on restart by design: no table, no external system.
 * Long ranges are averaged down to at most {@value #MAX_POINTS} points before they are returned.
 */
public class DevMetricsHistory {

  private static final Logger log = LoggerFactory.getLogger(DevMetricsHistory.class);

  static final int MAX_POINTS = 360;

  private final DevMetricsService metricsService;
  private final Duration interval;
  private final int capacity;
  private final Deque<Point> points = new ArrayDeque<>();

  public DevMetricsHistory(DevMetricsService metricsService, Duration interval,
      Duration retention) {
    this.metricsService = metricsService;
    this.interval = interval;
    this.capacity = (int) Math.max(1, retention.toSeconds() / Math.max(1, interval.toSeconds()));
  }

  public Duration interval() {
    return interval;
  }

  /** Called by the panel's scheduler every {@link #interval()}. */
  public void sample() {
    try {
      Point point = take();
      synchronized (points) {
        points.addLast(point);
        while (points.size() > capacity) {
          points.removeFirst();
        }
      }
    } catch (RuntimeException e) {
      log.debug("Dev panel metrics sample failed: {}", e.getMessage());
    }
  }

  public History last(Duration range) {
    Instant from = Instant.now().minus(range);
    List<Point> selected = new ArrayList<>();
    synchronized (points) {
      for (Point point : points) {
        if (!point.time().isBefore(from)) {
          selected.add(point);
        }
      }
    }
    return new History(interval.toSeconds(), downsample(selected));
  }

  private Point take() {
    var os = DevMetricsService.os();
    var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
    int active = 0;
    int idle = 0;
    int pending = 0;
    int max = 0;
    for (PoolState pool : metricsService.hikari()) {
      active += pool.active();
      idle += pool.idle();
      pending += pool.pending();
      max += pool.max();
    }
    double diskUsed = 0;
    for (Disk disk : DevMetricsService.disks()) {
      if (disk.totalSpace() > 0) {
        diskUsed = Math.max(diskUsed,
            (double) (disk.totalSpace() - disk.usableSpace()) / disk.totalSpace());
      }
    }
    long openFiles = os instanceof com.sun.management.UnixOperatingSystemMXBean unix
        ? unix.getOpenFileDescriptorCount() : -1;
    return new Point(Instant.now(), os.getProcessCpuLoad(), os.getCpuLoad(), heap.getUsed(),
        heap.getMax(), ManagementFactory.getThreadMXBean().getThreadCount(), active, idle,
        pending, max, diskUsed,
        ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed(),
        ManagementFactory.getClassLoadingMXBean().getLoadedClassCount(), openFiles);
  }

  /** Averages consecutive points into buckets; peaks of pending connections are kept as max. */
  static List<Point> downsample(List<Point> points) {
    if (points.size() <= MAX_POINTS) {
      return points;
    }
    int bucket = (int) Math.ceil(points.size() / (double) MAX_POINTS);
    List<Point> result = new ArrayList<>();
    for (int start = 0; start < points.size(); start += bucket) {
      List<Point> group = points.subList(start, Math.min(start + bucket, points.size()));
      int n = group.size();
      result.add(new Point(
          group.get(n - 1).time(),
          group.stream().mapToDouble(Point::cpuProcess).sum() / n,
          group.stream().mapToDouble(Point::cpuSystem).sum() / n,
          (long) group.stream().mapToLong(Point::heapUsed).average().orElse(0),
          group.get(n - 1).heapMax(),
          (int) Math.round(group.stream().mapToInt(Point::threads).average().orElse(0)),
          (int) Math.round(group.stream().mapToInt(Point::dbActive).average().orElse(0)),
          (int) Math.round(group.stream().mapToInt(Point::dbIdle).average().orElse(0)),
          group.stream().mapToInt(Point::dbPending).max().orElse(0),
          group.get(n - 1).dbMax(),
          group.stream().mapToDouble(Point::diskUsedRatio).max().orElse(0),
          group.get(n - 1).nonHeapUsed(),
          group.get(n - 1).loadedClasses(),
          group.get(n - 1).openFiles()));
    }
    return result;
  }

  /** {@code openFiles} is -1 where the platform does not report it (Windows). */
  public record Point(Instant time, double cpuProcess, double cpuSystem, long heapUsed,
                      long heapMax, int threads, int dbActive, int dbIdle, int dbPending,
                      int dbMax, double diskUsedRatio, long nonHeapUsed, int loadedClasses,
                      long openFiles) {
  }

  /** The raw samples of the last {@code range}, for trend checks. */
  public List<Point> raw(Duration range) {
    Instant from = Instant.now().minus(range);
    List<Point> selected = new ArrayList<>();
    synchronized (points) {
      for (Point point : points) {
        if (!point.time().isBefore(from)) {
          selected.add(point);
        }
      }
    }
    return selected;
  }

  public record History(long sampleSeconds, List<Point> points) {
  }
}
