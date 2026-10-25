package io.github.devpanel.insight.slo;

import io.github.devpanel.insight.LatencyHistogram;
import io.github.devpanel.insight.requests.EndpointHistory;
import io.github.devpanel.insight.requests.RequestStats;
import io.github.devpanel.support.DevPanelException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Goals per endpoint, e.g. "95% of calls of POST /v1/report answer within 500 ms". How each goal
 * did over the last 7 and 30 days comes from the hourly latency histograms (plus the last two
 * hours from memory), with the error budget left: the share of slow calls the goal still allows
 * this month, and at the pace of the last 7 days, when it runs out.
 */
public class SloService {

  private static final Duration RECENT = Duration.ofHours(2);

  private final JdbcTemplate jdbcTemplate;
  private final EndpointHistory history;
  private final RequestStats requests;

  public SloService(JdbcTemplate jdbcTemplate, EndpointHistory history, RequestStats requests) {
    this.jdbcTemplate = jdbcTemplate;
    this.history = history;
    this.requests = requests;
  }

  public List<Goal> goals() {
    return jdbcTemplate.query("SELECT id, endpoint, target_ms, objective, created_by, created_at"
            + " FROM devpanel_slo ORDER BY endpoint",
        (rs, i) -> new Goal(rs.getLong("id"), rs.getString("endpoint"), rs.getLong("target_ms"),
            rs.getDouble("objective"), rs.getString("created_by"),
            rs.getTimestamp("created_at").toInstant()));
  }

  public List<Status> statuses() {
    List<Status> statuses = new ArrayList<>();
    for (Goal goal : goals()) {
      statuses.add(status(goal));
    }
    return statuses;
  }

  public Status status(Goal goal) {
    long[] week = within(goal, 7);
    long[] month = within(goal, 30);
    double weekShare = week[1] == 0 ? -1 : (double) week[0] / week[1];
    double monthShare = month[1] == 0 ? -1 : (double) month[0] / month[1];
    double allowedBad = 1 - goal.objective();
    // Budget: of the slow calls the goal allows over 30 days, how many are left
    double budgetLeft = month[1] == 0 || allowedBad <= 0 ? 1
        : 1 - ((month[1] - month[0]) / (month[1] * allowedBad));
    // Burn: slow share of the last 7 days relative to what is allowed (1 = exactly on budget)
    double burnRate = week[1] == 0 || allowedBad <= 0 ? 0
        : ((week[1] - week[0]) / (double) week[1]) / allowedBad;
    double daysLeft = burnRate <= 1 || budgetLeft <= 0 ? (budgetLeft <= 0 ? 0 : -1)
        : budgetLeft * 30 / burnRate;
    return new Status(goal, week[0], week[1], weekShare, month[0], month[1], monthShare,
        Math.max(-1, budgetLeft), burnRate, daysLeft);
  }

  public Goal create(String endpoint, long targetMs, double objective, String createdBy) {
    if (endpoint == null || endpoint.isBlank() || endpoint.length() > 300) {
      throw DevPanelException.badRequest("Choose an endpoint");
    }
    if (targetMs < 1 || targetMs > 600_000) {
      throw DevPanelException.badRequest("The target must be between 1 ms and 10 minutes");
    }
    if (objective < 0.5 || objective >= 1) {
      throw DevPanelException.badRequest("The objective must be between 50% and 99.99%");
    }
    try {
      jdbcTemplate.update("INSERT INTO devpanel_slo (endpoint, target_ms, objective, created_by,"
              + " created_at) VALUES (?, ?, ?, ?, ?)", endpoint.trim(), targetMs, objective,
          createdBy, Timestamp.from(Instant.now()));
    } catch (DuplicateKeyException e) {
      throw DevPanelException.badRequest("This endpoint already has a goal");
    }
    return goals().stream().filter(goal -> goal.endpoint().equals(endpoint.trim())).findFirst()
        .orElseThrow();
  }

  public void delete(long id) {
    if (jdbcTemplate.update("DELETE FROM devpanel_slo WHERE id = ?", id) == 0) {
      throw DevPanelException.notFound("No such goal");
    }
  }

  /** {within target, all} over the last {@code days}. */
  private long[] within(Goal goal, int days) {
    Instant now = Instant.now();
    Instant recentStart = Instant.ofEpochMilli(
        (now.toEpochMilli() / 3_600_000 - 1) * 3_600_000);
    LatencyHistogram stored = history.histogram(goal.endpoint(), now.minus(Duration.ofDays(days)),
        recentStart);
    long[] recent = requests.withinTarget(goal.endpoint(), goal.targetMs(),
        now.toEpochMilli(), (int) RECENT.toHours());
    return new long[] {stored.countAtMost(goal.targetMs()) + recent[0],
        stored.count() + recent[1]};
  }

  /** {@code objective}: 0.5..0.9999, e.g. 0.95. */
  public record Goal(long id, String endpoint, long targetMs, double objective, String createdBy,
                     Instant createdAt) {
  }

  /**
   * Shares 0..1 (-1 without calls). {@code budgetLeft}: share of the 30-day error budget left;
   * {@code burnRate}: 7-day slow share / allowed (above 1 spends faster than allowed);
   * {@code budgetDaysLeft}: when the budget runs out at that pace, -1 if not.
   */
  public record Status(Goal goal, long weekWithin, long weekCalls, double weekShare,
                       long monthWithin, long monthCalls, double monthShare, double budgetLeft,
                       double burnRate, double budgetDaysLeft) {

    @com.fasterxml.jackson.annotation.JsonProperty("met")
    public boolean met() {
      return monthShare < 0 || monthShare >= goal.objective();
    }
  }
}
