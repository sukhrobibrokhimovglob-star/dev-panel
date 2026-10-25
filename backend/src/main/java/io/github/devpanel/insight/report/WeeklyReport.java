package io.github.devpanel.insight.report;

import io.github.devpanel.insight.health.Check;
import io.github.devpanel.insight.health.HealthService;
import io.github.devpanel.insight.logs.ExceptionGroups;
import io.github.devpanel.insight.ops.DeployInfo;
import io.github.devpanel.insight.requests.EndpointHistory;
import io.github.devpanel.insight.requests.EndpointHistory.Day;
import io.github.devpanel.insight.risk.Risk;
import io.github.devpanel.insight.risk.RiskEngine;
import io.github.devpanel.insight.risk.RiskStore.View;
import io.github.devpanel.insight.slo.SloService;
import io.github.devpanel.insight.smoke.SmokeChecks;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * A one-page report of the last 7 days for people who do not open the panel: health now, risks,
 * goals, traffic and errors per day, the busiest and slowest endpoints, errors, deploys and smoke
 * checks. Self-contained HTML; the browser prints it to PDF.
 */
public class WeeklyReport {

  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm")
      .withZone(ZoneId.systemDefault());

  private final HealthService health;
  private final RiskEngine risks;
  private final SloService slos;
  private final EndpointHistory history;
  private final ExceptionGroups exceptions;
  private final DeployInfo deploys;
  private final SmokeChecks smoke;

  /** {@code smoke} may be null. */
  public WeeklyReport(HealthService health, RiskEngine risks, SloService slos,
      EndpointHistory history, ExceptionGroups exceptions, DeployInfo deploys,
      SmokeChecks smoke) {
    this.health = health;
    this.risks = risks;
    this.slos = slos;
    this.history = history;
    this.exceptions = exceptions;
    this.deploys = deploys;
    this.smoke = smoke;
  }

  public String html() {
    DeployInfo.Current current = deploys.current();
    LocalDate today = LocalDate.now();
    StringBuilder html = new StringBuilder();
    html.append("<!doctype html><html><head><meta charset=\"utf-8\"><title>Weekly report — ")
        .append(escape(current.application())).append("</title><style>")
        .append("body{font:14px/1.45 -apple-system,Segoe UI,Roboto,sans-serif;color:#1f2937;"
            + "max-width:960px;margin:24px auto;padding:0 16px}h1{font-size:22px;margin:0}"
            + "h2{font-size:16px;margin:28px 0 8px;border-bottom:1px solid #e5e7eb;"
            + "padding-bottom:4px}table{border-collapse:collapse;width:100%;font-size:13px}"
            + "td,th{border-bottom:1px solid #f1f5f9;padding:5px 6px;text-align:left;"
            + "vertical-align:top}th{color:#64748b;font-weight:600}.n{text-align:right;"
            + "white-space:nowrap}.muted{color:#64748b}.c{color:#dc2626}.w{color:#d97706}"
            + ".ok{color:#16a34a}.i{color:#2563eb}.bar{background:#e2e8f0;height:8px;"
            + "border-radius:4px}.bar span{display:block;height:8px;border-radius:4px;"
            + "background:#3b82f6}@media print{body{margin:0}}</style></head><body>");
    html.append("<h1>").append(escape(current.application())).append(" — weekly report</h1>")
        .append("<div class=\"muted\">").append(DATE.format(today.minusDays(7))).append(" – ")
        .append(DATE.format(today)).append(" · ").append(escape(current.host()))
        .append(current.version() == null ? "" : " · version " + escape(current.version()))
        .append("</div>");

    HealthService.Report report = health.report();
    html.append("<h2>Health now: ").append(statusLabel(report.overall())).append("</h2>");
    List<Check> problems = report.checks().stream()
        .filter(check -> check.status() == Check.Status.WARNING
            || check.status() == Check.Status.CRITICAL).toList();
    if (problems.isEmpty()) {
      html.append("<p class=\"ok\">All checks are green.</p>");
    } else {
      html.append("<table>");
      for (Check check : problems) {
        html.append("<tr><td>").append(statusLabel(check.status())).append("</td><td><b>")
            .append(escape(check.title())).append("</b><br><span class=\"muted\">")
            .append(escape(check.detail())).append("</span></td></tr>");
      }
      html.append("</table>");
    }

    RiskEngine.Overview overview = risks.overview();
    RiskEngine.Changes week = risks.changesSince(Instant.now().minus(Duration.ofDays(7)));
    html.append("<h2>Risks</h2><p>").append(overview.counts().get("CRITICAL"))
        .append(" critical, ").append(overview.counts().get("WARNING")).append(" warnings, ")
        .append(overview.counts().get("INFO")).append(" notes open · this week ")
        .append(week.appeared().size()).append(" new, ").append(week.resolved().size())
        .append(" resolved.</p><table><tr><th></th><th>Risk</th><th>What will happen</th>"
            + "<th class=\"n\">When</th></tr>");
    overview.open().stream().filter(view -> !view.silenced()).limit(12).forEach(view -> {
      Risk risk = view.risk();
      html.append("<tr><td>").append(severityLabel(risk.severity())).append("</td><td><b>")
          .append(escape(risk.title())).append("</b>")
          .append(risk.fix() == null ? "" : "<br><span class=\"muted\">Fix: "
              + escape(risk.fix()) + "</span>")
          .append("</td><td>").append(escape(risk.impact())).append("</td><td class=\"n\">")
          .append(risk.daysLeft() == null ? "" : "~" + Math.round(risk.daysLeft()) + " days")
          .append("</td></tr>");
    });
    html.append("</table>");
    if (!week.resolved().isEmpty()) {
      html.append("<p class=\"ok\">Resolved this week: ");
      List<String> titles = new ArrayList<>();
      for (View view : week.resolved()) {
        titles.add(escape(view.risk().title()));
      }
      html.append(String.join("; ", titles)).append("</p>");
    }

    List<SloService.Status> goals = slos.statuses();
    if (!goals.isEmpty()) {
      html.append("<h2>Goals</h2><table><tr><th>Endpoint</th><th>Goal</th>"
          + "<th class=\"n\">7 days</th><th class=\"n\">30 days</th><th class=\"n\">Budget left"
          + "</th></tr>");
      for (SloService.Status status : goals) {
        html.append("<tr><td>").append(escape(status.goal().endpoint())).append("</td><td>")
            .append(percent(status.goal().objective())).append(" within ")
            .append(status.goal().targetMs()).append(" ms</td><td class=\"n\">")
            .append(percent(status.weekShare())).append("</td><td class=\"n ")
            .append(status.met() ? "ok" : "c").append("\">").append(percent(status.monthShare()))
            .append("</td><td class=\"n\">").append(percent(status.budgetLeft()))
            .append("</td></tr>");
      }
      html.append("</table>");
    }

    List<long[]> perDay = history.totalsPerDay(7);
    long most = perDay.stream().mapToLong(day -> day[1]).max().orElse(1);
    html.append("<h2>Traffic</h2><table><tr><th>Day</th><th class=\"n\">Requests</th><th></th>"
        + "<th class=\"n\">Server errors</th></tr>");
    for (long[] day : perDay) {
      html.append("<tr><td>").append(DATE.format(LocalDate.ofEpochDay(day[0])))
          .append("</td><td class=\"n\">").append(day[1]).append("</td><td style=\"width:40%\">"
              + "<div class=\"bar\"><span style=\"width:")
          .append(Math.round(day[1] * 100.0 / Math.max(1, most))).append("%\"></span></div></td>"
              + "<td class=\"n ").append(day[2] > 0 ? "c" : "").append("\">").append(day[2])
          .append("</td></tr>");
    }
    html.append("</table>");

    Map<String, List<Day>> days = history.daily(7);
    List<long[]> rows = new ArrayList<>();
    List<String> names = new ArrayList<>();
    days.forEach((endpoint, list) -> {
      long calls = list.stream().mapToLong(Day::calls).sum();
      long weighted = list.stream().mapToLong(day -> day.p95Ms() * day.calls()).sum();
      long errors = list.stream().mapToLong(Day::serverErrors).sum();
      names.add(endpoint);
      rows.add(new long[] {names.size() - 1, calls, calls == 0 ? 0 : weighted / calls, errors});
    });
    html.append("<h2>Busiest endpoints</h2>");
    endpointTable(html, rows, names, Comparator.comparingLong((long[] r) -> r[1]).reversed());
    html.append("<h2>Slowest endpoints (p95, 20+ calls)</h2>");
    endpointTable(html, rows.stream().filter(r -> r[1] >= 20).toList(), names,
        Comparator.comparingLong((long[] r) -> r[2]).reversed());

    List<ExceptionGroups.GroupView> errors = exceptions.list().stream()
        .filter(group -> group.lastSeen().isAfter(Instant.now().minus(Duration.ofDays(7))))
        .sorted(Comparator.comparingLong(ExceptionGroups.GroupView::count).reversed())
        .limit(10).toList();
    html.append("<h2>Errors</h2>");
    if (errors.isEmpty()) {
      html.append("<p class=\"ok\">No exceptions this week.</p>");
    } else {
      html.append("<table><tr><th>Error</th><th>Where</th><th class=\"n\">Count</th>"
          + "<th class=\"n\">Last</th></tr>");
      for (ExceptionGroups.GroupView group : errors) {
        html.append("<tr><td>").append(escape(group.type().substring(
                group.type().lastIndexOf('.') + 1))).append("<br><span class=\"muted\">")
            .append(escape(cut(group.message(), 160))).append("</span></td><td>")
            .append(escape(group.location())).append("</td><td class=\"n\">")
            .append(group.count()).append("</td><td class=\"n\">")
            .append(TIME.format(group.lastSeen())).append("</td></tr>");
      }
      html.append("</table>");
    }

    html.append("<h2>Deploys</h2><table><tr><th>When</th><th>Host</th><th>Version</th>"
        + "<th>Commit</th></tr>");
    deploys.recent().stream()
        .filter(deploy -> deploy.startedAt().isAfter(Instant.now().minus(Duration.ofDays(7))))
        .forEach(deploy -> html.append("<tr><td>").append(TIME.format(deploy.startedAt()))
            .append("</td><td>").append(escape(deploy.host())).append("</td><td>")
            .append(escape(deploy.version())).append("</td><td>").append(escape(deploy.commit()))
            .append("</td></tr>"));
    html.append("</table>");

    if (smoke != null) {
      List<SmokeChecks.CheckView> checks = smoke.list();
      if (!checks.isEmpty()) {
        html.append("<h2>Smoke checks</h2><table><tr><th>Check</th><th class=\"n\">Passed</th>"
            + "<th>Last</th></tr>");
        for (SmokeChecks.CheckView view : checks) {
          html.append("<tr><td>").append(escape(view.check().name())).append("</td><td class=\"n\">")
              .append(percent(view.success())).append("</td><td class=\"")
              .append(view.lastRun() == null ? "muted" : view.lastRun().ok() ? "ok" : "c")
              .append("\">").append(view.lastRun() == null ? "never ran"
                  : view.lastRun().ok() ? "passed" : escape(view.lastRun().error()))
              .append("</td></tr>");
        }
        html.append("</table>");
      }
    }
    html.append("<p class=\"muted\" style=\"margin-top:32px\">Made by the dev panel at ")
        .append(TIME.format(Instant.now())).append(".</p></body></html>");
    return html.toString();
  }

  private static void endpointTable(StringBuilder html, List<long[]> rows, List<String> names,
      Comparator<long[]> order) {
    html.append("<table><tr><th>Endpoint</th><th class=\"n\">Calls</th><th class=\"n\">p95</th>"
        + "<th class=\"n\">Server errors</th></tr>");
    rows.stream().sorted(order).limit(10).forEach(row -> html.append("<tr><td>")
        .append(escape(names.get((int) row[0]))).append("</td><td class=\"n\">").append(row[1])
        .append("</td><td class=\"n\">").append(row[2]).append(" ms</td><td class=\"n\">")
        .append(row[3]).append("</td></tr>"));
    html.append("</table>");
  }

  private static String statusLabel(Check.Status status) {
    return switch (status) {
      case OK -> "<span class=\"ok\">● OK</span>";
      case WARNING -> "<span class=\"w\">● Warning</span>";
      case CRITICAL -> "<span class=\"c\">● Critical</span>";
      case UNKNOWN -> "<span class=\"muted\">● Unknown</span>";
    };
  }

  private static String severityLabel(Risk.Severity severity) {
    return switch (severity) {
      case CRITICAL -> "<span class=\"c\">●</span>";
      case WARNING -> "<span class=\"w\">●</span>";
      case INFO -> "<span class=\"i\">●</span>";
    };
  }

  private static String percent(double share) {
    return share < 0 ? "—" : Math.round(share * 1000) / 10.0 + "%";
  }

  private static String cut(String text, int max) {
    return text == null || text.length() <= max ? text : text.substring(0, max) + "…";
  }

  static String escape(String text) {
    if (text == null) {
      return "";
    }
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;");
  }
}
