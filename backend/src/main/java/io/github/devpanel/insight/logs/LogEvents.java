package io.github.devpanel.insight.logs;

import io.github.devpanel.insight.MinuteCounter;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * What the application logs, as the panel keeps it: the last {@value #KEEP} lines (for the live
 * view), WARN/ERROR counts per minute (for the error-rate warning), exception groups and Hikari's
 * connection-leak reports. Filled by {@code LogbackCapture}; everything here is in memory and
 * cheap, since it runs on the application's logging threads.
 */
public class LogEvents {

  static final int KEEP = 5000;
  private static final int KEEP_LEAKS = 50;
  private static final String HIKARI_LEAK_LOGGER = "com.zaxxer.hikari.pool.ProxyLeakTask";

  private final Deque<LogLine> lines = new ArrayDeque<>();
  private final Deque<LeakReport> leaks = new ArrayDeque<>();
  private final List<Consumer<LogLine>> listeners = new CopyOnWriteArrayList<>();
  private final AtomicLong sequence = new AtomicLong();
  private final MinuteCounter errors = new MinuteCounter();
  private final MinuteCounter warnings = new MinuteCounter();
  private final ExceptionGroups exceptionGroups;
  private final LogPrivacy privacy;

  public LogEvents(ExceptionGroups exceptionGroups) {
    this(exceptionGroups, null);
  }

  /** @param privacy null to not look for personal data */
  public LogEvents(ExceptionGroups exceptionGroups, LogPrivacy privacy) {
    this.exceptionGroups = exceptionGroups;
    this.privacy = privacy;
  }

  public void accept(long time, String level, String logger, String thread, String message,
      String traceId, Throwable throwable, String errorText) {
    LogLine line = new LogLine(sequence.incrementAndGet(), time, level, logger, thread,
        message, traceId, errorText);
    if (privacy != null) {
      privacy.inspect(logger, level, message, time);
    }
    synchronized (lines) {
      lines.addLast(line);
      if (lines.size() > KEEP) {
        lines.removeFirst();
      }
    }
    if ("ERROR".equals(level)) {
      errors.add(time, 1);
      exceptionGroups.record(throwable, traceId);
    } else if ("WARN".equals(level)) {
      warnings.add(time, 1);
      if (HIKARI_LEAK_LOGGER.equals(logger) && errorText != null) {
        synchronized (leaks) {
          leaks.addFirst(new LeakReport(Instant.ofEpochMilli(time), thread, message, errorText));
          if (leaks.size() > KEEP_LEAKS) {
            leaks.removeLast();
          }
        }
      }
    }
    for (Consumer<LogLine> listener : listeners) {
      listener.accept(line);
    }
  }

  /** The kept lines of one request, oldest first. */
  public List<LogLine> byTrace(String traceId) {
    List<LogLine> matching = new ArrayList<>();
    synchronized (lines) {
      for (LogLine line : lines) {
        if (traceId.equals(line.traceId())) {
          matching.add(line);
        }
      }
    }
    return matching;
  }

  /** The kept lines matching the filter, oldest first, at most {@code limit}. */
  public List<LogLine> recent(String minLevel, String text, int limit) {
    List<LogLine> matching = new ArrayList<>();
    synchronized (lines) {
      for (LogLine line : lines) {
        if (matches(line, minLevel, text)) {
          matching.add(line);
        }
      }
    }
    return matching.subList(Math.max(0, matching.size() - limit), matching.size());
  }

  public static boolean matches(LogLine line, String minLevel, String text) {
    if (minLevel != null && rank(line.level()) < rank(minLevel)) {
      return false;
    }
    if (text == null || text.isBlank()) {
      return true;
    }
    String needle = text.toLowerCase(Locale.ROOT);
    return contains(line.message(), needle) || contains(line.logger(), needle)
        || contains(line.traceId(), needle) || contains(line.error(), needle);
  }

  public Runnable subscribe(Consumer<LogLine> listener) {
    listeners.add(listener);
    return () -> listeners.remove(listener);
  }

  public long errorsInLast(long now, int minutes) {
    return errors.sum(now, minutes);
  }

  public long warningsInLast(long now, int minutes) {
    return warnings.sum(now, minutes);
  }

  public long[] errorsPerMinute(long now, int minutes) {
    return errors.series(now, minutes);
  }

  public List<LeakReport> leaks() {
    synchronized (leaks) {
      return new ArrayList<>(leaks);
    }
  }

  private static int rank(String level) {
    return switch (level == null ? "" : level.toUpperCase(Locale.ROOT)) {
      case "ERROR" -> 4;
      case "WARN" -> 3;
      case "INFO" -> 2;
      case "DEBUG" -> 1;
      default -> 0;
    };
  }

  private static boolean contains(String value, String needle) {
    return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
  }

  /**
   * A connection taken from the pool and not returned within the leak-detection threshold;
   * {@code stack} is where it was taken.
   */
  public record LeakReport(Instant time, String thread, String message, String stack) {
  }
}
