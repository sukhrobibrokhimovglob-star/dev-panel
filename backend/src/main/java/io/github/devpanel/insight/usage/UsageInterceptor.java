package io.github.devpanel.insight.usage;

import io.github.devpanel.insight.trace.RequestRecorder;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Tells the request's recorder who is calling and for which organization. Runs inside Spring MVC,
 * where the application's security has already set the user; the organization is the first
 * configured path variable present, e.g. {@code {organizationId}}.
 */
public class UsageInterceptor implements HandlerInterceptor {

  private final List<String> tenantVariables;

  public UsageInterceptor(List<String> tenantVariables) {
    this.tenantVariables = tenantVariables;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
      Object handler) {
    RequestRecorder recorder = RequestRecorder.current();
    if (recorder == null) {
      return true;
    }
    Principal principal = request.getUserPrincipal();
    String tenant = null;
    if (request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE)
        instanceof Map<?, ?> variables) {
      for (String name : tenantVariables) {
        Object value = variables.get(name);
        if (value != null) {
          tenant = String.valueOf(value);
          break;
        }
      }
    }
    recorder.identify(principal == null ? null : principal.getName(), tenant);
    return true;
  }
}
