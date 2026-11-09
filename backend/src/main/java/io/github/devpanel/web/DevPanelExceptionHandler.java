package io.github.devpanel.web;

import io.github.devpanel.support.DevPanelErrors;
import io.github.devpanel.support.DevPanelException;
import io.github.devpanel.support.TraceIds;
import java.io.IOException;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Errors of the panel's own controllers only, ordered first so the application's global handler
 * (which may use a different body format) never sees them.
 */
@RestControllerAdvice(annotations = DevPanelApi.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DevPanelExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(DevPanelExceptionHandler.class);

  @ExceptionHandler(DevPanelException.class)
  public ResponseEntity<Map<String, Object>> handle(DevPanelException e,
      HttpServletRequest request, HttpServletResponse response) {
    return body(e.getStatus(), e.getMessage(), request, response);
  }

  @ExceptionHandler({HttpMessageNotReadableException.class,
      MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
  public ResponseEntity<Map<String, Object>> badRequest(Exception e, HttpServletRequest request,
      HttpServletResponse response) {
    return body(HttpStatus.BAD_REQUEST, "Invalid request: " + e.getMessage(), request, response);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, Object>> unexpected(Exception e, HttpServletRequest request,
      HttpServletResponse response) {
    if (clientGone(e)) {
      // The browser left (closed the Logs tab, a proxy timed out): nobody to answer
      log.debug("Dev panel client went away during {}", request.getRequestURI());
      return null;
    }
    if (response.isCommitted() || isStream(response)) {
      // An error body cannot be written into a started answer or an event stream
      log.warn("Dev panel request {} {} failed after the answer started", request.getMethod(),
          request.getRequestURI(), e);
      return null;
    }
    log.error("Dev panel request {} {} failed", request.getMethod(), request.getRequestURI(), e);
    return body(HttpStatus.INTERNAL_SERVER_ERROR, e.getClass().getSimpleName() + ": "
        + e.getMessage(), request, response);
  }

  /** Tomcat's ClientAbortException, Spring 6.1+'s AsyncRequestNotUsableException, a broken pipe. */
  static boolean clientGone(Throwable error) {
    for (Throwable e = error; e != null; e = e.getCause() == e ? null : e.getCause()) {
      String type = e.getClass().getSimpleName();
      if (type.equals("ClientAbortException") || type.equals("AsyncRequestNotUsableException")
          || type.equals("EofException")) {
        return true;
      }
      String message = e.getMessage();
      if (e instanceof IOException && message != null && (message.contains("Broken pipe")
          || message.contains("Connection reset") || message.contains("connection was aborted"))) {
        return true;
      }
    }
    return false;
  }

  private static boolean isStream(HttpServletResponse response) {
    String type = response.getContentType();
    return type != null && type.startsWith("text/event-stream");
  }

  private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String message,
      HttpServletRequest request, HttpServletResponse response) {
    return ResponseEntity.status(status)
        .body(DevPanelErrors.body(status.value(), message, TraceIds.of(request, response)));
  }
}
