package io.github.devpanel.query;

import io.github.devpanel.support.DevPanelException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs ad-hoc SQL against the application's database (PostgreSQL) for the panel.
 *
 * <p>The real guarantee is the database's: every query runs inside a transaction switched to
 * {@code READ ONLY} (and with a statement timeout), so Postgres itself refuses any write, DDL,
 * {@code nextval()} or {@code SELECT ... FOR UPDATE}, however the SQL is written. The checks in
 * {@link #validate(String)} on top of that only reject obviously wrong input early with a clear
 * message: anything that is not a single {@code SELECT}, system catalogs, and a handful of
 * side-effecting Postgres functions.
 *
 * <p>{@link #update(String)} is the one way to write, and only when
 * {@code devpanel.query.update-enabled=true}: a single {@code UPDATE} with a {@code WHERE}, rolled
 * back when it touches more than the configured number of rows. It cannot reach the panel's own
 * tables (where an account could raise its own role) or the migration tools' bookkeeping.
 *
 * <p>Transactions go through a transaction manager of the panel's own, never registered as a bean,
 * so the application's {@code @Transactional} setup is not affected.
 */
public class DevQueryService {

  private static final Logger log = LoggerFactory.getLogger(DevQueryService.class);

  private static final Pattern SELECT_START = Pattern.compile("^\\(*\\s*(select|with)\\b");
  private static final Pattern UPDATE_START = Pattern.compile("^update\\b");
  private static final Pattern WHERE = Pattern.compile("\\bwhere\\b");
  private static final Pattern RETURNING = Pattern.compile("\\breturning\\b");
  /** The panel's own accounts and audit, and Liquibase's and Flyway's bookkeeping. */
  private static final Pattern PROTECTED_TABLE =
      Pattern.compile("\\b(devpanel_\\w+|databasechangelog\\w*|flyway_schema_history)\\b");

  private static final List<String> WRITE_OPERATIONS = List.of(
      "insert", "delete", "drop", "alter", "create", "truncate",
      "grant", "revoke", "merge", "commit", "rollback",
      "vacuum", "reindex", "cluster", "lock", "copy",
      "do", "call", "execute", "prepare", "listen", "notify"
  );

  private static final Pattern PROHIBITED_IN_SELECT = operations("update");
  private static final Pattern PROHIBITED_IN_UPDATE = operations();

  private static final List<String> PROHIBITED_FUNCTIONS = List.of(
      "pg_sleep", "pg_backend_pid", "pg_terminate_backend",
      "pg_cancel_backend", "pg_read_file", "pg_ls_dir"
  );

  /**
   * SQLSTATE classes (and single codes) of errors caused by the query itself: data exceptions,
   * syntax errors and unknown tables/columns, writes refused by the read-only transaction, the
   * statement timeout, ... Anything else (connection loss, out of memory, ...) stays a 500.
   */
  private static final List<String> QUERY_ERROR_STATES = List.of(
      "0A", "21", "22", "25", "2F", "42", "54", "57014");

  private static final String SCHEMA_QUERY = """
      select table_schema, table_name, column_name
      from information_schema.columns
      where table_schema not in ('pg_catalog', 'information_schema')
        and table_schema not like 'pg\\_%'
      order by table_schema, table_name, ordinal_position""";

  private final JdbcTemplate jdbcTemplate;
  private final TransactionTemplate readOnly;
  private final TransactionTemplate readWrite;
  private final String statementTimeout;
  private final int maxRows;
  private final int maxUpdatedRows;
  private final boolean updateEnabled;

  public DevQueryService(DataSource dataSource, Duration timeout, int maxRows,
      int maxUpdatedRows, boolean updateEnabled) {
    this.jdbcTemplate = new JdbcTemplate(dataSource);
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    this.readOnly = new TransactionTemplate(transactionManager);
    this.readOnly.setReadOnly(true);
    this.readOnly.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.readWrite = new TransactionTemplate(transactionManager);
    this.readWrite.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.statementTimeout = Math.max(1, timeout.toMillis()) + "ms";
    this.maxRows = maxRows;
    this.maxUpdatedRows = maxUpdatedRows;
    this.updateEnabled = updateEnabled;
  }

  public boolean isUpdateEnabled() {
    return updateEnabled;
  }

  /** At most {@code devpanel.query.max-rows} rows; the rest is not read from the database. */
  public List<Map<String, Object>> run(String query) {
    String statement = validate(query);
    log.info("Dev panel query: {}", statement);
    return inReadOnly(() -> jdbcTemplate.query(statement, resultSet -> {
      var metaData = resultSet.getMetaData();
      int columns = metaData.getColumnCount();
      List<Map<String, Object>> rows = new ArrayList<>();
      while (rows.size() < maxRows && resultSet.next()) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int column = 1; column <= columns; column++) {
          row.put(metaData.getColumnLabel(column), resultSet.getObject(column));
        }
        rows.add(row);
      }
      return rows;
    }));
  }

  /** Runs the query like {@link #run(String)} but hands the whole result to {@code extractor}. */
  public <T> T runWith(String query, ResultSetExtractor<T> extractor) {
    String statement = validate(query);
    log.info("Dev panel query (export): {}", statement);
    return inReadOnly(() -> jdbcTemplate.query(statement, extractor));
  }

  /**
   * Runs a single {@code UPDATE ... WHERE ...} (see {@link #validateUpdate(String)}) and returns
   * how many rows it changed. More than {@code devpanel.query.max-updated-rows} rolls it back.
   */
  public int update(String query) {
    if (!updateEnabled) {
      throw DevPanelException.forbidden(
          "UPDATE is turned off; set devpanel.query.update-enabled=true to allow it");
    }
    String statement = validateUpdate(query);
    log.info("Dev panel update: {}", statement);
    Integer updated = readWrite.execute(status -> {
      jdbcTemplate.execute("SET LOCAL statement_timeout = '" + statementTimeout + "'");
      int rows;
      try {
        rows = jdbcTemplate.update(statement);
      } catch (DataAccessException e) {
        throw queryFailed(e);
      }
      if (rows > maxUpdatedRows) {
        status.setRollbackOnly();
        log.warn("Dev panel update rolled back, {} rows exceed the limit of {}", rows,
            maxUpdatedRows);
      }
      return rows;
    });
    int rows = updated == null ? 0 : updated;
    if (rows > maxUpdatedRows) {
      throw DevPanelException.badRequest("The update would change " + rows
          + " rows, more than the limit of " + maxUpdatedRows + "; it was rolled back");
    }
    return rows;
  }

  /**
   * Tables, views and their columns outside the system schemas, for the editor's autocomplete.
   * It runs fixed SQL of its own, so {@link #validate(String)}, which rejects
   * {@code information_schema}, does not apply.
   */
  public List<SchemaTable> schema() {
    return inReadOnly(() -> {
      Map<String, SchemaTable> tables = new LinkedHashMap<>();
      jdbcTemplate.query(SCHEMA_QUERY, resultSet -> {
        String schema = resultSet.getString("table_schema");
        String table = resultSet.getString("table_name");
        tables.computeIfAbsent(schema + "." + table,
                key -> new SchemaTable(schema, table, new ArrayList<>()))
            .columns().add(resultSet.getString("column_name"));
      });
      return new ArrayList<>(tables.values());
    });
  }

  private <T> T inReadOnly(Supplier<T> work) {
    return readOnly.execute(status -> {
      jdbcTemplate.execute("SET TRANSACTION READ ONLY");
      jdbcTemplate.execute("SET LOCAL statement_timeout = '" + statementTimeout + "'");
      try {
        return work.get();
      } catch (DataAccessException e) {
        throw queryFailed(e);
      }
    });
  }

  /**
   * A query the database rejected (typo in a table name, bad syntax, timeout, ...) is the
   * caller's mistake, so it becomes a 400 carrying the database's own message, position included.
   * Other failures are rethrown as they are.
   */
  static RuntimeException queryFailed(DataAccessException e) {
    if (e.getMostSpecificCause() instanceof SQLException sql && isQueryError(sql.getSQLState())) {
      return DevPanelException.badRequest("Query failed: " + sql.getMessage());
    }
    return e;
  }

  private static boolean isQueryError(String sqlState) {
    return sqlState != null && QUERY_ERROR_STATES.stream().anyMatch(sqlState::startsWith);
  }

  /** @return the query without surrounding whitespace and without a single trailing {@code ;}. */
  String validate(String queryToCheck) {
    String statement = singleStatement(queryToCheck);
    String query = statement.toLowerCase(Locale.ROOT);
    if (!SELECT_START.matcher(query).find()) {
      throw DevPanelException.badRequest("Only SELECT queries are allowed");
    }
    checkCommon(query, PROHIBITED_IN_SELECT);
    return statement;
  }

  /**
   * A single {@code UPDATE} with a {@code WHERE} (an intended full-table update says
   * {@code where true}), without {@code RETURNING}, outside the protected tables.
   */
  String validateUpdate(String queryToCheck) {
    String statement = singleStatement(queryToCheck);
    String query = statement.toLowerCase(Locale.ROOT);
    if (!UPDATE_START.matcher(query).find()) {
      throw DevPanelException.badRequest("Only UPDATE statements are allowed");
    }
    if (!WHERE.matcher(query).find()) {
      throw DevPanelException.badRequest(
          "An UPDATE needs a WHERE clause (write WHERE true to update every row)");
    }
    if (RETURNING.matcher(query).find()) {
      throw DevPanelException.badRequest("RETURNING is not supported, run a SELECT afterwards");
    }
    Matcher table = PROTECTED_TABLE.matcher(query);
    if (table.find()) {
      throw DevPanelException.badRequest("Table " + table.group(1) + " cannot be changed here");
    }
    checkCommon(query, PROHIBITED_IN_UPDATE);
    return statement;
  }

  private static String singleStatement(String queryToCheck) {
    if (queryToCheck == null) {
      throw DevPanelException.badRequest("Query cannot be empty");
    }
    String statement = queryToCheck.trim();
    if (statement.endsWith(";")) {
      statement = statement.substring(0, statement.length() - 1).trim();
    }
    if (statement.isEmpty()) {
      throw DevPanelException.badRequest("Query cannot be empty");
    }
    if (statement.contains(";")) {
      throw DevPanelException.badRequest("Only a single statement is allowed");
    }
    return statement;
  }

  private static void checkCommon(String query, Pattern prohibitedOperation) {
    if (query.contains("pg_catalog") || query.contains("information_schema")) {
      throw DevPanelException.badRequest("Access to system schemas is prohibited");
    }

    Matcher operation = prohibitedOperation.matcher(query);
    if (operation.find()) {
      throw DevPanelException.badRequest(
          "Query contains prohibited operation: " + operation.group(1));
    }

    for (String function : PROHIBITED_FUNCTIONS) {
      if (query.contains(function)) {
        throw DevPanelException.badRequest("Function " + function + " is prohibited");
      }
    }
  }

  private static Pattern operations(String... extra) {
    List<String> all = new ArrayList<>(WRITE_OPERATIONS);
    all.addAll(List.of(extra));
    return Pattern.compile("\\b(" + String.join("|", all) + ")\\b");
  }

  public record SchemaTable(String schema, String table, List<String> columns) {
  }
}
