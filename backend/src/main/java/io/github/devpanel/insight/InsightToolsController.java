package io.github.devpanel.insight;

import io.github.devpanel.account.DevAccount;
import io.github.devpanel.account.DevRole;
import io.github.devpanel.audit.DevActionLog;
import io.github.devpanel.insight.database.DatabaseAdvisor;
import io.github.devpanel.insight.database.ExplainService;
import io.github.devpanel.insight.logs.LogPrivacy;
import io.github.devpanel.insight.profiler.Profiler;
import io.github.devpanel.insight.report.WeeklyReport;
import io.github.devpanel.insight.requests.EndpointHistory;
import io.github.devpanel.insight.risk.RiskEngine;
import io.github.devpanel.insight.risk.RiskStore.View;
import io.github.devpanel.insight.runner.ApiRunner;
import io.github.devpanel.insight.slo.SloService;
import io.github.devpanel.insight.smoke.SmokeChecks;
import io.github.devpanel.insight.startup.StartupReport;
import io.github.devpanel.insight.tests.TestReports;
import io.github.devpanel.insight.trace.RequestRecorder;
import io.github.devpanel.insight.trace.TraceStore;
import io.github.devpanel.insight.usage.EndpointCatalog;
import io.github.devpanel.support.DevPanelException;
import io.github.devpanel.web.DevPanelApi;
import io.github.devpanel.web.DevPanelPaths;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The 0.4 tools: risks, the startup report, the database advisor and plans, personal data in
 * logs, goals (SLOs), endpoint details, the API runner, smoke checks, the profiler, tests, the
 * panel's own action log and the weekly report. Reading is open to every account; changing
 * anything needs a read-write account (the auth filter) and, where noted, an admin. Every
 * change is written to the action log.
 */
@DevPanelApi
@RestController
@RequestMapping(DevPanelPaths.API)
public class InsightToolsController {

  private final RiskEngine risks;
  private final StartupReport startup;
  private final DatabaseAdvisor advisor;
  private final ExplainService explain;
  private final ObjectProvider<LogPrivacy> privacy;
  private final SloService slos;
  private final EndpointCatalog catalog;
  private final EndpointHistory history;
  private final ObjectProvider<ApiRunner> runner;
  private final ObjectProvider<SmokeChecks> smoke;
  private final ObjectProvider<Profiler> profiler;
  private final TestReports tests;
  private final DevActionLog actions;
  private final WeeklyReport weekly;
  private final TraceStore traces;
  private final io.github.devpanel.insight.requests.RequestStats requests;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public InsightToolsController(RiskEngine risks, StartupReport startup, DatabaseAdvisor advisor,
      ExplainService explain, ObjectProvider<LogPrivacy> privacy, SloService slos,
      EndpointCatalog catalog, EndpointHistory history, ObjectProvider<ApiRunner> runner,
      ObjectProvider<SmokeChecks> smoke, ObjectProvider<Profiler> profiler, TestReports tests,
      DevActionLog actions, WeeklyReport weekly, TraceStore traces,
      io.github.devpanel.insight.requests.RequestStats requests) {
    this.requests = requests;
    this.risks = risks;
    this.startup = startup;
    this.advisor = advisor;
    this.explain = explain;
    this.privacy = privacy;
    this.slos = slos;
    this.catalog = catalog;
    this.history = history;
    this.runner = runner;
    this.smoke = smoke;
    this.profiler = profiler;
    this.tests = tests;
    this.actions = actions;
    this.weekly = weekly;
    this.traces = traces;
  }

  // ---------- risks ----------

  @GetMapping("/risks")
  public Map<String, Object> risks() {
    RiskEngine.Overview overview = risks.overview();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("open", overview.open().stream().map(InsightToolsController::item).toList());
    result.put("resolved", overview.resolved().stream().map(InsightToolsController::item)
        .toList());
    result.put("lastRun", overview.lastRun());
    result.put("lastRunMs", overview.lastRunMs());
    result.put("failures", overview.failures());
    result.put("counts", overview.counts());
    return result;
  }

  /** Runs the detectors now instead of waiting for the next round. */
  @PostMapping("/risks/run")
  public Map<String, Object> runRisks(DevAccount actor) {
    risks.run();
    actions.record(actor, "risks.run", null, null);
    return risks();
  }

  @PostMapping("/risks/snooze")
  public Map<String, Object> snooze(DevAccount actor, @RequestBody Map<String, Object> body) {
    String key = text(body, "key");
    int days = body.get("days") instanceof Number number ? number.intValue() : 7;
    risks.snooze(key, days);
    actions.record(actor, "risks.snooze", key, days + " days");
    return Map.of("ok", true);
  }

  @PostMapping("/risks/accept")
  public Map<String, Object> accept(DevAccount actor, @RequestBody Map<String, Object> body) {
    String key = text(body, "key");
    String reason = body.get("reason") == null ? null : String.valueOf(body.get("reason"));
    risks.accept(key, actor.username(), reason);
    actions.record(actor, "risks.accept", key, reason);
    return Map.of("ok", true);
  }

  @PostMapping("/risks/reopen")
  public Map<String, Object> reopen(DevAccount actor, @RequestBody Map<String, Object> body) {
    String key = text(body, "key");
    risks.reopen(key);
    actions.record(actor, "risks.reopen", key, null);
    return Map.of("ok", true);
  }

  @GetMapping("/risks/changes")
  public Map<String, Object> riskChanges(@RequestParam String since) {
    RiskEngine.Changes changes = risks.changesSince(Instant.parse(since));
    return Map.of("appeared", changes.appeared().stream().map(InsightToolsController::item)
            .toList(),
        "resolved", changes.resolved().stream().map(InsightToolsController::item).toList());
  }

  private static Map<String, Object> item(View view) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("risk", view.risk());
    item.put("score", view.score());
    item.put("worst", view.worst());
    item.put("firstSeen", view.firstSeen());
    item.put("lastSeen", view.lastSeen());
    item.put("resolvedAt", view.resolvedAt());
    item.put("snoozedUntil", view.snoozedUntil());
    item.put("acceptedBy", view.acceptedBy());
    item.put("acceptedReason", view.acceptedReason());
    item.put("acceptedAt", view.acceptedAt());
    item.put("silenced", view.silenced());
    item.put("flags", view.flags());
    item.put("changedFrom", view.changedFrom());
    item.put("changedAt", view.changedAt());
    return item;
  }

  // ---------- startup ----------

  @GetMapping("/startup")
  public Map<String, Object> startup() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("report", startup.report());
    result.put("previous", startup.durations(15).stream()
        .map(row -> Map.of("startedAt", Instant.ofEpochMilli(row[0]), "ms", row[1])).toList());
    result.put("inserts", startup.insertsOfRecentStarts(5));
    return result;
  }

  // ---------- database advisor and plans ----------

  @GetMapping("/database/advisor")
  public DatabaseAdvisor.Report databaseAdvisor() {
    if (!advisor.available()) {
      throw new DevPanelException(HttpStatus.NOT_IMPLEMENTED, "The advisor needs PostgreSQL");
    }
    return advisor.report(() -> InsightsConfiguration.recentSql(traces, requests));
  }

  /** Forgets the cached advice (it is otherwise refreshed every 6 hours). */
  @PostMapping("/database/advisor/refresh")
  public DatabaseAdvisor.Report refreshAdvisor(DevAccount actor) {
    advisor.clearCache();
    actions.record(actor, "database.advisor-refresh", null, null);
    return databaseAdvisor();
  }

  /**
   * {@code sql}, or {@code traceId} + {@code span} (index in the waterfall) to plan with the
   * values the request really used. {@code analyze} runs it (read-write accounts).
   */
  @PostMapping("/database/explain")
  public ExplainService.Plan explain(DevAccount actor, @RequestBody Map<String, Object> body) {
    boolean analyze = Boolean.TRUE.equals(body.get("analyze"));
    if (analyze && !actor.canWrite()) {
      throw DevPanelException.forbidden("Running the statement (ANALYZE) needs a read-write"
          + " account");
    }
    String sql = body.get("sql") == null ? null : String.valueOf(body.get("sql"));
    Object[] parameters = null;
    if (body.get("traceId") != null && body.get("span") instanceof Number index) {
      TraceStore.Trace trace = traces.find(String.valueOf(body.get("traceId")))
          .orElseThrow(() -> DevPanelException.notFound("This request is no longer kept"));
      if (index.intValue() < 0 || index.intValue() >= trace.spans().size()) {
        throw DevPanelException.badRequest("No such step");
      }
      RequestRecorder.Span span = trace.spans().get(index.intValue());
      if (!"SQL".equals(span.getKind())) {
        throw DevPanelException.badRequest("Only SQL steps have a plan");
      }
      sql = span.getText();
      parameters = span.boundParameters();
    }
    if (sql == null || sql.isBlank()) {
      throw DevPanelException.badRequest("Nothing to explain");
    }
    ExplainService.Plan plan = explain.explain(sql, parameters, analyze);
    actions.record(actor, analyze ? "database.explain-analyze" : "database.explain", null,
        plan.statement());
    return plan;
  }

  // ---------- personal data in logs ----------

  @GetMapping("/privacy/logs")
  public List<LogPrivacy.Finding> privacyInLogs() {
    LogPrivacy found = privacy.getIfAvailable();
    return found == null ? List.of() : found.findings();
  }

  // ---------- goals ----------

  @GetMapping("/slo")
  public List<SloService.Status> slo() {
    return slos.statuses();
  }

  @PostMapping("/slo")
  public SloService.Goal createSlo(DevAccount actor, @RequestBody Map<String, Object> body) {
    requireAdmin(actor);
    long target = body.get("targetMs") instanceof Number number ? number.longValue() : 0;
    double objective = body.get("objective") instanceof Number number ? number.doubleValue() : 0;
    SloService.Goal goal = slos.create(text(body, "endpoint"), target, objective,
        actor.username());
    actions.record(actor, "slo.create", goal.endpoint(),
        objective * 100 + "% within " + target + " ms");
    return goal;
  }

  @DeleteMapping("/slo/{id}")
  public Map<String, Object> deleteSlo(DevAccount actor, @PathVariable long id) {
    requireAdmin(actor);
    slos.delete(id);
    actions.record(actor, "slo.delete", String.valueOf(id), null);
    return Map.of("ok", true);
  }

  // ---------- endpoints ----------

  @GetMapping("/endpoints/catalog")
  public List<EndpointCatalog.Entry> endpointCatalog() {
    return catalog.all();
  }

  @GetMapping("/endpoints/details")
  public EndpointCatalog.Details endpointDetails(@RequestParam String endpoint) {
    EndpointCatalog.Details details = catalog.details(endpoint);
    if (details == null) {
      throw DevPanelException.notFound("No such endpoint: " + endpoint);
    }
    return details;
  }

  /** What is normal for each endpoint at this hour (median of the past days). */
  @GetMapping("/requests/usual")
  public Map<String, EndpointHistory.Usual> usual() {
    return history.usualThisHour();
  }

  // ---------- API runner ----------

  /**
   * Sends a request to the application. Writing methods need an admin and
   * {@code "confirmWrite": true}: they change real data.
   */
  @PostMapping("/runner/send")
  public ApiRunner.Response send(DevAccount actor, @RequestBody Map<String, Object> body) {
    ApiRunner api = runner.getIfAvailable();
    if (api == null) {
      throw new DevPanelException(HttpStatus.NOT_IMPLEMENTED, "The API runner is off or the"
          + " application has no embedded web server");
    }
    String method = body.get("method") == null ? "GET" : String.valueOf(body.get("method"));
    if (ApiRunner.writes(method)) {
      requireAdmin(actor);
      if (!Boolean.TRUE.equals(body.get("confirmWrite"))) {
        throw DevPanelException.badRequest("A " + method + " changes real data; confirm it");
      }
    }
    Map<String, String> headers = new LinkedHashMap<>();
    if (body.get("headers") instanceof Map<?, ?> map) {
      map.forEach((name, value) -> {
        if (name != null && value != null) {
          headers.put(String.valueOf(name), String.valueOf(value));
        }
      });
    }
    String path = text(body, "path");
    ApiRunner.Response response = api.send(new ApiRunner.Request(method, path, headers,
        body.get("body") == null ? null : String.valueOf(body.get("body"))));
    actions.record(actor, "runner.send", method.toUpperCase() + " " + path,
        "HTTP " + response.status() + " in " + response.ms() + " ms"
            + (headers.isEmpty() ? "" : "; headers " + String.join(", ", headers.keySet())));
    return response;
  }

  // ---------- smoke checks ----------

  @GetMapping("/smoke")
  public List<SmokeChecks.CheckView> smoke() {
    SmokeChecks checks = smoke.getIfAvailable();
    return checks == null ? List.of() : checks.list();
  }

  @PostMapping("/smoke")
  public SmokeChecks.Check createSmoke(DevAccount actor,
      @RequestBody SmokeChecks.Check check) {
    requireAdmin(actor);
    SmokeChecks.Check saved = requireSmoke().save(null, check, actor.username());
    actions.record(actor, "smoke.create", saved.name(), saved.method() + " " + saved.path());
    return saved;
  }

  @PutMapping("/smoke/{id}")
  public SmokeChecks.Check updateSmoke(DevAccount actor, @PathVariable long id,
      @RequestBody SmokeChecks.Check check) {
    requireAdmin(actor);
    SmokeChecks.Check saved = requireSmoke().save(id, check, actor.username());
    actions.record(actor, "smoke.update", saved.name(), saved.method() + " " + saved.path());
    return saved;
  }

  @DeleteMapping("/smoke/{id}")
  public Map<String, Object> deleteSmoke(DevAccount actor, @PathVariable long id) {
    requireAdmin(actor);
    requireSmoke().delete(id);
    actions.record(actor, "smoke.delete", String.valueOf(id), null);
    return Map.of("ok", true);
  }

  @PostMapping("/smoke/run")
  public List<SmokeChecks.Run> runSmoke(DevAccount actor,
      @RequestBody(required = false) Map<String, Object> body) {
    Long id = body != null && body.get("id") instanceof Number number ? number.longValue() : null;
    List<SmokeChecks.Run> runs = requireSmoke().runAll("MANUAL", id);
    actions.record(actor, "smoke.run", id == null ? "all" : String.valueOf(id), null);
    return runs;
  }

  // ---------- profiler ----------

  @PostMapping("/profiler/start")
  public Profiler.Profile startProfile(DevAccount actor, @RequestBody Map<String, Object> body) {
    requireAdmin(actor);
    int seconds = body.get("seconds") instanceof Number number ? number.intValue() : 20;
    String scope = body.get("scope") == null ? "requests" : String.valueOf(body.get("scope"));
    Profiler.Profile profile = requireProfiler().start(seconds, scope, actor.username());
    actions.record(actor, "profiler.start", scope, seconds + " s");
    return profile;
  }

  @GetMapping("/profiler")
  public List<Map<String, Object>> profiles() {
    return requireProfiler().list().stream().map(profile -> {
      Map<String, Object> summary = new LinkedHashMap<>();
      summary.put("id", profile.id());
      summary.put("startedAt", profile.startedAt());
      summary.put("seconds", profile.seconds());
      summary.put("scope", profile.scope());
      summary.put("by", profile.by());
      summary.put("status", profile.status());
      summary.put("threadSamples", profile.threadSamples());
      return summary;
    }).toList();
  }

  @GetMapping("/profiler/{id}")
  public Profiler.Profile profile(@PathVariable String id) {
    return requireProfiler().find(id);
  }

  // ---------- tests ----------

  @GetMapping("/tests")
  public TestReports.Overview tests() {
    return tests.overview();
  }

  /** {@code content}: devpanel-tests.json or JaCoCo's jacoco.csv, as text. */
  @PostMapping("/tests/upload")
  public Map<String, Object> uploadTests(DevAccount actor,
      @RequestBody Map<String, Object> body) {
    requireAdmin(actor);
    String kind = tests.upload(text(body, "content"), actor.username());
    actions.record(actor, "tests.upload", kind, null);
    return Map.of("kind", kind);
  }

  // ---------- the panel's own action log ----------

  @GetMapping("/panel-audit")
  public DevActionLog.Page panelAudit(DevAccount actor,
      @RequestParam(required = false) String username,
      @RequestParam(required = false) String action,
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
    requireAdmin(actor);
    return actions.list(username, action, Math.max(0, page), Math.max(1, Math.min(size, 200)));
  }

  // ---------- weekly report ----------

  @GetMapping("/report/weekly")
  public ResponseEntity<byte[]> weeklyReport(DevAccount actor,
      @RequestParam(defaultValue = "false") boolean download) {
    byte[] html = weekly.html().getBytes(StandardCharsets.UTF_8);
    actions.record(actor, "report.weekly", null, null);
    ResponseEntity.BodyBuilder response = ResponseEntity.ok()
        .contentType(new MediaType("text", "html", StandardCharsets.UTF_8));
    if (download) {
      response.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
          .filename("weekly-report-" + LocalDate.now() + ".html").build().toString());
    }
    return response.body(html);
  }

  private SmokeChecks requireSmoke() {
    SmokeChecks checks = smoke.getIfAvailable();
    if (checks == null) {
      throw new DevPanelException(HttpStatus.NOT_IMPLEMENTED, "Smoke checks need the API runner"
          + " and an embedded web server");
    }
    return checks;
  }

  private Profiler requireProfiler() {
    Profiler found = profiler.getIfAvailable();
    if (found == null) {
      throw new DevPanelException(HttpStatus.NOT_IMPLEMENTED, "The profiler is turned off");
    }
    return found;
  }

  private static String text(Map<String, Object> body, String field) {
    Object value = body == null ? null : body.get(field);
    if (value == null || String.valueOf(value).isBlank()) {
      throw DevPanelException.badRequest(field + " is required");
    }
    return String.valueOf(value);
  }

  private static void requireAdmin(DevAccount actor) {
    if (actor.role() == DevRole.USER) {
      throw DevPanelException.forbidden("Only admins can do this");
    }
  }
}
