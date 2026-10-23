package io.github.devpanel.insight.requests;

import io.github.devpanel.insight.requests.RequestStats.EndpointView;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Hourly endpoint numbers in {@code devpanel_endpoint_hour}, so comparisons survive a restart:
 * "p95 before this deploy" and "the same hour yesterday". One row per hour, endpoint and instance;
 * 14 days are kept.
 */
public class EndpointHistory {

  private static final Duration KEEP = Duration.ofDays(14);
  private static final Duration BEFORE_DEPLOY = Duration.ofDays(7);

  private final JdbcTemplate jdbcTemplate;
  private final RequestStats stats;
  private final String instance;

  public EndpointHistory(JdbcTemplate jdbcTemplate, RequestStats stats, String instance) {
    this.jdbcTemplate = jdbcTemplate;
    this.stats = stats;
    this.instance = instance.length() > 100 ? instance.substring(0, 100) : instance;
  }

  /** Writes the current and the previous hour (called every few minutes and at shutdown). */
  public void flush() {
    long current = System.currentTimeMillis() / 3_600_000;
    for (long hour = current - 1; hour <= current; hour++) {
      Timestamp hourStart = new Timestamp(hour * 3_600_000);
      Map<String, EndpointView> views = stats.hour(hour);
      if (views.isEmpty()) {
        continue;
      }
      jdbcTemplate.update("DELETE FROM devpanel_endpoint_hour WHERE hour_start = ? AND instance = ?",
          hourStart, instance);
      for (EndpointView view : views.values()) {
        jdbcTemplate.update("INSERT INTO devpanel_endpoint_hour (hour_start, endpoint, instance,"
                + " calls, server_errors, client_errors, avg_ms, p95_ms, max_ms, sql_per_call)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            hourStart, cut(view.endpoint()), instance, view.calls(), view.serverErrors(),
            view.clientErrors(), view.avgMs(), view.p95Ms(), view.maxMs(), view.sqlPerCall());
      }
    }
    jdbcTemplate.update("DELETE FROM devpanel_endpoint_hour WHERE hour_start < ?",
        Timestamp.from(Instant.now().minus(KEEP)));
  }

  /** Call-weighted p95 per endpoint over the week before {@code deployedAt}. */
  public Map<String, Long> p95BeforeDeploy(Instant deployedAt) {
    Map<String, Long> result = new HashMap<>();
    jdbcTemplate.query("SELECT endpoint, SUM(p95_ms * calls) AS weighted, SUM(calls) AS calls"
            + " FROM devpanel_endpoint_hour WHERE hour_start >= ? AND hour_start < ?"
            + " GROUP BY endpoint",
        rs -> {
          long calls = rs.getLong("calls");
          if (calls > 0) {
            result.put(rs.getString("endpoint"), rs.getLong("weighted") / calls);
          }
        },
        Timestamp.from(deployedAt.minus(BEFORE_DEPLOY)),
        new Timestamp(deployedAt.toEpochMilli() / 3_600_000 * 3_600_000));
    return result;
  }

  /** p95 and calls per endpoint in the same hour one day ago, all instances together. */
  public Map<String, long[]> sameHourYesterday() {
    long hourStart = (System.currentTimeMillis() / 3_600_000 - 24) * 3_600_000;
    Map<String, long[]> result = new HashMap<>();
    jdbcTemplate.query("SELECT endpoint, MAX(p95_ms) AS p95, SUM(calls) AS calls,"
            + " SUM(server_errors) AS errors FROM devpanel_endpoint_hour WHERE hour_start = ?"
            + " GROUP BY endpoint",
        rs -> {
          result.put(rs.getString("endpoint"),
              new long[] {rs.getLong("p95"), rs.getLong("calls"), rs.getLong("errors")});
        },
        new Timestamp(hourStart));
    return result;
  }

  /** Server errors per hour on the previous days at this hour, as the normal level to compare to. */
  public double usualServerErrorsPerHour() {
    long hour = System.currentTimeMillis() / 3_600_000;
    Double usual = jdbcTemplate.queryForObject("SELECT AVG(errors) FROM (SELECT hour_start,"
            + " SUM(server_errors) AS errors FROM devpanel_endpoint_hour WHERE hour_start IN"
            + " (?, ?, ?, ?, ?, ?, ?) GROUP BY hour_start) t", Double.class,
        new Timestamp((hour - 24) * 3_600_000), new Timestamp((hour - 48) * 3_600_000),
        new Timestamp((hour - 72) * 3_600_000), new Timestamp((hour - 96) * 3_600_000),
        new Timestamp((hour - 120) * 3_600_000), new Timestamp((hour - 144) * 3_600_000),
        new Timestamp((hour - 168) * 3_600_000));
    return usual == null ? -1 : usual;
  }

  private static String cut(String value) {
    return value.length() > 300 ? value.substring(0, 300) : value;
  }
}
