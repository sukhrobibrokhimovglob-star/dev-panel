package io.github.devpanel.insight.alerts;

import io.github.devpanel.insight.health.Check;
import io.github.devpanel.insight.health.Check.Status;
import io.github.devpanel.insight.health.HealthService;
import io.github.devpanel.insight.health.HealthService.Report;
import io.github.devpanel.insight.logs.ExceptionGroups;
import io.github.devpanel.insight.logs.ExceptionGroups.GroupView;
import io.github.devpanel.insight.requests.RequestStats;
import io.github.devpanel.insight.requests.RequestStats.EndpointView;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns health checks into notifications: one message when a check turns WARNING or CRITICAL
 * (or gets worse), one when it is fine again, never the same alert every minute. Plus a morning
 * summary. Each instance alerts on its own; the message names the instance.
 */
public class AlertEngine {

  private static final Logger log = LoggerFactory.getLogger(AlertEngine.class);
  private static final int KEEP = 300;
  /** Right after a start most numbers are empty; give them time. */
  private static final Duration WARM_UP = Duration.ofMinutes(3);
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm")
      .withZone(ZoneId.systemDefault());

  private final HealthService health;
  private final Notifier notifier;
  private final RequestStats requests;
  private final ExceptionGroups exceptions;
  private final String instance;
  private final LocalTime summaryAt;
  private final Map<String, Status> state = new HashMap<>();
  private final Deque<AlertEvent> events = new ArrayDeque<>();
  private final Instant started = Instant.now();
  private LocalDate summarySentOn;
  private LocalDate digestSentOn;
  private boolean deployImpactSent;
  private volatile String lastDeliveryError;
  private volatile io.github.devpanel.insight.risk.RiskEngine risks;
  private volatile io.github.devpanel.insight.deploy.DeployReport deployReport;
  private volatile java.time.DayOfWeek digestDay;
  private volatile boolean riskAlerts = true;

  /** @param summaryAt null for no daily summary */
  public AlertEngine(HealthService health, Notifier notifier, RequestStats requests,
      ExceptionGroups exceptions, String instance, LocalTime summaryAt) {
    this.health = health;
    this.notifier = notifier;
    this.requests = requests;
    this.exceptions = exceptions;
    this.instance = instance;
    this.summaryAt = summaryAt;
  }

  /**
   * The weekly risk digest and the deploy impact message need these; without them only health
   * alerts and the daily summary are sent.
   */
  public void useRisks(io.github.devpanel.insight.risk.RiskEngine engine,
      io.github.devpanel.insight.deploy.DeployReport report, java.time.DayOfWeek day,
      boolean alertUrgentRisks) {
    this.risks = engine;
    this.deployReport = report;
    this.digestDay = day;
    this.riskAlerts = alertUrgentRisks;
    if (engine != null) {
      engine.onUrgent(this::riskBecameUrgent);
    }
  }

  /** Called by the panel's scheduler once a minute. */
  public void evaluate() {
    if (Instant.now().isBefore(started.plus(WARM_UP))) {
      return;
    }
    Report report = health.report();
    Map<String, Check> current = new LinkedHashMap<>();
    for (Check check : report.checks()) {
      current.put(check.key(), check);
    }
    synchronized (state) {
      for (Check check : report.checks()) {
        if (check.status() != Status.WARNING && check.status() != Status.CRITICAL) {
          continue;
        }
        Status before = state.get(check.key());
        if (before == null || check.status().worseThan(before)) {
          state.put(check.key(), check.status());
          fire(new AlertEvent(Instant.now(), check.key(), check.area(), check.status(),
              check.title(), check.detail(), false));
        }
      }
      for (String key : new ArrayList<>(state.keySet())) {
        Check now = current.get(key);
        if (now == null || now.status() == Status.OK) {
          state.remove(key);
          fire(new AlertEvent(Instant.now(), key, now == null ? "" : now.area(), Status.OK,
              now == null ? "Resolved: " + key : now.title(),
              now == null ? null : now.detail(), true));
        }
      }
    }
    sendSummaryIfDue(report);
    sendDigestIfDue();
    sendDeployImpactIfDue();
  }

  /** A risk turned critical or its limit is less than a week away. */
  void riskBecameUrgent(io.github.devpanel.insight.risk.RiskStore.View view) {
    if (!riskAlerts) {
      return;
    }
    io.github.devpanel.insight.risk.Risk risk = view.risk();
    Status status = risk.severity() == io.github.devpanel.insight.risk.Risk.Severity.CRITICAL
        ? Status.CRITICAL : Status.WARNING;
    AlertEvent event = new AlertEvent(Instant.now(), "risk." + risk.key(), risk.area(), status,
        "🔮 " + risk.title(), risk.impact() + (risk.fix() == null ? "" : "\nWhat to do: "
            + risk.fix()), false);
    fire(event);
  }

  private void sendDigestIfDue() {
    io.github.devpanel.insight.risk.RiskEngine engine = risks;
    if (engine == null || digestDay == null || summaryAt == null || !notifier.configured()) {
      return;
    }
    LocalDate today = LocalDate.now();
    if (today.getDayOfWeek() != digestDay || LocalTime.now().isBefore(summaryAt)
        || today.equals(digestSentOn)) {
      return;
    }
    digestSentOn = today;
    deliver(digest(engine), Map.of("digest", true, "instance", instance));
  }

  /** The weekly message: open risks by importance, what appeared and what was fixed. */
  String digest(io.github.devpanel.insight.risk.RiskEngine engine) {
    io.github.devpanel.insight.risk.RiskEngine.Overview overview = engine.overview();
    io.github.devpanel.insight.risk.RiskEngine.Changes week =
        engine.changesSince(Instant.now().minus(Duration.ofDays(7)));
    StringBuilder text = new StringBuilder("🔮 Weekly risks — ").append(instance).append('\n');
    text.append("Open: ").append(overview.counts().get("CRITICAL")).append(" critical, ")
        .append(overview.counts().get("WARNING")).append(" warnings, ")
        .append(overview.counts().get("INFO")).append(" notes.\n");
    text.append("This week: ").append(week.appeared().size()).append(" new, ")
        .append(week.resolved().size()).append(" resolved.\n\nMost important:\n");
    overview.open().stream().filter(view -> !view.silenced()).limit(7).forEach(view -> {
      io.github.devpanel.insight.risk.Risk risk = view.risk();
      text.append(switch (risk.severity()) {
        case CRITICAL -> "🔴 ";
        case WARNING -> "🟠 ";
        case INFO -> "🔵 ";
      }).append(risk.title());
      if (risk.daysLeft() != null) {
        text.append(" (in ~").append(Math.round(risk.daysLeft())).append(" days)");
      }
      text.append('\n');
    });
    if (!week.resolved().isEmpty()) {
      text.append("\nResolved: ").append(week.resolved().stream().limit(5)
          .map(view -> view.risk().title()).collect(Collectors.joining("; ")));
    }
    return text.toString();
  }

  /** One message an hour after a start: what the deploy changed. */
  private void sendDeployImpactIfDue() {
    io.github.devpanel.insight.deploy.DeployReport reportBuilder = deployReport;
    if (deployImpactSent || reportBuilder == null || !notifier.configured()
        || Instant.now().isBefore(started.plus(Duration.ofMinutes(60)))) {
      return;
    }
    deployImpactSent = true;
    io.github.devpanel.insight.deploy.DeployReport.Report report = reportBuilder.build();
    if (report.noHistory()) {
      return;
    }
    StringBuilder text = new StringBuilder("🚀 Deploy impact after 1 h — ").append(instance)
        .append('\n');
    if (!report.faster().isEmpty()) {
      text.append("✅ Faster: ").append(report.faster().stream().limit(3)
          .map(change -> change.endpoint() + " " + change.p95BeforeMs() + "→" + change.p95NowMs()
              + " ms").collect(Collectors.joining(", "))).append('\n');
    }
    if (!report.slower().isEmpty()) {
      text.append("⚠️ Slower: ").append(report.slower().stream().limit(3)
          .map(change -> change.endpoint() + " " + change.p95BeforeMs() + "→" + change.p95NowMs()
              + " ms").collect(Collectors.joining(", "))).append('\n');
    }
    if (!report.newErrors().isEmpty()) {
      text.append("🆕 New errors: ").append(report.newErrors().size()).append('\n');
    }
    io.github.devpanel.insight.risk.RiskEngine engine = risks;
    if (engine != null) {
      io.github.devpanel.insight.risk.RiskEngine.Changes changes =
          engine.changesSince(report.deployedAt());
      text.append("Risks: ").append(changes.appeared().size()).append(" new, ")
          .append(changes.resolved().size()).append(" resolved");
    }
    deliver(text.toString(), Map.of("deployImpact", true, "instance", instance));
  }

  public void sendTest() throws IOException {
    notifier.send("✅ Dev panel test message from " + instance
        + "\nAlerts reach this chat.", Map.of("test", true, "instance", instance));
  }

  public Status statusOf(String key) {
    synchronized (state) {
      return state.get(key);
    }
  }

  public List<AlertEvent> history() {
    synchronized (events) {
      return new ArrayList<>(events);
    }
  }

  public Settings settings() {
    return new Settings(notifier.telegramConfigured(), notifier.webhookConfigured(), instance,
        summaryAt == null ? null : summaryAt.toString(), lastDeliveryError);
  }

  private void fire(AlertEvent event) {
    synchronized (events) {
      events.addFirst(event);
      if (events.size() > KEEP) {
        events.removeLast();
      }
    }
    if (!notifier.configured()) {
      return;
    }
    String icon = event.resolved() ? "✅" : event.status() == Status.CRITICAL ? "🔴" : "🟠";
    String text = icon + " " + (event.resolved() ? "Resolved: " : "") + event.title()
        + "\n" + (event.detail() == null ? "" : event.detail() + "\n")
        + "— " + instance + ", " + TIME.format(event.time());
    deliver(text, payload(event));
  }

  private void sendSummaryIfDue(Report report) {
    if (summaryAt == null) {
      return;
    }
    LocalDate today = LocalDate.now();
    if (LocalTime.now().isBefore(summaryAt) || today.equals(summarySentOn)) {
      return;
    }
    summarySentOn = today;
    if (notifier.configured()) {
      deliver(summary(report), Map.of("summary", true, "instance", instance));
    }
  }

  /** The morning message: what is wrong now and how the last 24 hours went. */
  String summary(Report report) {
    long now = System.currentTimeMillis();
    StringBuilder text = new StringBuilder("☀️ Daily summary — ").append(instance).append('\n');
    List<Check> problems = report.checks().stream()
        .filter(check -> check.status() == Status.WARNING || check.status() == Status.CRITICAL)
        .toList();
    text.append(problems.isEmpty() ? "All checks are green.\n"
        : problems.size() + " problem(s) now:\n");
    problems.stream().limit(8).forEach(check -> text.append(
        check.status() == Status.CRITICAL ? "🔴 " : "🟠 ").append(check.title()).append('\n'));
    text.append("\nLast 24 h: ").append(requests.requestsInLast(now, 1440)).append(" requests, ")
        .append(requests.serverErrorsInLast(now, 1440)).append(" server errors.\n");
    List<EndpointView> slowest = requests.endpoints(now, 24).stream()
        .filter(view -> view.calls() >= 20)
        .sorted(Comparator.comparingLong(EndpointView::p95Ms).reversed()).limit(3).toList();
    if (!slowest.isEmpty()) {
      text.append("Slowest (p95): ").append(slowest.stream()
          .map(view -> view.endpoint() + " " + view.p95Ms() + " ms")
          .collect(Collectors.joining(", "))).append('\n');
    }
    List<GroupView> errors = exceptions.list().stream()
        .filter(group -> group.lastSeen().isAfter(Instant.now().minus(Duration.ofHours(24))))
        .sorted(Comparator.comparingLong(GroupView::count).reversed()).limit(3).toList();
    if (!errors.isEmpty()) {
      text.append("Most frequent errors: ").append(errors.stream()
          .map(group -> group.type().substring(group.type().lastIndexOf('.') + 1) + " ×"
              + group.count())
          .collect(Collectors.joining(", "))).append('\n');
    }
    long fired = history().stream().filter(event -> !event.resolved()
        && event.time().isAfter(Instant.now().minus(Duration.ofHours(24)))).count();
    text.append("Alerts in the last 24 h: ").append(fired);
    return text.toString();
  }

  private void deliver(String text, Map<String, Object> payload) {
    try {
      notifier.send(text, payload);
      lastDeliveryError = null;
    } catch (IOException | RuntimeException e) {
      lastDeliveryError = Instant.now() + ": " + e.getMessage();
      log.warn("Dev panel could not deliver an alert: {}", e.getMessage());
    }
  }

  private Map<String, Object> payload(AlertEvent event) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("instance", instance);
    payload.put("time", event.time().toString());
    payload.put("key", event.key());
    payload.put("area", event.area());
    payload.put("status", event.status().name());
    payload.put("resolved", event.resolved());
    payload.put("title", event.title());
    payload.put("detail", event.detail());
    return payload;
  }

  public record AlertEvent(Instant time, String key, String area, Status status, String title,
                           String detail, boolean resolved) {
  }

  /** {@code lastDeliveryError}: why the last message could not be sent, if it could not. */
  public record Settings(boolean telegram, boolean webhook, String instance, String dailySummaryAt,
                         String lastDeliveryError) {
  }
}
