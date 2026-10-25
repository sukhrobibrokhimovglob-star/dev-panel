package io.github.devpanel.insight.trace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Everything one request did, recorded on its own thread: each SQL statement and external call
 * with its duration (the waterfall), rows read, and statements grouped by shape. Each shape also
 * knows where in the application's code it ran ({@link CallSites}), what kind of statement it is
 * and how many of its runs had exactly the same parameters. Outside a request nothing is recorded.
 *
 * <p>Repeated shapes are judged by kind: repeated lookups ({@code SELECT}) are N+1, repeated
 * writes ({@code INSERT}/{@code UPDATE}/{@code DELETE}) one per item are normal but could be
 * batched, and repeated {@code nextval} calls mean ids are fetched one at a time.
 */
public final class RequestRecorder {

  private static final ThreadLocal<RequestRecorder> CURRENT = new ThreadLocal<>();
  private static final Pattern LITERALS = Pattern.compile("'[^']*'|\\b\\d+\\b");
  private static final Pattern SPACES = Pattern.compile("\\s+");
  private static final int MAX_SPANS = 400;
  private static final int MAX_SHAPES = 300;
  private static final int MAX_TEXT = 2000;
  private static final int MAX_PARAMETER_KEYS = 500;

  private final long startNanos = System.nanoTime();
  private final List<Span> spans = new ArrayList<>();
  private final Map<String, ShapeStats> shapes = new HashMap<>();
  private int sqlCount;
  private long sqlNanos;
  private long rows;
  private int externalCount;
  private long externalNanos;
  private int externalInTransaction;
  private boolean truncated;
  private String user;
  private String tenant;

  public static RequestRecorder start() {
    RequestRecorder recorder = new RequestRecorder();
    CURRENT.set(recorder);
    return recorder;
  }

  public static void stop() {
    CURRENT.remove();
  }

  public static RequestRecorder current() {
    return CURRENT.get();
  }

  /** Without parameters (plain statements). */
  public Span sql(String sql, long startedNanos, long durationNanos, String error) {
    return sql(sql, startedNanos, durationNanos, error, null);
  }

  /**
   * @param parameters the bound values of a prepared statement in order, null when unknown
   * @return the span, so rows read later from its result set can be added to it
   */
  public Span sql(String sql, long startedNanos, long durationNanos, String error,
      Object[] parameters) {
    sqlCount++;
    sqlNanos += durationNanos;
    String shape = shape(sql);
    ShapeStats stats = null;
    if (shape != null && (shapes.size() < MAX_SHAPES || shapes.containsKey(shape))) {
      stats = shapes.get(shape);
      if (stats == null) {
        stats = new ShapeStats(kind(shape), CallSites.describe(CallSites.current()));
        shapes.put(shape, stats);
      }
      stats.add(durationNanos, parameters);
    }
    Span span = add(new Span("SQL", cut(sql), offsetMs(startedNanos),
        durationNanos / 1_000_000.0, error));
    if (span != null) {
      span.location = stats == null ? null : stats.location;
      span.parameters = parameters;
    }
    return span;
  }

  public void external(String method, String url, int status, long startedNanos,
      long durationNanos, String error) {
    externalCount++;
    externalNanos += durationNanos;
    boolean inTransaction = inTransaction();
    if (inTransaction) {
      externalInTransaction++;
    }
    Span span = add(new Span("HTTP", method + " " + cut(url), offsetMs(startedNanos),
        durationNanos / 1_000_000.0, error));
    if (span != null) {
      span.status = status;
      span.inTransaction = inTransaction;
      span.location = CallSites.describe(CallSites.current());
    }
  }

  void rowRead(Span span) {
    rows++;
    if (span != null) {
      span.rows++;
    }
  }

  public void identify(String userName, String tenantValue) {
    if (userName != null) {
      user = userName;
    }
    if (tenantValue != null) {
      tenant = tenantValue;
    }
  }

  private Span add(Span span) {
    if (spans.size() >= MAX_SPANS) {
      truncated = true;
      return null;
    }
    spans.add(span);
    return span;
  }

  public double elapsedMs() {
    return (System.nanoTime() - startNanos) / 1_000_000.0;
  }

  public int sqlCount() {
    return sqlCount;
  }

  public double sqlMs() {
    return sqlNanos / 1_000_000.0;
  }

  public long rows() {
    return rows;
  }

  public int externalCount() {
    return externalCount;
  }

  public double externalMs() {
    return externalNanos / 1_000_000.0;
  }

  /** External calls made while a database transaction (and so a connection) was held. */
  public int externalInTransaction() {
    return externalInTransaction;
  }

  public List<Span> spans() {
    return spans;
  }

  public boolean truncated() {
    return truncated;
  }

  public String user() {
    return user;
  }

  public String tenant() {
    return tenant;
  }

  /** Statements of the same shape run 3+ times, worst first, whatever their kind. */
  public List<Repeated> repeated() {
    List<Repeated> repeated = new ArrayList<>();
    shapes.forEach((shape, stats) -> {
      if (stats.count >= 3) {
        repeated.add(new Repeated(shape, stats.count, stats.nanos / 1_000_000.0, stats.kind,
            stats.location, stats.exactRepeats()));
      }
    });
    repeated.sort(Comparator.comparingInt(Repeated::count).reversed());
    return repeated;
  }

  /** Repeated lookups beyond the first of each shape: the N+1 measure. */
  public int wastedQueries() {
    int wasted = 0;
    for (ShapeStats stats : shapes.values()) {
      if (stats.count >= 3 && stats.kind == Kind.SELECT) {
        wasted += stats.count - 1;
      }
    }
    return wasted;
  }

  /** Repeated writes beyond the first of each shape: what JDBC batching would save. */
  public int writeRepeats() {
    int repeats = 0;
    for (ShapeStats stats : shapes.values()) {
      if (stats.count >= 3 && stats.kind.isWrite()) {
        repeats += stats.count - 1;
      }
    }
    return repeats;
  }

  /** Sequence calls ({@code nextval}): ids fetched one round trip at a time. */
  public int sequenceCalls() {
    int calls = 0;
    for (ShapeStats stats : shapes.values()) {
      if (stats.kind == Kind.SEQUENCE) {
        calls += stats.count;
      }
    }
    return calls;
  }

  /** Lookups that ran again with exactly the same parameters in this request. */
  public int exactRepeats() {
    int repeats = 0;
    for (ShapeStats stats : shapes.values()) {
      if (stats.kind == Kind.SELECT) {
        repeats += stats.exactRepeats();
      }
    }
    return repeats;
  }

  private double offsetMs(long startedNanos) {
    return Math.max(0, (startedNanos - startNanos) / 1_000_000.0);
  }

  private static boolean inTransaction() {
    try {
      return TransactionSynchronizationManager.isActualTransactionActive();
    } catch (LinkageError e) {
      return false;
    }
  }

  static String shape(String sql) {
    if (sql == null) {
      return null;
    }
    return SPACES.matcher(LITERALS.matcher(sql).replaceAll("?")).replaceAll(" ").trim();
  }

  static Kind kind(String shape) {
    String text = shape.stripLeading().toLowerCase(Locale.ROOT);
    while (text.startsWith("(")) {
      text = text.substring(1).stripLeading();
    }
    if (text.contains("nextval")) {
      return Kind.SEQUENCE;
    }
    if (text.startsWith("select") || text.startsWith("with")) {
      return Kind.SELECT;
    }
    if (text.startsWith("insert")) {
      return Kind.INSERT;
    }
    if (text.startsWith("update")) {
      return Kind.UPDATE;
    }
    if (text.startsWith("delete")) {
      return Kind.DELETE;
    }
    return Kind.OTHER;
  }

  private static String cut(String text) {
    if (text == null) {
      return "";
    }
    return text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) + "…" : text;
  }

  public enum Kind {
    SELECT, INSERT, UPDATE, DELETE, SEQUENCE, OTHER;

    public boolean isWrite() {
      return this == INSERT || this == UPDATE || this == DELETE;
    }
  }

  /** One step of the waterfall; times in ms from the start of the request. */
  public static final class Span {

    private final String kind;
    private final String text;
    private final double startMs;
    private final double durationMs;
    private final String error;
    private long rows;
    private int status;
    private String location;
    private boolean inTransaction;
    /** Not serialized: shown only through the plan viewer, which needs the real values. */
    private Object[] parameters;

    Span(String kind, String text, double startMs, double durationMs, String error) {
      this.kind = kind;
      this.text = text;
      this.startMs = startMs;
      this.durationMs = durationMs;
      this.error = error;
    }

    public String getKind() {
      return kind;
    }

    public String getText() {
      return text;
    }

    public double getStartMs() {
      return startMs;
    }

    public double getDurationMs() {
      return durationMs;
    }

    public String getError() {
      return error;
    }

    public long getRows() {
      return rows;
    }

    public int getStatus() {
      return status;
    }

    /** Where in the application's code it ran, innermost first; null when unknown. */
    public String getLocation() {
      return location;
    }

    /** HTTP only: made while a database transaction was open. */
    public boolean isInTransaction() {
      return inTransaction;
    }

    public boolean getHasParameters() {
      return parameters != null;
    }

    public Object[] boundParameters() {
      return parameters;
    }
  }

  /**
   * {@code totalMs}: time spent in all runs of this shape; {@code exactRepeats}: runs that had
   * exactly the parameters of an earlier run (the same row fetched again), -1 when unknown.
   */
  public record Repeated(String statement, int count, double totalMs, Kind kind, String location,
                         int exactRepeats) {

    public Repeated(String statement, int count, double totalMs) {
      this(statement, count, totalMs, RequestRecorder.kind(statement), null, -1);
    }
  }

  private static final class ShapeStats {

    final Kind kind;
    final String location;
    int count;
    long nanos;
    int withParameters;
    Set<String> parameterKeys;

    ShapeStats(Kind kind, String location) {
      this.kind = kind;
      this.location = location;
    }

    void add(long durationNanos, Object[] parameters) {
      count++;
      nanos += durationNanos;
      if (parameters != null) {
        if (parameterKeys == null) {
          parameterKeys = new HashSet<>();
        }
        if (parameterKeys.size() < MAX_PARAMETER_KEYS) {
          withParameters++;
          parameterKeys.add(key(parameters));
        }
      }
    }

    int exactRepeats() {
      return parameterKeys == null ? -1 : withParameters - parameterKeys.size();
    }

    private static String key(Object[] parameters) {
      StringBuilder key = new StringBuilder();
      for (Object parameter : parameters) {
        key.append(parameter instanceof byte[] bytes ? "bytes:" + bytes.length
            : String.valueOf(parameter)).append('\u0001');
      }
      return key.toString();
    }
  }
}
