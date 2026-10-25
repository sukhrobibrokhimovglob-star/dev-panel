package io.github.devpanel.insight.logs;

import io.github.devpanel.support.Sensitive;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Notices personal and secret data the application writes to its logs (PNFL, tokens, passwords,
 * card numbers, e-mail, phone numbers), per logger and kind, with one masked example. Logs are
 * read by many people and kept for long; this is where data leaks quietly.
 */
public class LogPrivacy {

  private static final int SCAN = 2000;
  private static final int MAX_ENTRIES = 500;

  private final Sensitive sensitive;
  private final Map<String, Entry> entries = new ConcurrentHashMap<>();

  public LogPrivacy(Sensitive sensitive) {
    this.sensitive = sensitive;
  }

  void inspect(String logger, String level, String message, long time) {
    if (message == null || logger == null
        || io.github.devpanel.insight.trace.CallSites.isPanel(logger)) {
      return;
    }
    String text = message.length() > SCAN ? message.substring(0, SCAN) : message;
    Set<String> kinds = sensitive.kinds(text);
    for (String kind : kinds) {
      String key = logger + '\u0001' + kind;
      Entry entry = entries.get(key);
      if (entry == null) {
        if (entries.size() >= MAX_ENTRIES) {
          continue;
        }
        entry = entries.computeIfAbsent(key, k -> new Entry(logger, kind));
      }
      entry.hit(time, level, text);
    }
  }

  /** Most frequent first. */
  public List<Finding> findings() {
    List<Finding> findings = new ArrayList<>();
    entries.values().forEach(entry -> findings.add(entry.view(sensitive)));
    findings.sort(Comparator.comparingLong(Finding::count).reversed());
    return findings;
  }

  private static final class Entry {

    final String logger;
    final String kind;
    long count;
    long firstSeen;
    long lastSeen;
    String level;
    String example;

    Entry(String logger, String kind) {
      this.logger = logger;
      this.kind = kind;
    }

    synchronized void hit(long time, String lineLevel, String text) {
      if (count == 0) {
        firstSeen = time;
      }
      count++;
      lastSeen = time;
      level = lineLevel;
      example = text.length() > 300 ? text.substring(0, 300) : text;
    }

    synchronized Finding view(Sensitive sensitive) {
      return new Finding(logger, kind, level, count, Instant.ofEpochMilli(firstSeen),
          Instant.ofEpochMilli(lastSeen), sensitive.mask(example));
    }
  }

  /** {@code example}: the last such line, masked. */
  public record Finding(String logger, String kind, String level, long count, Instant firstSeen,
                        Instant lastSeen, String example) {
  }
}
