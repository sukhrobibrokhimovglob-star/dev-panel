package io.github.devpanel.insight.risk;

import io.github.devpanel.insight.risk.Risk.Severity;
import io.github.devpanel.insight.risk.RiskStore.View;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs every risk detector on the panel's thread, keeps their findings' lifecycle in
 * {@link RiskStore} and hands urgent ones to the alerts: critical risks and limits reached within
 * a week, once per severity, unless someone snoozed or accepted them.
 */
public class RiskEngine {

  private static final Logger log = LoggerFactory.getLogger(RiskEngine.class);
  private static final double URGENT_DAYS = 7;

  private final List<RiskDetector> detectors;
  private final RiskStore store;
  private final Map<String, String> failures = new LinkedHashMap<>();
  private volatile Instant lastRun;
  private volatile long lastRunMs;
  private volatile Consumer<View> urgentListener = view -> { };

  public RiskEngine(List<RiskDetector> detectors, RiskStore store) {
    this.detectors = detectors;
    this.store = store;
  }

  public void onUrgent(Consumer<View> listener) {
    this.urgentListener = listener;
  }

  public synchronized void run() {
    long start = System.currentTimeMillis();
    List<Risk> risks = new ArrayList<>();
    Set<String> succeeded = new HashSet<>();
    Map<String, String> failed = new LinkedHashMap<>();
    for (RiskDetector detector : detectors) {
      try {
        risks.addAll(detector.detect());
        succeeded.add(detector.name());
      } catch (RuntimeException e) {
        failed.put(detector.name(), e.getClass().getSimpleName() + ": " + e.getMessage());
        log.debug("Dev panel risk detector {} failed", detector.name(), e);
      }
    }
    store.sync(risks, succeeded, Instant.now());
    synchronized (failures) {
      failures.clear();
      failures.putAll(failed);
    }
    lastRun = Instant.now();
    lastRunMs = System.currentTimeMillis() - start;
    for (View view : store.all()) {
      if (urgent(view) && (view.alerted() == null
          || view.alerted().rank() < view.risk().severity().rank())) {
        store.alerted(view.risk().key(), view.risk().severity());
        try {
          urgentListener.accept(view);
        } catch (RuntimeException e) {
          log.debug("Dev panel risk alert failed", e);
        }
      }
    }
  }

  /** Open risks, most important first (silenced ones last), then recently resolved ones. */
  public Overview overview() {
    List<View> all = store.all();
    List<View> open = new ArrayList<>(all.stream().filter(View::open).toList());
    open.sort(Comparator.comparing(View::silenced)
        .thenComparing(Comparator.comparingDouble(View::score).reversed()));
    List<View> resolved = new ArrayList<>(all.stream().filter(view -> !view.open()).toList());
    resolved.sort(Comparator.comparing(View::resolvedAt).reversed());
    Map<String, String> failed;
    synchronized (failures) {
      failed = new LinkedHashMap<>(failures);
    }
    return new Overview(open, resolved.subList(0, Math.min(50, resolved.size())), lastRun,
        lastRunMs, failed, counts(open));
  }

  /** Risks that appeared and went away since {@code since} (e.g. a deploy). */
  public Changes changesSince(Instant since) {
    List<View> appeared = new ArrayList<>();
    List<View> resolved = new ArrayList<>();
    for (View view : store.all()) {
      if (view.open() && view.firstSeen() != null && view.firstSeen().isAfter(since)) {
        appeared.add(view);
      } else if (!view.open() && view.resolvedAt().isAfter(since)) {
        resolved.add(view);
      }
    }
    appeared.sort(Comparator.comparingDouble(View::score).reversed());
    return new Changes(appeared, resolved);
  }

  public void snooze(String key, int days) {
    store.snooze(key, Instant.now().plus(Duration.ofDays(Math.max(1, Math.min(days, 365)))));
  }

  public void accept(String key, String by, String reason) {
    store.accept(key, by, reason == null || reason.isBlank() ? "accepted" : reason.trim());
  }

  public void reopen(String key) {
    store.reopen(key);
  }

  public Instant lastRun() {
    return lastRun;
  }

  static boolean urgent(View view) {
    if (!view.open() || view.silenced()) {
      return false;
    }
    Risk risk = view.risk();
    return risk.severity() == Severity.CRITICAL
        || (risk.daysLeft() != null && risk.daysLeft() <= URGENT_DAYS
            && risk.severity() != Severity.INFO);
  }

  private static Map<String, Long> counts(List<View> open) {
    Map<String, Long> counts = new LinkedHashMap<>();
    for (Severity severity : Severity.values()) {
      counts.put(severity.name(), open.stream().filter(view -> !view.silenced()
          && view.risk().severity() == severity).count());
    }
    counts.put("SILENCED", open.stream().filter(View::silenced).count());
    counts.put("FORECAST", open.stream().filter(view -> !view.silenced()
        && view.risk().category() == Risk.Category.FORECAST).count());
    counts.put("WEAKNESS", open.stream().filter(view -> !view.silenced()
        && view.risk().category() == Risk.Category.WEAKNESS).count());
    return counts;
  }

  /** {@code failures}: detectors that could not run last time, with why. */
  public record Overview(List<View> open, List<View> resolved, Instant lastRun, long lastRunMs,
                         Map<String, String> failures, Map<String, Long> counts) {
  }

  public record Changes(List<View> appeared, List<View> resolved) {
  }
}
