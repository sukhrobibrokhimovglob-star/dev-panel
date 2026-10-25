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
   * @param reason   why it was kept: SLOW, ERROR, N_PLUS_ONE, RUNNER or SAMPLE
   * @param request  what was asked, to show, copy as curl and replay (no credentials)
   * @param response the start of the answer for responses of 400 and above, else null
   */
  public record Trace(String id, Instant time, String endpoint, String path, int status,
                      double totalMs, double sqlMs, double externalMs, int sqlCount, long rows,
                      long responseBytes, String user, String tenant, String clientIp,
                      String reason, boolean truncated, List<RequestRecorder.Span> spans,
                      List<RequestRecorder.Repeated> repeated, RequestInfo request,
                      String response, int writeRepeats, int exactRepeats, int sequenceCalls,
                      int externalInTransaction) {

    TraceSummary summary() {
      return new TraceSummary(id, time, endpoint, status, totalMs, sqlMs, externalMs, sqlCount,
          rows, reason);
    }
  }

  /**
   * {@code body}: the start of the request body, secrets (password, token ...) masked;
   * {@code authorized}: it carried an Authorization header, which is never kept.
   */
  public record RequestInfo(String method, String query, String contentType,
                            java.util.Map<String, String> headers, String body,
                            boolean authorized) {
  }

  public record TraceSummary(String id, Instant time, String endpoint, int status, double totalMs,
                             double sqlMs, double externalMs, int sqlCount, long rows,
                             String reason) {
  }
}
