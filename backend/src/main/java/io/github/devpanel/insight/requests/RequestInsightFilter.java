package io.github.devpanel.insight.requests;

import io.github.devpanel.insight.logs.ExceptionGroups;
import io.github.devpanel.support.TraceIds;
import java.io.IOException;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Times every request of the application (not the panel's own) and counts its SQL, then files it
 * under its endpoint pattern. An exception that ended in a 5xx goes to the exception groups even
 * when the application's error handler turned it into a response without logging it.
 */
public class RequestInsightFilter extends OncePerRequestFilter {

  /** Set by {@link ExceptionCaptureResolver} for exceptions Spring MVC handled. */
  static final String EXCEPTION_ATTRIBUTE = RequestInsightFilter.class.getName() + ".EXCEPTION";

  private final RequestStats stats;
  private final ExceptionGroups exceptionGroups;
  private final String basePath;

  public RequestInsightFilter(RequestStats stats, ExceptionGroups exceptionGroups,
      String basePath) {
    this.stats = stats;
    this.exceptionGroups = exceptionGroups;
    this.basePath = basePath;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    return path.equals(basePath) || path.startsWith(basePath + "/");
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain chain) throws ServletException, IOException {
    long start = System.currentTimeMillis();
    SqlCounter sql = SqlCounter.start();
    Throwable failure = null;
    try {
      chain.doFilter(request, response);
    } catch (IOException | ServletException | RuntimeException e) {
      failure = e;
      throw e;
    } finally {
      SqlCounter.stop();
      long now = System.currentTimeMillis();
      int status = failure != null ? 500 : response.getStatus();
      String traceId = TraceIds.of(request, response);
      stats.record(endpoint(request, status), status, now - start, sql, traceId, now);
      if (status >= 500) {
        Throwable handled = request.getAttribute(EXCEPTION_ATTRIBUTE) instanceof Throwable t
            ? t : null;
        exceptionGroups.record(failure != null ? failure : handled, traceId);
      }
    }
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
