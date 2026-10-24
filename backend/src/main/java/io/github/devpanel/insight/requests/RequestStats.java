package io.github.devpanel.insight.requests;

import io.github.devpanel.insight.LatencyHistogram;
import io.github.devpanel.insight.MinuteCounter;
import io.github.devpanel.insight.trace.RequestRecorder;
import io.github.devpanel.insight.trace.RequestRecorder.Repeated;
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
 * time percentiles, and where the time goes (database, external calls, the application's own
 * code), rows read, response size and wasted (repeated) queries, in hourly buckets for the last
 * 48 hours. Also the slowest recent requests and the N+1 suspects, each with its trace id.
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

  /** @param nPlusOneThreshold wasted (repeated) queries in one request that count as N+1 */
  public RequestStats(long slowThresholdMs, int nPlusOneThreshold) {
    this.slowThresholdMs = slowThresholdMs;
    this.nPlusOneThreshold = nPlusOneThreshold;
  }

  public long slowThresholdMs() {
    return slowThresholdMs;
  }

  public boolean isNPlusOne(int wastedQueries) {
    return wastedQueries >= nPlusOneThreshold;
  }

  void record(String endpoint, int status, long ms, RequestRecorder recorder, long bytes,
      String traceId, long now) {
    requests.add(now, 1);
    if (status >= 500) {
      serverErrors.add(now, 1);
    }
    int wasted = recorder.wastedQueries();
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
        bucket.add(status, ms, recorder, bytes, wasted);
      }
      if (ms >= slowThresholdMs) {
        slow.addFirst(new SlowRequest(Instant.ofEpochMilli(now), endpoint, status, ms,
            recorder.sqlCount(), Math.round(recorder.sqlMs()), Math.round(recorder.externalMs()),
            recorder.rows(), traceId));
        if (slow.size() > KEEP_SLOW) {
          slow.removeLast();
        }
      }
      if (isNPlusOne(wasted)) {
        List<Repeated> repeated = recorder.repeated();
        nPlusOne.addFirst(new NPlusOne(Instant.ofEpochMilli(now), endpoint,
            recorder.sqlCount(), wasted, repeated.subList(0, Math.min(8, repeated.size())),
            traceId));
        if (nPlusOne.size() > KEEP_N_PLUS_ONE) {
          nPlusOne.removeLast();
        }
      }
    }
  }

  /** Endpoints over the last {@code lastHours} hours, busiest first. */
  public synchronized List<EndpointView> endpoints(long now, int lastHours) {
    Map<String, Bucket> merged = merge(now, lastHours);
    Map<String, Bucket> lastHour = merge(now, 1);
    List<EndpointView> views = new ArrayList<>();
    merged.forEach((endpoint, bucket) -> {
      Bucket recent = lastHour.get(endpoint);
      views.add(bucket.view(endpoint, recent == null ? 0 : recent.latency.percentileMs(95)));
    });
    views.sort(Comparator.comparingLong(EndpointView::calls).reversed());
    return views;
  }

  /** Endpoints from {@code fromMillis} until now, merged; for the deploy report. */
  public synchronized Map<String, EndpointView> since(long fromMillis, long now) {
    long fromHour = fromMillis / 3_600_000;
    Map<String, Bucket> merged = new HashMap<>();
    hours.forEach((hour, endpoints) -> {
      if (hour >= fromHour) {
        endpoints.forEach((endpoint, bucket) ->
            merged.computeIfAbsent(endpoint, key -> new Bucket()).merge(bucket));
      }
    });
    Map<String, EndpointView> views = new HashMap<>();
    merged.forEach((endpoint, bucket) -> views.put(endpoint, bucket.view(endpoint, 0)));
    return views;
  }

  /** Buckets of one hour, for {@link EndpointHistory} to store. */
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

  private Map<String, Bucket> merge(long now, int lastHours) {
    long current = now / 3_600_000;
    Map<String, Bucket> merged = new HashMap<>();
    for (long hour = current - lastHours + 1; hour <= current; hour++) {
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
    double dbMs;
    double externalMs;
    long rowsTotal;
    long rowsMax;
    long bytesTotal;
    long bytesMax;
    long wastedTotal;

    void add(int status, long ms, RequestRecorder recorder, long bytes, int wasted) {
      latency.record(ms);
      if (status >= 500) {
        serverErrors++;
      } else if (status >= 400) {
        clientErrors++;
      }
      sqlTotal += recorder.sqlCount();
      sqlMax = Math.max(sqlMax, recorder.sqlCount());
      dbMs += recorder.sqlMs();
      externalMs += recorder.externalMs();
      rowsTotal += recorder.rows();
      rowsMax = Math.max(rowsMax, recorder.rows());
      bytesTotal += bytes;
      bytesMax = Math.max(bytesMax, bytes);
      wastedTotal += wasted;
    }

    void merge(Bucket other) {
      latency.merge(other.latency);
      clientErrors += other.clientErrors;
      serverErrors += other.serverErrors;
      sqlTotal += other.sqlTotal;
      sqlMax = Math.max(sqlMax, other.sqlMax);
      dbMs += other.dbMs;
      externalMs += other.externalMs;
      rowsTotal += other.rowsTotal;
      rowsMax = Math.max(rowsMax, other.rowsMax);
      bytesTotal += other.bytesTotal;
      bytesMax = Math.max(bytesMax, other.bytesMax);
      wastedTotal += other.wastedTotal;
    }

    EndpointView view(String endpoint, long p95LastHour) {
      long calls = latency.count();
      double perCall = calls == 0 ? 0 : 1.0 / calls;
      long avgDb = Math.round(dbMs * perCall);
      long avgExternal = Math.round(externalMs * perCall);
      long avgCode = Math.max(0, latency.averageMs() - avgDb - avgExternal);
      return new EndpointView(endpoint, calls, serverErrors, clientErrors,
          latency.averageMs(), latency.percentileMs(50), latency.percentileMs(95),
          latency.percentileMs(99), latency.maxMs(), p95LastHour,
          calls == 0 ? 0 : (double) sqlTotal / calls, sqlMax, avgDb, avgExternal, avgCode,
          Math.round(rowsTotal * perCall), rowsMax, Math.round(bytesTotal * perCall), bytesMax,
          calls == 0 ? 0 : (double) wastedTotal / calls);
    }
  }

  /**
   * Times in ms. {@code avgDbMs}/{@code avgExternalMs}/{@code avgCodeMs} split the average time;
   * code is what is left (the application's own work, serialization, waiting for a connection).
   * {@code wastedPerCall}: repeated SQL per call (N+1).
   */
  public record EndpointView(String endpoint, long calls, long serverErrors, long clientErrors,
                             long avgMs, long p50Ms, long p95Ms, long p99Ms, long maxMs,
                             long p95LastHourMs, double sqlPerCall, long sqlMax, long avgDbMs,
                             long avgExternalMs, long avgCodeMs, long avgRows, long maxRows,
                             long avgBytes, long maxBytes, double wastedPerCall) {
  }

  public record SlowRequest(Instant time, String endpoint, int status, long ms, int sql,
                            long dbMs, long externalMs, long rows, String traceId) {
  }

  /** {@code wasted}: repeats beyond the first, over all repeated statements of this request. */
  public record NPlusOne(Instant time, String endpoint, int sqlTotal, int wasted,
                         List<Repeated> repeated, String traceId) {
  }
}
