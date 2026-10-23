package io.github.devpanel.insight.requests;

import io.github.devpanel.insight.LatencyHistogram;
import io.github.devpanel.insight.MinuteCounter;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per endpoint ({@code GET /v1/patients/{id}}, not every concrete URL): calls, errors, response
 * time percentiles and SQL statements per call, in hourly buckets for the last 48 hours. Also the
 * slowest recent requests and requests that look like N+1 queries, each with its trace id.
 */
public class RequestStats {

  private static final int HOURS = 48;
  private static final int KEEP_SLOW = 100;
  private static final int KEEP_N_PLUS_ONE = 100;
  private static final int MAX_ENDPOINTS = 1000;

  private final long slowThresholdMs;
  private final int nPlusOneThreshold;
  private final Map<Long, Map<String, Bucket>> hours = new HashMap<>();
  private final Deque<SlowRequest> slow = new ArrayDeque<>();
  private final Deque<NPlusOne> nPlusOne = new ArrayDeque<>();
  private final MinuteCounter requests = new MinuteCounter();
  private final MinuteCounter serverErrors = new MinuteCounter();

  public RequestStats(long slowThresholdMs, int nPlusOneThreshold) {
    this.slowThresholdMs = slowThresholdMs;
    this.nPlusOneThreshold = nPlusOneThreshold;
  }

  void record(String endpoint, int status, long ms, SqlCounter sql, String traceId, long now) {
    requests.add(now, 1);
    if (status >= 500) {
      serverErrors.add(now, 1);
    }
    long hour = now / 3_600_000;
    synchronized (this) {
      Map<String, Bucket> endpoints = hours.computeIfAbsent(hour, key -> new HashMap<>());
      hours.keySet().removeIf(old -> old <= hour - HOURS);
      Bucket bucket = endpoints.get(endpoint);
      if (bucket == null && endpoints.size() < MAX_ENDPOINTS) {
        bucket = new Bucket();
        endpoints.put(endpoint, bucket);
      }
      if (bucket != null) {
        bucket.add(status, ms, sql == null ? 0 : sql.total());
      }
      if (ms >= slowThresholdMs) {
        slow.addFirst(new SlowRequest(Instant.ofEpochMilli(now), endpoint, status, ms,
            sql == null ? 0 : sql.total(), traceId));
        if (slow.size() > KEEP_SLOW) {
          slow.removeLast();
        }
      }
      Map.Entry<String, Integer> repeated = sql == null ? null : sql.mostRepeated();
      if (repeated != null && repeated.getValue() >= nPlusOneThreshold) {
        nPlusOne.addFirst(new NPlusOne(Instant.ofEpochMilli(now), endpoint, sql.total(),
            repeated.getValue(), repeated.getKey(), traceId));
        if (nPlusOne.size() > KEEP_N_PLUS_ONE) {
          nPlusOne.removeLast();
        }
      }
    }
  }

  /** Endpoints over the last {@code lastHours} hours, busiest first. */
  public synchronized List<EndpointView> endpoints(long now, int lastHours) {
    Map<String, Bucket> merged = merge(now, lastHours, 0);
    Map<String, Bucket> lastHour = merge(now, 1, 0);
    List<EndpointView> views = new ArrayList<>();
    merged.forEach((endpoint, bucket) -> {
      Bucket recent = lastHour.get(endpoint);
      views.add(bucket.view(endpoint, recent == null ? 0 : recent.latency.percentileMs(95)));
    });
    views.sort(Comparator.comparingLong(EndpointView::calls).reversed());
    return views;
  }

  /** The same endpoint's p95 for one past hour, e.g. the same hour yesterday. */
  public synchronized Map<String, Long> p95At(long hourStartMillis) {
    Map<String, Long> result = new HashMap<>();
    Map<String, Bucket> endpoints = hours.get(hourStartMillis / 3_600_000);
    if (endpoints != null) {
      endpoints.forEach((endpoint, bucket) -> result.put(endpoint,
          bucket.latency.percentileMs(95)));
    }
    return result;
  }

  /** Buckets of whole past hours, for {@link EndpointHistory} to store. */
  synchronized Map<String, EndpointView> hour(long hour) {
    Map<String, EndpointView> views = new HashMap<>();
    Map<String, Bucket> endpoints = hours.get(hour);
    if (endpoints != null) {
      endpoints.forEach((endpoint, bucket) -> views.put(endpoint, bucket.view(endpoint, 0)));
    }
    return views;
  }

  public synchronized List<SlowRequest> slowRequests() {
    return new ArrayList<>(slow);
  }

  public synchronized List<NPlusOne> nPlusOneSuspects() {
    return new ArrayList<>(nPlusOne);
  }

  public long requestsInLast(long now, int minutes) {
    return requests.sum(now, minutes);
  }

  public long serverErrorsInLast(long now, int minutes) {
    return serverErrors.sum(now, minutes);
  }

  public long[] requestsPerMinute(long now, int minutes) {
    return requests.series(now, minutes);
  }

  public long[] serverErrorsPerMinute(long now, int minutes) {
    return serverErrors.series(now, minutes);
  }

  private Map<String, Bucket> merge(long now, int lastHours, int skipHours) {
    long current = now / 3_600_000;
    Map<String, Bucket> merged = new HashMap<>();
    for (long hour = current - lastHours + 1; hour <= current - skipHours; hour++) {
      Map<String, Bucket> endpoints = hours.get(hour);
      if (endpoints != null) {
        endpoints.forEach((endpoint, bucket) ->
            merged.computeIfAbsent(endpoint, key -> new Bucket()).merge(bucket));
      }
    }
    return merged;
  }

  private static final class Bucket {

    final LatencyHistogram latency = new LatencyHistogram();
    long clientErrors;
    long serverErrors;
    long sqlTotal;
    long sqlMax;

    void add(int status, long ms, int sql) {
      latency.record(ms);
      if (status >= 500) {
        serverErrors++;
      } else if (status >= 400) {
        clientErrors++;
      }
      sqlTotal += sql;
      sqlMax = Math.max(sqlMax, sql);
    }

    void merge(Bucket other) {
      latency.merge(other.latency);
      clientErrors += other.clientErrors;
      serverErrors += other.serverErrors;
      sqlTotal += other.sqlTotal;
      sqlMax = Math.max(sqlMax, other.sqlMax);
    }

    EndpointView view(String endpoint, long p95LastHour) {
      long calls = latency.count();
      return new EndpointView(endpoint, calls, serverErrors, clientErrors,
          latency.averageMs(), latency.percentileMs(50), latency.percentileMs(95),
          latency.percentileMs(99), latency.maxMs(), p95LastHour,
          calls == 0 ? 0 : (double) sqlTotal / calls, sqlMax);
    }
  }

  /** Times in ms; {@code sqlPerCall} counts statements Hibernate ran (JPA only). */
  public record EndpointView(String endpoint, long calls, long serverErrors, long clientErrors,
                             long avgMs, long p50Ms, long p95Ms, long p99Ms, long maxMs,
                             long p95LastHourMs, double sqlPerCall, long sqlMax) {
  }

  public record SlowRequest(Instant time, String endpoint, int status, long ms, int sql,
                            String traceId) {
  }

  /** {@code repeats}: how often the same statement shape ran in this one request. */
  public record NPlusOne(Instant time, String endpoint, int sqlTotal, int repeats,
                         String statement, String traceId) {
  }
}
