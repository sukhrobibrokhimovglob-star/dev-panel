package io.github.devpanel.insight.trace;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * Full waterfalls of the requests worth looking at: slow ones, failed ones, N+1 suspects, plus a
 * sample of the most recent others. In memory, the last {@value #KEEP}.
 */
public class TraceStore {

  static final int KEEP = 300;

  private final Deque<Trace> traces = new ArrayDeque<>();

  public synchronized void add(Trace trace) {
    traces.addFirst(trace);
    while (traces.size() > KEEP) {
      traces.removeLast();
    }
  }

  public synchronized List<TraceSummary> list() {
    List<TraceSummary> summaries = new ArrayList<>();
    for (Trace trace : traces) {
      summaries.add(trace.summary());
    }
    return summaries;
  }

  public synchronized Optional<Trace> find(String id) {
    return traces.stream().filter(trace -> trace.id().equals(id)).findFirst();
  }

  /**
   * @param reason why it was kept: SLOW, ERROR, N_PLUS_ONE or SAMPLE
   */
  public record Trace(String id, Instant time, String endpoint, String path, int status,
                      double totalMs, double sqlMs, double externalMs, int sqlCount, long rows,
                      long responseBytes, String user, String tenant, String clientIp,
                      String reason, boolean truncated, List<RequestRecorder.Span> spans,
                      List<RequestRecorder.Repeated> repeated) {

    TraceSummary summary() {
      return new TraceSummary(id, time, endpoint, status, totalMs, sqlMs, externalMs, sqlCount,
          rows, reason);
    }
  }

  public record TraceSummary(String id, Instant time, String endpoint, int status, double totalMs,
                             double sqlMs, double externalMs, int sqlCount, long rows,
                             String reason) {
  }
}
