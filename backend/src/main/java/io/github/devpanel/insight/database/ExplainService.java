package io.github.devpanel.insight.database;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devpanel.query.DevQueryService;
import io.github.devpanel.support.DevPanelException;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL's plan for a SELECT: which tables it reads in full, which indexes it uses, where the
 * rows and the time go, with plain-language hints. Statements from the waterfall come with their
 * real parameter values; statements without them ({@code $1} from pg_stat_statements) get a
 * generic plan, valid for any value (PREPARE with {@code force_generic_plan}, PostgreSQL 12+).
 *
 * <p>{@code analyze} really runs the statement (read-only transaction, 15 s timeout) to show actual
 * rows and times; only SELECTs pass the same checks as the SQL console.
 */
public class ExplainService {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern DOLLAR = Pattern.compile("\\$(\\d+)");
  private static final int LARGE_TABLE = 10_000;

  private final JdbcTemplate jdbcTemplate;
  private final TransactionTemplate readOnly;
  private final DatabaseInsights insights;

  public ExplainService(DataSource dataSource, DatabaseInsights insights) {
    this.jdbcTemplate = new JdbcTemplate(dataSource);
    this.readOnly = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    this.readOnly.setReadOnly(true);
    this.readOnly.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.insights = insights;
  }

  /** @param parameters the statement's bound values in order, or null when unknown */
  public Plan explain(String sql, Object[] parameters, boolean analyze) {
    if (!insights.isPostgres()) {
      throw new DevPanelException(HttpStatus.NOT_IMPLEMENTED, "Plans need PostgreSQL");
    }
    String statement = DevQueryService.validateSelect(sql.replaceFirst("^\\[batch ×\\d+]\\s*",
        ""));
    List<String> warnings = new ArrayList<>();
    int version = version();
    boolean questionMarks = countPlaceholders(statement) > 0;
    boolean dollars = DOLLAR.matcher(statement).find();
    // Without the real values: a generic plan, valid for any value (PostgreSQL 12+)
    boolean generic = false;
    if (questionMarks && parameters != null) {
      statement = inline(statement, parameters);
    } else if ((questionMarks || dollars) && !analyze && version >= 120000) {
      if (questionMarks) {
        statement = numberPlaceholders(statement);
      }
      generic = true;
      warnings.add("Planned for any parameter values (generic plan), without running it.");
    } else if (questionMarks || dollars) {
      statement = questionMarks
          ? inline(statement, new Object[countPlaceholders(statement)])
          : DOLLAR.matcher(statement).replaceAll("NULL");
      warnings.add("The parameter values are unknown, so the plan uses NULL for them; the"
          + " real plan may differ.");
    }
    String planned = statement;
    boolean asGeneric = generic;
    String json = readOnly.execute(status -> {
      jdbcTemplate.execute("SET TRANSACTION READ ONLY");
      jdbcTemplate.execute("SET LOCAL statement_timeout = '15s'");
      try {
        if (!asGeneric) {
          return jdbcTemplate.queryForObject("EXPLAIN (" + (analyze
              ? "ANALYZE, BUFFERS, FORMAT JSON" : "FORMAT JSON") + ") " + planned, String.class);
        }
        // $1 inside a JDBC statement would be taken for a bound value: PREPARE owns them, and
        // force_generic_plan makes the NULLs below irrelevant to the plan
        String name = "devpanel_explain_" + Long.toHexString(System.nanoTime());
        jdbcTemplate.execute("SET LOCAL plan_cache_mode = force_generic_plan");
        jdbcTemplate.execute("PREPARE " + name + " AS " + planned);
        String nulls = String.join(", ", java.util.Collections.nCopies(highestPlaceholder(
            planned), "NULL"));
        String plan = jdbcTemplate.queryForObject("EXPLAIN (FORMAT JSON) EXECUTE " + name
            + (nulls.isEmpty() ? "" : "(" + nulls + ")"), String.class);
        jdbcTemplate.execute("DEALLOCATE " + name);
        return plan;
      } catch (DataAccessException e) {
        throw DevPanelException.badRequest("PostgreSQL could not plan it: "
            + e.getMostSpecificCause().getMessage());
      }
    });
    try {
      JsonNode root = JSON.readTree(json).get(0);
      List<Node> nodes = new ArrayList<>();
      Map<String, Long> tableRows = tableRows();
      flatten(root.get("Plan"), 0, nodes);
      List<String> hints = hints(nodes, tableRows, analyze);
      return new Plan(statement, json, nodes, warnings, hints, analyze,
          root.path("Planning Time").asDouble(-1), root.path("Execution Time").asDouble(-1));
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Unreadable plan", e);
    }
  }

  private void flatten(JsonNode plan, int depth, List<Node> nodes) {
    if (plan == null) {
      return;
    }
    nodes.add(new Node(depth, plan.path("Node Type").asText(),
        text(plan, "Relation Name"), text(plan, "Index Name"), text(plan, "Alias"),
        plan.path("Startup Cost").asDouble(), plan.path("Total Cost").asDouble(),
        plan.path("Plan Rows").asLong(),
        plan.has("Actual Rows") ? plan.path("Actual Rows").asLong() : -1,
        plan.has("Actual Total Time") ? plan.path("Actual Total Time").asDouble() : -1,
        plan.has("Actual Loops") ? plan.path("Actual Loops").asLong() : -1,
        text(plan, "Filter"), text(plan, "Index Cond"),
        plan.has("Rows Removed by Filter") ? plan.path("Rows Removed by Filter").asLong() : -1,
        text(plan, "Sort Method"), text(plan, "Join Type"), text(plan, "Hash Cond")));
    JsonNode children = plan.get("Plans");
    if (children != null) {
      for (JsonNode child : children) {
        flatten(child, depth + 1, nodes);
      }
    }
  }

  private static List<String> hints(List<Node> nodes, Map<String, Long> tableRows,
      boolean analyze) {
    List<String> hints = new ArrayList<>();
    for (Node node : nodes) {
      if ("Seq Scan".equals(node.type()) && node.relation() != null) {
        long rows = tableRows.getOrDefault(node.relation(), 0L);
        if (rows >= LARGE_TABLE && node.filter() != null) {
          hints.add("Reads all " + rows + " rows of " + node.relation() + " to keep those matching "
              + node.filter() + ". An index on the filtered column(s) would avoid the full scan.");
        } else if (rows >= LARGE_TABLE) {
          hints.add("Reads all " + rows + " rows of " + node.relation()
              + " (no condition): is the whole table really needed, or is paging missing?");
        }
      }
      if (node.filter() != null && node.filter().toLowerCase(Locale.ROOT).contains("~~")
          && node.filter().contains("'%")) {
        hints.add("A LIKE '%...%' search on " + (node.relation() == null ? "a table"
            : node.relation()) + " cannot use a normal index; a trigram index (pg_trgm) can.");
      }
      if (analyze && node.loops() > 100 && "Index Scan".equals(node.type())) {
        hints.add("The index scan on " + node.relation() + " ran " + node.loops()
            + " times (once per row of the outer side), a nested loop. Fine for few rows,"
            + " slow for many.");
      }
      if (node.sortMethod() != null && node.sortMethod().toLowerCase(Locale.ROOT)
          .contains("external")) {
        hints.add("The sort did not fit in memory and went to disk (" + node.sortMethod()
            + "); raise work_mem or sort fewer rows.");
      }
      if (analyze && node.actualRows() >= 0 && node.planRows() > 0
          && (node.actualRows() > node.planRows() * 10
              || node.actualRows() * 10 < node.planRows() && node.planRows() > 100)) {
        hints.add("The planner expected " + node.planRows() + " rows from " + node.type()
            + (node.relation() == null ? "" : " on " + node.relation()) + " but got "
            + node.actualRows() + ": outdated statistics? Run ANALYZE.");
      }
    }
    return hints.stream().distinct().toList();
  }

  private Map<String, Long> tableRows() {
    Map<String, Long> rows = new HashMap<>();
    try {
      for (DatabaseInsights.TableStat table : insights.tables()) {
        rows.put(table.table(), table.liveRows());
      }
    } catch (RuntimeException ignored) {
      // hints without row counts
    }
    return rows;
  }

  private int version() {
    Integer version = jdbcTemplate.queryForObject(
        "select current_setting('server_version_num')::int", Integer.class);
    return version == null ? 0 : version;
  }

  /** Placeholders {@code ?} outside quoted strings and identifiers. */
  static int countPlaceholders(String sql) {
    int count = 0;
    boolean single = false;
    boolean doubled = false;
    for (int i = 0; i < sql.length(); i++) {
      char c = sql.charAt(i);
      if (c == '\'' && !doubled) {
        single = !single;
      } else if (c == '"' && !single) {
        doubled = !doubled;
      } else if (c == '?' && !single && !doubled) {
        count++;
      }
    }
    return count;
  }

  static String inline(String sql, Object[] parameters) {
    StringBuilder out = new StringBuilder();
    boolean single = false;
    boolean doubled = false;
    int next = 0;
    for (int i = 0; i < sql.length(); i++) {
      char c = sql.charAt(i);
      if (c == '\'' && !doubled) {
        single = !single;
      } else if (c == '"' && !single) {
        doubled = !doubled;
      } else if (c == '?' && !single && !doubled) {
        out.append(literal(next < parameters.length ? parameters[next] : null));
        next++;
        continue;
      }
      out.append(c);
    }
    return out.toString();
  }

  static int highestPlaceholder(String sql) {
    int highest = 0;
    java.util.regex.Matcher matcher = DOLLAR.matcher(sql);
    while (matcher.find()) {
      highest = Math.max(highest, Integer.parseInt(matcher.group(1)));
    }
    return highest;
  }

  private static String numberPlaceholders(String sql) {
    StringBuilder out = new StringBuilder();
    boolean single = false;
    boolean doubled = false;
    int next = 1;
    for (int i = 0; i < sql.length(); i++) {
      char c = sql.charAt(i);
      if (c == '\'' && !doubled) {
        single = !single;
      } else if (c == '"' && !single) {
        doubled = !doubled;
      } else if (c == '?' && !single && !doubled) {
        out.append('$').append(next++);
        continue;
      }
      out.append(c);
    }
    return out.toString();
  }

  static String literal(Object value) {
    if (value == null || value instanceof byte[]) {
      return "NULL";
    }
    if (value instanceof Number || value instanceof Boolean) {
      return value.toString();
    }
    if (value instanceof Date || value instanceof Temporal || value instanceof UUID
        || value instanceof Enum<?> || value instanceof Character || value instanceof String) {
      return "'" + value.toString().replace("'", "''") + "'";
    }
    return "NULL";
  }

  private static String text(JsonNode node, String field) {
    return node.has(field) ? node.get(field).asText() : null;
  }

  /** Times in ms; {@code actualRows}, {@code actualMs}, {@code loops}: -1 without analyze. */
  public record Node(int depth, String type, String relation, String index, String alias,
                     double startupCost, double totalCost, long planRows, long actualRows,
                     double actualMs, long loops, String filter, String indexCondition,
                     long removedByFilter, String sortMethod, String joinType,
                     String hashCondition) {
  }

  /** {@code planningMs}/{@code executionMs}: -1 when not measured. */
  public record Plan(String statement, String json, List<Node> nodes, List<String> warnings,
                     List<String> hints, boolean analyzed, double planningMs,
                     double executionMs) {
  }
}
