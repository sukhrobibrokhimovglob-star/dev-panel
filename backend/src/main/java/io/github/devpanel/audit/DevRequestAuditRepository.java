package io.github.devpanel.audit;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;

/** {@code devpanel_request_audit}: one row per error response of the application. */
public class DevRequestAuditRepository {

  private static final int DEFAULT_PAGE_SIZE = 20;
  private static final int MAX_PAGE_SIZE = 200;

  private final JdbcTemplate jdbcTemplate;

  public DevRequestAuditRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public void insert(AuditEntry entry) {
    jdbcTemplate.update("INSERT INTO devpanel_request_audit (trace_id, http_method, path,"
            + " status_code, duration_ms, client_ip, occurred_at, response_body)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        entry.traceId(), entry.httpMethod(), entry.path(), entry.statusCode(),
        entry.durationMs(), entry.clientIp(), Timestamp.valueOf(entry.occurredAt()),
        entry.responseBody());
  }

  public int deleteOlderThan(LocalDateTime before) {
    return jdbcTemplate.update("DELETE FROM devpanel_request_audit WHERE occurred_at < ?",
        Timestamp.valueOf(before));
  }

  /** Newest first; every filter is optional, {@code path} matches a part, case-insensitively. */
  public Page filter(Filter filter, Integer page, Integer size) {
    StringBuilder where = new StringBuilder(" WHERE 1 = 1");
    List<Object> args = new ArrayList<>();
    if (hasText(filter.traceId())) {
      where.append(" AND trace_id = ?");
      args.add(filter.traceId().trim());
    }
    if (hasText(filter.httpMethod())) {
      where.append(" AND UPPER(http_method) = ?");
      args.add(filter.httpMethod().trim().toUpperCase(Locale.ROOT));
    }
    if (hasText(filter.path())) {
      where.append(" AND LOWER(path) LIKE ?");
      args.add("%" + filter.path().trim().toLowerCase(Locale.ROOT) + "%");
    }
    if (filter.statusCode() != null) {
      where.append(" AND status_code = ?");
      args.add(filter.statusCode());
    }
    if (filter.from() != null) {
      where.append(" AND occurred_at >= ?");
      args.add(Timestamp.valueOf(filter.from()));
    }
    if (filter.to() != null) {
      where.append(" AND occurred_at <= ?");
      args.add(Timestamp.valueOf(filter.to()));
    }

    Long total = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM devpanel_request_audit" + where, Long.class, args.toArray());

    int resolvedPage = page != null && page >= 0 ? page : 0;
    int resolvedSize = size != null && size > 0 ? Math.min(size, MAX_PAGE_SIZE) : DEFAULT_PAGE_SIZE;
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(resolvedSize);
    pageArgs.add((long) resolvedPage * resolvedSize);
    List<AuditEntry> list = jdbcTemplate.query("SELECT id, trace_id, http_method, path,"
            + " status_code, duration_ms, client_ip, occurred_at, response_body"
            + " FROM devpanel_request_audit"
            + where + " ORDER BY occurred_at DESC, id DESC LIMIT ? OFFSET ?",
        (rs, rowNum) -> new AuditEntry(rs.getLong("id"), rs.getString("trace_id"),
            rs.getString("http_method"), rs.getString("path"), rs.getInt("status_code"),
            rs.getLong("duration_ms"), rs.getString("client_ip"),
            rs.getTimestamp("occurred_at").toLocalDateTime(), rs.getString("response_body")),
        pageArgs.toArray());
    return new Page(list, total == null ? 0 : total);
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  /** {@code responseBody}: the start of what the error response said, sensitive data masked. */
  public record AuditEntry(Long id, String traceId, String httpMethod, String path,
                           int statusCode, long durationMs, String clientIp,
                           LocalDateTime occurredAt, String responseBody) {
  }

  public record Filter(String traceId, String httpMethod, String path, Integer statusCode,
                       LocalDateTime from, LocalDateTime to) {
  }

  public record Page(List<AuditEntry> list, long total) {
  }
}
