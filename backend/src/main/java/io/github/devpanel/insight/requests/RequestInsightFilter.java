package io.github.devpanel.insight.requests;

import io.github.devpanel.insight.logs.ExceptionGroups;
import io.github.devpanel.insight.security.SecurityStats;
import io.github.devpanel.insight.trace.CountingResponse;
import io.github.devpanel.insight.trace.RequestRecorder;
import io.github.devpanel.insight.trace.TraceStore;
import io.github.devpanel.insight.trace.TraceStore.Trace;
import io.github.devpanel.insight.usage.UsageStats;
import io.github.devpanel.support.TraceIds;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Follows every request of the application (not the panel's own): times it, records its SQL and
 * external calls (see {@link RequestRecorder}), counts the response bytes, then files the result
 * under its endpoint pattern, its user and organization, and its client IP. Slow, failed and N+1
 * requests keep their full waterfall, as does one recent request per endpoint per minute.
 * An exception that ended in a 5xx goes to the exception groups even when the application's
 * error handler turned it into a response without logging it.
 */
public class RequestInsightFilter extends OncePerRequestFilter {

  /** Set by {@link ExceptionCaptureResolver} for exceptions Spring MVC handled. */
  static final String EXCEPTION_ATTRIBUTE = RequestInsightFilter.class.getName() + ".EXCEPTION";
  private static final long SAMPLE_EVERY_MS = 60_000;

  private final RequestStats stats;
  private final ExceptionGroups exceptionGroups;
  private final UsageStats usage;
  private final SecurityStats security;
  private final TraceStore traces;
  private final String basePath;
  private final boolean trustForwardedFor;
  private final Map<String, Long> lastSample = new ConcurrentHashMap<>();

  public RequestInsightFilter(RequestStats stats, ExceptionGroups exceptionGroups,
      UsageStats usage, SecurityStats security, TraceStore traces, String basePath,
      boolean trustForwardedFor) {
    this.stats = stats;
    this.exceptionGroups = exceptionGroups;
    this.usage = usage;
    this.security = security;
    this.traces = traces;
    this.basePath = basePath;
    this.trustForwardedFor = trustForwardedFor;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = path(request);
    return path.equals(basePath) || path.startsWith(basePath + "/");
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain chain) throws ServletException, IOException {
    long start = System.currentTimeMillis();
    RequestRecorder recorder = RequestRecorder.start();
    CountingResponse counting = new CountingResponse(response);
    Throwable failure = null;
    try {
      chain.doFilter(request, counting);
    } catch (IOException | ServletException | RuntimeException e) {
      failure = e;
      throw e;
    } finally {
      RequestRecorder.stop();
      try {
        finish(request, response, counting, recorder, start, failure);
      } catch (RuntimeException ignored) {
        // Statistics must never break a response
      }
    }
  }

  private void finish(HttpServletRequest request, HttpServletResponse response,
      CountingResponse counting, RequestRecorder recorder, long start, Throwable failure) {
    long now = System.currentTimeMillis();
    long ms = now - start;
    int status = failure != null ? 500 : response.getStatus();
    String traceId = TraceIds.of(request, response);
    String endpoint = endpoint(request, status);
    String path = path(request);
    String ip = clientIp(request);
    long bytes = counting.count();

    stats.record(endpoint, status, ms, recorder, bytes, traceId, now);
    usage.record(recorder.user(), recorder.tenant(), endpoint, status, ms, now);
    security.record(ip, path, status, recorder.user(),
        request.getHeader("Authorization") != null, now);
    if (status >= 500) {
      Throwable handled = request.getAttribute(EXCEPTION_ATTRIBUTE) instanceof Throwable t
          ? t : null;
      exceptionGroups.record(failure != null ? failure : handled, traceId);
    }

    String reason = status >= 500 ? "ERROR"
        : stats.isNPlusOne(recorder.wastedQueries()) ? "N_PLUS_ONE"
        : ms >= stats.slowThresholdMs() ? "SLOW"
        : sampleDue(endpoint, now) ? "SAMPLE" : null;
    if (reason != null) {
      traces.add(new Trace(traceId != null ? traceId : UUID.randomUUID().toString(),
          Instant.ofEpochMilli(start), endpoint, path, status, ms, recorder.sqlMs(),
          recorder.externalMs(), recorder.sqlCount(), recorder.rows(), bytes, recorder.user(),
          recorder.tenant(), ip, reason, recorder.truncated(), recorder.spans(),
          recorder.repeated()));
    }
  }

  private boolean sampleDue(String endpoint, long now) {
    Long last = lastSample.get(endpoint);
    if (last != null && last > now - SAMPLE_EVERY_MS) {
      return false;
    }
    if (lastSample.size() < 2000) {
      lastSample.put(endpoint, now);
    }
    return true;
  }

  private String clientIp(HttpServletRequest request) {
    if (trustForwardedFor) {
      String forwarded = request.getHeader("X-Forwarded-For");
      if (forwarded != null && !forwarded.isBlank()) {
        return forwarded.split(",")[0].trim();
      }
      String real = request.getHeader("X-Real-IP");
      if (real != null && !real.isBlank()) {
        return real.trim();
      }
    }
    return request.getRemoteAddr();
  }

  private static String path(HttpServletRequest request) {
    return request.getRequestURI().substring(request.getContextPath().length());
  }

  /**
   * {@code GET /v1/patients/{id}}. Requests that never reached a controller (unknown URL, or
   * refused earlier, e.g. 401 by security) are grouped by status instead of by URL, so random
   * URLs cannot flood the statistics.
   */
  private static String endpoint(HttpServletRequest request, int status) {
    Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
    if (pattern == null) {
      return request.getMethod() + (status == 404 ? " (not found)"
          : " (no controller, " + status + ")");
    }
    return request.getMethod() + " " + pattern;
  }
}
