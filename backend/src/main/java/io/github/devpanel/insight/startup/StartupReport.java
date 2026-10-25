package io.github.devpanel.insight.startup;

import io.github.devpanel.insight.ops.DeployInfo;
import io.github.devpanel.insight.trace.RequestRecorder;
import io.github.devpanel.insight.trace.RequestRecorder.Kind;
import io.github.devpanel.insight.trace.RequestRecorder.Repeated;
import java.lang.management.ManagementFactory;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.context.metrics.buffering.StartupTimeline;
import org.springframework.core.metrics.StartupStep;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What this start did and how long it took: the slowest beans (their own time, without the beans
 * they needed), the SQL the start ran, rows inserted per table, database migrations applied. A
 * start that inserts the same rows every time (reference data loaded again and again) shows up
 * here, and across starts in the risks. Stored per start in {@code devpanel_deploy}.
 */
public class StartupReport {

  private static final Pattern INSERT_TABLE =
      Pattern.compile("(?i)^\\s*insert\\s+into\\s+([\\w.\"]+)");
  private static final int TOP = 30;

  private final JdbcTemplate jdbcTemplate;
  private final DeployInfo deploys;
  private volatile Report report;

  public StartupReport(JdbcTemplate jdbcTemplate, DeployInfo deploys) {
    this.jdbcTemplate = jdbcTemplate;
    this.deploys = deploys;
  }

  /** Called once the application is ready, on the thread that started it. */
  public void finish() {
    StartupRecorder.Recording recording = StartupRecorder.take();
    long jvmStart = ManagementFactory.getRuntimeMXBean().getStartTime();
    long uptime = ManagementFactory.getRuntimeMXBean().getUptime();
    RequestRecorder recorder = recording == null ? null : recording.recorder();
    if (recorder != null && RequestRecorder.current() == recorder) {
      RequestRecorder.stop();
    }
    List<Bean> beans = recording == null || recording.startup() == null ? List.of()
        : beans(recording.startup().drainBufferedTimeline());
    Map<String, Integer> inserts = new LinkedHashMap<>();
    List<Repeated> statements = new ArrayList<>();
    int sqlCount = 0;
    double sqlMs = 0;
    if (recorder != null) {
      sqlCount = recorder.sqlCount();
      sqlMs = recorder.sqlMs();
      for (Repeated repeated : recorder.repeated()) {
        if (!isPanel(repeated.statement())) {
          statements.add(repeated);
        }
      }
      recorder.spans().forEach(span -> {
        Matcher matcher = INSERT_TABLE.matcher(span.getText());
        if ("SQL".equals(span.getKind()) && matcher.find()) {
          String table = matcher.group(1).replace("\"", "").toLowerCase(Locale.ROOT);
          if (!isPanel(table)) {
            inserts.merge(table, 1, Integer::sum);
          }
        }
      });
      // Spans stop at 400; shapes keep counting, so take insert counts from them when larger
      for (Repeated repeated : recorder.repeated()) {
        if (repeated.kind() == Kind.INSERT) {
          Matcher matcher = INSERT_TABLE.matcher(repeated.statement());
          if (matcher.find()) {
            String table = matcher.group(1).replace("\"", "").toLowerCase(Locale.ROOT);
            if (!isPanel(table)) {
              inserts.merge(table, repeated.count(), Math::max);
            }
          }
        }
      }
    }
    statements.sort(Comparator.comparingInt(Repeated::count).reversed());
    Report built = new Report(Instant.ofEpochMilli(jvmStart), uptime,
        recording == null ? -1 : System.currentTimeMillis() - recording.contextStartedAt(),
        sqlCount, Math.round(sqlMs), beans, statements.subList(0, Math.min(TOP,
        statements.size())), inserts, migrations(Instant.ofEpochMilli(jvmStart)),
        recording != null && recording.startup() == null);
    report = built;
    store(built);
  }

  public Report report() {
    return report;
  }

  /** Rows inserted per table by each of the last {@code count} starts, newest first. */
  public List<Map<String, Integer>> insertsOfRecentStarts(int count) {
    try {
      return jdbcTemplate.query("SELECT startup_inserts FROM devpanel_deploy"
              + " WHERE startup_inserts IS NOT NULL ORDER BY started_at DESC LIMIT ?",
          (rs, i) -> parse(rs.getString(1)), count);
    } catch (DataAccessException e) {
      return List.of();
    }
  }

  /** Startup duration (ms) of the last {@code count} starts, newest first. */
  public List<long[]> durations(int count) {
    try {
      return jdbcTemplate.query("SELECT started_at, startup_ms FROM devpanel_deploy"
              + " WHERE startup_ms IS NOT NULL ORDER BY started_at DESC LIMIT ?",
          (rs, i) -> new long[] {rs.getTimestamp(1).getTime(), rs.getLong(2)}, count);
    } catch (DataAccessException e) {
      return List.of();
    }
  }

  private void store(Report built) {
    try {
      StringBuilder inserts = new StringBuilder();
      built.inserts().forEach((table, rows) -> {
        if (inserts.length() < 3900) {
          inserts.append(inserts.length() == 0 ? "" : ";").append(table).append(':').append(rows);
        }
      });
      jdbcTemplate.update("UPDATE devpanel_deploy SET startup_ms = ?, startup_sql = ?,"
              + " startup_inserts = ? WHERE started_at = ? AND host = ?",
          built.uptimeMs(), built.sqlCount(), inserts.toString(),
          Timestamp.from(deploys.current().startedAt()), cut(deploys.current().host()));
    } catch (DataAccessException ignored) {
      // only informative
    }
  }

  private List<Migration> migrations(Instant since) {
    List<Migration> migrations = new ArrayList<>();
    Timestamp from = Timestamp.from(since);
    try {
      migrations.addAll(jdbcTemplate.query("SELECT id, author, filename, dateexecuted"
              + " FROM databasechangelog WHERE dateexecuted >= ? ORDER BY orderexecuted",
          (rs, i) -> new Migration("liquibase", rs.getString("id"), rs.getString("filename"),
              rs.getString("author")), from));
    } catch (DataAccessException ignored) {
      // no Liquibase
    }
    try {
      migrations.addAll(jdbcTemplate.query("SELECT version, description, script, installed_by"
              + " FROM flyway_schema_history WHERE installed_on >= ? ORDER BY installed_rank",
          (rs, i) -> new Migration("flyway", rs.getString("version"), rs.getString("script"),
              rs.getString("installed_by")), from));
    } catch (DataAccessException ignored) {
      // no Flyway
    }
    return migrations;
  }

  /** Bean creation steps with their own time: total minus the beans created inside them. */
  static List<Bean> beans(StartupTimeline timeline) {
    Map<Long, Long> childNanos = new HashMap<>();
    List<StartupTimeline.TimelineEvent> events = timeline.getEvents();
    for (StartupTimeline.TimelineEvent event : events) {
      Long parent = event.getStartupStep().getParentId();
      if (parent != null && "spring.beans.instantiate".equals(event.getStartupStep().getName())) {
        childNanos.merge(parent, event.getDuration().toNanos(), Long::sum);
      }
    }
    List<Bean> beans = new ArrayList<>();
    for (StartupTimeline.TimelineEvent event : events) {
      StartupStep step = event.getStartupStep();
      if (!"spring.beans.instantiate".equals(step.getName())) {
        continue;
      }
      String name = null;
      String type = null;
      for (StartupStep.Tag tag : step.getTags()) {
        if ("beanName".equals(tag.getKey())) {
          name = tag.getValue();
        } else if ("beanType".equals(tag.getKey())) {
          type = tag.getValue();
        }
      }
      long total = event.getDuration().toMillis();
      long own = Math.max(0, (event.getDuration().toNanos()
          - childNanos.getOrDefault(step.getId(), 0L)) / 1_000_000);
      if (name != null && !name.startsWith("devPanel")) {
        beans.add(new Bean(name, type, total, own));
      }
    }
    beans.sort(Comparator.comparingLong(Bean::ownMs).reversed());
    return beans.subList(0, Math.min(TOP, beans.size()));
  }

  static Map<String, Integer> parse(String text) {
    Map<String, Integer> inserts = new LinkedHashMap<>();
    if (text == null || text.isBlank()) {
      return inserts;
    }
    for (String part : text.split(";")) {
      int colon = part.lastIndexOf(':');
      if (colon > 0) {
        try {
          inserts.put(part.substring(0, colon), Integer.parseInt(part.substring(colon + 1)));
        } catch (NumberFormatException ignored) {
          // skip
        }
      }
    }
    return inserts;
  }

  private static boolean isPanel(String text) {
    String lower = text.toLowerCase(Locale.ROOT);
    return lower.contains("devpanel_") || lower.contains("databasechangelog")
        || lower.contains("flyway_schema_history");
  }

  private static String cut(String value) {
    return value == null || value.length() <= 100 ? value : value.substring(0, 100);
  }

  /** {@code ownMs}: without the beans it needed, which have rows of their own. */
  public record Bean(String name, String type, long totalMs, long ownMs) {
  }

  public record Migration(String tool, String id, String file, String author) {
  }

  /**
   * {@code uptimeMs}: JVM start until ready; {@code contextMs}: Spring's part of it;
   * {@code ownStartupRecorder}: the application records startup steps itself, so beans are
   * missing here.
   */
  public record Report(Instant jvmStartedAt, long uptimeMs, long contextMs, int sqlCount,
                       long sqlMs, List<Bean> beans, List<Repeated> statements,
                       Map<String, Integer> inserts, List<Migration> migrations,
                       boolean ownStartupRecorder) {
  }
}
