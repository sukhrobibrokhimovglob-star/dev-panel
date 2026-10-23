package io.github.devpanel.web;

import io.github.devpanel.support.DevPanelErrors;
import io.github.devpanel.support.DevPanelException;
import io.github.devpanel.support.TraceIds;
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
    log.error("Dev panel request {} {} failed", request.getMethod(), request.getRequestURI(), e);
    return body(HttpStatus.INTERNAL_SERVER_ERROR, e.getClass().getSimpleName() + ": "
        + e.getMessage(), request, response);
  }

  private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String message,
      HttpServletRequest request, HttpServletResponse response) {
    return ResponseEntity.status(status)
        .body(DevPanelErrors.body(status.value(), message, TraceIds.of(request, response)));
  }
}
