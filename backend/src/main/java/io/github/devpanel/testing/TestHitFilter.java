package io.github.devpanel.testing;

import java.io.IOException;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/** Records MockMvc requests of web slice tests, where the panel itself is not loaded. */
public class TestHitFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain chain) throws ServletException, IOException {
    TestHits.enter(request);
    try {
      chain.doFilter(request, response);
    } finally {
      if (TestHits.active()) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        int status = response.getStatus();
        TestHits.record(request, pattern == null
            ? request.getMethod() + (status == 404 ? " (not found)"
                : " (no controller, " + status + ")")
            : request.getMethod() + " " + pattern, status);
      }
    }
  }
}
