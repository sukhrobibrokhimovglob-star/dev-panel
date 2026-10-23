package io.github.devpanel.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

/**
 * The one error body of the panel, {@code {"error": {"message"}, "message", "status", "traceId"}},
 * written both by the exception handler and by the auth filter (which runs before Spring MVC).
 */
public final class DevPanelErrors {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private DevPanelErrors() {
  }

  public static Map<String, Object> body(int status, String message, String traceId) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", status);
    body.put("message", message);
    body.put("error", Map.of("message", message == null ? "" : message));
    body.put("traceId", traceId);
    return body;
  }

  public static void write(HttpServletRequest request, HttpServletResponse response, int status,
      String message) throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    MAPPER.writeValue(response.getOutputStream(),
        body(status, message, TraceIds.of(request, response)));
  }
}
