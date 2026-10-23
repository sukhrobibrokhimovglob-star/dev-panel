package io.github.devpanel.audit;

import io.github.devpanel.audit.DevRequestAuditRepository.AuditEntry;
import io.github.devpanel.support.TraceIds;
import java.io.IOException;
import java.time.LocalDateTime;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Records every error response (status {@code >= 400}) of the whole application, so the panel can
 * filter them by trace id, time and status code. Successful requests are not recorded, to keep
 * this from adding a write per request on production traffic. Registered ahead of Spring Security,
 * so it also sees 401/403 responses. The panel's own requests are not recorded.
 */
public class DevRequestAuditFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(DevRequestAuditFilter.class);

  private static final int MAX_PATH = 255;

  private final DevRequestAuditRepository repository;
  private final String basePath;

  public DevRequestAuditFilter(DevRequestAuditRepository repository, String basePath) {
    this.repository = repository;
    this.basePath = basePath;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    return path.equals(basePath) || path.startsWith(basePath + "/");
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    long start = System.currentTimeMillis();
    boolean failed = false;
    try {
      filterChain.doFilter(request, response);
    } catch (IOException | ServletException | RuntimeException ex) {
      failed = true;
      throw ex;
    } finally {
      int status = failed ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR : response.getStatus();
      recordIfError(request, response, status, System.currentTimeMillis() - start);
    }
  }

  private void recordIfError(HttpServletRequest request, HttpServletResponse response, int status,
      long durationMs) {
    if (status < 400) {
      return;
    }
    try {
      String path = request.getRequestURI();
      repository.insert(new AuditEntry(null, TraceIds.of(request, response), request.getMethod(),
          path.length() > MAX_PATH ? path.substring(0, MAX_PATH) : path, status, durationMs,
          request.getRemoteAddr(), LocalDateTime.now()));
    } catch (RuntimeException ex) {
      log.warn("Could not record request audit for {} {}: {}", request.getMethod(),
          request.getRequestURI(), ex.getMessage());
    }
  }
}
