package io.github.devpanel.insight.outbound;

import io.github.devpanel.insight.LatencyHistogram;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Calls the application makes to other systems (Feign clients, RestTemplate), per target host:
 * how many, how fast, how many failed or timed out, and since when it has been failing. Tells
 * apart "our code is slow" from "the service we call is down".
 */
public class OutboundStats {

  /** This many failures in a row, and none succeeded since, count as "down". */
  static final int DOWN_AFTER = 3;
  private static final int MAX_TARGETS = 200;

  private final Map<String, Target> targets = new ConcurrentHashMap<>();
  private final Instant started = Instant.now();

  public void success(String url, int status, long ms) {
    Target target = target(url);
    if (target != null) {
      target.record(ms, status >= 500 ? "HTTP " + status : null, false);
    }
  }

  public void failure(String url, Throwable error, long ms) {
    Target target = target(url);
    if (target != null) {
      boolean timeout = error instanceof SocketTimeoutException
          || (error.getCause() instanceof SocketTimeoutException);
      target.record(ms, error.getClass().getSimpleName() + ": " + error.getMessage(), timeout);
    }
  }

  public List<TargetView> list() {
    List<TargetView> views = new ArrayList<>();
    targets.values().forEach(target -> views.add(target.view()));
    views.sort(Comparator.comparing(TargetView::calls).reversed());
    return views;
  }

  public Instant started() {
    return started;
  }

  private Target target(String url) {
    String host = host(url);
    if (!targets.containsKey(host) && targets.size() >= MAX_TARGETS) {
      return null;
    }
    return targets.computeIfAbsent(host, Target::new);
  }

  static String host(String url) {
    try {
      URI uri = URI.create(url);
      if (uri.getHost() == null) {
        return url;
      }
      return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort()
          : "");
    } catch (IllegalArgumentException e) {
      return url;
    }
  }

  private static final class Target {

    final String host;
    final LatencyHistogram latency = new LatencyHistogram();
    long errors;
    long timeouts;
    int failingInARow;
    Instant failingSince;
    Instant lastCall;
    Instant lastError;
    String lastErrorMessage;

    Target(String host) {
      this.host = host;
    }

    synchronized void record(long ms, String error, boolean timeout) {
      latency.record(ms);
      lastCall = Instant.now();
      if (error == null) {
        failingInARow = 0;
        failingSince = null;
        return;
      }
      errors++;
      if (timeout) {
        timeouts++;
      }
      if (failingInARow++ == 0) {
        failingSince = lastCall;
      }
      lastError = lastCall;
      lastErrorMessage = error.length() > 500 ? error.substring(0, 500) : error;
    }

    synchronized TargetView view() {
      boolean down = failingInARow >= DOWN_AFTER;
      return new TargetView(host, latency.count(), errors, timeouts, latency.averageMs(),
          latency.percentileMs(95), latency.maxMs(), down, down ? failingSince : null, lastCall,
          lastError, lastErrorMessage);
    }
  }

  /** {@code downSince}: first of the failures in a row that made it count as down. */
  public record TargetView(String host, long calls, long errors, long timeouts, long avgMs,
                           long p95Ms, long maxMs, boolean down, Instant downSince,
                           Instant lastCall, Instant lastError, String lastErrorMessage) {
  }
}
