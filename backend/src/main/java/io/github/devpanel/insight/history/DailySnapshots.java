package io.github.devpanel.insight.history;

import io.github.devpanel.insight.database.DatabaseInsights;
import io.github.devpanel.insight.memory.MemoryWatch;
import io.github.devpanel.insight.server.TomcatCapacity;
import io.github.devpanel.metrics.DevMetricsService;
import io.github.devpanel.metrics.HikariPools.PoolState;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * One number per day and metric in {@code devpanel_daily}, kept 400 days: database size, the
 * fullest disk, the heap left after garbage collection, the busiest moment of the connection pool
 * and of the request threads, rows and bytes of the largest tables. Long trends ("full in 40
 * days", "the pool's daily peak keeps rising") are drawn from these, as the in-memory metrics
 * only cover a day and are lost on restart.
 *
 * <p>Peaks are sampled every minute and written every ten; the day's value of a peak is the
 * highest of all instances and restarts.
 */
public class DailySnapshots {

  private static final Logger log = LoggerFactory.getLogger(DailySnapshots.class);
  private static final int KEEP_DAYS = 400;
  private static final int TABLES = 100;
  private static final Set<String> PEAKS = Set.of("pool.peak", "pool.pending.peak",
      "threads.peak", "disk.used");

  private final JdbcTemplate jdbcTemplate;
  private final DevMetricsService metrics;
  private final DatabaseInsights database;
  private final MemoryWatch memory;
  private final TomcatCapacity tomcat;
  private final String instance;
  private final Map<String, Double> peaks = new HashMap<>();
  private LocalDate peaksDay = LocalDate.now();

  /** {@code memory} and {@code tomcat} may be null. */
  public DailySnapshots(JdbcTemplate jdbcTemplate, DevMetricsService metrics,
      DatabaseInsights database, MemoryWatch memory, TomcatCapacity tomcat, String instance) {
    this.jdbcTemplate = jdbcTemplate;
    this.metrics = metrics;
    this.database = database;
    this.memory = memory;
    this.tomcat = tomcat;
    this.instance = instance.length() > 100 ? instance.substring(0, 100) : instance;
  }

  /** Every minute: remembers the busiest moment of today. */
  public synchronized void sample() {
    if (!LocalDate.now().equals(peaksDay)) {
      peaks.clear();
      peaksDay = LocalDate.now();
    }
    for (PoolState pool : metrics.hikari()) {
      if (pool.max() > 0) {
        peak("pool.peak", (double) pool.active() / pool.max());
      }
      peak("pool.pending.peak", pool.pending());
    }
    TomcatCapacity.State state = tomcat == null ? null : tomcat.state();
    if (state != null && state.maxThreads() > 0 && state.busyThreads() >= 0) {
      peak("threads.peak", (double) state.busyThreads() / state.maxThreads());
    }
    for (DevMetricsService.Disk disk : DevMetricsService.disks()) {
      if (disk.totalSpace() > 0) {
        peak("disk.used",
            (double) (disk.totalSpace() - disk.usableSpace()) / disk.totalSpace());
      }
    }
  }

  /** Every ten minutes: writes today's values. */
  public void record() {
    Map<String, Double> values = new LinkedHashMap<>();
    synchronized (this) {
      values.putAll(peaks);
    }
    if (memory != null) {
      List<MemoryWatch.FloorPoint> floor = memory.analysis().floor();
      if (!floor.isEmpty()) {
        values.put("heap.floor", (double) floor.get(floor.size() - 1).bytes());
        values.put("heap.max", (double) memory.analysis().heapMax());
      }
    }
    if (database.isPostgres()) {
      try {
        values.put("db.size", (double) database.overview().sizeBytes());
        List<DatabaseInsights.TableStat> tables = database.tables();
        for (DatabaseInsights.TableStat table : tables.subList(0,
            Math.min(TABLES, tables.size()))) {
          String name = table.schema() + "." + table.table();
          values.put("table.rows:" + name, (double) table.liveRows());
          values.put("table.bytes:" + name, (double) table.totalBytes());
        }
      } catch (DataAccessException e) {
        log.debug("Dev panel daily snapshot of the database failed: {}", e.getMessage());
      }
    }
    write(LocalDate.now(), values);
  }

  private void write(LocalDate day, Map<String, Double> values) {
    Date date = Date.valueOf(day);
    Map<String, Double> stored = new HashMap<>();
    jdbcTemplate.query("SELECT metric, metric_value FROM devpanel_daily WHERE snapshot_day = ?"
            + " AND instance = ?",
        rs -> {
          stored.put(rs.getString(1), rs.getDouble(2));
        }, date, instance);
    List<Object[]> deletes = new ArrayList<>();
    List<Object[]> inserts = new ArrayList<>();
    values.forEach((metric, value) -> {
      String key = metric.length() > 300 ? metric.substring(0, 300) : metric;
      double kept = PEAKS.contains(metric) && stored.containsKey(key)
          ? Math.max(stored.get(key), value) : value;
      if (stored.containsKey(key)) {
        deletes.add(new Object[] {date, key, instance});
      }
      inserts.add(new Object[] {date, key, instance, kept});
    });
    if (!deletes.isEmpty()) {
      jdbcTemplate.batchUpdate("DELETE FROM devpanel_daily WHERE snapshot_day = ? AND metric = ?"
          + " AND instance = ?", deletes);
    }
    if (!inserts.isEmpty()) {
      jdbcTemplate.batchUpdate("INSERT INTO devpanel_daily (snapshot_day, metric, instance, metric_value)"
          + " VALUES (?, ?, ?, ?)", inserts);
    }
    jdbcTemplate.update("DELETE FROM devpanel_daily WHERE snapshot_day < ?",
        Date.valueOf(day.minusDays(KEEP_DAYS)));
  }

  /** One value per day (the highest of all instances), oldest first. */
  public List<Point> series(String metric, int days) {
    return jdbcTemplate.query("SELECT snapshot_day, MAX(metric_value) FROM devpanel_daily"
            + " WHERE metric = ? AND snapshot_day >= ? GROUP BY snapshot_day ORDER BY snapshot_day",
        (rs, i) -> new Point(rs.getDate(1).toLocalDate(), rs.getDouble(2)), metric,
        Date.valueOf(LocalDate.now().minusDays(days)));
  }

  /** Every metric starting with {@code prefix}, one value per day each, oldest first. */
  public Map<String, List<Point>> seriesByPrefix(String prefix, int days) {
    Map<String, List<Point>> result = new LinkedHashMap<>();
    jdbcTemplate.query("SELECT metric, snapshot_day, MAX(metric_value) FROM devpanel_daily"
            + " WHERE metric LIKE ? AND snapshot_day >= ? GROUP BY metric, snapshot_day"
            + " ORDER BY metric, snapshot_day",
        rs -> {
          result.computeIfAbsent(rs.getString(1), key -> new ArrayList<>())
              .add(new Point(rs.getDate(2).toLocalDate(), rs.getDouble(3)));
        },
        prefix.replace("%", "\\%") + "%", Date.valueOf(LocalDate.now().minusDays(days)));
    return result;
  }

  private void peak(String metric, double value) {
    peaks.merge(metric, value, Math::max);
  }

  public record Point(LocalDate day, double value) {
  }
}
