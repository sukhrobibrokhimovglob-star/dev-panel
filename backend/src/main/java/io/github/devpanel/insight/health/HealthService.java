package io.github.devpanel.insight.health;

import io.github.devpanel.insight.Trend;
import io.github.devpanel.insight.database.DatabaseInsights;
import io.github.devpanel.insight.health.Check.Status;
import io.github.devpanel.insight.jobs.JobStats;
import io.github.devpanel.insight.logs.ExceptionGroups;
import io.github.devpanel.insight.logs.ExceptionGroups.GroupView;
import io.github.devpanel.insight.logs.LogEvents;
import io.github.devpanel.insight.memory.MemoryWatch;
import io.github.devpanel.insight.outbound.OutboundStats;
import io.github.devpanel.insight.requests.EndpointHistory;
import io.github.devpanel.insight.requests.RequestStats;
import io.github.devpanel.insight.requests.RequestStats.EndpointView;
import io.github.devpanel.metrics.DevMetricsHistory;
import io.github.devpanel.metrics.DevMetricsHistory.Point;
import io.github.devpanel.metrics.DevMetricsService;
import io.github.devpanel.metrics.DevMetricsService.Disk;
import io.github.devpanel.metrics.HikariPools.PoolState;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The health page: every area checked and summed up in one sentence each. Thresholds are
 * relative to the application's own normal wherever there is history (the same hour on previous
 * days, the week before the deploy), so a busy hour does not raise alarms and a quiet failure
 * still does. Each check is independent; one that fails to compute shows as UNKNOWN.
 */
public class HealthService {

  private static final Logger log = LoggerFactory.getLogger(HealthService.class);
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm")
      .withZone(ZoneId.systemDefault());

  private final DevMetricsService metrics;
  private final DevMetricsHistory history;
  private final MemoryWatch memory;
  private final LogEvents logs;
  private final ExceptionGroups exceptions;
  private final RequestStats requests;
  private final EndpointHistory endpointHistory;
  private final DatabaseInsights database;
  private final OutboundStats outbound;
  private final JobStats jobs;
  private final ClockCheck clock;
  private final Instant started = Instant.now();

  /** Any of the optional insights may be null when the application lacks the library. */
  public HealthService(DevMetricsService metrics, DevMetricsHistory history, MemoryWatch memory,
      LogEvents logs, ExceptionGroups exceptions, RequestStats requests,
      EndpointHistory endpointHistory, DatabaseInsights database, OutboundStats outbound,
      JobStats jobs, ClockCheck clock) {
    this.metrics = metrics;
    this.history = history;
    this.memory = memory;
    this.logs = logs;
    this.exceptions = exceptions;
    this.requests = requests;
    this.endpointHistory = endpointHistory;
    this.database = database;
    this.outbound = outbound;
    this.jobs = jobs;
    this.clock = clock;
  }

  public Report report() {
    List<Check> checks = new ArrayList<>();
    run(checks, "server", this::cpu);
    run(checks, "memory", this::memory);
    run(checks, "threads", this::threads);
    run(checks, "disk", this::disk);
    run(checks, "database", this::pool);
    run(checks, "database", this::databaseServer);
    run(checks, "api", this::errorRate);
    run(checks, "api", this::latency);
    run(checks, "api", this::nPlusOne);
    run(checks, "errors", this::newExceptions);
    run(checks, "external", this::external);
    run(checks, "jobs", this::jobs);
    run(checks, "clock", this::clock);
    Status overall = Status.OK;
    for (Check check : checks) {
      if (check.status().worseThan(overall)) {
        overall = check.status();
      }
    }
    return new Report(overall, checks, Instant.now(), started);
  }

  private void run(List<Check> checks, String area, Function<Long, List<Check>> check) {
    try {
      checks.addAll(check.apply(System.currentTimeMillis()));
    } catch (RuntimeException e) {
      log.debug("Dev panel health check {} failed", area, e);
      checks.add(new Check(area + ".unknown", area, Status.UNKNOWN, "Could not be checked",
          e.getClass().getSimpleName() + ": " + e.getMessage(), null));
    }
  }

  private List<Check> cpu(long now) {
    List<Point> last = history.raw(Duration.ofMinutes(5));
    double process = average(last, Point::cpuProcess);
    double system = average(last, Point::cpuSystem);
    String numbers = "Process " + percent(process) + ", whole server " + percent(system)
        + " over the last 5 min";
    if (process >= 0.9 || system >= 0.95) {
      return List.of(new Check("cpu", "server", Status.CRITICAL, "CPU is saturated", numbers,
          "metrics"));
    }
    if (process >= 0.75 || system >= 0.85) {
      return List.of(new Check("cpu", "server", Status.WARNING, "CPU is high", numbers,
          "metrics"));
    }
    return List.of(new Check("cpu", "server", Status.OK, "CPU is fine", numbers, "metrics"));
  }

  private List<Check> memory(long now) {
    if (memory == null) {
      return List.of();
    }
    List<Check> checks = new ArrayList<>();
    MemoryWatch.Analysis analysis = memory.analysis();
    long floor = analysis.floor().isEmpty() ? 0
        : analysis.floor().get(analysis.floor().size() - 1).bytes();
    if (analysis.leakSuspected()) {
      String eta = analysis.hoursToFull() >= 0
          ? "; at this pace the heap is full in about " + hours(analysis.hoursToFull()) : "";
      checks.add(new Check("memory.leak", "memory",
          analysis.hoursToFull() >= 0 && analysis.hoursToFull() < 12
              ? Status.CRITICAL : Status.WARNING,
          "Possible memory leak",
          "Memory left after garbage collection keeps rising: +"
              + mb((long) analysis.floorGrowthPerHour()) + "/h, now " + mb(floor) + " of "
              + mb(analysis.heapMax()) + eta + ". Take a heap histogram on the JVM tab.",
          "jvm"));
    } else if (analysis.floor().size() < 6) {
      checks.add(new Check("memory.leak", "memory", Status.OK, "No memory leak seen",
          "Needs about an hour of garbage collections to judge the trend", "jvm"));
    } else {
      checks.add(new Check("memory.leak", "memory", Status.OK, "No memory leak seen",
          "Memory left after garbage collection is stable at about " + mb(floor), "jvm"));
    }
    if (analysis.heapMax() > 0 && floor > analysis.heapMax() * 0.9) {
      checks.add(new Check("memory.full", "memory", Status.CRITICAL, "Heap almost full",
          "Even right after garbage collection " + mb(floor) + " of " + mb(analysis.heapMax())
              + " stays in use; OutOfMemoryError is near", "jvm"));
    }
    if (analysis.gcShare() >= 0.1) {
      checks.add(new Check("memory.gc", "memory",
          analysis.gcShare() >= 0.2 ? Status.CRITICAL : Status.WARNING,
          "Too much time in garbage collection",
          percent(analysis.gcShare()) + " of the last 5 min went to GC pauses (longest "
              + analysis.maxPauseMs() + " ms); usually the heap is too small or filling up",
          "jvm"));
    }
    return checks;
  }

  private List<Check> threads(long now) {
    List<Check> checks = new ArrayList<>();
    DevMetricsService.Snapshot snapshot = metrics.snapshot();
    if (snapshot.threads().deadlocked() > 0) {
      checks.add(new Check("threads.deadlock", "threads", Status.CRITICAL, "Deadlocked threads",
          snapshot.threads().deadlocked() + " threads wait for each other forever; see the thread"
              + " dump on the JVM tab", "jvm"));
    }
    List<Point> sixHours = history.raw(Duration.ofHours(6));
    Check threadTrend = rising(sixHours, point -> point.threads(), "threads.leak", "threads",
        "Thread count keeps rising", "threads", 0.5);
    if (threadTrend != null) {
      checks.add(threadTrend);
    }
    Check classTrend = rising(sixHours, point -> point.loadedClasses(), "threads.classes",
        "threads", "Loaded classes keep rising (class loader leak?)", "classes", 0.2);
    if (classTrend != null) {
      checks.add(classTrend);
    }
    long maxFiles = DevMetricsService.os() instanceof com.sun.management.UnixOperatingSystemMXBean
        unix ? unix.getMaxFileDescriptorCount() : -1;
    if (maxFiles > 0 && !sixHours.isEmpty()) {
      long open = sixHours.get(sixHours.size() - 1).openFiles();
      Trend trend = trend(sixHours, point -> point.openFiles());
      double hoursLeft = trend.r2() > 0.8 ? trend.hoursUntil(maxFiles) : -1;
      if (open > maxFiles * 0.8 || (hoursLeft >= 0 && hoursLeft < 24)) {
        checks.add(new Check("threads.files", "threads",
            open > maxFiles * 0.9 ? Status.CRITICAL : Status.WARNING, "Running out of open files",
            open + " of " + maxFiles + " file handles in use"
                + (hoursLeft >= 0 ? ", limit reached in about " + hours(hoursLeft) : "")
                + " (unclosed files, sockets or connections?)", "metrics"));
      }
    }
    if (checks.isEmpty()) {
      checks.add(new Check("threads", "threads", Status.OK, "Threads look fine",
          snapshot.threads().live() + " threads, none deadlocked", "jvm"));
    }
    return checks;
  }

  private List<Check> disk(long now) {
    List<Check> checks = new ArrayList<>();
    for (Disk disk : metrics.snapshot().disks()) {
      if (disk.totalSpace() <= 0) {
        continue;
      }
      double free = (double) disk.usableSpace() / disk.totalSpace();
      if (free < 0.10) {
        checks.add(new Check("disk.free." + disk.path(), "disk",
            free < 0.05 ? Status.CRITICAL : Status.WARNING, "Disk " + disk.path() + " almost full",
            "Only " + gb(disk.usableSpace()) + " (" + percent(free) + ") free", "metrics"));
      }
    }
    List<Point> day = history.raw(Duration.ofHours(24));
    Trend trend = trend(day, Point::diskUsedRatio);
    double hoursLeft = trend.r2() > 0.8 && day.size() >= 60 ? trend.hoursUntil(0.98) : -1;
    if (hoursLeft >= 0 && hoursLeft < 72) {
      checks.add(new Check("disk.forecast", "disk",
          hoursLeft < 24 ? Status.CRITICAL : Status.WARNING, "Disk will be full soon",
          "At the current pace the fullest disk is full in about " + hours(hoursLeft)
              + " (growing " + percent(trend.slopePerHour() * 24) + " of its size per day)",
          "metrics"));
    }
    if (checks.isEmpty()) {
      checks.add(new Check("disk", "disk", Status.OK, "Enough disk space", null, "metrics"));
    }
    return checks;
  }

  private List<Check> pool(long now) {
    List<Check> checks = new ArrayList<>();
    for (PoolState pool : metrics.hikari()) {
      List<Point> last = history.raw(Duration.ofSeconds(75));
      boolean waitingForAMinute = last.size() >= 4
          && last.stream().allMatch(point -> point.dbPending() > 0);
      if (pool.pending() > 0 || waitingForAMinute) {
        checks.add(new Check("database.pool", "database",
            waitingForAMinute ? Status.CRITICAL : Status.WARNING,
            "Requests wait for a database connection",
            pool.pending() + " waiting, " + pool.active() + " of " + pool.max() + " connections"
                + " busy in " + pool.pool() + ". Look for slow queries or connections that are"
                + " not returned (Database tab, Leaks).", "database"));
      } else if (pool.active() >= pool.max()) {
        checks.add(new Check("database.pool", "database", Status.WARNING, "Pool fully used",
            "All " + pool.max() + " connections of " + pool.pool() + " are busy", "database"));
      }
    }
    if (logs != null) {
      long recent = logs.leaks().stream()
          .filter(leak -> leak.time().isAfter(Instant.ofEpochMilli(now).minus(Duration.ofHours(1))))
          .count();
      if (recent > 0) {
        checks.add(new Check("database.leak", "database",
            recent > 5 ? Status.CRITICAL : Status.WARNING, "Connections kept too long",
            recent + " times in the last hour a connection was held longer than the leak"
                + " threshold; the Database tab shows the code that took it", "database"));
      }
    }
    if (checks.isEmpty() && !metrics.hikari().isEmpty()) {
      PoolState pool = metrics.hikari().get(0);
      checks.add(new Check("database.pool", "database", Status.OK, "Connection pool is fine",
          pool.active() + " of " + pool.max() + " connections busy, nobody waiting", "database"));
    }
    return checks;
  }

  private List<Check> databaseServer(long now) {
    if (database == null || !database.isPostgres()) {
      return List.of();
    }
    List<Check> checks = new ArrayList<>();
    DatabaseInsights.Overview overview = database.overview();
    double used = (double) overview.connections() / Math.max(1, overview.maxConnections());
    if (used >= 0.75) {
      checks.add(new Check("database.connections", "database",
          used >= 0.9 ? Status.CRITICAL : Status.WARNING, "PostgreSQL connections nearly used up",
          overview.connections() + " of max_connections " + overview.maxConnections()
              + " in use (all applications together)", "database"));
    }
    long stuck = database.activity().stream()
        .filter(activity -> activity.state() != null
            && activity.state().startsWith("idle in transaction")
            && activity.transactionMs() > 5 * 60_000)
        .count();
    if (stuck > 0) {
      checks.add(new Check("database.idle-transaction", "database", Status.WARNING,
          "Transactions left open",
          stuck + " connections have sat in an open transaction for over 5 min; they hold locks"
              + " and block vacuum", "database"));
    }
    if (overview.blocked() > 0) {
      checks.add(new Check("database.blocked", "database", Status.WARNING, "Queries are blocked",
          overview.blocked() + " queries wait for locks held by others", "database"));
    }
    if (checks.isEmpty()) {
      checks.add(new Check("database.server", "database", Status.OK, "PostgreSQL is fine",
          overview.connections() + " of " + overview.maxConnections() + " connections, cache hit "
              + percent(overview.cacheHitRatio()), "database"));
    }
    return checks;
  }

  private List<Check> errorRate(long now) {
    long recent = requests.serverErrorsInLast(now, 10);
    long calls = requests.requestsInLast(now, 10);
    double usual = endpointHistory.usualServerErrorsPerHour();
    if (usual < 0) {
      // No history yet: compare with the average of the last 24 h in memory
      usual = requests.serverErrorsInLast(now, 24 * 60) / 24.0;
    }
    double usualTenMinutes = usual / 6;
    String numbers = recent + " server errors (5xx) in the last 10 min out of " + calls
        + " requests; usual is about " + Math.round(usualTenMinutes);
    if (recent >= 10 && recent >= Math.max(3, usualTenMinutes * 3)) {
      return List.of(new Check("api.errors", "api",
          recent >= usualTenMinutes * 10 ? Status.CRITICAL : Status.WARNING,
          "Error rate is unusually high", numbers, "errors"));
    }
    return List.of(new Check("api.errors", "api", Status.OK, "Error rate is normal", numbers,
        "api"));
  }

  private List<Check> latency(long now) {
    Map<String, long[]> yesterday = endpointHistory.sameHourYesterday();
    Map<String, Long> beforeDeploy = Duration.between(started, Instant.now()).toHours() < 24
        ? endpointHistory.p95BeforeDeploy(started) : Map.of();
    List<String> slower = new ArrayList<>();
    for (EndpointView view : requests.endpoints(now, 1)) {
      if (view.calls() < 20) {
        continue;
      }
      long[] before = yesterday.get(view.endpoint());
      Long deployBaseline = beforeDeploy.get(view.endpoint());
      long baseline = deployBaseline != null ? deployBaseline
          : before != null && before[1] >= 20 ? before[0] : -1;
      if (baseline > 0 && view.p95Ms() >= baseline * 2 && view.p95Ms() - baseline >= 300) {
        slower.add(view.endpoint() + ": p95 " + baseline + " → " + view.p95Ms() + " ms"
            + (deployBaseline != null ? " (since this deploy)" : " (vs yesterday)"));
      }
    }
    if (!slower.isEmpty()) {
      return List.of(new Check("api.latency", "api", Status.WARNING,
          slower.size() + " endpoint(s) got much slower",
          String.join("; ", slower.subList(0, Math.min(3, slower.size()))), "api"));
    }
    return List.of(new Check("api.latency", "api", Status.OK, "Response times are normal", null,
        "api"));
  }

  private List<Check> nPlusOne(long now) {
    Instant hourAgo = Instant.ofEpochMilli(now).minus(Duration.ofHours(1));
    return requests.nPlusOneSuspects().stream()
        .filter(suspect -> suspect.time().isAfter(hourAgo))
        .max((a, b) -> Integer.compare(a.repeats(), b.repeats()))
        .map(worst -> List.of(new Check("api.n-plus-one", "api", Status.WARNING,
            "Repeated queries in one request (N+1)",
            worst.endpoint() + " ran the same query " + worst.repeats() + " times in one request ("
                + worst.sqlTotal() + " queries in total)", "api")))
        .orElse(List.of());
  }

  private List<Check> newExceptions(long now) {
    List<GroupView> fresh = exceptions.list().stream().filter(GroupView::isNew).toList();
    if (fresh.isEmpty()) {
      return List.of(new Check("errors.new", "errors", Status.OK,
          "No new kinds of errors since the deploy", null, "errors"));
    }
    GroupView first = fresh.get(0);
    return List.of(new Check("errors.new." + first.fingerprint(), "errors", Status.WARNING,
        fresh.size() + " new kind(s) of error since the deploy",
        simpleName(first.type()) + " at " + first.location() + " (" + first.count() + "×)",
        "errors"));
  }

  private List<Check> external(long now) {
    if (outbound == null) {
      return List.of();
    }
    List<Check> checks = new ArrayList<>();
    for (OutboundStats.TargetView target : outbound.list()) {
      if (target.down()) {
        checks.add(new Check("external.down." + target.host(), "external", Status.CRITICAL,
            target.host() + " is failing",
            "Every call since " + TIME.format(target.downSince()) + " failed: "
                + target.lastErrorMessage(), "integrations"));
      } else if (target.calls() >= 20 && target.errors() > target.calls() * 0.2) {
        checks.add(new Check("external.errors." + target.host(), "external", Status.WARNING,
            target.host() + " often fails",
            target.errors() + " of " + target.calls() + " calls failed", "integrations"));
      }
    }
    return checks;
  }

  private List<Check> jobs(long now) {
    if (jobs == null) {
      return List.of();
    }
    List<Check> checks = new ArrayList<>();
    for (JobStats.JobView job : jobs.list()) {
      if (job.missed()) {
        checks.add(new Check("jobs.missed." + job.name(), "jobs", Status.WARNING,
            "Job " + job.name() + " did not run",
            "It was due at " + TIME.format(job.nextExpected()) + " (" + job.schedule() + ")",
            "jobs"));
      } else if (job.lastError() != null) {
        checks.add(new Check("jobs.failed." + job.name(), "jobs", Status.WARNING,
            "Job " + job.name() + " failed", job.lastError(), "jobs"));
      }
    }
    return checks;
  }

  private List<Check> clock(long now) {
    Long offset = clock.offsetMs();
    if (offset == null) {
      return List.of();
    }
    long seconds = Math.abs(offset) / 1000;
    String text = "The server's clock is " + (seconds >= 60 ? (seconds / 60) + " min " : "")
        + (seconds % 60) + " s " + (offset > 0 ? "ahead" : "behind")
        + ". Turn on time sync: sudo timedatectl set-ntp true";
    if (seconds >= 60) {
      return List.of(new Check("clock", "clock", Status.CRITICAL, "Server clock is wrong", text
          + " (2FA codes, token expiry and log times depend on it)", "health"));
    }
    if (seconds >= 5) {
      return List.of(new Check("clock", "clock", Status.WARNING, "Server clock drifts", text,
          "health"));
    }
    return List.of(new Check("clock", "clock", Status.OK, "Server clock is correct",
        "Off by less than 5 s", "health"));
  }

  /** A clear, steady rise over 6 hours by more than {@code minGrowth} of the starting value. */
  private static Check rising(List<Point> points, ToDoubleFunction<Point> value, String key,
      String area, String title, String what, double minGrowth) {
    if (points.size() < 60) {
      return null;
    }
    Trend trend = trend(points, value);
    double first = value.applyAsDouble(points.get(0));
    double last = value.applyAsDouble(points.get(points.size() - 1));
    if (trend.r2() >= 0.8 && first > 0 && last - first > first * minGrowth) {
      return new Check(key, area, Status.WARNING, title,
          what + " went from " + Math.round(first) + " to " + Math.round(last)
              + " in the last hours and keeps growing", "jvm");
    }
    return null;
  }

  private static Trend trend(List<Point> points, ToDoubleFunction<Point> value) {
    List<Long> times = new ArrayList<>();
    List<Double> values = new ArrayList<>();
    for (Point point : points) {
      double v = value.applyAsDouble(point);
      if (v >= 0) {
        times.add(point.time().toEpochMilli());
        values.add(v);
      }
    }
    return Trend.of(times, values);
  }

  private static double average(List<Point> points, ToDoubleFunction<Point> value) {
    return points.stream().mapToDouble(value).filter(v -> v >= 0).average().orElse(0);
  }

  private static String percent(double ratio) {
    return Math.round(ratio * 1000) / 10.0 + "%";
  }

  private static String mb(long bytes) {
    return (bytes / (1024 * 1024)) + " MB";
  }

  private static String gb(long bytes) {
    return Math.round(bytes / (1024.0 * 1024 * 1024) * 10) / 10.0 + " GB";
  }

  private static String hours(double hours) {
    return hours >= 48 ? Math.round(hours / 24) + " days" : Math.max(1, Math.round(hours)) + " h";
  }

  private static String simpleName(String type) {
    return type.substring(type.lastIndexOf('.') + 1);
  }

  public record Report(Status overall, List<Check> checks, Instant serverTime, Instant started) {
  }
}
