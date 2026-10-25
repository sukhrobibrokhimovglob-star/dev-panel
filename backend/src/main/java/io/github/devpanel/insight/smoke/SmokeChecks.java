package io.github.devpanel.insight.smoke;

import io.github.devpanel.insight.runner.ApiRunner;
import io.github.devpanel.support.DevPanelException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Saved requests that must keep working ("the patient search answers 200 within 2 s"), run 2
 * minutes after each start, every {@code devpanel.insights.smoke-interval} and on demand. A failing
 * check turns the health page red and alerts. Only reading methods (GET, HEAD): a check runs
 * unattended against production data. Header values may name environment variables
 * ({@code Authorization: Bearer ${SMOKE_TOKEN}}), so secrets need not be stored in the panel.
 */
public class SmokeChecks {

  private static final Pattern VARIABLE = Pattern.compile("\\$\\{([A-Za-z0-9_.-]+)}");
  private static final Duration KEEP = Duration.ofDays(30);
  private static final int HISTORY = 30;

  private final JdbcTemplate jdbcTemplate;
  private final ApiRunner runner;
  private final Environment environment;

  public SmokeChecks(JdbcTemplate jdbcTemplate, ApiRunner runner, Environment environment) {
    this.jdbcTemplate = jdbcTemplate;
    this.runner = runner;
    this.environment = environment;
  }

  public List<Check> checks() {
    return jdbcTemplate.query("SELECT id, name, method, path, headers, expected_status, max_ms,"
            + " body_contains, enabled, created_by, created_at FROM devpanel_smoke ORDER BY id",
        (rs, i) -> new Check(rs.getLong("id"), rs.getString("name"), rs.getString("method"),
            rs.getString("path"), rs.getString("headers"), rs.getInt("expected_status"),
            rs.getLong("max_ms"), rs.getString("body_contains"), rs.getBoolean("enabled"),
            rs.getString("created_by"), rs.getTimestamp("created_at").toInstant()));
  }

  /** Each check with its last runs, newest first. */
  public List<CheckView> list() {
    Map<Long, List<Run>> runs = new HashMap<>();
    jdbcTemplate.query("SELECT check_id, ran_at, ok, status, ms, error, run_trigger"
            + " FROM devpanel_smoke_run WHERE ran_at >= ? ORDER BY ran_at DESC",
        rs -> {
          List<Run> list = runs.computeIfAbsent(rs.getLong("check_id"), key -> new ArrayList<>());
          if (list.size() < HISTORY) {
            list.add(new Run(rs.getTimestamp("ran_at").toInstant(), rs.getBoolean("ok"),
                rs.getInt("status"), rs.getLong("ms"), rs.getString("error"),
                rs.getString("run_trigger")));
          }
        },
        Timestamp.from(Instant.now().minus(Duration.ofDays(7))));
    List<CheckView> views = new ArrayList<>();
    for (Check check : checks()) {
      List<Run> history = runs.getOrDefault(check.id(), List.of());
      long ok = history.stream().filter(Run::ok).count();
      views.add(new CheckView(check, history.isEmpty() ? null : history.get(0), history,
          history.isEmpty() ? -1 : (double) ok / history.size()));
    }
    return views;
  }

  public Check save(Long id, Check input, String by) {
    String method = input.method() == null ? "GET" : input.method().trim()
        .toUpperCase(Locale.ROOT);
    if (!method.equals("GET") && !method.equals("HEAD")) {
      throw DevPanelException.badRequest("Smoke checks only read: GET or HEAD");
    }
    if (input.name() == null || input.name().isBlank() || input.name().length() > 100) {
      throw DevPanelException.badRequest("Give the check a short name");
    }
    if (input.path() == null || !input.path().startsWith("/") || input.path().length() > 1000) {
      throw DevPanelException.badRequest("The path must start with /");
    }
    int expected = input.expectedStatus() <= 0 ? 200 : input.expectedStatus();
    long maxMs = input.maxMs() <= 0 ? 5000 : Math.min(input.maxMs(), 60_000);
    String headers = input.headers() == null ? null : input.headers().trim();
    if (headers != null && headers.length() > 2000) {
      throw DevPanelException.badRequest("Headers are too long");
    }
    String contains = input.bodyContains() == null || input.bodyContains().isBlank() ? null
        : input.bodyContains().trim();
    if (id == null) {
      jdbcTemplate.update("INSERT INTO devpanel_smoke (name, method, path, headers,"
              + " expected_status, max_ms, body_contains, enabled, created_by, created_at)"
              + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", input.name().trim(), method,
          input.path().trim(), headers, expected, maxMs, contains, input.enabled(), by,
          Timestamp.from(Instant.now()));
      List<Check> all = checks();
      return all.get(all.size() - 1);
    }
    if (jdbcTemplate.update("UPDATE devpanel_smoke SET name = ?, method = ?, path = ?,"
            + " headers = ?, expected_status = ?, max_ms = ?, body_contains = ?, enabled = ?"
            + " WHERE id = ?", input.name().trim(), method, input.path().trim(), headers,
        expected, maxMs, contains, input.enabled(), id) == 0) {
      throw DevPanelException.notFound("No such smoke check");
    }
    return checks().stream().filter(check -> check.id() == id).findFirst().orElseThrow();
  }

  public void delete(long id) {
    jdbcTemplate.update("DELETE FROM devpanel_smoke_run WHERE check_id = ?", id);
    if (jdbcTemplate.update("DELETE FROM devpanel_smoke WHERE id = ?", id) == 0) {
      throw DevPanelException.notFound("No such smoke check");
    }
  }

  /** Runs every enabled check (or only {@code onlyId}); {@code trigger}: START, SCHEDULE, MANUAL. */
  public List<Run> runAll(String trigger, Long onlyId) {
    List<Run> runs = new ArrayList<>();
    for (Check check : checks()) {
      if ((onlyId == null && !check.enabled()) || (onlyId != null && check.id() != onlyId)) {
        continue;
      }
      Run run = run(check, trigger);
      runs.add(run);
      jdbcTemplate.update("INSERT INTO devpanel_smoke_run (check_id, ran_at, ok, status, ms,"
              + " error, run_trigger) VALUES (?, ?, ?, ?, ?, ?, ?)", check.id(),
          Timestamp.from(run.time()), run.ok(), run.status(), run.ms(), cut(run.error()),
          trigger);
    }
    jdbcTemplate.update("DELETE FROM devpanel_smoke_run WHERE ran_at < ?",
        Timestamp.from(Instant.now().minus(KEEP)));
    return runs;
  }

  Run run(Check check, String trigger) {
    ApiRunner.Response response = runner.send(new ApiRunner.Request(check.method(), check.path(),
        headers(check.headers()), null));
    String error = null;
    if (response.error() != null) {
      error = response.error();
    } else if (response.status() != check.expectedStatus()) {
      error = "HTTP " + response.status() + " instead of " + check.expectedStatus();
    } else if (response.ms() > check.maxMs()) {
      error = "Took " + response.ms() + " ms, more than " + check.maxMs() + " ms";
    } else if (check.bodyContains() != null && (response.body() == null
        || !response.body().contains(check.bodyContains()))) {
      error = "The answer does not contain \"" + check.bodyContains() + "\"";
    }
    return new Run(Instant.now(), error == null, response.status(), response.ms(), error,
        trigger);
  }

  /** "Name: value" per line; ${VARIABLE} is replaced from the environment. */
  Map<String, String> headers(String text) {
    Map<String, String> headers = new LinkedHashMap<>();
    if (text == null) {
      return headers;
    }
    for (String line : text.split("\\r?\\n")) {
      int colon = line.indexOf(':');
      if (colon <= 0) {
        continue;
      }
      String value = line.substring(colon + 1).trim();
      Matcher matcher = VARIABLE.matcher(value);
      StringBuilder resolved = new StringBuilder();
      while (matcher.find()) {
        String replacement = environment.getProperty(matcher.group(1), "");
        matcher.appendReplacement(resolved, Matcher.quoteReplacement(replacement));
      }
      matcher.appendTail(resolved);
      headers.put(line.substring(0, colon).trim(), resolved.toString());
    }
    return headers;
  }

  private static String cut(String text) {
    return text == null || text.length() <= 1000 ? text : text.substring(0, 1000);
  }

  /** {@code headers}: "Name: value" lines, values may use ${ENV_VARIABLE}. */
  public record Check(long id, String name, String method, String path, String headers,
                      int expectedStatus, long maxMs, String bodyContains, boolean enabled,
                      String createdBy, Instant createdAt) {
  }

  public record Run(Instant time, boolean ok, int status, long ms, String error,
                    String trigger) {
  }

  /** {@code success}: share of passed runs in the last 7 days, -1 without runs. */
  public record CheckView(Check check, Run lastRun, List<Run> history, double success) {
  }
}
