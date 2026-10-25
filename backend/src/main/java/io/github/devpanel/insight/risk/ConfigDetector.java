package io.github.devpanel.insight.risk;

import io.github.devpanel.DevPanelProperties;
import io.github.devpanel.account.DevAccountRepository;
import io.github.devpanel.account.DevRole;
import io.github.devpanel.insight.alerts.AlertEngine;
import io.github.devpanel.insight.health.ClockCheck;
import io.github.devpanel.insight.history.DailySnapshots;
import io.github.devpanel.insight.ops.LogLevels;
import io.github.devpanel.insight.risk.Risk.Category;
import io.github.devpanel.insight.risk.Risk.Severity;
import io.github.devpanel.insight.server.TomcatCapacity;
import io.github.devpanel.metrics.DevMetricsService;
import io.github.devpanel.metrics.HikariPools.PoolState;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

/**
 * Settings that are risky in production: the database connection held for the whole request
 * (open-in-view), every SQL statement written to the log, DEBUG logging, Hibernate changing the
 * schema, consoles and actuator endpoints open, a small pool behind many threads, plus the panel's
 * own leftovers (an initial password still in the config, a tolerated clock drift, alerts that
 * cannot be delivered).
 */
public class ConfigDetector implements RiskDetector {

  private final Environment environment;
  private final ApplicationContext context;
  private final LogLevels logLevels;
  private final DevMetricsService metrics;
  private final TomcatCapacity tomcat;
  private final DailySnapshots daily;
  private final DevAccountRepository accounts;
  private final DevPanelProperties properties;
  private final Supplier<AlertEngine> alerts;
  private final ClockCheck clock;

  /** {@code logLevels} and {@code tomcat} may be null. */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public ConfigDetector(Environment environment, ApplicationContext context, LogLevels logLevels,
      DevMetricsService metrics, TomcatCapacity tomcat, DailySnapshots daily,
      DevAccountRepository accounts, DevPanelProperties properties, Supplier<AlertEngine> alerts,
      ClockCheck clock) {
    this.environment = environment;
    this.context = context;
    this.logLevels = logLevels;
    this.metrics = metrics;
    this.tomcat = tomcat;
    this.daily = daily;
    this.accounts = accounts;
    this.properties = properties;
    this.alerts = alerts;
    this.clock = clock;
  }

  @Override
  public String name() {
    return "config";
  }

  @Override
  public List<Risk> detect() {
    List<Risk> risks = new ArrayList<>();
    ClassLoader loader = context.getClassLoader();
    boolean jpa = present("javax.persistence.EntityManagerFactory", loader)
        && context.getBeanNamesForType(classOrNull("javax.persistence.EntityManagerFactory",
            loader)).length > 0;
    if (jpa && !"false".equalsIgnoreCase(environment.getProperty("spring.jpa.open-in-view"))) {
      risks.add(risk("config.open-in-view", Severity.WARNING,
          "Each request keeps its database connection until the answer is sent",
          "spring.jpa.open-in-view is on (Spring's default): lazy loading may happen while JSON"
              + " is written, and the connection stays taken during it and during any external"
              + " call. Under load the pool runs out sooner.",
          "spring.jpa.open-in-view is not set to false.",
          "Set spring.jpa.open-in-view=false and load what the answer needs inside the service"
              + " (JOIN FETCH / DTOs); test the endpoints that relied on lazy loading."));
    }
    String sqlLevel = logLevels == null ? null : logLevels.effective("org.hibernate.SQL");
    boolean showSql = "true".equalsIgnoreCase(environment.getProperty("spring.jpa.show-sql"));
    if ("DEBUG".equals(sqlLevel) || "TRACE".equals(sqlLevel) || showSql) {
      risks.add(risk("config.sql-log", Severity.WARNING, "Every SQL statement is written to the log",
          "Logging each statement costs time on every request and makes the log files grow fast;"
              + " with literal values it also copies personal data into the logs.",
          showSql ? "spring.jpa.show-sql=true." : "Logger org.hibernate.SQL is at " + sqlLevel
              + ".",
          "Remove it in production (logging.level.org.hibernate.SQL=INFO); turn it on for a few"
              + " minutes from the panel's Log levels tab when needed."));
    }
    String rootLevel = logLevels == null ? null : logLevels.effective("ROOT");
    if ("DEBUG".equals(rootLevel) || "TRACE".equals(rootLevel)) {
      risks.add(risk("config.root-debug", Severity.WARNING, "The whole application logs at "
              + rootLevel,
          "Libraries write thousands of lines per minute: slower requests, huge log files.",
          "Root logger at " + rootLevel + ".",
          "Set logging.level.root=INFO and raise single packages when needed."));
    }
    String ddl = environment.getProperty("spring.jpa.hibernate.ddl-auto", "");
    if (ddl.equalsIgnoreCase("update") || ddl.toLowerCase(Locale.ROOT).startsWith("create")) {
      risks.add(risk("config.ddl-auto", ddl.toLowerCase(Locale.ROOT).startsWith("create")
              ? Severity.CRITICAL : Severity.WARNING,
          "Hibernate changes the database schema at start (ddl-auto=" + ddl + ")",
          "Schema changes nobody reviewed, in production, at start; create even drops tables.",
          "spring.jpa.hibernate.ddl-auto=" + ddl + ".",
          "Use migrations (Liquibase/Flyway) and set ddl-auto to validate or none."));
    }
    if ("true".equalsIgnoreCase(environment.getProperty("spring.h2.console.enabled"))) {
      risks.add(risk("config.h2-console", Severity.CRITICAL, "The H2 console is enabled",
          "Anyone who reaches it can run SQL.", "spring.h2.console.enabled=true.",
          "Turn it off outside development."));
    }
    String exposure = environment.getProperty("management.endpoints.web.exposure.include", "");
    if (exposure.contains("*")) {
      risks.add(risk("config.actuator", Severity.WARNING, "All actuator endpoints are exposed",
          "Heap dumps, environment and configuration may be readable by anyone who reaches them,"
              + " unless security protects /actuator.",
          "management.endpoints.web.exposure.include=" + exposure + ".",
          "Expose only health (and info), or protect /actuator/** with security."));
    }
    if (present("org.springframework.boot.devtools.RemoteSpringApplication", loader)) {
      risks.add(risk("config.devtools", Severity.WARNING, "Spring DevTools is in the production build",
          "It restarts the application on class changes and may open a remote endpoint.",
          "spring-boot-devtools is on the classpath.",
          "Mark it <optional>true</optional> / developmentOnly so it stays out of the jar."));
    }
    pool(risks);
    panel(risks);
    return risks;
  }

  private void pool(List<Risk> risks) {
    int connections = 0;
    for (PoolState pool : metrics.hikari()) {
      connections += pool.max();
    }
    TomcatCapacity.State state = tomcat == null ? null : tomcat.state();
    if (connections <= 0 || state == null || state.maxThreads() <= 0) {
      return;
    }
    double waited = daily.series("pool.pending.peak", 14).stream()
        .filter(point -> point.value() > 0).count();
    if (state.maxThreads() >= connections * 4 && waited > 0) {
      risks.add(risk("config.pool-size", Severity.WARNING,
          "Up to " + state.maxThreads() + " request threads share " + connections
              + " database connections",
          "On busy moments requests already waited for a connection; each waiting request"
              + " holds a thread, and slowness spreads to every endpoint.",
          "Requests waited for a connection on " + Math.round(waited) + " of the last 14 days.",
          "First shorten what holds connections (slow queries, external calls in transactions);"
              + " then consider a larger spring.datasource.hikari.maximum-pool-size."));
    }
  }

  private void panel(List<Risk> risks) {
    String initial = properties.getSuperAdmin().getInitialPassword();
    if (initial != null && !initial.isBlank()) {
      boolean changed = accounts.findByRole(DevRole.SUPER_ADMIN)
          .map(account -> !account.mustChangePassword()).orElse(false);
      if (changed) {
        risks.add(risk("config.panel-initial-password", Severity.INFO,
            "The dev panel's initial password is still in the configuration",
            "It is no longer used (the super admin changed it), but whoever reads the"
                + " repository sees an old password, and it would be used again after a reset.",
            "devpanel.super-admin.initial-password is set.",
            "Remove it from devpanel.properties."));
      }
    }
    java.time.Duration drift = properties.getTwoFactor().getAllowedClockDrift();
    Long offset = clock.offsetMs();
    if (drift != null && drift.toSeconds() >= 60 && offset != null
        && Math.abs(offset) < 30_000) {
      risks.add(risk("config.panel-clock-drift", Severity.INFO,
          "The panel still tolerates a wrong server clock",
          "Two-factor codes stay valid longer than needed now that the clock is right.",
          "devpanel.two-factor.allowed-clock-drift=" + drift.toMinutes() + "m, clock off by "
              + Math.abs(offset) / 1000 + " s.",
          "Remove devpanel.two-factor.allowed-clock-drift."));
    }
    AlertEngine engine = alerts.get();
    if (engine != null && engine.settings().lastDeliveryError() != null) {
      risks.add(risk("config.alerts-undelivered", Severity.WARNING,
          "Alerts cannot be delivered",
          "Telegram/webhook is configured but messages do not arrive: nobody learns about"
              + " problems until users call.",
          engine.settings().lastDeliveryError(),
          "Allow outgoing HTTPS from the server to api.telegram.org (or the webhook), then send"
              + " a test from the Alerts tab."));
    }
  }

  private static Risk risk(String key, Severity severity, String title, String impact,
      String evidence, String fix) {
    return new Risk(key, Category.WEAKNESS, "config", severity, title, impact, evidence, fix,
        null, null, null, "config", 1);
  }

  private static boolean present(String className, ClassLoader loader) {
    return ClassUtils.isPresent(className, loader);
  }

  private static Class<?> classOrNull(String className, ClassLoader loader) {
    try {
      return ClassUtils.forName(className, loader);
    } catch (ClassNotFoundException | LinkageError e) {
      return Object.class;
    }
  }
}
