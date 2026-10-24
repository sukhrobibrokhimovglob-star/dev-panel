package io.github.devpanel.insight.trace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Everything one request did, recorded on its own thread: each SQL statement and external call
 * with its duration (the waterfall), rows read, and statements grouped by shape for N+1
 * detection. Outside a request nothing is recorded.
 */
public final class RequestRecorder {

  private static final ThreadLocal<RequestRecorder> CURRENT = new ThreadLocal<>();
  private static final Pattern LITERALS = Pattern.compile("'[^']*'|\\b\\d+\\b");
  private static final Pattern SPACES = Pattern.compile("\\s+");
  private static final int MAX_SPANS = 400;
  private static final int MAX_SHAPES = 300;
  private static final int MAX_TEXT = 2000;

  private final long startNanos = System.nanoTime();
  private final List<Span> spans = new ArrayList<>();
  private final Map<String, ShapeStats> shapes = new HashMap<>();
  private int sqlCount;
  private long sqlNanos;
  private long rows;
  private int externalCount;
  private long externalNanos;
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

  /** @return the span, so rows read later from its result set can be added to it */
  public Span sql(String sql, long startedNanos, long durationNanos, String error) {
    sqlCount++;
    sqlNanos += durationNanos;
    String shape = shape(sql);
    if (shape != null && (shapes.size() < MAX_SHAPES || shapes.containsKey(shape))) {
      shapes.computeIfAbsent(shape, key -> new ShapeStats()).add(durationNanos);
    }
    return add(new Span("SQL", cut(sql), offsetMs(startedNanos), durationNanos / 1_000_000.0,
        error));
  }

  public void external(String method, String url, int status, long startedNanos,
      long durationNanos, String error) {
    externalCount++;
    externalNanos += durationNanos;
    Span span = add(new Span("HTTP", method + " " + cut(url), offsetMs(startedNanos),
        durationNanos / 1_000_000.0, error));
    if (span != null) {
      span.status = status;
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

  /**
   * Statements of the same shape run 3+ times: each repeat beyond the first is a wasted round
   * trip (N+1). Worst first.
   */
  public List<Repeated> repeated() {
    List<Repeated> repeated = new ArrayList<>();
    shapes.forEach((shape, stats) -> {
      if (stats.count >= 3) {
        repeated.add(new Repeated(shape, stats.count, stats.nanos / 1_000_000.0));
      }
    });
    repeated.sort(Comparator.comparingInt(Repeated::count).reversed());
    return repeated;
  }

  public int wastedQueries() {
    return repeated().stream().mapToInt(r -> r.count() - 1).sum();
  }

  private double offsetMs(long startedNanos) {
    return Math.max(0, (startedNanos - startNanos) / 1_000_000.0);
  }

  static String shape(String sql) {
    if (sql == null) {
      return null;
    }
    return SPACES.matcher(LITERALS.matcher(sql).replaceAll("?")).replaceAll(" ").trim();
  }

  private static String cut(String text) {
    if (text == null) {
      return "";
    }
    return text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) + "…" : text;
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
  }

  /** {@code totalMs}: time spent in all runs of this shape. */
  public record Repeated(String statement, int count, double totalMs) {
  }

  private static final class ShapeStats {

    int count;
    long nanos;

    void add(long durationNanos) {
      count++;
      nanos += durationNanos;
    }
  }
}
