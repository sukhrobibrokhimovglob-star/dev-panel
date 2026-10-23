package io.github.devpanel.insight.logs;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The same exception from the same place, counted once: "NullPointerException at
 * PatientService.java:142, 87 times today" instead of 87 log entries. Fed by ERROR log events and
 * by requests that ended in a 5xx; the same Throwable seen by both is counted once.
 *
 * <p>Recording only touches memory (it runs on the logging thread); {@link #flush()} writes the
 * counts to {@code devpanel_exception} every half minute, which also tells whether a group was
 * ever seen before this start ("new since deploy").
 */
public class ExceptionGroups {

  /** Frames of these packages are skipped when looking for where an exception comes from. */
  private static final List<String> FRAMEWORK = List.of("java.", "javax.", "jakarta.", "jdk.",
      "sun.", "com.sun.", "org.springframework.", "org.hibernate.", "org.apache.", "com.zaxxer.",
      "org.postgresql.", "com.fasterxml.", "feign.", "io.github.devpanel.", "ch.qos.logback.",
      "org.slf4j.", "net.bytebuddy.", "org.aspectj.", "reactor.", "io.netty.", "org.h2.");
  private static final int MAX_GROUPS = 500;
  private static final int MAX_FRAMES = 25;

  private final JdbcTemplate jdbcTemplate;
  private final Instant started = Instant.now();
  private final Map<String, Group> groups = new ConcurrentHashMap<>();
  /** Throwables already counted, so a logged and re-thrown exception counts once. */
  private final Set<Throwable> recent = Collections.newSetFromMap(
      Collections.synchronizedMap(new IdentityLru(256)));

  public ExceptionGroups(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Instant started() {
    return started;
  }

  public void record(Throwable throwable, String traceId) {
    if (throwable == null) {
      return;
    }
    Throwable root = throwable;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    // By the root cause: a wrapped re-throw and the log entry of the same failure differ outside
    if (!recent.add(root)) {
      return;
    }
    String location = location(root);
    String type = root.getClass().getName();
    String fingerprint = Integer.toHexString((type + "@" + location).hashCode());
    if (!groups.containsKey(fingerprint) && groups.size() >= MAX_GROUPS) {
      return;
    }
    Group group = groups.computeIfAbsent(fingerprint,
        key -> new Group(key, type, location, sample(throwable)));
    group.hit(String.valueOf(root.getMessage()), traceId);
  }

  public List<GroupView> list() {
    List<GroupView> views = new ArrayList<>();
    for (Group group : groups.values()) {
      views.add(group.view(started));
    }
    views.sort(Comparator.comparing(GroupView::lastSeen).reversed());
    return views;
  }

  public long newSinceStart() {
    return groups.values().stream().filter(group -> group.view(started).isNew()).count();
  }

  /** Called by the panel's scheduler. */
  public void flush() {
    for (Group group : groups.values()) {
      long delta = group.takeDelta();
      if (delta == 0 && group.firstSeenEver != null) {
        continue;
      }
      try {
        Timestamp last = Timestamp.from(group.lastSeen);
        int updated = jdbcTemplate.update("UPDATE devpanel_exception SET last_seen = ?,"
                + " total = total + ?, message = ?, last_trace_id = ? WHERE fingerprint = ?",
            last, delta, cut(group.message, 1000), group.lastTraceId, group.fingerprint);
        if (updated == 0) {
          jdbcTemplate.update("INSERT INTO devpanel_exception (fingerprint, type, location,"
                  + " message, sample, first_seen, last_seen, total, last_trace_id)"
                  + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
              group.fingerprint, cut(group.type, 255), cut(group.location, 500),
              cut(group.message, 1000), cut(group.sample, 8000), Timestamp.from(group.firstSeen),
              last, delta, group.lastTraceId);
        }
        if (group.firstSeenEver == null) {
          group.firstSeenEver = jdbcTemplate.queryForObject(
              "SELECT first_seen FROM devpanel_exception WHERE fingerprint = ?", Timestamp.class,
              group.fingerprint).toInstant();
        }
      } catch (DataAccessException e) {
        group.restoreDelta(delta);
      }
    }
  }

  private static String location(Throwable root) {
    StackTraceElement[] stack = root.getStackTrace();
    for (StackTraceElement frame : stack) {
      if (FRAMEWORK.stream().noneMatch(frame.getClassName()::startsWith)) {
        return frame.toString();
      }
    }
    return stack.length > 0 ? stack[0].toString() : "unknown";
  }

  private static String sample(Throwable throwable) {
    StringBuilder text = new StringBuilder();
    Throwable current = throwable;
    for (int depth = 0; current != null && depth < 4; depth++) {
      text.append(depth == 0 ? "" : "Caused by: ").append(current).append('\n');
      StackTraceElement[] stack = current.getStackTrace();
      for (int i = 0; i < Math.min(stack.length, MAX_FRAMES); i++) {
        text.append("\tat ").append(stack[i]).append('\n');
      }
      if (stack.length > MAX_FRAMES) {
        text.append("\t... ").append(stack.length - MAX_FRAMES).append(" more\n");
      }
      current = current.getCause() == current ? null : current.getCause();
    }
    return text.toString();
  }

  private static String cut(String value, int max) {
    return value == null || value.length() <= max ? value : value.substring(0, max);
  }

  private static final class Group {

    final String fingerprint;
    final String type;
    final String location;
    final String sample;
    final Instant firstSeen = Instant.now();
    volatile Instant firstSeenEver;
    volatile Instant lastSeen = firstSeen;
    volatile String message;
    volatile String lastTraceId;
    long count;
    long delta;

    Group(String fingerprint, String type, String location, String sample) {
      this.fingerprint = fingerprint;
      this.type = type;
      this.location = location;
      this.sample = sample;
    }

    synchronized void hit(String newMessage, String traceId) {
      count++;
      delta++;
      lastSeen = Instant.now();
      message = newMessage;
      if (traceId != null) {
        lastTraceId = traceId;
      }
    }

    synchronized long takeDelta() {
      long taken = delta;
      delta = 0;
      return taken;
    }

    synchronized void restoreDelta(long taken) {
      delta += taken;
    }

    synchronized GroupView view(Instant started) {
      Instant first = firstSeenEver != null ? firstSeenEver : firstSeen;
      return new GroupView(fingerprint, type, location, message, sample, first, lastSeen, count,
          lastTraceId, !first.isBefore(started));
    }
  }

  /** {@code count} since this start; {@code isNew}: never seen before this start. */
  public record GroupView(String fingerprint, String type, String location, String message,
                          String sample, Instant firstSeen, Instant lastSeen, long count,
                          String lastTraceId, boolean isNew) {
  }

  /**
   * The most recent throwables. Throwable keeps Object's equals/hashCode, so this compares by
   * identity: two different exceptions with the same message are two events.
   */
  private static final class IdentityLru extends LinkedHashMap<Throwable, Boolean> {

    private final int max;

    IdentityLru(int max) {
      this.max = max;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<Throwable, Boolean> eldest) {
      return size() > max;
    }
  }
}
