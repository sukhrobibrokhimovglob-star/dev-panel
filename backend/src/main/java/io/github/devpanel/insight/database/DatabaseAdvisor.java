package io.github.devpanel.insight.database;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Weak spots in the database that do not hurt yet but will as data grows, found in PostgreSQL's
 * own catalog and statistics: foreign keys without an index, id columns approaching the largest
 * number they can hold, possible duplicates in reference tables, tables read all the time but
 * almost never changed (cache them), tables read by full scans with the column an index would
 * need, statistics the planner never got, bloat. Each check is a fixed query of the panel's own,
 * with a short timeout; the expensive ones are cached for hours.
 */
public class DatabaseAdvisor {

  private static final Logger log = LoggerFactory.getLogger(DatabaseAdvisor.class);
  private static final long CACHE_MS = 6 * 3_600_000L;
  private static final int DUPLICATE_TABLE_LIMIT = 200_000;
  private static final Pattern DUPLICATE_COLUMN = Pattern.compile(
      "^(code|name|name_[a-z]{2}|title|short_name|full_name|pnfl|pinfl|inn|tin|email|phone"
          + "|login|username|number|serial|slug)$");
  private static final Pattern FROM_ALIAS = Pattern.compile(
      "(?i)\\b(?:from|join)\\s+(?:\"?(\\w+)\"?\\.)?\"?(\\w+)\"?(?:\\s+(?:as\\s+)?(\\w+))?");
  private static final Pattern EQUALS_PARAMETER = Pattern.compile(
      "(?i)(?:\\b(\\w+)\\.)?\"?(\\w+)\"?\\s*(=|in\\s*\\(|>=|<=|>|<|between)\\s*(?:\\?|\\$\\d+)");
  private static final Pattern LIKE_PARAMETER = Pattern.compile(
      "(?i)(?:lower|upper)?\\s*\\(?\\s*(?:\\b(\\w+)\\.)?\"?(\\w+)\"?\\s*\\)?\\s*(?:i?like)\\s*"
          + "(?:lower\\s*\\(\\s*)?(?:\\?|\\$\\d+)");
  private static final Set<String> SQL_WORDS = Set.of("where", "and", "or", "on", "set", "select",
      "left", "right", "inner", "outer", "join", "order", "group", "by", "limit", "offset",
      "having", "not", "case", "when", "then", "else", "end", "as", "is", "null", "lower",
      "upper", "cross", "full", "natural", "using", "values", "returning", "union", "except",
      "intersect", "exists", "distinct", "lateral");

  private final JdbcTemplate jdbcTemplate;
  private final DatabaseInsights insights;
  private final Map<String, Cached<?>> cache = new HashMap<>();

  public DatabaseAdvisor(DataSource dataSource, DatabaseInsights insights) {
    this.jdbcTemplate = new JdbcTemplate(dataSource);
    this.jdbcTemplate.setQueryTimeout(15);
    this.insights = insights;
  }

  public boolean available() {
    return insights.isPostgres();
  }

  /** Foreign keys whose columns do not start any index: every delete of a parent scans them. */
  public List<ForeignKey> foreignKeysWithoutIndex() {
    return cached("fk", () -> jdbcTemplate.query("""
        select n.nspname as schema_name, t.relname as table_name, c.conname,
               (select string_agg(a.attname, ', ' order by x.n)
                  from unnest(c.conkey) with ordinality as x(attnum, n)
                  join pg_attribute a on a.attrelid = c.conrelid and a.attnum = x.attnum)
                 as columns,
               rt.relname as referenced,
               coalesce(s.n_live_tup, 0) as live_rows,
               pg_total_relation_size(c.conrelid) as bytes
        from pg_constraint c
        join pg_class t on t.oid = c.conrelid
        join pg_namespace n on n.oid = t.relnamespace
        join pg_class rt on rt.oid = c.confrelid
        left join pg_stat_user_tables s on s.relid = c.conrelid
        where c.contype = 'f'
          and n.nspname not in ('pg_catalog', 'information_schema')
          and t.relname not like 'devpanel\\_%'
          and not exists (
            select 1 from pg_index i
            where i.indrelid = c.conrelid
              and (string_to_array(i.indkey::text, ' ')::int2[])[1:cardinality(c.conkey)]
                  @> c.conkey
              and cardinality(c.conkey) <= cardinality(string_to_array(i.indkey::text, ' ')))
        order by coalesce(s.n_live_tup, 0) desc
        limit 200""",
        (rs, i) -> new ForeignKey(rs.getString("schema_name"), rs.getString("table_name"),
            rs.getString("conname"), rs.getString("columns"), rs.getString("referenced"),
            rs.getLong("live_rows"), rs.getLong("bytes"))));
  }

  /**
   * Sequences and the id columns they feed, by how much of the largest possible value is used.
   * An {@code integer} column fed by a {@code bigint} sequence stops at 2,147,483,647.
   */
  public List<IdCapacity> idCapacity() {
    return cached("ids", () -> {
      List<IdCapacity> result = new ArrayList<>();
      jdbcTemplate.query("""
          select s.schemaname, s.sequencename, s.data_type::text as data_type,
                 s.last_value, s.max_value,
                 tbl.relname as table_name, att.attname as column_name,
                 format_type(att.atttypid, null) as column_type
          from pg_sequences s
          join pg_namespace sn on sn.nspname = s.schemaname
          join pg_class seq on seq.relname = s.sequencename and seq.relnamespace = sn.oid
          left join pg_depend dep on dep.objid = seq.oid
               and dep.classid = 'pg_class'::regclass and dep.refclassid = 'pg_class'::regclass
               and dep.deptype in ('a', 'i')
          left join pg_class tbl on tbl.oid = dep.refobjid
          left join pg_attribute att on att.attrelid = tbl.oid and att.attnum = dep.refobjsubid
          where s.last_value is not null and s.increment_by > 0
            and s.sequencename not like 'devpanel\\_%'""",
          rs -> {
            long last = rs.getLong("last_value");
            long limit = rs.getLong("max_value");
            String columnType = rs.getString("column_type");
            if ("integer".equals(columnType)) {
              limit = Math.min(limit, Integer.MAX_VALUE);
            } else if ("smallint".equals(columnType)) {
              limit = Math.min(limit, Short.MAX_VALUE);
            }
            result.add(new IdCapacity(rs.getString("schemaname"), rs.getString("sequencename"),
                rs.getString("data_type"), rs.getString("table_name"),
                rs.getString("column_name"), columnType, last, limit,
                limit <= 0 ? 0 : (double) last / limit));
          });
      result.sort((a, b) -> Double.compare(b.used(), a.used()));
      return result;
    });
  }

  /**
   * Text columns that look like they should be unique (code, name, title, PNFL ...) in tables up
   * to {@value #DUPLICATE_TABLE_LIMIT} rows, without a unique index, that hold repeated values.
   */
  public List<Duplicates> duplicates() {
    return cached("duplicates", () -> {
      List<Map<String, Object>> columns = jdbcTemplate.queryForList("""
          select c.table_schema, c.table_name, c.column_name, s.n_live_tup
          from information_schema.columns c
          join pg_stat_user_tables s on s.schemaname = c.table_schema
               and s.relname = c.table_name
          where c.data_type in ('character varying', 'text', 'character')
            and s.n_live_tup between 2 and ?
            and c.table_name not like 'devpanel\\_%'
            and c.table_name not like 'databasechangelog%'
            and not exists (
              select 1 from pg_index i
              join pg_attribute a on a.attrelid = i.indrelid and a.attnum = i.indkey[0]
              where i.indrelid = s.relid and i.indisunique and i.indnatts = 1
                and a.attname = c.column_name)
          order by s.n_live_tup desc""", DUPLICATE_TABLE_LIMIT);
      List<Duplicates> found = new ArrayList<>();
      int checked = 0;
      for (Map<String, Object> column : columns) {
        String name = String.valueOf(column.get("column_name"));
        if (!DUPLICATE_COLUMN.matcher(name.toLowerCase(Locale.ROOT)).matches() || checked >= 40) {
          continue;
        }
        checked++;
        String schema = String.valueOf(column.get("table_schema"));
        String table = String.valueOf(column.get("table_name"));
        try {
          Map<String, Object> counts = jdbcTemplate.queryForMap("select count(" + quote(name)
              + ") as total, count(distinct lower(trim(" + quote(name) + "))) as distinct_values"
              + " from " + quote(schema) + "." + quote(table));
          long total = ((Number) counts.get("total")).longValue();
          long distinct = ((Number) counts.get("distinct_values")).longValue();
          if (total >= 10 && total - distinct >= Math.max(3, total * 0.05)) {
            List<String> examples = jdbcTemplate.queryForList("select lower(trim(" + quote(name)
                    + ")) from " + quote(schema) + "." + quote(table) + " where " + quote(name)
                    + " is not null group by lower(trim(" + quote(name) + "))"
                    + " having count(*) > 1 order by count(*) desc limit 3", String.class);
            found.add(new Duplicates(schema, table, name, total, distinct, examples));
          }
        } catch (DataAccessException e) {
          log.debug("Dev panel duplicate check of {}.{} failed: {}", table, name, e.getMessage());
        }
      }
      found.sort((a, b) -> Long.compare(b.total() - b.distinct(), a.total() - a.distinct()));
      return found;
    });
  }

  /**
   * Tables read very often but changed rarely: the application could keep them in memory.
   * Rates per day since PostgreSQL's statistics were last reset.
   */
  public List<CacheCandidate> cacheCandidates() {
    return cached("cache", () -> jdbcTemplate.query("""
        select s.schemaname, s.relname, s.n_live_tup,
               s.seq_scan + coalesce(s.idx_scan, 0) as reads,
               s.n_tup_ins + s.n_tup_upd + s.n_tup_del as writes,
               greatest(1, extract(epoch from (now() - coalesce(d.stats_reset,
                   pg_postmaster_start_time()))) / 86400.0) as days,
               pg_total_relation_size(s.relid) as bytes
        from pg_stat_user_tables s
        cross join pg_stat_database d
        where d.datname = current_database()
          and s.n_live_tup between 1 and 100000
          and s.relname not like 'devpanel\\_%'
        order by (s.seq_scan + coalesce(s.idx_scan, 0)) desc
        limit 100""",
        (rs, i) -> {
          double days = rs.getDouble("days");
          return new CacheCandidate(rs.getString("schemaname"), rs.getString("relname"),
              rs.getLong("n_live_tup"), Math.round(rs.getLong("reads") / days),
              Math.round(rs.getLong("writes") / days), rs.getLong("bytes"));
        }).stream()
        .filter(c -> c.readsPerDay() >= 5_000 && c.writesPerDay() * 1_000 <= c.readsPerDay())
        .toList());
  }

  /**
   * Tables mostly read by full scans, each with the columns the application filters it by (from
   * pg_stat_statements and the requests the panel saw) that no index starts with.
   */
  public List<IndexSuggestion> indexSuggestions(Supplier<Collection<String>> recentSql) {
    return cached("indexes", () -> {
      List<DatabaseInsights.TableStat> scanned = insights.tables().stream()
          .filter(t -> t.liveRows() >= 5_000 && t.seqScans() >= 50
              && t.seqScans() > t.indexScans() / 4)
          .filter(t -> !t.table().startsWith("devpanel_"))
          .toList();
      if (scanned.isEmpty()) {
        return List.<IndexSuggestion>of();
      }
      List<String> statements = new ArrayList<>();
      if (insights.overview().statementsAvailable()) {
        try {
          insights.statements().forEach(statement -> statements.add(statement.query()));
        } catch (RuntimeException e) {
          log.debug("Dev panel could not read pg_stat_statements: {}", e.getMessage());
        }
      }
      statements.addAll(recentSql.get());
      Map<String, Set<String>> leading = leadingIndexColumns();
      List<IndexSuggestion> suggestions = new ArrayList<>();
      for (DatabaseInsights.TableStat table : scanned) {
        Map<String, String[]> columns = new LinkedHashMap<>();
        for (String sql : statements) {
          filteredColumns(sql, table.table()).forEach((column, how) ->
              columns.putIfAbsent(column, new String[] {how, sql}));
        }
        Set<String> indexed = leading.getOrDefault(table.schema() + "." + table.table(),
            Set.of());
        columns.forEach((column, how) -> {
          if (indexed.contains(column)) {
            return;
          }
          boolean like = "like".equals(how[0]);
          String index = "idx_" + table.table() + "_" + column + (like ? "_trgm" : "");
          String create = like
              ? "CREATE EXTENSION IF NOT EXISTS pg_trgm;\nCREATE INDEX CONCURRENTLY IF NOT EXISTS "
                  + index + " ON " + table.schema() + "." + table.table()
                  + " USING gin (lower(" + column + ") gin_trgm_ops);"
              : "CREATE INDEX CONCURRENTLY IF NOT EXISTS " + index + " ON " + table.schema() + "."
                  + table.table() + " (" + column + ");";
          suggestions.add(new IndexSuggestion(table.schema(), table.table(), column, like,
              table.liveRows(), table.seqScans(), table.indexScans(), create,
              how[1].length() > 600 ? how[1].substring(0, 600) + "…" : how[1]));
        });
      }
      return suggestions;
    });
  }

  /** Tables the planner has no or outdated statistics for: it then guesses row counts badly. */
  public List<StaleStatistics> staleStatistics() {
    return cached("stats", () -> jdbcTemplate.query("""
        select s.schemaname, s.relname, s.n_live_tup, s.n_mod_since_analyze,
               greatest(s.last_analyze, s.last_autoanalyze) as analyzed,
               pg_total_relation_size(s.relid) as bytes,
               c.reltuples
        from pg_stat_user_tables s join pg_class c on c.oid = s.relid
        where s.relname not like 'devpanel\\_%'
          and ((greatest(s.last_analyze, s.last_autoanalyze) is null
                and pg_total_relation_size(s.relid) > 1024 * 1024)
            or (s.n_mod_since_analyze > 10000
                and s.n_mod_since_analyze > s.n_live_tup * 0.5))
        order by pg_total_relation_size(s.relid) desc
        limit 50""",
        (rs, i) -> new StaleStatistics(rs.getString("schemaname"), rs.getString("relname"),
            rs.getLong("n_live_tup"), rs.getLong("n_mod_since_analyze"),
            rs.getTimestamp("analyzed") == null ? null
                : rs.getTimestamp("analyzed").toInstant().toString(),
            rs.getLong("bytes"), rs.getDouble("reltuples"))));
  }

  /** Everything at once for the page; each part on its own, a failing one is just empty. */
  public Report report(Supplier<Collection<String>> recentSql) {
    return new Report(safe(this::foreignKeysWithoutIndex), safe(this::idCapacity),
        safe(this::duplicates), safe(this::cacheCandidates),
        safe(() -> indexSuggestions(recentSql)), safe(this::staleStatistics));
  }

  public void clearCache() {
    synchronized (cache) {
      cache.clear();
    }
  }

  /** column → "equals" or "like", for the columns {@code sql} filters {@code table} by. */
  static Map<String, String> filteredColumns(String sql, String table) {
    Map<String, String> columns = new LinkedHashMap<>();
    String lowerTable = table.toLowerCase(Locale.ROOT);
    Set<String> aliases = new HashSet<>();
    boolean mentioned = false;
    Matcher from = FROM_ALIAS.matcher(sql);
    int tablesInQuery = 0;
    while (from.find()) {
      tablesInQuery++;
      if (from.group(2).equalsIgnoreCase(lowerTable)) {
        mentioned = true;
        aliases.add(lowerTable);
        String alias = from.group(3);
        if (alias != null && !SQL_WORDS.contains(alias.toLowerCase(Locale.ROOT))) {
          aliases.add(alias.toLowerCase(Locale.ROOT));
        }
      }
    }
    if (!mentioned) {
      return columns;
    }
    String where = whereClause(sql);
    Matcher like = LIKE_PARAMETER.matcher(where);
    while (like.find()) {
      String alias = like.group(1);
      if (belongs(alias, aliases, tablesInQuery)) {
        columns.putIfAbsent(like.group(2).toLowerCase(Locale.ROOT), "like");
      }
    }
    Matcher equals = EQUALS_PARAMETER.matcher(where);
    while (equals.find()) {
      String alias = equals.group(1);
      String column = equals.group(2).toLowerCase(Locale.ROOT);
      if (belongs(alias, aliases, tablesInQuery) && !SQL_WORDS.contains(column)
          && !column.matches("\\d+")) {
        columns.putIfAbsent(column, "equals");
      }
    }
    return columns;
  }

  private static boolean belongs(String alias, Set<String> aliases, int tablesInQuery) {
    return alias == null ? tablesInQuery == 1 : aliases.contains(alias.toLowerCase(Locale.ROOT));
  }

  private static String whereClause(String sql) {
    String lower = sql.toLowerCase(Locale.ROOT);
    int where = lower.indexOf(" where ");
    return where < 0 ? "" : sql.substring(where);
  }

  /** schema.table → columns that are the first column of some index. */
  private Map<String, Set<String>> leadingIndexColumns() {
    Map<String, Set<String>> result = new HashMap<>();
    jdbcTemplate.query("""
        select n.nspname, t.relname, a.attname
        from pg_index i
        join pg_class t on t.oid = i.indrelid
        join pg_namespace n on n.oid = t.relnamespace
        join pg_attribute a on a.attrelid = i.indrelid and a.attnum = i.indkey[0]
        where n.nspname not in ('pg_catalog', 'information_schema')""",
        rs -> {
          result.computeIfAbsent(rs.getString(1) + "." + rs.getString(2), key -> new HashSet<>())
              .add(rs.getString(3).toLowerCase(Locale.ROOT));
        });
    return result;
  }

  @SuppressWarnings("unchecked")
  private <T> T cached(String key, Supplier<T> load) {
    synchronized (cache) {
      Cached<?> hit = cache.get(key);
      if (hit != null && hit.at() > System.currentTimeMillis() - CACHE_MS) {
        return (T) hit.value();
      }
    }
    T value = load.get();
    synchronized (cache) {
      cache.put(key, new Cached<>(System.currentTimeMillis(), value));
    }
    return value;
  }

  private static <T> List<T> safe(Supplier<List<T>> part) {
    try {
      return part.get();
    } catch (RuntimeException e) {
      log.debug("Dev panel database advice failed: {}", e.getMessage());
      return List.of();
    }
  }

  private static String quote(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }

  private record Cached<T>(long at, T value) {
  }

  public record ForeignKey(String schema, String table, String constraint, String columns,
                           String referenced, long rows, long bytes) {

    @com.fasterxml.jackson.annotation.JsonProperty("createIndex")
    public String createIndex() {
      return "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_" + table + "_"
          + columns.replace(", ", "_") + " ON " + schema + "." + table + " (" + columns + ");";
    }
  }

  /** {@code used}: last value / the largest value the column or sequence can hold (0..1). */
  public record IdCapacity(String schema, String sequence, String sequenceType, String table,
                           String column, String columnType, long lastValue, long limit,
                           double used) {
  }

  /** {@code examples}: the most repeated values (lower-cased). */
  public record Duplicates(String schema, String table, String column, long total, long distinct,
                           List<String> examples) {
  }

  public record CacheCandidate(String schema, String table, long rows, long readsPerDay,
                               long writesPerDay, long bytes) {
  }

  /** {@code like}: filtered with LIKE, so a trigram index is suggested. */
  public record IndexSuggestion(String schema, String table, String column, boolean like,
                                long rows, long seqScans, long indexScans, String createIndex,
                                String exampleSql) {
  }

  /** {@code plannerRows}: what the planner believes (0 or -1 when it never looked). */
  public record StaleStatistics(String schema, String table, long rows, long changedSinceAnalyze,
                                String lastAnalyzed, long bytes, double plannerRows) {
  }

  public record Report(List<ForeignKey> foreignKeys, List<IdCapacity> ids,
                       List<Duplicates> duplicates, List<CacheCandidate> cacheCandidates,
                       List<IndexSuggestion> indexes, List<StaleStatistics> statistics) {
  }
}
