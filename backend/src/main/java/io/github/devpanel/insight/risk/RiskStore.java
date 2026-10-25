package io.github.devpanel.insight.risk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devpanel.insight.risk.Risk.Severity;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The lifecycle of risks in {@code devpanel_finding}: when each was first seen, whether it got
 * worse, when it went away, and what people decided about it (snoozed until a date, or accepted
 * with a reason). A risk that comes back after being resolved counts as new again.
 */
public class RiskStore {

  private static final ObjectMapper JSON = new ObjectMapper()
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  private static final int MAX_PAYLOAD = 8000;
  private static final Duration KEEP_RESOLVED = Duration.ofDays(90);
  private static final String COLUMNS = "finding_key, category, area, severity, worst_severity,"
      + " title, payload, score, first_seen, last_seen, resolved_at, snoozed_until, accepted_by,"
      + " accepted_reason, accepted_at, alerted_severity";

  private final JdbcTemplate jdbcTemplate;

  public RiskStore(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Stores this run's risks; risks of {@code succeeded} detectors that were open and are gone
   * now are resolved. Detectors that failed keep their risks as they were.
   */
  public synchronized void sync(List<Risk> risks, Set<String> succeeded, Instant now) {
    Map<String, View> stored = new HashMap<>();
    for (View view : all()) {
      stored.put(view.risk().key(), view);
    }
    Timestamp at = Timestamp.from(now);
    Set<String> seen = new java.util.HashSet<>();
    for (Risk risk : risks) {
      String key = cut(risk.key(), 300);
      if (!seen.add(key)) {
        continue;
      }
      View old = stored.get(key);
      String changedFrom = null;
      Instant changedAt = null;
      if (old != null && old.open() && old.risk().severity() != risk.severity()) {
        changedFrom = old.risk().severity().name();
        changedAt = now;
      } else if (old != null && old.open()) {
        changedFrom = old.changedFrom();
        changedAt = old.changedAt();
      }
      String payload = payload(risk, changedFrom, changedAt);
      if (old == null) {
        jdbcTemplate.update("INSERT INTO devpanel_finding (finding_key, category, area, severity,"
                + " worst_severity, title, payload, score, first_seen, last_seen)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", key, risk.category().name(),
            cut(risk.area(), 30), risk.severity().name(), risk.severity().name(),
            cut(risk.title(), 500), payload, risk.score(), at, at);
      } else {
        boolean reopened = old.resolvedAt() != null;
        Severity worst = reopened || risk.severity().rank() > old.worst().rank()
            ? risk.severity() : old.worst();
        jdbcTemplate.update("UPDATE devpanel_finding SET category = ?, area = ?, severity = ?,"
                + " worst_severity = ?, title = ?, payload = ?, score = ?, last_seen = ?,"
                + " resolved_at = NULL" + (reopened ? ", first_seen = ?, alerted_severity = NULL"
                : "") + " WHERE finding_key = ?",
            reopened ? new Object[] {risk.category().name(), cut(risk.area(), 30),
                risk.severity().name(), worst.name(), cut(risk.title(), 500), payload,
                risk.score(), at, at, key}
                : new Object[] {risk.category().name(), cut(risk.area(), 30),
                    risk.severity().name(), worst.name(), cut(risk.title(), 500), payload,
                    risk.score(), at, key});
      }
    }
    for (View old : stored.values()) {
      String key = old.risk().key();
      String detector = key.contains(".") ? key.substring(0, key.indexOf('.')) : key;
      if (old.resolvedAt() == null && !seen.contains(key) && succeeded.contains(detector)) {
        jdbcTemplate.update("UPDATE devpanel_finding SET resolved_at = ? WHERE finding_key = ?",
            at, key);
      }
    }
    jdbcTemplate.update("DELETE FROM devpanel_finding WHERE resolved_at < ?",
        Timestamp.from(now.minus(KEEP_RESOLVED)));
  }

  public List<View> all() {
    return jdbcTemplate.query("SELECT " + COLUMNS + " FROM devpanel_finding", this::view);
  }

  public void snooze(String key, Instant until) {
    require(jdbcTemplate.update("UPDATE devpanel_finding SET snoozed_until = ? WHERE"
        + " finding_key = ?", Timestamp.from(until), key));
  }

  public void accept(String key, String by, String reason) {
    require(jdbcTemplate.update("UPDATE devpanel_finding SET accepted_by = ?, accepted_reason = ?,"
            + " accepted_at = ? WHERE finding_key = ?", by, cut(reason, 500),
        Timestamp.from(Instant.now()), key));
  }

  /** Back to open: no snooze, not accepted. */
  public void reopen(String key) {
    require(jdbcTemplate.update("UPDATE devpanel_finding SET snoozed_until = NULL,"
        + " accepted_by = NULL, accepted_reason = NULL, accepted_at = NULL WHERE"
        + " finding_key = ?", key));
  }

  public void alerted(String key, Severity severity) {
    jdbcTemplate.update("UPDATE devpanel_finding SET alerted_severity = ? WHERE finding_key = ?",
        severity == null ? null : severity.name(), key);
  }

  private static void require(int updated) {
    if (updated == 0) {
      throw io.github.devpanel.support.DevPanelException.notFound("No such risk");
    }
  }

  private View view(ResultSet rs, int row) throws SQLException {
    Payload payload = read(rs.getString("payload"));
    Risk risk = payload == null || payload.risk() == null
        ? new Risk(rs.getString("finding_key"), Risk.Category.valueOf(rs.getString("category")),
            rs.getString("area"), Severity.valueOf(rs.getString("severity")),
            rs.getString("title"), null, null, null, null, null, null, null, 1)
        : payload.risk();
    String alerted = rs.getString("alerted_severity");
    return new View(risk, rs.getDouble("score"), Severity.valueOf(rs.getString("worst_severity")),
        instant(rs.getTimestamp("first_seen")), instant(rs.getTimestamp("last_seen")),
        instant(rs.getTimestamp("resolved_at")), instant(rs.getTimestamp("snoozed_until")),
        rs.getString("accepted_by"), rs.getString("accepted_reason"),
        instant(rs.getTimestamp("accepted_at")),
        alerted == null ? null : Severity.valueOf(alerted),
        payload == null ? null : payload.changedFrom(),
        payload == null || payload.changedAt() == null ? null
            : Instant.ofEpochMilli(payload.changedAt()));
  }

  private static String payload(Risk risk, String changedFrom, Instant changedAt) {
    Risk fitted = risk;
    try {
      Long at = changedAt == null ? null : changedAt.toEpochMilli();
      String json = JSON.writeValueAsString(new Payload(fitted, changedFrom, at));
      if (json.length() > MAX_PAYLOAD) {
        fitted = new Risk(risk.key(), risk.category(), risk.area(), risk.severity(),
            risk.title(), cut(risk.impact(), 1000), cut(risk.evidence(), 2000),
            cut(risk.fix(), 1000), cut(risk.where(), 500), cut(risk.sql(), 1000),
            risk.daysLeft(), risk.tab(), risk.weight());
        json = JSON.writeValueAsString(new Payload(fitted, changedFrom, at));
      }
      return json.length() > MAX_PAYLOAD ? null : json;
    } catch (JsonProcessingException e) {
      return null;
    }
  }

  private static Payload read(String json) {
    if (json == null) {
      return null;
    }
    try {
      return JSON.readValue(json, Payload.class);
    } catch (JsonProcessingException e) {
      return null;
    }
  }

  private static Instant instant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }

  private static String cut(String value, int max) {
    return value == null || value.length() <= max ? value : value.substring(0, max - 1) + "…";
  }

  /** What is stored besides the columns: the full risk and its last severity change (ms). */
  public record Payload(Risk risk, String changedFrom, Long changedAt) {
  }

  /**
   * A stored risk. {@code changedFrom}: its severity before the last change (null if it never
   * changed); {@code alerted}: the severity it was last alerted at.
   */
  public record View(Risk risk, double score, Severity worst, Instant firstSeen, Instant lastSeen,
                     Instant resolvedAt, Instant snoozedUntil, String acceptedBy,
                     String acceptedReason, Instant acceptedAt, Severity alerted,
                     String changedFrom, Instant changedAt) {

    public boolean open() {
      return resolvedAt == null;
    }

    public boolean silenced() {
      return acceptedBy != null || (snoozedUntil != null && snoozedUntil.isAfter(Instant.now()));
    }

    public boolean isNew() {
      return firstSeen != null && firstSeen.isAfter(Instant.now().minus(Duration.ofDays(1)));
    }

    /** Its severity went up since it was first seen. */
    public boolean worse() {
      return changedFrom != null && Severity.valueOf(changedFrom).rank() < risk.severity().rank();
    }

    public List<String> flags() {
      List<String> flags = new ArrayList<>();
      if (isNew()) {
        flags.add("NEW");
      }
      if (worse()) {
        flags.add("WORSE");
      }
      return flags;
    }
  }
}
