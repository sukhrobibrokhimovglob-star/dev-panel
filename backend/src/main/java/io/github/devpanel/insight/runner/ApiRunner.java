package io.github.devpanel.insight.runner;

import io.github.devpanel.insight.trace.RunnerMarker;
import io.github.devpanel.support.DevPanelException;
import io.github.devpanel.support.TraceIds;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.core.env.Environment;

/**
 * Sends a request to this very application, over the loopback interface (no proxy, no other
 * host), as the browser would, and returns the answer with its trace id so the waterfall can be
 * opened right after. Used by the API runner and the smoke checks. The panel's own paths cannot
 * be called; credentials are whatever the caller puts in the headers and are never stored.
 */
public class ApiRunner {

  public static final Set<String> METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH",
      "DELETE", "OPTIONS");
  private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");
  /** Set by the HTTP client itself. */
  private static final Set<String> RESTRICTED = Set.of("host", "content-length", "connection",
      "expect", "upgrade", "transfer-encoding", "te", "trailer", "keep-alive");
  private static final int MAX_BODY = 2 * 1024 * 1024;
  private static final Duration TIMEOUT = Duration.ofSeconds(60);

  private final ServletWebServerApplicationContext context;
  private final Environment environment;
  private final String basePath;
  private volatile HttpClient client;

  public ApiRunner(ServletWebServerApplicationContext context, Environment environment,
      String basePath) {
    this.context = context;
    this.environment = environment;
    this.basePath = basePath;
  }

  public static boolean writes(String method) {
    return !READ_METHODS.contains(method.toUpperCase(Locale.ROOT));
  }

  public Response send(Request request) {
    String method = request.method() == null ? "GET" : request.method().trim()
        .toUpperCase(Locale.ROOT);
    if (!METHODS.contains(method)) {
      throw DevPanelException.badRequest("Unsupported method " + method);
    }
    String path = request.path() == null ? "" : request.path().trim();
    if (!path.startsWith("/") || path.startsWith("//") || path.contains("\\")
        || path.chars().anyMatch(c -> c < 0x20)) {
      throw DevPanelException.badRequest("The path must start with / (e.g. /v1/users?page=0)");
    }
    String bare = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
    if (bare.equals(basePath) || bare.startsWith(basePath + "/")) {
      throw DevPanelException.badRequest("The panel's own API cannot be called from here");
    }
    if (context.getWebServer() == null || context.getWebServer().getPort() <= 0) {
      throw DevPanelException.badRequest("The web server is not running yet");
    }
    boolean https = "true".equalsIgnoreCase(environment.getProperty("server.ssl.enabled"));
    String contextPath = context.getServletContext() == null ? ""
        : context.getServletContext().getContextPath();
    URI uri = URI.create((https ? "https" : "http") + "://127.0.0.1:"
        + context.getWebServer().getPort() + contextPath + path);

    HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(TIMEOUT);
    if (request.headers() != null) {
      request.headers().forEach((name, value) -> {
        if (name == null || name.isBlank() || value == null) {
          return;
        }
        String clean = name.trim();
        if (RESTRICTED.contains(clean.toLowerCase(Locale.ROOT))
            || clean.chars().anyMatch(c -> c <= 0x20 || c == ':')
            || value.chars().anyMatch(c -> c == '\r' || c == '\n')) {
          return;
        }
        builder.header(clean, value);
      });
    }
    builder.header(RunnerMarker.HEADER, RunnerMarker.token());
    byte[] body = request.body() == null ? new byte[0]
        : request.body().getBytes(StandardCharsets.UTF_8);
    builder.method(method, body.length == 0 && !writes(method)
        ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));

    long start = System.currentTimeMillis();
    try {
      HttpResponse<InputStream> response = client(https).send(builder.build(),
          HttpResponse.BodyHandlers.ofInputStream());
      byte[] content;
      boolean truncated;
      try (InputStream in = response.body()) {
        content = in.readNBytes(MAX_BODY);
        truncated = in.read() >= 0;
      }
      long ms = System.currentTimeMillis() - start;
      Map<String, String> headers = new LinkedHashMap<>();
      response.headers().map().forEach((name, values) -> {
        if (!name.equalsIgnoreCase("set-cookie")) {
          headers.put(name, String.join(", ", values));
        }
      });
      return new Response(response.statusCode(), ms, headers,
          new String(content, StandardCharsets.UTF_8), content.length, truncated,
          response.headers().firstValue(TraceIds.header()).orElse(null), null);
    } catch (IOException e) {
      return new Response(0, System.currentTimeMillis() - start, Map.of(), null, 0, false, null,
          e.getClass().getSimpleName() + ": " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Response(0, System.currentTimeMillis() - start, Map.of(), null, 0, false, null,
          "Interrupted");
    }
  }

  private HttpClient client(boolean https) {
    HttpClient existing = client;
    if (existing != null) {
      return existing;
    }
    HttpClient.Builder builder = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .version(HttpClient.Version.HTTP_1_1);
    if (https) {
      // Only ever 127.0.0.1, whose certificate is issued for the public name
      builder.sslContext(trustAll());
    }
    client = builder.build();
    return client;
  }

  private static SSLContext trustAll() {
    try {
      SSLContext ssl = SSLContext.getInstance("TLS");
      ssl.init(null, new TrustManager[] {new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
          return new X509Certificate[0];
        }
      }}, new SecureRandom());
      return ssl;
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** {@code path} with the query string, relative to the application, e.g. /v1/users?page=0. */
  public record Request(String method, String path, Map<String, String> headers, String body) {
  }

  /** {@code status} 0 when the call itself failed ({@code error}). */
  public record Response(int status, long ms, Map<String, String> headers, String body,
                         long bytes, boolean truncated, String traceId, String error) {
  }
}
