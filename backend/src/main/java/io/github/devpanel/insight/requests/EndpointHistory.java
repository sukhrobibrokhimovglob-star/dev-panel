package io.github.devpanel.insight.requests;

import io.github.devpanel.insight.LatencyHistogram;
import io.github.devpanel.insight.requests.RequestStats.EndpointView;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Hourly endpoint numbers in {@code devpanel_endpoint_hour}, so comparisons survive a restart:
 * "p95 before this deploy", "the same hour yesterday", the usual level at this hour and weekday,
 * daily trends and monthly goals. One row per hour, endpoint and instance; 35 days are kept.
 */
public class EndpointHistory {

  /** Long enough for monthly goals (SLOs) and month-long trends. */
  private static final Duration KEEP = Duration.ofDays(35);
  private static final Duration BEFORE_DEPLOY = Duration.ofDays(7);

  private final JdbcTemplate jdbcTemplate;
  private final RequestStats stats;
  private final String instance;
  private volatile UsualCache usualCache;

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
                + " calls, server_errors, client_errors, avg_ms, p95_ms, max_ms, sql_per_call,"
                + " avg_rows, max_rows, avg_bytes, histogram)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            hourStart, cut(view.endpoint()), instance, view.calls(), view.serverErrors(),
            view.clientErrors(), view.avgMs(), view.p95Ms(), view.maxMs(), view.sqlPerCall(),
            view.avgRows(), view.maxRows(), view.avgBytes(),
            stats.histogram(hour, view.endpoint()));
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

  /**
   * Per endpoint over the week before {@code deployedAt}: calls, call-weighted p95, server
   * errors and SQL per call.
   */
  public Map<String, Before> beforeDeploy(Instant deployedAt) {
    Map<String, Before> result = new HashMap<>();
    jdbcTemplate.query("SELECT endpoint, SUM(calls) AS calls, SUM(p95_ms * calls) AS weighted,"
            + " SUM(server_errors) AS errors, SUM(sql_per_call * calls) AS sql"
            + " FROM devpanel_endpoint_hour WHERE hour_start >= ? AND hour_start < ?"
            + " GROUP BY endpoint",
        rs -> {
          long calls = rs.getLong("calls");
          if (calls > 0) {
            result.put(rs.getString("endpoint"), new Before(calls, rs.getLong("weighted") / calls,
                rs.getLong("errors"), rs.getDouble("sql") / calls));
          }
        },
        Timestamp.from(deployedAt.minus(BEFORE_DEPLOY)),
        new Timestamp(deployedAt.toEpochMilli() / 3_600_000 * 3_600_000));
    return result;
  }

  public record Before(long calls, long p95Ms, long serverErrors, double sqlPerCall) {
  }

  /** Endpoints called at least once in the kept history (35 days). */
  public java.util.Set<String> calledEndpoints() {
    return new java.util.HashSet<>(jdbcTemplate.queryForList(
        "SELECT DISTINCT endpoint FROM devpanel_endpoint_hour", String.class));
  }

  /** When the kept history starts; null when there is none yet. */
  public Instant historyStart() {
    Timestamp first = jdbcTemplate.queryForObject(
        "SELECT MIN(hour_start) FROM devpanel_endpoint_hour", Timestamp.class);
    return first == null ? null : first.toInstant();
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

  /**
   * Per endpoint and day over the last {@code days}: calls, call-weighted p95 and average rows,
   * the largest row count of one call. Oldest day first.
   */
  public Map<String, List<Day>> daily(int days) {
    Map<String, List<Day>> result = new HashMap<>();
    jdbcTemplate.query("SELECT endpoint, CAST(hour_start AS DATE) AS hour_day, SUM(calls) AS calls,"
            + " SUM(p95_ms * calls) AS weighted, SUM(server_errors) AS errors,"
            + " SUM(client_errors) AS client_errors, MAX(max_rows) AS max_rows,"
            + " SUM(avg_rows * calls) AS rows_total, SUM(avg_bytes * calls) AS bytes_total"
            + " FROM devpanel_endpoint_hour WHERE hour_start >= ?"
            + " GROUP BY endpoint, CAST(hour_start AS DATE) ORDER BY 2",
        rs -> {
          long calls = rs.getLong("calls");
          if (calls > 0) {
            result.computeIfAbsent(rs.getString("endpoint"), key -> new ArrayList<>())
                .add(new Day(rs.getDate("hour_day").toLocalDate(), calls,
                    rs.getLong("weighted") / calls, rs.getLong("errors"),
                    rs.getLong("client_errors"), rs.getLong("max_rows"),
                    rs.getLong("rows_total") / calls, rs.getLong("bytes_total") / calls));
          }
        },
        Timestamp.from(Instant.now().minus(Duration.ofDays(days))));
    return result;
  }

  /**
   * The usual numbers of each endpoint at this hour of the day: the median over the same hour
   * on the previous 7 days and on the same weekday of the previous 4 weeks.
   */
  public Map<String, Usual> usualThisHour() {
    long hour = System.currentTimeMillis() / 3_600_000;
    UsualCache cached = usualCache;
    if (cached != null && cached.hour() == hour
        && cached.at() > System.currentTimeMillis() - 300_000) {
      return cached.usual();
    }
    Map<String, Usual> usual = loadUsual(hour);
    usualCache = new UsualCache(hour, System.currentTimeMillis(), usual);
    return usual;
  }

  private Map<String, Usual> loadUsual(long hour) {
    List<Timestamp> hours = new ArrayList<>();
    for (int day = 1; day <= 7; day++) {
      hours.add(new Timestamp((hour - 24L * day) * 3_600_000));
    }
    for (int week = 2; week <= 4; week++) {
      hours.add(new Timestamp((hour - 168L * week) * 3_600_000));
    }
    String marks = String.join(", ", java.util.Collections.nCopies(hours.size(), "?"));
    Map<String, List<long[]>> samples = new HashMap<>();
    java.util.Set<Long> hoursWithData = new java.util.HashSet<>();
    jdbcTemplate.query("SELECT endpoint, hour_start, SUM(calls) AS calls, MAX(p95_ms) AS p95,"
            + " SUM(server_errors) AS errors FROM devpanel_endpoint_hour"
            + " WHERE hour_start IN (" + marks + ") GROUP BY endpoint, hour_start",
        rs -> {
          hoursWithData.add(rs.getTimestamp("hour_start").getTime());
          samples.computeIfAbsent(rs.getString("endpoint"), key -> new ArrayList<>())
              .add(new long[] {rs.getLong("calls"), rs.getLong("p95"), rs.getLong("errors")});
        },
        hours.toArray());
    Map<String, Usual> usual = new HashMap<>();
    samples.forEach((endpoint, list) -> {
      // An hour the application ran without calls to this endpoint counts as zero calls
      List<long[]> padded = new ArrayList<>(list);
      while (padded.size() < hoursWithData.size()) {
        padded.add(new long[] {0, 0, 0});
      }
      usual.put(endpoint, new Usual(hoursWithData.size(), median(padded, 0), median(list, 1),
          median(padded, 2)));
    });
    return usual;
  }

  /** The stored histograms of {@code endpoint} from {@code from} until {@code until}, merged. */
  public LatencyHistogram histogram(String endpoint, Instant from, Instant until) {
    LatencyHistogram merged = new LatencyHistogram();
    jdbcTemplate.query("SELECT histogram FROM devpanel_endpoint_hour WHERE endpoint = ?"
            + " AND hour_start >= ? AND hour_start < ?",
        rs -> {
          merged.merge(LatencyHistogram.parse(rs.getString("histogram")));
        },
        cut(endpoint), Timestamp.from(from), Timestamp.from(until));
    return merged;
  }

  /** Requests and server errors per day over the last {@code days}, all endpoints. */
  public List<long[]> totalsPerDay(int days) {
    return jdbcTemplate.query("SELECT CAST(hour_start AS DATE) AS hour_day, SUM(calls) AS calls,"
            + " SUM(server_errors) AS errors FROM devpanel_endpoint_hour WHERE hour_start >= ?"
            + " GROUP BY CAST(hour_start AS DATE) ORDER BY 1",
        (rs, i) -> new long[] {rs.getDate("hour_day").toLocalDate().toEpochDay(), rs.getLong("calls"),
            rs.getLong("errors")},
        Timestamp.from(Instant.now().minus(Duration.ofDays(days))));
  }

  private static long median(List<long[]> list, int index) {
    long[] values = list.stream().mapToLong(sample -> sample[index]).sorted().toArray();
    return values.length == 0 ? 0 : values[values.length / 2];
  }

  /** One endpoint's day; times in ms. */
  public record Day(java.time.LocalDate day, long calls, long p95Ms, long serverErrors,
                    long clientErrors, long maxRows, long avgRows, long avgBytes) {
  }

  private record UsualCache(long hour, long at, Map<String, Usual> usual) {
  }

  /** {@code samples}: how many past hours the medians come from. */
  public record Usual(int samples, long calls, long p95Ms, long serverErrors) {
  }

  private static String cut(String value) {
    return value.length() > 300 ? value.substring(0, 300) : value;
  }
}
