package io.github.devpanel.insight.deps;

import io.github.devpanel.insight.outbound.OutboundStats;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Checks once a minute that what the application depends on answers, even when no user is
 * calling it (at night, before the first request of the day): the database, every external host
 * the application has called, and any URL configured in {@code devpanel.insights.dependencies}.
 * Any HTTP answer counts as reachable (a 401 or 404 still means the host is up); only no
 * connection, a timeout or a 5xx counts as down.
 */
public class DependencyChecks {

  private static final int HISTORY = 60;
  private static final int TIMEOUT_MS = 5000;

  private final DataSource dataSource;
  private final OutboundStats outbound;
  private final Map<String, String> configured;
  private final Map<String, Target> targets = new LinkedHashMap<>();

  /** @param configured name → URL */
  public DependencyChecks(DataSource dataSource, OutboundStats outbound,
      Map<String, String> configured) {
    this.dataSource = dataSource;
    this.outbound = outbound;
    this.configured = configured;
  }

  /** Called by the panel's scheduler every minute. */
  public void checkAll() {
    check("Database", "jdbc", this::checkDatabase);
    configured.forEach((name, url) -> check(name, url, () -> checkHttp(url)));
    if (outbound != null) {
      for (OutboundStats.TargetView target : outbound.list()) {
        if (!configured.containsValue(target.host())) {
          check(target.host(), target.host(), () -> checkHttp(target.host()));
        }
      }
    }
  }

  public synchronized List<TargetView> list() {
    List<TargetView> views = new ArrayList<>();
    targets.forEach((name, target) -> views.add(target.view(name)));
    return views;
  }

  private void check(String name, String address, Check check) {
    long start = System.currentTimeMillis();
    String error;
    try {
      error = check.run();
    } catch (Exception e) {
      error = e.getClass().getSimpleName() + ": " + e.getMessage();
    }
    long ms = System.currentTimeMillis() - start;
    synchronized (this) {
      targets.computeIfAbsent(name, key -> new Target(address)).add(error, ms);
    }
  }

  private String checkDatabase() throws Exception {
    try (var connection = dataSource.getConnection()) {
      return connection.isValid(TIMEOUT_MS / 1000) ? null : "Connection is not valid";
    }
  }

  private static String checkHttp(String url) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
    connection.setRequestMethod("HEAD");
    connection.setConnectTimeout(TIMEOUT_MS);
    connection.setReadTimeout(TIMEOUT_MS);
    connection.setInstanceFollowRedirects(false);
    try {
      int status = connection.getResponseCode();
      return status >= 500 ? "HTTP " + status : null;
    } finally {
      connection.disconnect();
    }
  }

  @FunctionalInterface
  private interface Check {

    /** @return null when fine, else what is wrong */
    String run() throws Exception;
  }

  private static final class Target {

    final String address;
    final Deque<Boolean> history = new ArrayDeque<>();
    String lastError;
    long lastMs;
    Instant lastCheck;
    Instant downSince;
    Instant lastOk;

    Target(String address) {
      this.address = address;
    }

    void add(String error, long ms) {
      lastCheck = Instant.now();
      lastMs = ms;
      boolean ok = error == null;
      history.addLast(ok);
      if (history.size() > HISTORY) {
        history.removeFirst();
      }
      if (ok) {
        lastOk = lastCheck;
        downSince = null;
        lastError = null;
      } else {
        lastError = error;
        if (downSince == null) {
          downSince = lastCheck;
        }
      }
    }

    TargetView view(String name) {
      long up = history.stream().filter(Boolean::booleanValue).count();
      return new TargetView(name, address, downSince == null, lastMs, lastCheck, lastOk,
          downSince, lastError, history.isEmpty() ? 1 : (double) up / history.size(),
          new ArrayList<>(history));
    }
  }

  /** {@code uptime}: share of the last hour's checks that succeeded, 0..1. */
  public record TargetView(String name, String address, boolean up, long lastMs,
                           Instant lastCheck, Instant lastOk, Instant downSince,
                           String lastError, double uptime, List<Boolean> history) {
  }
}
