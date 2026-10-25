package io.github.devpanel.insight.ops;

import io.github.devpanel.support.DevPanelException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingSystem;

/**
 * Changes a logger's level while the application runs, e.g. DEBUG for one package while chasing a
 * problem, and puts it back by itself after the chosen time, so nobody forgets production on
 * DEBUG. A restart also restores the configured levels.
 */
public class LogLevels {

  private static final Logger log = LoggerFactory.getLogger(LogLevels.class);
  private static final Duration MAX = Duration.ofHours(4);

  private final LoggingSystem loggingSystem;
  private final Map<String, Change> changes = new ConcurrentHashMap<>();

  public LogLevels(LoggingSystem loggingSystem) {
    this.loggingSystem = loggingSystem;
  }

  /** Configured loggers, and any logger whose name contains {@code filter}. */
  public List<LoggerView> list(String filter) {
    String needle = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
    List<LoggerView> views = new ArrayList<>();
    for (LoggerConfiguration configuration : loggingSystem.getLoggerConfigurations()) {
      boolean explicit = configuration.getConfiguredLevel() != null;
      boolean matches = !needle.isEmpty()
          && configuration.getName().toLowerCase(Locale.ROOT).contains(needle);
      if ((needle.isEmpty() && explicit) || matches) {
        Change change = changes.get(configuration.getName());
        views.add(new LoggerView(configuration.getName(),
            name(configuration.getConfiguredLevel()), name(configuration.getEffectiveLevel()),
            change == null ? null : change.until()));
      }
      if (views.size() >= 300) {
        break;
      }
    }
    return views;
  }

  /** The level {@code logger} really logs at (inherited from its parents), e.g. DEBUG. */
  public String effective(String logger) {
    String name = logger;
    while (name != null) {
      LoggerConfiguration configuration = loggingSystem.getLoggerConfiguration(name);
      if (configuration != null && configuration.getEffectiveLevel() != null) {
        return name(configuration.getEffectiveLevel());
      }
      int dot = name.lastIndexOf('.');
      name = dot > 0 ? name.substring(0, dot) : "ROOT".equals(name) ? null : "ROOT";
    }
    return null;
  }

  public void set(String logger, String level, int minutes, String by) {
    if (logger == null || logger.isBlank()) {
      throw DevPanelException.badRequest("Logger name is required");
    }
    LogLevel newLevel;
    try {
      newLevel = LogLevel.valueOf(level.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException | NullPointerException e) {
      throw DevPanelException.badRequest("Level must be TRACE, DEBUG, INFO, WARN, ERROR or OFF");
    }
    Duration requested = Duration.ofMinutes(Math.max(1, minutes));
    Duration duration = requested.compareTo(MAX) > 0 ? MAX : requested;
    String name = "ROOT".equalsIgnoreCase(logger) ? LoggingSystem.ROOT_LOGGER_NAME : logger.trim();
    LoggerConfiguration before = loggingSystem.getLoggerConfiguration(name);
    LogLevel original = before == null ? null : before.getConfiguredLevel();
    changes.compute(name, (key, existing) -> new Change(
        existing != null ? existing.original() : original, Instant.now().plus(duration)));
    loggingSystem.setLogLevel(name, newLevel);
    log.warn("Dev panel: {} set logger '{}' to {} for {} min", by, name, newLevel,
        duration.toMinutes());
  }

  public void reset(String logger) {
    Change change = changes.remove(logger);
    if (change != null) {
      loggingSystem.setLogLevel(logger, change.original());
    }
  }

  /** Called by the panel's scheduler; puts expired changes back. */
  public void revertExpired() {
    Instant now = Instant.now();
    for (Map.Entry<String, Change> entry : changes.entrySet()) {
      if (entry.getValue().until().isBefore(now)) {
        reset(entry.getKey());
        log.info("Dev panel: logger '{}' is back to its configured level", entry.getKey());
      }
    }
  }

  private static String name(LogLevel level) {
    return level == null ? null : level.name();
  }

  private record Change(LogLevel original, Instant until) {
  }

  /** {@code changedUntil}: set from the panel, reverts at that time. */
  public record LoggerView(String name, String configured, String effective,
                           Instant changedUntil) {
  }
}
