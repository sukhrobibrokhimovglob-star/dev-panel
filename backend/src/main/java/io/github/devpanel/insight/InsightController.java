package io.github.devpanel.insight;

import io.github.devpanel.account.DevAccount;
import io.github.devpanel.account.DevRole;
import io.github.devpanel.insight.alerts.AlertEngine;
import io.github.devpanel.insight.database.DatabaseInsights;
import io.github.devpanel.insight.deploy.DeployReport;
import io.github.devpanel.insight.deps.DependencyChecks;
import io.github.devpanel.insight.security.SecurityStats;
import io.github.devpanel.insight.server.TomcatCapacity;
import io.github.devpanel.insight.trace.TraceStore;
import io.github.devpanel.insight.usage.EndpointCatalog;
import io.github.devpanel.insight.usage.UsageStats;
import io.github.devpanel.insight.health.ClockCheck;
import io.github.devpanel.insight.health.HealthService;
import io.github.devpanel.insight.jobs.JobStats;
import io.github.devpanel.insight.logs.ExceptionGroups;
import io.github.devpanel.insight.logs.LiveLogs;
import io.github.devpanel.insight.logs.LogEvents;
import io.github.devpanel.insight.memory.HeapHistogram;
import io.github.devpanel.insight.memory.MemoryWatch;
import io.github.devpanel.insight.ops.ConfigViewer;
import io.github.devpanel.insight.ops.DeployInfo;
import io.github.devpanel.insight.ops.LogLevels;
import io.github.devpanel.insight.outbound.OutboundStats;
import io.github.devpanel.insight.requests.EndpointHistory;
import io.github.devpanel.insight.requests.RequestStats;
import io.github.devpanel.insight.requests.RequestStats.EndpointView;
import io.github.devpanel.support.DevPanelException;
import io.github.devpanel.web.DevPanelApi;
import io.github.devpanel.web.DevPanelPaths;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Everything the monitoring tabs show. Reading is open to every account; what can disturb the
 * application (killing a query, a heap histogram that pauses it, changing log levels, sending a
 * test alert) needs a read-write account (the auth filter) and, where noted, an admin.
 */
@DevPanelApi
@RestController
@RequestMapping(DevPanelPaths.API)
public class InsightController {

  private final HealthService health;
  private final AlertEngine alerts;
  private final ClockCheck clock;
  private final RequestStats requests;
  private final EndpointHistory endpointHistory;
  private final ExceptionGroups exceptions;
  private final DatabaseInsights database;
  private final DeployInfo deploys;
  private final ConfigViewer config;
  private final ObjectProvider<MemoryWatch> memory;
  private final ObjectProvider<LogEvents> logs;
  private final ObjectProvider<LiveLogs> liveLogs;
  private final ObjectProvider<OutboundStats> outbound;
  private final ObjectProvider<JobStats> jobs;
  private final ObjectProvider<LogLevels> logLevels;
  private final TraceStore traces;
  private final UsageStats usage;
  private final EndpointCatalog catalog;
  private final SecurityStats security;
  private final DeployReport deployReport;
  private final ObjectProvider<TomcatCapacity> tomcat;
  private final ObjectProvider<DependencyChecks> dependencies;
  private final HeapHistogram histogram = new HeapHistogram();
  private final io.github.devpanel.audit.DevActionLog actions;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public InsightController(HealthService health, AlertEngine alerts, ClockCheck clock,
      RequestStats requests, EndpointHistory endpointHistory, ExceptionGroups exceptions,
      DatabaseInsights database, DeployInfo deploys, ConfigViewer config,
      ObjectProvider<MemoryWatch> memory, ObjectProvider<LogEvents> logs,
      ObjectProvider<LiveLogs> liveLogs, ObjectProvider<OutboundStats> outbound,
      ObjectProvider<JobStats> jobs, ObjectProvider<LogLevels> logLevels, TraceStore traces,
      UsageStats usage, EndpointCatalog catalog, SecurityStats security,
      DeployReport deployReport, ObjectProvider<TomcatCapacity> tomcat,
      ObjectProvider<DependencyChecks> dependencies,
      io.github.devpanel.audit.DevActionLog actions) {
    this.actions = actions;
    this.health = health;
    this.alerts = alerts;
    this.clock = clock;
    this.requests = requests;
    this.endpointHistory = endpointHistory;
    this.exceptions = exceptions;
    this.database = database;
    this.deploys = deploys;
    this.config = config;
    this.memory = memory;
    this.logs = logs;
    this.liveLogs = liveLogs;
    this.outbound = outbound;
    this.jobs = jobs;
    this.logLevels = logLevels;
    this.traces = traces;
    this.usage = usage;
    this.catalog = catalog;
    this.security = security;
    this.deployReport = deployReport;
    this.tomcat = tomcat;
    this.dependencies = dependencies;
  }

  // ---------- 0.3: waterfall, usage, security, capacity, dependencies, deploy report ----------

  @GetMapping("/requests/traces")
  public List<TraceStore.TraceSummary> traces() {
    return traces.list();
  }

  @GetMapping("/requests/traces/{id}")
  public TraceStore.Trace trace(@PathVariable String id) {
    return traces.find(id).orElseThrow(() -> DevPanelException.notFound(
        "This request is no longer kept (only the last few hundred are)"));
  }

  @GetMapping("/usage")
  public Map<String, Object> usage() {
    long now = System.currentTimeMillis();
    return Map.of("users", usage.users(now), "tenants", usage.tenants(now),
        "activeUsers", usage.activeUsers(now, 5));
  }

  /** Declared endpoints with no call in the kept history (14 days) nor since this start. */
  @GetMapping("/usage/unused")
  public Map<String, Object> unused() {
    java.util.Set<String> called = endpointHistory.calledEndpoints();
    requests.endpoints(System.currentTimeMillis(), 48)
        .forEach(view -> called.add(view.endpoint()));
    List<EndpointCatalog.Entry> all = catalog.all();
    List<EndpointCatalog.Entry> unused = all.stream()
        .filter(entry -> !called.contains(entry.endpoint())).toList();
    Instant since = endpointHistory.historyStart();
    return Map.of("total", all.size(), "unused", unused,
        "since", since == null ? exceptions.started() : since);
  }

  @GetMapping("/security")
  public Map<String, Object> security() {
    long now = System.currentTimeMillis();
    return Map.of("top10min", security.top(now, 10, 50), "top1h", security.top(now, 60, 50),
        "events", security.events(), "findings", security.findings(now));
  }

  @GetMapping("/server/tomcat")
  public Map<String, Object> tomcat() {
    TomcatCapacity capacity = tomcat.getIfAvailable();
    TomcatCapacity.State state = capacity == null ? null : capacity.state();
    return state == null ? Map.of() : Map.of("state", state);
  }

  @GetMapping("/dependencies")
  public List<DependencyChecks.TargetView> dependencies() {
    DependencyChecks checks = dependencies.getIfAvailable();
    return checks == null ? List.of() : checks.list();
  }

  @GetMapping("/deploys/report")
  public DeployReport.Report deployReport() {
    return deployReport.build();
  }

  // ---------- health and alerts ----------

  @GetMapping("/health")
  public Map<String, Object> health() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("report", health.report());
    result.put("clock", clock.state());
    result.put("deploy", deploys.current());
    return result;
  }

  @GetMapping("/alerts")
  public Map<String, Object> alerts() {
    return Map.of("settings", alerts.settings(), "history", alerts.history());
  }

  @PostMapping("/alerts/test")
  public Map<String, Object> testAlert(DevAccount actor) {
    actions.record(actor, "alerts.test", null, null);
    try {
      alerts.sendTest();
      return Map.of("sent", true);
    } catch (IOException | RuntimeException e) {
      throw DevPanelException.badRequest("Could not send: " + e.getMessage());
    }
  }

  // ---------- memory ----------

  @GetMapping("/memory")
  public Map<String, Object> memory() {
    MemoryWatch watch = memory.getIfAvailable();
    if (watch == null) {
      throw new DevPanelException(HttpStatus.NOT_IMPLEMENTED, "GC notifications unavailable");
    }
    return Map.of("analysis", watch.analysis(), "collections", watch.recentCollections(50));
  }

  /** Runs a full GC first, so it briefly pauses the application. */
  @PostMapping("/memory/histogram")
  public List<HeapHistogram.Row> histogram(DevAccount actor) {
    requireAdmin(actor);
    actions.record(actor, "jvm.heap-histogram", null, null);
    return histogram.top();
  }

  // ---------- API ----------

  @GetMapping("/requests/endpoints")
  public List<Map<String, Object>> endpoints(@RequestParam(defaultValue = "24") int hours) {
    long now = System.currentTimeMillis();
    Map<String, long[]> yesterday = endpointHistory.sameHourYesterday();
    Map<String, Long> beforeDeploy = endpointHistory.p95BeforeDeploy(exceptions.started());
    List<Map<String, Object>> rows = new ArrayList<>();
    for (EndpointView view : requests.endpoints(now, Math.max(1, Math.min(hours, 48)))) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("stats", view);
      long[] lastDay = yesterday.get(view.endpoint());
      row.put("p95SameHourYesterdayMs", lastDay == null ? null : lastDay[0]);
      row.put("p95BeforeDeployMs", beforeDeploy.get(view.endpoint()));
      rows.add(row);
    }
    return rows;
  }

  @GetMapping("/requests/slow")
  public List<RequestStats.SlowRequest> slow() {
    return requests.slowRequests();
  }

  @GetMapping("/requests/n-plus-one")
  public List<RequestStats.NPlusOne> nPlusOne() {
    return requests.nPlusOneSuspects();
  }

  @GetMapping("/requests/rate")
  public Map<String, Object> rate(@RequestParam(defaultValue = "60") int minutes) {
    long now = System.currentTimeMillis();
    int range = Math.max(5, Math.min(minutes, 1440));
    LogEvents events = logs.getIfAvailable();
    return Map.of("end", Instant.ofEpochMilli(now),
        "requests", requests.requestsPerMinute(now, range),
        "serverErrors", requests.serverErrorsPerMinute(now, range),
        "loggedErrors", events == null ? new long[0] : events.errorsPerMinute(now, range));
  }

  // ---------- errors ----------

  @GetMapping("/exceptions")
  public List<ExceptionGroups.GroupView> exceptions() {
    return exceptions.list();
  }

  // ---------- database ----------

  @GetMapping("/database/overview")
  public DatabaseInsights.Overview databaseOverview() {
    return database.overview();
  }

  @GetMapping("/database/activity")
  public List<DatabaseInsights.Activity> databaseActivity() {
    return database.activity();
  }

  @GetMapping("/database/statements")
  public List<DatabaseInsights.Statement> databaseStatements() {
    return database.statements();
  }

  @GetMapping("/database/tables")
  public List<DatabaseInsights.TableStat> databaseTables() {
    return database.tables();
  }

  @GetMapping("/database/unused-indexes")
  public List<DatabaseInsights.UnusedIndex> unusedIndexes() {
    return database.unusedIndexes();
  }

  @GetMapping("/database/leaks")
  public List<LogEvents.LeakReport> leaks() {
    LogEvents events = logs.getIfAvailable();
    return events == null ? List.of() : events.leaks();
  }

  /** {@code mode}: cancel (stop the statement) or terminate (close the connection). */
  @PostMapping("/database/activity/{pid}/{mode}")
  public Map<String, Object> stop(DevAccount actor, @PathVariable int pid,
      @PathVariable String mode) {
    requireAdmin(actor);
    if (!"cancel".equals(mode) && !"terminate".equals(mode)) {
      throw DevPanelException.badRequest("mode is cancel or terminate");
    }
    boolean done = database.stop(pid, "terminate".equals(mode));
    actions.record(actor, "database." + mode, String.valueOf(pid), done ? "done" : "not done");
    return Map.of("done", done);
  }

  // ---------- integrations, jobs ----------

  @GetMapping("/integrations")
  public List<OutboundStats.TargetView> integrations() {
    OutboundStats stats = outbound.getIfAvailable();
    return stats == null ? List.of() : stats.list();
  }

  @GetMapping("/jobs")
  public List<JobStats.JobView> jobs() {
    JobStats stats = jobs.getIfAvailable();
    return stats == null ? List.of() : stats.list();
  }

  // ---------- logs ----------

  @GetMapping("/logs/live")
  public SseEmitter liveLogs(@RequestParam(required = false) String level,
      @RequestParam(required = false) String q,
      javax.servlet.http.HttpServletResponse response) {
    LiveLogs live = liveLogs.getIfAvailable();
    if (live == null) {
      throw new DevPanelException(HttpStatus.NOT_IMPLEMENTED, "Live logs need Logback");
    }
    // nginx would otherwise hold the stream back in its buffer
    response.setHeader("X-Accel-Buffering", "no");
    return live.open(level, q);
  }

  @GetMapping("/logging/levels")
  public List<LogLevels.LoggerView> levels(@RequestParam(required = false) String filter) {
    return requireLogLevels().list(filter);
  }

  @PostMapping("/logging/levels")
  public Map<String, Object> setLevel(DevAccount actor, @RequestBody Map<String, Object> body) {
    Object minutes = body.get("minutes");
    requireLogLevels().set((String) body.get("logger"), (String) body.get("level"),
        minutes instanceof Number number ? number.intValue() : 30, actor.username());
    actions.record(actor, "logging.set", (String) body.get("logger"), body.get("level") + " for "
        + (minutes instanceof Number number ? number.intValue() : 30) + " min");
    return Map.of("ok", true);
  }

  @DeleteMapping("/logging/levels/{logger}")
  public Map<String, Object> resetLevel(DevAccount actor, @PathVariable String logger) {
    requireLogLevels().reset(logger);
    actions.record(actor, "logging.reset", logger, null);
    return Map.of("ok", true);
  }

  // ---------- config and deploys ----------

  @GetMapping("/config")
  public Map<String, Object> config(DevAccount actor,
      @RequestParam(required = false) String filter) {
    requireAdmin(actor);
    actions.record(actor, "config.view", filter, null);
    return Map.of("profiles", config.activeProfiles(), "properties", config.list(filter));
  }

  @GetMapping("/deploys")
  public Map<String, Object> deploys() {
    return Map.of("current", deploys.current(), "recent", deploys.recent());
  }

  private LogLevels requireLogLevels() {
    LogLevels levels = logLevels.getIfAvailable();
    if (levels == null) {
      throw new DevPanelException(HttpStatus.NOT_IMPLEMENTED, "No Spring Boot logging system");
    }
    return levels;
  }

  private static void requireAdmin(DevAccount actor) {
    if (actor.role() == DevRole.USER) {
      throw DevPanelException.forbidden("Only admins can do this");
    }
  }
}
