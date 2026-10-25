package io.github.devpanel.insight.risk;

import io.github.devpanel.insight.Trend;
import io.github.devpanel.insight.database.DatabaseAdvisor;
import io.github.devpanel.insight.database.DatabaseInsights;
import io.github.devpanel.insight.history.DailySnapshots;
import io.github.devpanel.insight.history.DailySnapshots.Point;
import io.github.devpanel.insight.memory.MemoryWatch;
import io.github.devpanel.insight.requests.EndpointHistory;
import io.github.devpanel.insight.requests.EndpointHistory.Day;
import io.github.devpanel.insight.risk.Risk.Category;
import io.github.devpanel.insight.risk.Risk.Severity;
import io.github.devpanel.insight.slo.SloService;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Limits that will be reached at the current pace: endpoints that get slower day by day as the
 * data grows, answers that return more and more rows, tables growing fast while read by full
 * scans, the disk, the heap, the connection pool and request threads at their daily peak, id
 * sequences, HTTPS certificates of called services, goals (SLOs) running out of budget.
 *
 * <p>Trends need history: a straight line through at least a week of daily values that fits
 * well (R² ≥ 0.6), so a single busy day does not raise a forecast.
 */
public class ForecastDetector implements RiskDetector {

  private static final int MIN_DAYS = 7;
  private static final double MIN_FIT = 0.6;

  private final EndpointHistory endpoints;
  private final DailySnapshots daily;
  private final MemoryWatch memory;
  private final DatabaseInsights database;
  private final DatabaseAdvisor advisor;
  private final CertificateChecks certificates;
  private final SloService slos;
  private final long slowMs;

  /** {@code memory} may be null. */
  public ForecastDetector(EndpointHistory endpoints, DailySnapshots daily, MemoryWatch memory,
      DatabaseInsights database, DatabaseAdvisor advisor, CertificateChecks certificates,
      SloService slos, long slowMs) {
    this.endpoints = endpoints;
    this.daily = daily;
    this.memory = memory;
    this.database = database;
    this.advisor = advisor;
    this.certificates = certificates;
    this.slos = slos;
    this.slowMs = slowMs;
  }

  @Override
  public String name() {
    return "forecast";
  }

  @Override
  public List<Risk> detect() {
    List<Risk> risks = new ArrayList<>();
    Map<String, List<Day>> days = endpoints.daily(30);
    slowingEndpoints(days, risks);
    growingAnswers(days, risks);
    disk(risks);
    peaks("pool.peak", "database", "The connection pool", "connections",
        "Requests start waiting for a database connection: everything slows down at once,"
            + " usually at the busiest hour first.",
        "Find what holds connections long (slow queries, external calls inside transactions,"
            + " the Risks list), then raise spring.datasource.hikari.maximum-pool-size if the"
            + " database can take it.", risks);
    peaks("threads.peak", "server", "Request threads", "request threads",
        "New requests queue up and time out at the proxy (502/504) although the server is up.",
        "Make the slow endpoints faster first; raise server.tomcat.threads.max only if CPU and"
            + " the connection pool have room.", risks);
    heap(risks);
    if (advisor.available()) {
      databaseGrowth(risks);
      tables(risks);
      ids(risks);
    }
    certificates(risks);
    goals(risks);
    return risks;
  }

  private void slowingEndpoints(Map<String, List<Day>> days, List<Risk> risks) {
    days.forEach((endpoint, list) -> {
      List<Day> busy = list.stream().filter(day -> day.calls() >= 30).toList();
      if (busy.size() < MIN_DAYS) {
        return;
      }
      Trend trend = trend(busy, day -> (double) day.p95Ms());
      double first = busy.get(0).p95Ms();
      double last = busy.get(busy.size() - 1).p95Ms();
      double perDay = trend.slopePerHour() * 24;
      if (trend.r2() < MIN_FIT || perDay <= 0 || last < first * 1.3 || last - first < 50) {
        return;
      }
      double daysLeft = last >= slowMs ? 0 : (slowMs - last) / perDay;
      Severity severity = last >= slowMs ? Severity.WARNING
          : daysLeft <= 30 ? Severity.WARNING : Severity.INFO;
      risks.add(new Risk("forecast.slowing:" + endpoint, Category.FORECAST, "api", severity,
          endpoint + " gets slower every day",
          last >= slowMs ? "Already slower than " + slowMs + " ms at p95, and still growing."
              : "At this pace its p95 passes " + slowMs + " ms in about " + Risk.days(daysLeft)
                  + ". This is what growing data does to a query without the right index or"
                  + " paging.",
          "p95 went from " + Math.round(first) + " ms to " + Math.round(last) + " ms over "
              + busy.size() + " days (+" + Math.round(perDay) + " ms/day, fit "
              + Math.round(trend.r2() * 100) + "%).",
          "Open a slow request of this endpoint in the API tab: the waterfall shows which SQL"
              + " grows; the Explain button shows whether it reads whole tables.",
          endpoint, null, last >= slowMs ? null : daysLeft, "api",
          weight(busy.get(busy.size() - 1).calls())));
    });
  }

  private void growingAnswers(Map<String, List<Day>> days, List<Risk> risks) {
    days.forEach((endpoint, list) -> {
      if (list.isEmpty()) {
        return;
      }
      long maxRows = list.stream().mapToLong(Day::maxRows).max().orElse(0);
      long maxBytes = list.stream().mapToLong(Day::avgBytes).max().orElse(0);
      boolean big = maxRows >= 5_000 || maxBytes >= 2_000_000;
      Trend trend = list.size() >= MIN_DAYS ? trend(list, day -> (double) day.maxRows())
          : Trend.NONE;
      boolean growing = trend.r2() >= MIN_FIT && trend.slopePerHour() > 0 && maxRows >= 1_000;
      if (!big && !growing) {
        return;
      }
      double perDay = trend.slopePerHour() * 24;
      risks.add(new Risk("forecast.rows:" + endpoint, Category.FORECAST, "api",
          maxRows >= 20_000 ? Severity.WARNING : Severity.INFO,
          endpoint + " returns more and more rows",
          "One call reads " + maxRows + " rows" + (growing ? " and the number keeps growing" : "")
              + ": time, memory and response size grow with the data until it times out.",
          "Largest call: " + maxRows + " rows read" + (growing ? ", +" + Math.round(perDay)
              + " rows/day" : "") + "; average answer " + (maxBytes / 1024) + " KB.",
          "Add paging (Pageable, LIMIT) or a narrower filter; return only the fields the screen"
              + " shows.", endpoint, null, null, "api", 1));
    });
  }

  private void disk(List<Risk> risks) {
    List<Point> points = daily.series("disk.used", 60);
    if (points.size() < MIN_DAYS) {
      return;
    }
    Trend trend = trendOf(points);
    double used = points.get(points.size() - 1).value();
    double hours = trend.r2() >= MIN_FIT ? trend.hoursUntil(0.95) : -1;
    if (hours < 0 || hours / 24 > 180) {
      return;
    }
    double daysLeft = hours / 24;
    risks.add(new Risk("forecast.disk", Category.FORECAST, "disk", Risk.bySoonness(daysLeft),
        "The disk will be full in about " + Risk.days(daysLeft),
        "A full disk stops logging, uploads and often the database: the application fails"
            + " everywhere at once.",
        "The fullest disk is " + Math.round(used * 100) + "% used and grows "
            + String.format("%.2f", trend.slopePerHour() * 24 * 100) + " points per day.",
        "Find what grows (logs without rotation, uploads, database); clean up or ask for more"
            + " space now, while there is time.", null, null, daysLeft, "metrics", 2));
  }

  private void peaks(String metric, String area, String what, String unit, String impact,
      String fix, List<Risk> risks) {
    List<Point> points = daily.series(metric, 60);
    if (points.isEmpty()) {
      return;
    }
    double latest = points.get(points.size() - 1).value();
    double highest = points.stream().mapToDouble(Point::value).max().orElse(0);
    Trend trend = points.size() >= MIN_DAYS ? trendOf(points) : Trend.NONE;
    double hours = trend.r2() >= MIN_FIT ? trend.hoursUntil(1.0) : -1;
    boolean near = highest >= 0.9;
    boolean rising = hours >= 0 && hours / 24 <= 90;
    if (!near && !rising) {
      return;
    }
    Double daysLeft = rising ? hours / 24 : null;
    risks.add(new Risk("forecast." + metric, Category.FORECAST, area,
        near && highest >= 1 ? Severity.WARNING
            : daysLeft != null ? Risk.bySoonness(daysLeft) : Severity.WARNING,
        what + (near ? " reaches its limit at the busiest moment"
            : " fills up a little more every day"),
        impact,
        "Busiest moment: " + Math.round(latest * 100) + "% of the " + unit + " in use today, "
            + Math.round(highest * 100) + "% at most in " + points.size() + " days"
            + (rising ? "; at this pace 100% in about " + Risk.days(daysLeft) : "") + ".",
        fix, null, null, daysLeft, "metrics", 2));
  }

  private void heap(List<Risk> risks) {
    if (memory == null) {
      return;
    }
    MemoryWatch.Analysis analysis = memory.analysis();
    if (analysis.leakSuspected() && analysis.hoursToFull() >= 0) {
      double daysLeft = analysis.hoursToFull() / 24;
      risks.add(new Risk("forecast.heap", Category.FORECAST, "memory",
          daysLeft < 0.5 ? Severity.CRITICAL : Severity.WARNING,
          "Memory keeps growing: OutOfMemoryError in about " + Risk.days(daysLeft),
          "When the heap is full the application stalls in garbage collection and then fails;"
              + " a restart only postpones it.",
          "Memory left after garbage collection grows by "
              + (Math.round(analysis.floorGrowthPerHour() / 1024 / 1024)) + " MB per hour.",
          "Take a heap histogram on the JVM tab twice an hour apart: the class whose count keeps"
              + " growing is the leak (often a cache or a static list without limit).",
          null, null, daysLeft, "jvm", 2));
      return;
    }
    List<Point> floor = daily.series("heap.floor", 60);
    List<Point> max = daily.series("heap.max", 60);
    if (floor.size() < MIN_DAYS || max.isEmpty()) {
      return;
    }
    Trend trend = trendOf(floor);
    double limit = max.get(max.size() - 1).value() * 0.95;
    double hours = trend.r2() >= MIN_FIT ? trend.hoursUntil(limit) : -1;
    if (hours >= 0 && hours / 24 <= 90) {
      double daysLeft = hours / 24;
      risks.add(new Risk("forecast.heap-days", Category.FORECAST, "memory",
          Risk.bySoonness(daysLeft), "Memory needed day by day grows towards the heap limit",
          "Even right after garbage collection the application holds a bit more every day; at"
              + " this pace the heap is too small in about " + Risk.days(daysLeft) + ".",
          "Heap after GC: " + mb(floor.get(0).value()) + " → "
              + mb(floor.get(floor.size() - 1).value()) + " over " + floor.size() + " days, limit "
              + mb(max.get(max.size() - 1).value()) + ".",
          "Check caches and in-memory collections that grow with data; raise -Xmx if the growth"
              + " is expected.", null, null, daysLeft, "jvm", 1.5));
    }
  }

  private void databaseGrowth(List<Risk> risks) {
    List<Point> size = daily.series("db.size", 90);
    if (size.size() < 14) {
      return;
    }
    Trend trend = trendOf(size);
    double perDay = trend.slopePerHour() * 24;
    double now = size.get(size.size() - 1).value();
    if (trend.r2() >= MIN_FIT && perDay > now * 0.01) {
      risks.add(new Risk("forecast.db-size", Category.FORECAST, "database", Severity.INFO,
          "The database grows " + Math.round(perDay * 30 / now * 100) + "% a month",
          "Backups, restores and full scans take longer with every month; plan disk and"
              + " archiving before it becomes urgent.",
          "Size " + mb(now) + ", +" + mb(perDay) + " per day over " + size.size() + " days.",
          "See which tables grow fastest (Database → Tables); archive or delete old rows of"
              + " logs and history tables.", null, null, null, "database", 1));
    }
  }

  private void tables(List<Risk> risks) {
    Map<String, List<Point>> rows = daily.seriesByPrefix("table.rows:", 60);
    Map<String, DatabaseInsights.TableStat> stats = new HashMap<>();
    for (DatabaseInsights.TableStat table : database.tables()) {
      stats.put(table.schema() + "." + table.table(), table);
    }
    rows.forEach((metric, points) -> {
      String table = metric.substring("table.rows:".length());
      DatabaseInsights.TableStat stat = stats.get(table);
      if (points.size() < MIN_DAYS || stat == null) {
        return;
      }
      Trend trend = trendOf(points);
      double perDay = trend.slopePerHour() * 24;
      double now = points.get(points.size() - 1).value();
      boolean scanned = stat.seqScans() > stat.indexScans() && stat.seqScans() >= 50;
      boolean fast = now >= 1_000 && perDay >= now * 0.03;
      if (trend.r2() < MIN_FIT || perDay <= 0 || !(fast || scanned && now >= 5_000)) {
        return;
      }
      risks.add(new Risk("forecast.table:" + table, Category.FORECAST, "database",
          scanned ? Severity.WARNING : Severity.INFO,
          table + " grows " + Math.round(perDay) + " rows a day" + (scanned
              ? " and is read by full scans" : ""),
          scanned ? "Every full scan reads the whole table: it gets slower by the day, and"
              + " with it every query that reads it."
              : "Fast growth for a table of this size: check it is intended (not a log without"
                  + " cleanup or rows written again and again).",
          Math.round(now) + " rows now, +" + Math.round(perDay) + "/day; in 30 days about "
              + Math.round(now + perDay * 30) + ". Full scans " + stat.seqScans()
              + ", index scans " + stat.indexScans() + ".",
          scanned ? "See the index suggestions for this table (Risks or Database → Advisor)."
              : "If old rows are not needed, delete or archive them regularly.",
          table, null, null, "database", scanned ? 1.5 : 1));
    });
  }

  private void ids(List<Risk> risks) {
    for (DatabaseAdvisor.IdCapacity id : advisor.idCapacity()) {
      if (id.used() < 0.5) {
        continue;
      }
      String where = id.table() != null ? id.table() + "." + id.column() : id.sequence();
      risks.add(new Risk("forecast.ids:" + id.sequence(), Category.FORECAST, "database",
          id.used() >= 0.8 ? Severity.CRITICAL : Severity.WARNING,
          "Ids of " + where + " are " + Math.round(id.used() * 100) + "% used up",
          "When the sequence or the column reaches its largest value, every insert into the"
              + " table fails with an error.",
          "Last value " + id.lastValue() + " of at most " + id.limit()
              + (id.columnType() != null ? " (column type " + id.columnType() + ")" : "") + ".",
          "integer".equals(id.columnType())
              ? "Change the column to bigint (ALTER TABLE ... ALTER COLUMN ... TYPE bigint) in a"
                  + " maintenance window."
              : "Change the sequence and column to bigint.", where, null, null, "database",
          2));
    }
  }

  private void certificates(List<Risk> risks) {
    for (CertificateChecks.Result result : certificates.check()) {
      if (result.expires() == null) {
        if (result.error() != null && result.error().toLowerCase().contains("certificate")) {
          risks.add(new Risk("forecast.cert:" + result.host(), Category.FORECAST, "external",
              Severity.CRITICAL, "The certificate of " + result.host() + " is not accepted",
              "Calls to this service fail the HTTPS handshake.", result.error(),
              "Ask the service's owners to renew the certificate, or add their CA to the"
                  + " truststore.", result.host(), null, 0.0, "integrations", 2));
        }
        continue;
      }
      if (result.daysLeft() > 30) {
        continue;
      }
      risks.add(new Risk("forecast.cert:" + result.host(), Category.FORECAST, "external",
          result.daysLeft() <= 7 ? Severity.CRITICAL : Severity.WARNING,
          result.daysLeft() <= 0 ? "The certificate of " + result.host() + " has expired"
              : "The certificate of " + result.host() + " expires in "
                  + Risk.days(result.daysLeft()),
          "After it expires every call to this service fails, until its owners renew it.",
          "Expires " + result.expires() + "; issued by " + result.issuer() + ".",
          "Tell the owners of " + result.host() + " now; renewing takes them minutes, finding"
              + " out after it expired takes everyone hours.", result.host(), null,
          Math.max(0, result.daysLeft()), "integrations", 2));
    }
  }

  private void goals(List<Risk> risks) {
    for (SloService.Status status : slos.statuses()) {
      SloService.Goal goal = status.goal();
      String target = Math.round(goal.objective() * 1000) / 10.0 + "% within " + goal.targetMs()
          + " ms";
      if (!status.met()) {
        risks.add(new Risk("forecast.slo:" + goal.endpoint(), Category.FORECAST, "api",
            Severity.WARNING, "Goal missed: " + goal.endpoint(),
            "The endpoint is slower than agreed (" + target + ") over the last 30 days.",
            Math.round(status.monthShare() * 1000) / 10.0 + "% of " + status.monthCalls()
                + " calls were within " + goal.targetMs() + " ms.",
            "Look at its slowest requests in the API tab.", goal.endpoint(), null, 0.0, "slo",
            1.5));
      } else if (status.budgetDaysLeft() >= 0 && status.budgetDaysLeft() <= 30) {
        risks.add(new Risk("forecast.slo:" + goal.endpoint(), Category.FORECAST, "api",
            Risk.bySoonness(status.budgetDaysLeft()),
            "Goal of " + goal.endpoint() + " will be missed in about "
                + Risk.days(status.budgetDaysLeft()),
            "The last 7 days were slower than the goal (" + target + ") allows; at this pace the"
                + " month's allowance of slow calls runs out.",
            "Budget left " + Math.round(status.budgetLeft() * 100) + "%, spent "
                + String.format("%.1f", status.burnRate()) + "× faster than allowed.",
            "Look at what changed in the last week (deploys, data growth) in the API tab.",
            goal.endpoint(), null, status.budgetDaysLeft(), "slo", 1.5));
      }
    }
  }

  private static Trend trend(List<Day> days, java.util.function.ToDoubleFunction<Day> value) {
    List<Long> times = new ArrayList<>();
    List<Double> values = new ArrayList<>();
    for (Day day : days) {
      times.add(day.day().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli());
      values.add(value.applyAsDouble(day));
    }
    return Trend.of(times, values);
  }

  private static Trend trendOf(List<Point> points) {
    List<Long> times = new ArrayList<>();
    List<Double> values = new ArrayList<>();
    for (Point point : points) {
      times.add(point.day().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli());
      values.add(point.value());
    }
    return Trend.of(times, values);
  }

  /** Busier endpoints matter more: 1 at ~1,000 calls a day, up to 3. */
  static double weight(long callsPerDay) {
    return Math.max(0.5, Math.min(3, Math.log10(Math.max(1, callsPerDay)) / 3));
  }

  private static String mb(double bytes) {
    return Math.round(bytes / 1024 / 1024) + " MB";
  }
}
