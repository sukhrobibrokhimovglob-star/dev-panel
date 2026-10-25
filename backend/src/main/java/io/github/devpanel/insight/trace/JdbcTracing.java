package io.github.devpanel.insight.trace;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Wraps every DataSource bean so each statement of a request is timed and its rows counted, for
 * JPA, JdbcTemplate and plain JDBC alike. The wrappers only pass calls through: no statement,
 * transaction or result changes. {@code unwrap} still reaches the real pool (Hikari etc.).
 * Outside a request (no {@link RequestRecorder}) only a thread-local lookup is added.
 *
 * <p>The bean is then a {@link DataSource}, no longer its concrete class: code injecting e.g.
 * {@code HikariDataSource} by type would not find it. Turn off with
 * {@code devpanel.insights.sql-tracing=false} in that case.
 */
public class JdbcTracing implements BeanPostProcessor {

  private static final Set<String> EXECUTE = Set.of("execute", "executeQuery", "executeUpdate",
      "executeLargeUpdate", "executeBatch", "executeLargeBatch");

  @Override
  public Object postProcessAfterInitialization(Object bean, String beanName) {
    if (bean instanceof DataSource dataSource && !Proxy.isProxyClass(bean.getClass())) {
      return proxy(DataSource.class, new DataSourceHandler(dataSource));
    }
    return bean;
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> type, InvocationHandler handler) {
    return (T) Proxy.newProxyInstance(JdbcTracing.class.getClassLoader(), new Class<?>[] {type},
        handler);
  }

  private abstract static class Passthrough implements InvocationHandler {

    final Object target;

    Passthrough(Object target) {
      this.target = target;
    }

    Object call(Method method, Object[] args) throws Throwable {
      try {
        return method.invoke(target, args);
      } catch (InvocationTargetException e) {
        throw e.getCause();
      }
    }

    /** unwrap/isWrapperFor reach the real object; equals/hashCode compare the proxies sanely. */
    Object common(Object proxy, Method method, Object[] args) throws Throwable {
      switch (method.getName()) {
        case "unwrap":
          Class<?> type = (Class<?>) args[0];
          return type.isInstance(target) ? target : call(method, args);
        case "isWrapperFor":
          return ((Class<?>) args[0]).isInstance(target) || (Boolean) call(method, args);
        case "equals":
          return proxy == args[0];
        case "hashCode":
          return System.identityHashCode(proxy);
        case "toString":
          return "devpanel-traced " + target;
        default:
          return NOT_HANDLED;
      }
    }
  }

  private static final Object NOT_HANDLED = new Object();

  private static final class DataSourceHandler extends Passthrough {

    DataSourceHandler(DataSource target) {
      super(target);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      Object common = common(proxy, method, args);
      if (common != NOT_HANDLED) {
        return common;
      }
      Object result = call(method, args);
      if (result instanceof Connection connection && "getConnection".equals(method.getName())) {
        return proxy(Connection.class, new ConnectionHandler(connection));
      }
      return result;
    }
  }

  private static final class ConnectionHandler extends Passthrough {

    ConnectionHandler(Connection target) {
      super(target);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      Object common = common(proxy, method, args);
      if (common != NOT_HANDLED) {
        return common;
      }
      Object result = call(method, args);
      String name = method.getName();
      String sql = args != null && args.length > 0 && args[0] instanceof String text ? text : null;
      if (result instanceof CallableStatement statement && "prepareCall".equals(name)) {
        return proxy(CallableStatement.class, new StatementHandler(statement, sql));
      }
      if (result instanceof PreparedStatement statement && "prepareStatement".equals(name)) {
        return proxy(PreparedStatement.class, new StatementHandler(statement, sql));
      }
      if (result instanceof Statement statement && "createStatement".equals(name)) {
        return proxy(Statement.class, new StatementHandler(statement, null));
      }
      return result;
    }
  }

  private static final class StatementHandler extends Passthrough {

    private static final int MAX_PARAMETERS = 100;
    private static final int MAX_PARAMETER_TEXT = 300;

    private final String preparedSql;
    private RequestRecorder.Span lastSpan;
    /** Bound values by index, kept only while a request is recorded. */
    private java.util.TreeMap<Integer, Object> parameters;
    private int batched;

    StatementHandler(Statement target, String preparedSql) {
      super(target);
      this.preparedSql = preparedSql;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      Object common = common(proxy, method, args);
      if (common != NOT_HANDLED) {
        return common;
      }
      String name = method.getName();
      RequestRecorder recorder = RequestRecorder.current();
      if (recorder != null) {
        remember(name, args);
      }
      if (recorder == null || !EXECUTE.contains(name)) {
        Object result = call(method, args);
        if (recorder != null && result instanceof ResultSet resultSet
            && ("getResultSet".equals(name))) {
          return proxy(ResultSet.class, new ResultSetHandler(resultSet, recorder, lastSpan));
        }
        return result;
      }
      String sql = args != null && args.length > 0 && args[0] instanceof String text
          ? text : preparedSql;
      boolean batch = name.endsWith("Batch");
      if (batch && batched > 1) {
        sql = "[batch ×" + batched + "] " + sql;
      }
      Object[] bound = !batch && sql == preparedSql && parameters != null
          ? parameters.values().toArray() : null;
      if (batch) {
        batched = 0;
      }
      long start = System.nanoTime();
      try {
        Object result = call(method, args);
        lastSpan = recorder.sql(sql, start, System.nanoTime() - start, null, bound);
        if (result instanceof ResultSet resultSet) {
          return proxy(ResultSet.class, new ResultSetHandler(resultSet, recorder, lastSpan));
        }
        return result;
      } catch (Throwable e) {
        lastSpan = recorder.sql(sql, start, System.nanoTime() - start,
            e.getClass().getSimpleName() + ": " + e.getMessage(), bound);
        throw e;
      }
    }

    /** setString(1, "x"), setNull(2, type), clearParameters(), addBatch() ... */
    private void remember(String name, Object[] args) {
      if ("clearParameters".equals(name)) {
        parameters = null;
        return;
      }
      if ("addBatch".equals(name)) {
        batched++;
        return;
      }
      if (!name.startsWith("set") || args == null || args.length < 2
          || !(args[0] instanceof Integer index) || index < 1 || index > MAX_PARAMETERS) {
        return;
      }
      if (parameters == null) {
        parameters = new java.util.TreeMap<>();
      }
      Object value = "setNull".equals(name) ? null : args[1];
      if (value instanceof String text && text.length() > MAX_PARAMETER_TEXT) {
        value = text.substring(0, MAX_PARAMETER_TEXT);
      } else if (value != null && !(value instanceof Number || value instanceof Boolean
          || value instanceof String || value instanceof java.util.Date
          || value instanceof java.time.temporal.Temporal || value instanceof java.util.UUID
          || value instanceof Character || value instanceof Enum<?>)) {
        value = value instanceof byte[] bytes ? bytes : value.getClass().getSimpleName();
      }
      parameters.put(index, value);
    }
  }

  private static final class ResultSetHandler extends Passthrough {

    private final RequestRecorder recorder;
    private final RequestRecorder.Span span;

    ResultSetHandler(ResultSet target, RequestRecorder recorder, RequestRecorder.Span span) {
      super(target);
      this.recorder = recorder;
      this.span = span;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      Object common = common(proxy, method, args);
      if (common != NOT_HANDLED) {
        return common;
      }
      Object result = call(method, args);
      if ("next".equals(method.getName()) && Boolean.TRUE.equals(result)) {
        recorder.rowRead(span);
      }
      return result;
    }
  }
}
