package io.github.devpanel.insight.trace;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;

/**
 * Keeps a copy of the first {@value #CAPTURE} bytes of a request body as the application reads
 * it (nothing is read ahead, nothing changes for the application), so a kept request can be
 * shown, copied as curl and replayed. Multipart uploads are not copied.
 */
public class CapturingRequest extends HttpServletRequestWrapper {

  static final int CAPTURE = 8192;

  /** Never kept: credentials and cookies. */
  private static final Set<String> SECRET_HEADERS = Set.of("authorization", "cookie",
      "proxy-authorization", "set-cookie", "x-api-key", "x-auth-token", "x-csrf-token",
      "x-xsrf-token");
  /** Kept out because they only describe the connection, not the request. */
  private static final Set<String> TRANSPORT_HEADERS = Set.of("host", "connection",
      "content-length", "accept-encoding", "user-agent", "sec-fetch-site", "sec-fetch-mode",
      "sec-fetch-dest", "sec-ch-ua", "sec-ch-ua-mobile", "sec-ch-ua-platform", "referer",
      "origin", "x-forwarded-for", "x-real-ip", "x-forwarded-proto", "x-forwarded-host",
      "x-forwarded-port", "accept-language", "cache-control", "pragma", "priority", "dnt",
      "upgrade-insecure-requests", "te", "keep-alive", "via", "forwarded");

  private final ByteArrayOutputStream body = new ByteArrayOutputStream();
  private final boolean capture;
  private ServletInputStream stream;
  private BufferedReader reader;

  public CapturingRequest(HttpServletRequest request) {
    super(request);
    String type = request.getContentType();
    this.capture = type == null || !type.toLowerCase(Locale.ROOT).startsWith("multipart/");
  }

  @Override
  public ServletInputStream getInputStream() throws IOException {
    if (stream == null) {
      ServletInputStream delegate = super.getInputStream();
      stream = new ServletInputStream() {
        @Override
        public int read() throws IOException {
          int b = delegate.read();
          if (b >= 0 && capture && body.size() < CAPTURE) {
            body.write(b);
          }
          return b;
        }

        @Override
        public int read(byte[] buffer, int off, int len) throws IOException {
          int read = delegate.read(buffer, off, len);
          keep(buffer, off, read);
          return read;
        }

        @Override
        public boolean isFinished() {
          return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
          return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
          delegate.setReadListener(listener);
        }
      };
    }
    return stream;
  }

  @Override
  public BufferedReader getReader() throws IOException {
    if (reader == null) {
      BufferedReader delegate = super.getReader();
      reader = new BufferedReader(new Reader() {
        @Override
        public int read(char[] buffer, int off, int len) throws IOException {
          int read = delegate.read(buffer, off, len);
          if (read > 0 && capture && body.size() < CAPTURE) {
            byte[] bytes = new String(buffer, off, read).getBytes(StandardCharsets.UTF_8);
            keep(bytes, 0, bytes.length);
          }
          return read;
        }

        @Override
        public void close() throws IOException {
          delegate.close();
        }
      });
    }
    return reader;
  }

  private void keep(byte[] buffer, int off, int read) {
    if (read > 0 && capture) {
      int room = CAPTURE - body.size();
      if (room > 0) {
        body.write(buffer, off, Math.min(room, read));
      }
    }
  }

  /** The start of the body as read, or the form parameters, or null. */
  public String capturedBody() {
    if (body.size() > 0) {
      return body.toString(StandardCharsets.UTF_8);
    }
    String type = getContentType();
    if (type != null && type.toLowerCase(Locale.ROOT).startsWith(
        "application/x-www-form-urlencoded") && !"GET".equals(getMethod())) {
      StringBuilder form = new StringBuilder();
      getParameterMap().forEach((name, values) -> {
        for (String value : values) {
          if (form.length() > 0) {
            form.append('&');
          }
          form.append(name).append('=').append(value);
        }
      });
      return form.length() == 0 ? null : form.toString();
    }
    return capture ? null : "[" + type + "]";
  }

  /** Headers worth repeating in a replay: no credentials, no connection details. */
  public Map<String, String> replayableHeaders() {
    Map<String, String> headers = new LinkedHashMap<>();
    for (String name : Collections.list(getHeaderNames())) {
      String lower = name.toLowerCase(Locale.ROOT);
      if (SECRET_HEADERS.contains(lower) || TRANSPORT_HEADERS.contains(lower)
          || lower.startsWith("x-devpanel")) {
        continue;
      }
      String value = getHeader(name);
      if (value != null && headers.size() < 30) {
        headers.put(name, value.length() > 500 ? value.substring(0, 500) : value);
      }
    }
    return headers;
  }

  /** Whether the request carried credentials (they are never kept). */
  public boolean hadAuthorization() {
    return getHeader("Authorization") != null;
  }
}
