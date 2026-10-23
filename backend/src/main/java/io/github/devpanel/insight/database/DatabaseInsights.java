package io.github.devpanel.insight.database;

import io.github.devpanel.support.DevPanelException;
import java.sql.DatabaseMetaData;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcUtils;

/**
 * What PostgreSQL itself knows about the application's database: what runs right now and who
 * blocks whom, the slowest statements (pg_stat_statements), tables that are scanned without an
 * index or full of dead rows, unused indexes, size and connections. Fixed queries of the panel's
 * own; nothing here goes through the SQL console.
 */
public class DatabaseInsights {

  private static final int TIMEOUT_SECONDS = 10;

  private final JdbcTemplate jdbcTemplate;
  private final boolean postgres;

  public DatabaseInsights(DataSource dataSource) {
    this.jdbcTemplate = new JdbcTemplate(dataSource);
    this.jdbcTemplate.setQueryTimeout(TIMEOUT_SECONDS);
    this.postgres = isPostgres(dataSource);
  }

  public boolean isPostgres() {
    return postgres;
  }

  public Overview overview() {
    requirePostgres();
    Map<String, Object> row = jdbcTemplate.queryForMap("""
        select version() as version,
               current_database() as database,
               pg_database_size(current_database()) as size_bytes,
               current_setting('max_connections')::int as max_connections,
               (select count(*) from pg_stat_activity) as connections,
               (select count(*) from pg_stat_activity where datname = current_database()) as own,
               (select count(*) from pg_stat_activity where datname = current_database()
                  and state = 'active') as active,
               (select count(*) from pg_stat_activity where datname = current_database()
                  and state like 'idle in transaction%') as idle_in_transaction,
               (select count(*) from pg_stat_activity where datname = current_database()
                  and cardinality(pg_blocking_pids(pid)) > 0) as blocked,
               d.blks_hit, d.blks_read, d.deadlocks, d.temp_bytes, d.xact_commit, d.xact_rollback
        from pg_stat_database d where d.datname = current_database()""");
    long hit = number(row.get("blks_hit"));
    long read = number(row.get("blks_read"));
    return new Overview(String.valueOf(row.get("version")), String.valueOf(row.get("database")),
        number(row.get("size_bytes")), number(row.get("max_connections")),
        number(row.get("connections")), number(row.get("own")), number(row.get("active")),
        number(row.get("idle_in_transaction")), number(row.get("blocked")),
        hit + read == 0 ? 1 : (double) hit / (hit + read), number(row.get("deadlocks")),
        number(row.get("temp_bytes")), number(row.get("xact_commit")),
        number(row.get("xact_rollback")), statementsAvailable());
  }

  /** Everything connected to this database except this very query, longest running first. */
  public List<Activity> activity() {
    requirePostgres();
    return jdbcTemplate.query("""
        select pid, usename, application_name, client_addr::text as client, state,
               wait_event_type, wait_event, backend_start, xact_start, query_start,
               extract(epoch from (now() - coalesce(query_start, backend_start))) * 1000 as ms,
               extract(epoch from (now() - xact_start)) * 1000 as xact_ms,
               pg_blocking_pids(pid)::text as blocked_by, left(query, 4000) as query
        from pg_stat_activity
        where datname = current_database() and pid <> pg_backend_pid()
          and backend_type = 'client backend'
        order by (state = 'idle') asc, coalesce(xact_start, query_start) asc nulls last""",
        (rs, i) -> new Activity(rs.getInt("pid"), rs.getString("usename"),
            rs.getString("application_name"), rs.getString("client"), rs.getString("state"),
            rs.getString("wait_event_type"), rs.getString("wait_event"),
            time(rs.getTimestamp("xact_start")), time(rs.getTimestamp("query_start")),
            rs.getLong("ms"), rs.getObject("xact_ms") == null ? -1 : rs.getLong("xact_ms"),
            pids(rs.getString("blocked_by")), rs.getString("query")));
  }

  /** The 50 statements that took the most time in total, from pg_stat_statements. */
  public List<Statement> statements() {
    requirePostgres();
    if (!statementsAvailable()) {
      throw new DevPanelException(HttpStatus.NOT_IMPLEMENTED, "pg_stat_statements is not"
          + " installed. Add shared_preload_libraries = 'pg_stat_statements' to postgresql.conf,"
          + " restart PostgreSQL and run CREATE EXTENSION pg_stat_statements;");
    }
    Integer version = jdbcTemplate.queryForObject(
        "select current_setting('server_version_num')::int", Integer.class);
    String total = version != null && version >= 130000 ? "total_exec_time" : "total_time";
    String mean = version != null && version >= 130000 ? "mean_exec_time" : "mean_time";
    String max = version != null && version >= 130000 ? "max_exec_time" : "max_time";
    return jdbcTemplate.query("select left(query, 4000) as query, calls, " + total + " as total,"
            + " " + mean + " as mean, " + max + " as max, rows, shared_blks_read, shared_blks_hit"
            + " from pg_stat_statements s join pg_database d on d.oid = s.dbid"
            + " where d.datname = current_database() order by " + total + " desc limit 50",
        (rs, i) -> new Statement(rs.getString("query"), rs.getLong("calls"),
            rs.getDouble("total"), rs.getDouble("mean"), rs.getDouble("max"), rs.getLong("rows"),
            rs.getLong("shared_blks_hit") + rs.getLong("shared_blks_read") == 0 ? 1
                : rs.getDouble("shared_blks_hit")
                    / (rs.getLong("shared_blks_hit") + rs.getLong("shared_blks_read"))));
  }

  /**
   * Tables by size, with two warnings: probably missing an index (big, read mostly by full
   * scans) and bloated (many dead rows, vacuum falls behind).
   */
  public List<TableStat> tables() {
    requirePostgres();
    return jdbcTemplate.query("""
        select schemaname, relname, n_live_tup, n_dead_tup, seq_scan, seq_tup_read,
               coalesce(idx_scan, 0) as idx_scan, pg_total_relation_size(relid) as total_bytes,
               greatest(last_autovacuum, last_vacuum) as vacuumed,
               greatest(last_autoanalyze, last_analyze) as analyzed
        from pg_stat_user_tables order by pg_total_relation_size(relid) desc limit 200""",
        (rs, i) -> {
          long live = rs.getLong("n_live_tup");
          long dead = rs.getLong("n_dead_tup");
          long seqScan = rs.getLong("seq_scan");
          long idxScan = rs.getLong("idx_scan");
          long seqRead = rs.getLong("seq_tup_read");
          boolean missingIndex = live > 10_000 && seqScan > 100 && seqScan > idxScan
              && seqRead / Math.max(1, seqScan) > 5_000;
          boolean bloated = dead > 10_000 && dead > (live + dead) * 0.2;
          return new TableStat(rs.getString("schemaname"), rs.getString("relname"), live, dead,
              seqScan, idxScan, rs.getLong("total_bytes"), time(rs.getTimestamp("vacuumed")),
              time(rs.getTimestamp("analyzed")), missingIndex, bloated);
        });
  }

  /** Indexes never used since statistics were reset; they cost space and every write. */
  public List<UnusedIndex> unusedIndexes() {
    requirePostgres();
    return jdbcTemplate.query("""
        select s.schemaname, s.relname, s.indexrelname, pg_relation_size(s.indexrelid) as bytes
        from pg_stat_user_indexes s join pg_index i on i.indexrelid = s.indexrelid
        where s.idx_scan = 0 and not i.indisunique and not i.indisprimary
          and pg_relation_size(s.indexrelid) > 1024 * 1024
        order by pg_relation_size(s.indexrelid) desc limit 100""",
        (rs, i) -> new UnusedIndex(rs.getString("schemaname"), rs.getString("relname"),
            rs.getString("indexrelname"), rs.getLong("bytes")));
  }

  /**
   * Stops a statement ({@code terminate=false}) or closes the whole connection. Only for
   * connections to this database, never the panel's own.
   */
  public boolean stop(int pid, boolean terminate) {
    requirePostgres();
    Integer own = jdbcTemplate.queryForObject("select count(*) from pg_stat_activity"
        + " where pid = ? and datname = current_database() and pid <> pg_backend_pid()",
        Integer.class, pid);
    if (own == null || own == 0) {
      throw DevPanelException.notFound("No such connection to this database: " + pid);
    }
    Boolean done = jdbcTemplate.queryForObject(terminate
        ? "select pg_terminate_backend(?)" : "select pg_cancel_backend(?)", Boolean.class, pid);
    return Boolean.TRUE.equals(done);
  }

  /** For the health check: numbers only, never throws. */
  public Overview overviewOrNull() {
    try {
      return postgres ? overview() : null;
    } catch (DataAccessException e) {
      return null;
    }
  }

  private boolean statementsAvailable() {
    try {
      Integer count = jdbcTemplate.queryForObject(
          "select count(*) from pg_extension where extname = 'pg_stat_statements'", Integer.class);
      return count != null && count > 0;
    } catch (DataAccessException e) {
      return false;
    }
  }

  private void requirePostgres() {
    if (!postgres) {
      throw new DevPanelException(HttpStatus.NOT_IMPLEMENTED,
          "Database insights need PostgreSQL");
    }
  }

  private static boolean isPostgres(DataSource dataSource) {
    try {
      String product = JdbcUtils.extractDatabaseMetaData(dataSource,
          DatabaseMetaData::getDatabaseProductName);
      return product != null && product.toLowerCase(Locale.ROOT).contains("postgres");
    } catch (Exception e) {
      return false;
    }
  }

  private static long number(Object value) {
    return value instanceof Number number ? number.longValue() : 0;
  }

  private static String time(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toLocalDateTime().toString();
  }

  private static List<Integer> pids(String array) {
    List<Integer> pids = new ArrayList<>();
    if (array != null) {
      for (String part : array.replace("{", "").replace("}", "").split(",")) {
        if (!part.isBlank()) {
          pids.add(Integer.parseInt(part.trim()));
        }
      }
    }
    return pids;
  }

  public record Overview(String version, String database, long sizeBytes, long maxConnections,
                         long connections, long ownConnections, long active,
                         long idleInTransaction, long blocked, double cacheHitRatio,
                         long deadlocks, long tempBytes, long commits, long rollbacks,
                         boolean statementsAvailable) {
  }

  /** {@code ms}: how long the current statement (or the idle state) has lasted. */
  public record Activity(int pid, String user, String application, String client, String state,
                         String waitType, String waitEvent, String transactionStart,
                         String queryStart, long ms, long transactionMs, List<Integer> blockedBy,
                         String query) {
  }

  /** Times in ms; {@code cacheHitRatio} 0..1. */
  public record Statement(String query, long calls, double totalMs, double meanMs, double maxMs,
                          long rows, double cacheHitRatio) {
  }

  public record TableStat(String schema, String table, long liveRows, long deadRows,
                          long seqScans, long indexScans, long totalBytes, String lastVacuum,
                          String lastAnalyze, boolean missingIndex, boolean bloated) {
  }

  public record UnusedIndex(String schema, String table, String index, long bytes) {
  }
}
