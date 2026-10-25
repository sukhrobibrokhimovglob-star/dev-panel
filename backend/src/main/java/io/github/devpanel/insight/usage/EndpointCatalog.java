package io.github.devpanel.insight.usage;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.context.ApplicationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ValueConstants;
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

  /**
   * What a call of {@code endpoint} needs, read from its controller method: path variables,
   * query parameters, headers and an example JSON body built from the body's class.
   */
  public Details details(String endpoint) {
    int space = endpoint.indexOf(' ');
    if (space < 0) {
      return null;
    }
    String method = endpoint.substring(0, space);
    String pattern = endpoint.substring(space + 1);
    for (RequestMappingHandlerMapping mapping
        : context.getBeansOfType(RequestMappingHandlerMapping.class).values()) {
      for (Map.Entry<RequestMappingInfo, HandlerMethod> entry
          : mapping.getHandlerMethods().entrySet()) {
        RequestMappingInfo info = entry.getKey();
        Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
        boolean methodMatches = methods.isEmpty()
            || methods.stream().anyMatch(m -> m.name().equals(method));
        if (methodMatches && patterns(info).contains(pattern)) {
          return describe(method, pattern, entry.getValue());
        }
      }
    }
    return null;
  }

  private static Details describe(String method, String pattern, HandlerMethod handler) {
    List<Parameter> pathVariables = new ArrayList<>();
    List<Parameter> query = new ArrayList<>();
    List<Parameter> headers = new ArrayList<>();
    String bodyType = null;
    Object bodyExample = null;
    for (MethodParameter parameter : handler.getMethodParameters()) {
      parameter.initParameterNameDiscovery(new DefaultParameterNameDiscoverer());
      Class<?> type = parameter.getParameterType();
      PathVariable path = parameter.getParameterAnnotation(PathVariable.class);
      RequestParam param = parameter.getParameterAnnotation(RequestParam.class);
      RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
      RequestBody body = parameter.getParameterAnnotation(RequestBody.class);
      if (path != null) {
        pathVariables.add(new Parameter(name(path.value(), path.name(), parameter), true,
            type.getSimpleName(), null));
      } else if (param != null) {
        String fallback = ValueConstants.DEFAULT_NONE.equals(param.defaultValue()) ? null
            : param.defaultValue();
        query.add(new Parameter(name(param.value(), param.name(), parameter),
            param.required() && fallback == null, type.getSimpleName(), fallback));
      } else if (header != null) {
        headers.add(new Parameter(name(header.value(), header.name(), parameter),
            header.required(), type.getSimpleName(), null));
      } else if (body != null) {
        bodyType = parameter.getGenericParameterType().getTypeName();
        bodyExample = Examples.of(parameter.getGenericParameterType(), 0);
      } else if (isPageable(type)) {
        query.add(new Parameter("page", false, "int", "0"));
        query.add(new Parameter("size", false, "int", "20"));
      } else if (!isFramework(type)
          && !org.springframework.beans.BeanUtils.isSimpleProperty(type)) {
        // @ModelAttribute-like object bound from query parameters
        for (Field field : Examples.fields(type)) {
          query.add(new Parameter(field.getName(), false, field.getType().getSimpleName(), null));
        }
      }
    }
    return new Details(method + " " + pattern, method, pattern,
        handler.getBeanType().getSimpleName() + "." + handler.getMethod().getName(),
        pathVariables, query, headers, bodyType, bodyExample);
  }

  private static String name(String value, String name, MethodParameter parameter) {
    if (value != null && !value.isEmpty()) {
      return value;
    }
    if (name != null && !name.isEmpty()) {
      return name;
    }
    return parameter.getParameterName() == null ? "arg" + parameter.getParameterIndex()
        : parameter.getParameterName();
  }

  private static boolean isPageable(Class<?> type) {
    return type.getName().equals("org.springframework.data.domain.Pageable");
  }

  private static boolean isFramework(Class<?> type) {
    String name = type.getName();
    return name.startsWith("javax.") || name.startsWith("jakarta.")
        || name.startsWith("org.springframework.") || name.startsWith("java.security.")
        || name.startsWith("java.io.") || name.startsWith("java.util.Locale")
        || name.startsWith("io.github.devpanel.");
  }

  /** {@code example} for parameters with a default value. */
  public record Parameter(String name, boolean required, String type, String example) {
  }

  /** {@code bodyExample}: a JSON-ready skeleton of the body (maps, lists, values). */
  public record Details(String endpoint, String method, String pattern, String handler,
                        List<Parameter> pathVariables, List<Parameter> query,
                        List<Parameter> headers, String bodyType, Object bodyExample) {
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
