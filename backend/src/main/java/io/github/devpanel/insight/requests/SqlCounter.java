package io.github.devpanel.insight.requests;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * SQL statements run by the current request's thread, counted by shape: literals and parameter
 * values are blanked, so {@code select ... where id = 5} and {@code ... id = 6} are one shape.
 * The same shape run 50 times in one request is the classic N+1 query problem.
 */
public final class SqlCounter {

  private static final ThreadLocal<SqlCounter> CURRENT = new ThreadLocal<>();
  private static final Pattern LITERALS = Pattern.compile("'[^']*'|\\b\\d+\\b");
  private static final Pattern SPACES = Pattern.compile("\\s+");
  private static final int MAX_SHAPES = 200;

  private final Map<String, Integer> shapes = new HashMap<>();
  private int total;

  static SqlCounter start() {
    SqlCounter counter = new SqlCounter();
    CURRENT.set(counter);
    return counter;
  }

  static void stop() {
    CURRENT.remove();
  }

  /** Called for every statement Hibernate prepares; outside a request it does nothing. */
  public static void record(String sql) {
    SqlCounter counter = CURRENT.get();
    if (counter == null || sql == null) {
      return;
    }
    counter.total++;
    String shape = SPACES.matcher(LITERALS.matcher(sql).replaceAll("?")).replaceAll(" ").trim();
    if (counter.shapes.size() < MAX_SHAPES || counter.shapes.containsKey(shape)) {
      counter.shapes.merge(shape, 1, Integer::sum);
    }
  }

  int total() {
    return total;
  }

  /** The most repeated shape and how often it ran, or null when nothing ran. */
  Map.Entry<String, Integer> mostRepeated() {
    return shapes.entrySet().stream().max(Map.Entry.comparingByValue()).orElse(null);
  }
}
