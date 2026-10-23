package io.github.devpanel.support;

import javax.servlet.http.HttpServletRequest;

/** The browser's address, also behind a proxy that sets {@code X-Forwarded-For}. */
public final class ClientIp {

  private ClientIp() {
  }

  public static String of(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    String ip = forwarded != null && !forwarded.isBlank()
        ? forwarded.split(",")[0].trim()
        : request.getRemoteAddr();
    return ip.length() > 64 ? ip.substring(0, 64) : ip;
  }
}
