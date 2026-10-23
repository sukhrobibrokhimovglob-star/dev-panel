package io.github.devpanel.insight.requests;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/**
 * Sees every exception a controller throws, before the application's own handlers turn it into a
 * response, and leaves it to them (returns null). {@link RequestInsightFilter} then groups it if
 * the response was a 5xx.
 */
public class ExceptionCaptureResolver implements HandlerExceptionResolver, Ordered {

  @Override
  public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response,
      Object handler, Exception ex) {
    request.setAttribute(RequestInsightFilter.EXCEPTION_ATTRIBUTE, ex);
    return null;
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }
}
