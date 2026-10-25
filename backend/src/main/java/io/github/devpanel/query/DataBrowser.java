package io.github.devpanel.query;

import io.github.devpanel.query.DevQueryService.SchemaTable;
import io.github.devpanel.support.DevPanelException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Browse tables without writing SQL: pick a table, filter, sort, page. The statement is built by
 * the panel from names checked against the database's own list of tables and columns; values are
 * bound as parameters. Read-only transaction with the console's timeout; the panel's own tables
 * are not listed.
 */
public class DataBrowser {

  private static final int MAX_PAGE = 200;

  private final DevQueryService queries;
  private final JdbcTemplate jdbcTemplate;
  private final TransactionTemplate readOnly;
  private final String timeout;

  public DataBrowser(DataSource dataSource, DevQueryService queries, Duration timeout) {
    this.queries = queries;
    this.jdbcTemplate = new JdbcTemplate(dataSource);
    this.readOnly = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    this.readOnly.setReadOnly(true);
    this.readOnly.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.timeout = Math.max(1, timeout.toMillis()) + "ms";
    this.jdbcTemplate.setQueryTimeout((int) Math.max(1, timeout.toSeconds()));
  }

  public List<SchemaTable> tables() {
    return queries.schema().stream()
        .filter(table -> !table.table().toLowerCase(Locale.ROOT).startsWith("devpanel_"))
        .toList();
  }

  /**
   * @param filters {@code column:operator:value}; operators eq, ne, contains, gt, lt, gte, lte,
   *                null, notnull
   */
  public Page rows(String schema, String table, int page, int size, String sort,
      boolean descending, List<String> filters) {
    SchemaTable found = tables().stream()
        .filter(t -> t.schema().equals(schema) && t.table().equals(table)).findFirst()
        .orElseThrow(() -> DevPanelException.notFound("No such table: " + schema + "." + table));
    List<String> columns = found.columns();
    StringBuilder where = new StringBuilder();
    List<Object> args = new ArrayList<>();
    for (String filter : filters == null ? List.<String>of() : filters) {
      String[] parts = filter.split(":", 3);
      if (parts.length < 2 || !columns.contains(parts[0])) {
        throw DevPanelException.badRequest("Unknown column in filter: " + parts[0]);
      }
      String column = quote(parts[0]);
      String value = parts.length > 2 ? parts[2] : "";
      where.append(where.length() == 0 ? " WHERE " : " AND ");
      switch (parts[1]) {
        case "eq" -> {
          where.append("CAST(").append(column).append(" AS VARCHAR(4000)) = ?");
          args.add(value);
        }
        case "ne" -> {
          where.append("CAST(").append(column).append(" AS VARCHAR(4000)) <> ?");
          args.add(value);
        }
        case "contains" -> {
          where.append("LOWER(CAST(").append(column).append(" AS VARCHAR(4000))) LIKE ?");
          args.add("%" + value.toLowerCase(Locale.ROOT).replace("%", "\\%").replace("_", "\\_")
              + "%");
        }
        case "gt", "lt", "gte", "lte" -> {
          String operator = switch (parts[1]) {
            case "gt" -> ">";
            case "lt" -> "<";
            case "gte" -> ">=";
            default -> "<=";
          };
          Object bound = number(value);
          if (bound == null) {
            where.append("CAST(").append(column).append(" AS VARCHAR(4000)) ").append(operator)
                .append(" ?");
            args.add(value);
          } else {
            where.append(column).append(' ').append(operator).append(" ?");
            args.add(bound);
          }
        }
        case "null" -> where.append(column).append(" IS NULL");
        case "notnull" -> where.append(column).append(" IS NOT NULL");
        default -> throw DevPanelException.badRequest("Unknown filter operator " + parts[1]);
      }
    }
    String order = "";
    if (sort != null && !sort.isBlank()) {
      if (!columns.contains(sort)) {
        throw DevPanelException.badRequest("Unknown column to sort by: " + sort);
      }
      order = " ORDER BY " + quote(sort) + (descending ? " DESC" : " ASC");
    }
    int pageSize = Math.max(1, Math.min(size, MAX_PAGE));
    int pageNumber = Math.max(0, page);
    String from = " FROM " + quote(schema) + "." + quote(table) + where;
    String select = "SELECT *" + from + order + " LIMIT " + pageSize + " OFFSET "
        + ((long) pageNumber * pageSize);
    return readOnly.execute(status -> {
      if (queries.postgres()) {
        jdbcTemplate.execute("SET TRANSACTION READ ONLY");
        jdbcTemplate.execute("SET LOCAL statement_timeout = '" + timeout + "'");
      }
      try {
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + from, Long.class,
            args.toArray());
        List<Map<String, Object>> rows = jdbcTemplate.query(select, (rs, i) -> {
          Map<String, Object> row = new LinkedHashMap<>();
          var meta = rs.getMetaData();
          for (int column = 1; column <= meta.getColumnCount(); column++) {
            Object value = rs.getObject(column);
            row.put(meta.getColumnLabel(column), value instanceof byte[] bytes
                ? "[" + bytes.length + " bytes]" : value instanceof java.sql.Clob
                || value instanceof java.sql.Blob || value instanceof java.sql.Array
                ? String.valueOf(rs.getString(column)) : value);
          }
          return row;
        }, args.toArray());
        return new Page(columns, rows, total == null ? 0 : total, pageNumber, pageSize);
      } catch (DataAccessException e) {
        throw DevQueryService.queryFailed(e);
      }
    });
  }

  private static Object number(String value) {
    try {
      return new BigDecimal(value.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static String quote(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }

  public record Page(List<String> columns, List<Map<String, Object>> rows, long total, int page,
                     int size) {
  }
}
