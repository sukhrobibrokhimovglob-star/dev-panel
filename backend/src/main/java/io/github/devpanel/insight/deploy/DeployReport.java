package io.github.devpanel.insight.deploy;

import io.github.devpanel.insight.logs.ExceptionGroups;
import io.github.devpanel.insight.logs.ExceptionGroups.GroupView;
import io.github.devpanel.insight.requests.EndpointHistory;
import io.github.devpanel.insight.requests.EndpointHistory.Before;
import io.github.devpanel.insight.requests.RequestStats;
import io.github.devpanel.insight.requests.RequestStats.EndpointView;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * "What did this deploy change?": per endpoint, p95 and SQL per call since this start against
 * the week before it, the error rate before and after, and exceptions never seen before.
 * Only endpoints with enough calls on both sides are judged.
 */
public class DeployReport {

  private static final int MIN_CALLS = 5;
  /** Faster or slower: at least this factor and this many ms apart. */
  private static final double FACTOR = 1.3;
  private static final long MIN_DIFF_MS = 50;

  private final RequestStats requests;
  private final EndpointHistory history;
  private final ExceptionGroups exceptions;

  public DeployReport(RequestStats requests, EndpointHistory history,
      ExceptionGroups exceptions) {
    this.requests = requests;
    this.history = history;
    this.exceptions = exceptions;
  }

  public Report build() {
    Instant deployedAt = exceptions.started();
    long now = System.currentTimeMillis();
    Map<String, EndpointView> since = requests.since(deployedAt.toEpochMilli(), now);
    Map<String, Before> before = history.beforeDeploy(deployedAt);

    List<Change> faster = new ArrayList<>();
    List<Change> slower = new ArrayList<>();
    List<Change> unchanged = new ArrayList<>();
    long callsNow = 0;
    long errorsNow = 0;
    for (EndpointView view : since.values()) {
      callsNow += view.calls();
      errorsNow += view.serverErrors();
      Before old = before.get(view.endpoint());
      if (old == null || old.calls() < MIN_CALLS || view.calls() < MIN_CALLS) {
        continue;
      }
      Change change = new Change(view.endpoint(), old.calls(), view.calls(), old.p95Ms(),
          view.p95Ms(), old.sqlPerCall(), view.sqlPerCall());
      if (view.p95Ms() * FACTOR <= old.p95Ms() && old.p95Ms() - view.p95Ms() >= MIN_DIFF_MS) {
        faster.add(change);
      } else if (view.p95Ms() >= old.p95Ms() * FACTOR
          && view.p95Ms() - old.p95Ms() >= MIN_DIFF_MS) {
        slower.add(change);
      } else {
        unchanged.add(change);
      }
    }
    faster.sort(Comparator.comparingDouble(Change::ratio));
    slower.sort(Comparator.comparingDouble(Change::ratio).reversed());

    long callsBefore = before.values().stream().mapToLong(Before::calls).sum();
    long errorsBefore = before.values().stream().mapToLong(Before::serverErrors).sum();
    List<GroupView> newErrors = exceptions.list().stream().filter(GroupView::isNew).toList();
    return new Report(deployedAt, Duration.between(deployedAt, Instant.now()).toMinutes(),
        faster, slower, unchanged.size(), callsBefore, errorsBefore, callsNow, errorsNow,
        newErrors, before.isEmpty());
  }

  /** {@code ratio}: p95 now / before (below 1 is faster). */
  public record Change(String endpoint, long callsBefore, long callsNow, long p95BeforeMs,
                       long p95NowMs, double sqlBefore, double sqlNow) {

    public double ratio() {
      return p95BeforeMs == 0 ? 1 : (double) p95NowMs / p95BeforeMs;
    }
  }

  /** {@code noHistory}: nothing stored from before this start yet (first deploy with the panel). */
  public record Report(Instant deployedAt, long minutesRunning, List<Change> faster,
                       List<Change> slower, int unchanged, long callsBefore, long errorsBefore,
                       long callsNow, long errorsNow, List<GroupView> newErrors,
                       boolean noHistory) {
  }
}
