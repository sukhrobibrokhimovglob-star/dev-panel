package io.github.devpanel.audit;

import io.github.devpanel.account.DevAccount;
import io.github.devpanel.support.ClientIp;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * What people did inside the panel: SQL they ran, queries they stopped, log levels, accounts,
 * requests sent from the API runner, risks they snoozed. Kept 180 days in {@code devpanel_action},
 * so "who changed this, when, from where" has an answer.
 */
public class DevActionLog {

  private static final Logger log = LoggerFactory.getLogger(DevActionLog.class);
  private static final int KEEP_DAYS = 180;

  private final JdbcTemplate jdbcTemplate;

  public DevActionLog(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** Never fails the action itself. */
  public void record(DevAccount actor, String action, String target, String detail) {
    try {
      jdbcTemplate.update("INSERT INTO devpanel_action (occurred_at, username, action, target,"
              + " detail, client_ip) VALUES (?, ?, ?, ?, ?, ?)",
          Timestamp.from(Instant.now()), actor == null ? null : actor.username(), cut(action, 60),
          cut(target, 500), cut(detail, 2000), clientIp());
    } catch (DataAccessException e) {
      log.warn("Dev panel could not record action {}: {}", action, e.getMessage());
    }
  }

  public Page list(String username, String action, int page, int size) {
    StringBuilder where = new StringBuilder(" WHERE 1 = 1");
    List<Object> args = new ArrayList<>();
    if (username != null && !username.isBlank()) {
      where.append(" AND username = ?");
      args.add(username.trim().toLowerCase());
    }
    if (action != null && !action.isBlank()) {
      where.append(" AND action = ?");
      args.add(action.trim());
    }
    Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM devpanel_action" + where,
        Long.class, args.toArray());
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(size);
    pageArgs.add((long) page * size);
    List<Entry> entries = jdbcTemplate.query("SELECT id, occurred_at, username, action, target,"
            + " detail, client_ip FROM devpanel_action" + where
            + " ORDER BY occurred_at DESC, id DESC LIMIT ? OFFSET ?",
        (rs, i) -> new Entry(rs.getLong("id"), rs.getTimestamp("occurred_at").toInstant(),
            rs.getString("username"), rs.getString("action"), rs.getString("target"),
            rs.getString("detail"), rs.getString("client_ip")),
        pageArgs.toArray());
    return new Page(entries, total == null ? 0 : total);
  }

  public void deleteOld() {
    jdbcTemplate.update("DELETE FROM devpanel_action WHERE occurred_at < ?",
        Timestamp.from(Instant.now().minusSeconds(KEEP_DAYS * 86_400L)));
  }

  private static String clientIp() {
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
      HttpServletRequest request = attributes.getRequest();
      return ClientIp.of(request);
    }
    return null;
  }

  private static String cut(String value, int max) {
    return value == null || value.length() <= max ? value : value.substring(0, max - 1) + "…";
  }

  public record Entry(long id, Instant time, String username, String action, String target,
                      String detail, String clientIp) {
  }

  public record Page(List<Entry> content, long total) {
  }
}
