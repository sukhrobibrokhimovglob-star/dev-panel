package io.github.devpanel.insight.usage;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Every endpoint the application declares ({@code GET /v1/users/{id}}, as the statistics name
 * them), so the panel can list the ones nobody called: candidates for dead code.
 */
public class EndpointCatalog {

  private final ApplicationContext context;
  private final String basePath;

  public EndpointCatalog(ApplicationContext context, String basePath) {
    this.context = context;
    this.basePath = basePath;
  }

  public List<Entry> all() {
    Set<String> seen = new TreeSet<>();
    List<Entry> entries = new ArrayList<>();
    for (RequestMappingHandlerMapping mapping
        : context.getBeansOfType(RequestMappingHandlerMapping.class).values()) {
      mapping.getHandlerMethods().forEach((info, method) -> {
        for (String pattern : patterns(info)) {
          if (pattern.startsWith(basePath) || pattern.equals("/error")) {
            continue;
          }
          Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
          List<String> names = methods.isEmpty()
              ? List.of("GET", "POST", "PUT", "PATCH", "DELETE")
              : methods.stream().map(Enum::name).toList();
          for (String name : names) {
            String endpoint = name + " " + pattern;
            if (seen.add(endpoint)) {
              entries.add(new Entry(endpoint, handler(method)));
            }
          }
        }
      });
    }
    entries.sort((a, b) -> a.endpoint().compareTo(b.endpoint()));
    return entries;
  }

  private static Set<String> patterns(RequestMappingInfo info) {
    return info.getPatternValues();
  }

  private static String handler(HandlerMethod method) {
    return method.getBeanType().getSimpleName() + "." + method.getMethod().getName();
  }

  public record Entry(String endpoint, String handler) {
  }
}
