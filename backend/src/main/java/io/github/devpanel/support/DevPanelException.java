package io.github.devpanel.support;

import org.springframework.http.HttpStatus;

/** An error the panel reports to the browser as-is: its status and its message. */
public class DevPanelException extends RuntimeException {

  private final HttpStatus status;

  public DevPanelException(HttpStatus status, String message) {
    super(message);
    this.status = status;
  }

  public static DevPanelException badRequest(String message) {
    return new DevPanelException(HttpStatus.BAD_REQUEST, message);
  }

  public static DevPanelException forbidden(String message) {
    return new DevPanelException(HttpStatus.FORBIDDEN, message);
  }

  public static DevPanelException notFound(String message) {
    return new DevPanelException(HttpStatus.NOT_FOUND, message);
  }

  public HttpStatus getStatus() {
    return status;
  }
}
