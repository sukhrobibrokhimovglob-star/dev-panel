package io.github.devpanel.web;

import io.github.devpanel.support.TraceIds;
import java.io.IOException;
import java.util.UUID;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Assigns every request a trace id, exposes it to the logs (MDC) and echoes it back in the
 * response header, so an error in the audit can be matched with its log lines. An id supplied by
 * the caller (e.g. a gateway) is reused only if it is a safe token; anything else is replaced to
 * keep log lines injection-free. The log pattern has to print it, e.g. {@code [%X{traceId}]}.
 */
public class DevPanelTraceIdFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain chain) throws ServletException, IOException {
    String incoming = request.getHeader(TraceIds.header());
    String traceId = TraceIds.isWellFormed(incoming)
        ? incoming
        : UUID.randomUUID().toString().replace("-", "");
    MDC.put(TraceIds.mdcKey(), traceId);
    request.setAttribute(TraceIds.REQUEST_ATTRIBUTE, traceId);
    response.setHeader(TraceIds.header(), traceId);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(TraceIds.mdcKey());
    }
  }
}
