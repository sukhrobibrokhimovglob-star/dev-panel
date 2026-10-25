package io.github.devpanel.insight;

import io.github.devpanel.DevPanelProperties;
import io.github.devpanel.insight.alerts.AlertEngine;
import io.github.devpanel.insight.alerts.Notifier;
import io.github.devpanel.insight.database.DatabaseInsights;
import io.github.devpanel.insight.deploy.DeployReport;
import io.github.devpanel.insight.deps.DependencyChecks;
import io.github.devpanel.insight.security.SecurityStats;
import io.github.devpanel.insight.server.TomcatCapacity;
import io.github.devpanel.insight.trace.JdbcTracing;
import io.github.devpanel.insight.trace.TraceStore;
import io.github.devpanel.insight.usage.EndpointCatalog;
import io.github.devpanel.insight.usage.UsageInterceptor;
import io.github.devpanel.insight.usage.UsageStats;
import io.github.devpanel.insight.health.ClockCheck;
import io.github.devpanel.insight.health.HealthService;
import io.github.devpanel.insight.jobs.JobRunAspect;
import io.github.devpanel.insight.jobs.JobStats;
import io.github.devpanel.insight.logs.ExceptionGroups;
import io.github.devpanel.insight.logs.LiveLogs;
import io.github.devpanel.account.DevAccountRepository;
import io.github.devpanel.insight.InsightLifecycle.Periodic;
import io.github.devpanel.insight.database.DatabaseAdvisor;
import io.github.devpanel.insight.database.ExplainService;
import io.github.devpanel.insight.history.DailySnapshots;
import io.github.devpanel.insight.logs.LogEvents;
import io.github.devpanel.insight.logs.LogPrivacy;
import io.github.devpanel.insight.profiler.Profiler;
import io.github.devpanel.insight.report.WeeklyReport;
import io.github.devpanel.insight.risk.CertificateChecks;
import io.github.devpanel.insight.risk.CodeDetector;
import io.github.devpanel.insight.risk.ConfigDetector;
import io.github.devpanel.insight.risk.DatabaseDetector;
import io.github.devpanel.insight.risk.ForecastDetector;
import io.github.devpanel.insight.risk.PrivacyDetector;
import io.github.devpanel.insight.risk.RiskDetector;
import io.github.devpanel.insight.risk.RiskEngine;
import io.github.devpanel.insight.risk.RiskStore;
import io.github.devpanel.insight.risk.StartupDetector;
import io.github.devpanel.insight.runner.ApiRunner;
import io.github.devpanel.insight.slo.SloService;
import io.github.devpanel.insight.smoke.SmokeChecks;
import io.github.devpanel.insight.tests.TestReports;
import io.github.devpanel.insight.tests.TestsDetector;
import io.github.devpanel.insight.trace.CallSites;
import io.github.devpanel.insight.trace.RequestRecorder;
import io.github.devpanel.audit.DevActionLog;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import io.github.devpanel.insight.startup.StartupReport;
import io.github.devpanel.support.Sensitive;
import io.github.devpanel.insight.logs.LogbackCapture;
import io.github.devpanel.insight.memory.MemoryWatch;
import io.github.devpanel.insight.ops.ConfigViewer;
import io.github.devpanel.insight.ops.DeployInfo;
import io.github.devpanel.insight.ops.LogLevels;
import io.github.devpanel.insight.outbound.FeignOutboundCapability;
import io.github.devpanel.insight.outbound.OutboundStats;
import io.github.devpanel.insight.outbound.RestTemplateOutbound;
import io.github.devpanel.insight.requests.EndpointHistory;
import io.github.devpanel.insight.requests.ExceptionCaptureResolver;
import io.github.devpanel.insight.requests.RequestInsightFilter;
import io.github.devpanel.insight.requests.RequestStats;
import io.github.devpanel.insight.ops.DeployInfo.Current;
import io.github.devpanel.metrics.DevMetricsHistory;
import io.github.devpanel.metrics.DevMetricsService;
import io.github.devpanel.metrics.HikariPools;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The monitoring part of the panel ({@code devpanel.insights.enabled}, on by default). Every
 * piece that needs a library (Logback, Hibernate, Feign, AspectJ) is created only when the
 * application has it; nothing here is a bean type the application would look up itself.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "devpanel.insights", name = "enabled", havingValue = "true",
    matchIfMissing = true)
@Import({InsightController.class, InsightToolsController.class,
    InsightsConfiguration.LogbackInsights.class,
    InsightsConfiguration.MemoryInsights.class, InsightsConfiguration.FeignInsights.class,
    InsightsConfiguration.JobInsights.class, InsightsConfiguration.TomcatInsights.class,
    InsightsConfiguration.TracingInsights.class, InsightsConfiguration.DependencyInsights.class})
public class InsightsConfiguration {

  /** After the trace and audit filters, so the trace id is known. */
  private static final int REQUEST_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 13;

  @Bean
  InsightScheduler devPanelInsightScheduler() {
    return new InsightScheduler();
  }

  @Bean
  Sensitive devPanelSensitive(DevPanelProperties properties) {
    return new Sensitive(properties.getInsights().getSensitivePatterns());
  }

  @Bean
  StartupReport devPanelStartupReport(DataSource dataSource, DeployInfo devPanelDeployInfo) {
    return new StartupReport(new JdbcTemplate(dataSource), devPanelDeployInfo);
  }

  @Bean
  ExceptionGroups devPanelExceptionGroups(DataSource dataSource) {
    return new ExceptionGroups(new JdbcTemplate(dataSource));
  }

  @Bean
  RequestStats devPanelRequestStats(DevPanelProperties properties) {
    return new RequestStats(properties.getInsights().getSlowRequest().toMillis(),
        properties.getInsights().getNPlusOneThreshold());
  }

  @Bean
  DeployInfo devPanelDeployInfo(DataSource dataSource, ConfigurableEnvironment environment) {
    return new DeployInfo(new JdbcTemplate(dataSource),
        environment.getProperty("spring.application.name", "application"));
  }

  @Bean
  EndpointHistory devPanelEndpointHistory(DataSource dataSource, RequestStats devPanelRequestStats,
      DeployInfo devPanelDeployInfo) {
    return new EndpointHistory(new JdbcTemplate(dataSource), devPanelRequestStats,
        devPanelDeployInfo.current().host());
  }

  @Bean
  TraceStore devPanelTraceStore() {
    return new TraceStore();
  }

  @Bean
  UsageStats devPanelUsageStats() {
    return new UsageStats();
  }

  @Bean
  SecurityStats devPanelSecurityStats(DevPanelProperties properties) {
    DevPanelProperties.Insights insights = properties.getInsights();
    return new SecurityStats(insights.getFloodPerMinute(), insights.getFailedLoginsPer10Min(),
        insights.getScanPathsPer10Min());
  }

  @Bean
  EndpointCatalog devPanelEndpointCatalog(ApplicationContext context,
      DevPanelProperties properties) {
    return new EndpointCatalog(context, properties.getBasePath());
  }

  @Bean
  DeployReport devPanelDeployReport(RequestStats devPanelRequestStats,
      EndpointHistory devPanelEndpointHistory, ExceptionGroups devPanelExceptionGroups) {
    return new DeployReport(devPanelRequestStats, devPanelEndpointHistory,
        devPanelExceptionGroups);
  }

  /** Lets the recorder know the user and organization of each request (inside Spring MVC). */
  @Bean
  WebMvcConfigurer devPanelUsageInterceptor(DevPanelProperties properties) {
    UsageInterceptor interceptor =
        new UsageInterceptor(properties.getInsights().getTenantPathVariables());
    return new WebMvcConfigurer() {
      @Override
      public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor);
      }
    };
  }

  @Bean
  FilterRegistrationBean<RequestInsightFilter> devPanelRequestInsightFilter(
      RequestStats devPanelRequestStats, ExceptionGroups devPanelExceptionGroups,
      UsageStats devPanelUsageStats, SecurityStats devPanelSecurityStats,
      TraceStore devPanelTraceStore, Sensitive devPanelSensitive,
      DevPanelProperties properties) {
    FilterRegistrationBean<RequestInsightFilter> registration = new FilterRegistrationBean<>(
        new RequestInsightFilter(devPanelRequestStats, devPanelExceptionGroups,
            devPanelUsageStats, devPanelSecurityStats, devPanelTraceStore,
            properties.getBasePath(), properties.getInsights().isTrustForwardedFor(),
            devPanelSensitive));
    registration.setOrder(REQUEST_FILTER_ORDER);
    registration.setName("devPanelRequestInsightFilter");
    return registration;
  }

  @Bean
  ExceptionCaptureResolver devPanelExceptionCaptureResolver() {
    return new ExceptionCaptureResolver();
  }

  @Bean
  DatabaseInsights devPanelDatabaseInsights(DataSource dataSource) {
    return new DatabaseInsights(dataSource);
  }

  @Bean
  OutboundStats devPanelOutboundStats() {
    return new OutboundStats();
  }

  /** Static: a bean post-processor must not wait for this configuration's other beans. */
  @Bean
  static RestTemplateOutbound devPanelRestTemplateOutbound(ObjectProvider<OutboundStats> stats) {
    return new RestTemplateOutbound(stats);
  }

  @Bean
  ClockCheck devPanelClockCheck(DevPanelProperties properties) {
    return new ClockCheck(properties.getInsights().getClockReferenceUrl());
  }

  @Bean
  @SuppressWarnings("checkstyle:ParameterNumber")
  HealthService devPanelHealthService(DevMetricsService devPanelMetricsService,
      DevMetricsHistory devPanelMetricsHistory, ObjectProvider<MemoryWatch> memory,
      ObjectProvider<LogEvents> logs, ExceptionGroups devPanelExceptionGroups,
      RequestStats devPanelRequestStats, EndpointHistory devPanelEndpointHistory,
      DatabaseInsights devPanelDatabaseInsights, OutboundStats devPanelOutboundStats,
      ObjectProvider<JobStats> jobs, ClockCheck devPanelClockCheck,
      ObjectProvider<TomcatCapacity> tomcat, SecurityStats devPanelSecurityStats,
      ObjectProvider<DependencyChecks> dependencies) {
    return new HealthService(devPanelMetricsService, devPanelMetricsHistory,
        memory.getIfAvailable(), logs.getIfAvailable(), devPanelExceptionGroups,
        devPanelRequestStats, devPanelEndpointHistory, devPanelDatabaseInsights,
        devPanelOutboundStats, jobs.getIfAvailable(), devPanelClockCheck,
        tomcat.getIfAvailable(), devPanelSecurityStats, dependencies.getIfAvailable());
  }

  @Bean
  DailySnapshots devPanelDailySnapshots(DataSource dataSource,
      DevMetricsService devPanelMetricsService, DatabaseInsights devPanelDatabaseInsights,
      ObjectProvider<MemoryWatch> memory, ObjectProvider<TomcatCapacity> tomcat,
      DeployInfo devPanelDeployInfo) {
    return new DailySnapshots(new JdbcTemplate(dataSource), devPanelMetricsService,
        devPanelDatabaseInsights, memory.getIfAvailable(), tomcat.getIfAvailable(),
        devPanelDeployInfo.current().host());
  }

  @Bean
  DatabaseAdvisor devPanelDatabaseAdvisor(DataSource dataSource,
      DatabaseInsights devPanelDatabaseInsights) {
    return new DatabaseAdvisor(dataSource, devPanelDatabaseInsights);
  }

  @Bean
  ExplainService devPanelExplainService(DataSource dataSource,
      DatabaseInsights devPanelDatabaseInsights) {
    return new ExplainService(dataSource, devPanelDatabaseInsights);
  }

  @Bean
  SloService devPanelSloService(DataSource dataSource, EndpointHistory devPanelEndpointHistory,
      RequestStats devPanelRequestStats) {
    return new SloService(new JdbcTemplate(dataSource), devPanelEndpointHistory,
        devPanelRequestStats);
  }

  @Bean
  CertificateChecks devPanelCertificateChecks(OutboundStats devPanelOutboundStats,
      ObjectProvider<DependencyChecks> dependencies) {
    return new CertificateChecks(devPanelOutboundStats, dependencies.getIfAvailable());
  }

  @Bean
  TestReports devPanelTestReports(DataSource dataSource, EndpointCatalog devPanelEndpointCatalog,
      EndpointHistory devPanelEndpointHistory, DeployInfo devPanelDeployInfo) {
    return new TestReports(new JdbcTemplate(dataSource), devPanelEndpointCatalog,
        devPanelEndpointHistory, devPanelDeployInfo);
  }

  @Bean
  ApiRunner devPanelApiRunner(ApplicationContext context, ConfigurableEnvironment environment,
      DevPanelProperties properties) {
    return context instanceof ServletWebServerApplicationContext web
        && properties.getInsights().isApiRunner()
        ? new ApiRunner(web, environment, properties.getBasePath()) : null;
  }

  @Bean
  SmokeChecks devPanelSmokeChecks(DataSource dataSource, ObjectProvider<ApiRunner> runner,
      ConfigurableEnvironment environment) {
    ApiRunner api = runner.getIfAvailable();
    return api == null ? null : new SmokeChecks(new JdbcTemplate(dataSource), api, environment);
  }

  @Bean
  Profiler devPanelProfiler(DevPanelProperties properties) {
    return properties.getInsights().isProfiler() ? new Profiler() : null;
  }

  @Bean
  RiskEngine devPanelRiskEngine(DataSource dataSource, EndpointHistory devPanelEndpointHistory,
      DailySnapshots devPanelDailySnapshots, ObjectProvider<MemoryWatch> memory,
      DatabaseInsights devPanelDatabaseInsights, DatabaseAdvisor devPanelDatabaseAdvisor,
      CertificateChecks devPanelCertificateChecks, SloService devPanelSloService,
      RequestStats devPanelRequestStats, TraceStore devPanelTraceStore,
      ConfigurableEnvironment environment, ApplicationContext context,
      ObjectProvider<LogLevels> logLevels, DevMetricsService devPanelMetricsService,
      ObjectProvider<TomcatCapacity> tomcat, DevAccountRepository devPanelAccountRepository,
      ObjectProvider<AlertEngine> alerts, ClockCheck devPanelClockCheck,
      StartupReport devPanelStartupReport, ObjectProvider<LogPrivacy> privacy,
      TestReports devPanelTestReports, DevPanelProperties properties) {
    List<RiskDetector> detectors = new ArrayList<>();
    detectors.add(new ForecastDetector(devPanelEndpointHistory, devPanelDailySnapshots,
        memory.getIfAvailable(), devPanelDatabaseInsights, devPanelDatabaseAdvisor,
        devPanelCertificateChecks, devPanelSloService,
        properties.getInsights().getSlowRequest().toMillis()));
    detectors.add(new DatabaseDetector(devPanelDatabaseAdvisor, devPanelDatabaseInsights,
        () -> recentSql(devPanelTraceStore, devPanelRequestStats)));
    detectors.add(new CodeDetector(devPanelRequestStats, devPanelTraceStore, environment,
        properties.getInsights().getNPlusOneThreshold()));
    detectors.add(new StartupDetector(devPanelStartupReport, devPanelDatabaseInsights));
    detectors.add(new ConfigDetector(environment, context, logLevels.getIfAvailable(),
        devPanelMetricsService, tomcat.getIfAvailable(), devPanelDailySnapshots,
        devPanelAccountRepository, properties, alerts::getIfAvailable, devPanelClockCheck));
    LogPrivacy logPrivacy = privacy.getIfAvailable();
    if (logPrivacy != null && properties.getInsights().isLogPrivacy()) {
      detectors.add(new PrivacyDetector(logPrivacy));
    }
    detectors.add(new TestsDetector(devPanelTestReports));
    return new RiskEngine(detectors, new RiskStore(new JdbcTemplate(dataSource)));
  }

  /** The SQL texts of recently traced requests, for index suggestions. */
  static Collection<String> recentSql(TraceStore traces, RequestStats requests) {
    Set<String> statements = new LinkedHashSet<>();
    for (TraceStore.TraceSummary summary : traces.list()) {
      traces.find(summary.id()).ifPresent(trace -> {
        for (RequestRecorder.Span span : trace.spans()) {
          if ("SQL".equals(span.getKind()) && statements.size() < 3000) {
            statements.add(span.getText());
          }
        }
      });
    }
    requests.nPlusOneSuspects().forEach(suspect -> suspect.repeated()
        .forEach(repeated -> statements.add(repeated.statement())));
    return statements;
  }

  @Bean
  WeeklyReport devPanelWeeklyReport(HealthService devPanelHealthService,
      RiskEngine devPanelRiskEngine, SloService devPanelSloService,
      EndpointHistory devPanelEndpointHistory, ExceptionGroups devPanelExceptionGroups,
      DeployInfo devPanelDeployInfo, ObjectProvider<SmokeChecks> smoke) {
    return new WeeklyReport(devPanelHealthService, devPanelRiskEngine, devPanelSloService,
        devPanelEndpointHistory, devPanelExceptionGroups, devPanelDeployInfo,
        smoke.getIfAvailable());
  }

  @Bean
  AlertEngine devPanelAlertEngine(HealthService devPanelHealthService,
      RequestStats devPanelRequestStats, ExceptionGroups devPanelExceptionGroups,
      DeployInfo devPanelDeployInfo, DevPanelProperties properties) {
    DevPanelProperties.Alerts alerts = properties.getAlerts();
    Current current = devPanelDeployInfo.current();
    String instance = alerts.getInstanceName() != null && !alerts.getInstanceName().isBlank()
        ? alerts.getInstanceName()
        : current.application() + " @ " + current.host();
    return new AlertEngine(devPanelHealthService,
        new Notifier(alerts.getTelegramBotToken(), alerts.getTelegramChatId(),
            alerts.getWebhookUrl()),
        devPanelRequestStats, devPanelExceptionGroups, instance,
        summaryTime(alerts.getDailySummaryAt()));
  }

  @Bean
  LogLevels devPanelLogLevels(ObjectProvider<LoggingSystem> loggingSystem) {
    LoggingSystem system = loggingSystem.getIfAvailable(
        () -> LoggingSystem.get(InsightsConfiguration.class.getClassLoader()));
    return new LogLevels(system);
  }

  @Bean
  ConfigViewer devPanelConfigViewer(ConfigurableEnvironment environment) {
    return new ConfigViewer(environment);
  }

  @Bean
  @SuppressWarnings("checkstyle:ParameterNumber")
  InsightLifecycle devPanelInsightLifecycle(InsightScheduler devPanelInsightScheduler,
      ExceptionGroups devPanelExceptionGroups, EndpointHistory devPanelEndpointHistory,
      AlertEngine devPanelAlertEngine, ClockCheck devPanelClockCheck,
      DeployInfo devPanelDeployInfo, ObjectProvider<LogbackCapture> logback,
      ObjectProvider<MemoryWatch> memory, ObjectProvider<HikariPools> hikari,
      ObjectProvider<LogLevels> logLevels, ObjectProvider<DependencyChecks> dependencies,
      StartupReport devPanelStartupReport, DailySnapshots devPanelDailySnapshots,
      RiskEngine devPanelRiskEngine, ObjectProvider<SmokeChecks> smoke,
      HealthService devPanelHealthService, DeployReport devPanelDeployReport,
      DevActionLog devPanelActionLog, ApplicationContext context, DevPanelProperties properties) {
    DevPanelProperties.Insights insights = properties.getInsights();
    SmokeChecks smokeChecks = smoke.getIfAvailable();
    List<Runnable> onReady = new ArrayList<>();
    onReady.add(() -> CallSites.applicationPackages(applicationPackages(context)));
    onReady.add(devPanelStartupReport::finish);
    onReady.add(() -> {
      devPanelHealthService.useSmokeChecks(smokeChecks);
      devPanelAlertEngine.useRisks(devPanelRiskEngine, devPanelDeployReport,
          digestDay(properties.getAlerts().getWeeklyDigestDay()),
          properties.getAlerts().isRiskAlerts());
    });
    List<Periodic> periodic = new ArrayList<>();
    periodic.add(new Periodic("daily-sample", 30, 60, devPanelDailySnapshots::sample));
    periodic.add(new Periodic("daily-record", 90, 600, devPanelDailySnapshots::record));
    long riskEvery = Math.max(60, insights.getRiskInterval().toSeconds());
    periodic.add(new Periodic("risks", 150, riskEvery, devPanelRiskEngine::run));
    periodic.add(new Periodic("action-log-cleanup", 600, 86_400, devPanelActionLog::deleteOld));
    if (smokeChecks != null) {
      long smokeEvery = Math.max(60, insights.getSmokeInterval().toSeconds());
      periodic.add(new Periodic("smoke", 120, smokeEvery,
          () -> smokeChecks.runAll("SCHEDULE", null)));
    }
    return new InsightLifecycle(devPanelInsightScheduler.executor(), devPanelExceptionGroups,
        devPanelEndpointHistory, devPanelAlertEngine, devPanelClockCheck, devPanelDeployInfo,
        logback, memory, hikari, logLevels, dependencies,
        insights.getConnectionLeakThreshold().toMillis(), onReady, periodic);
  }

  /** The packages of {@code @SpringBootApplication}, so code locations name the app's own code. */
  private static List<String> applicationPackages(ApplicationContext context) {
    try {
      return org.springframework.boot.autoconfigure.AutoConfigurationPackages
          .get(context.getAutowireCapableBeanFactory());
    } catch (IllegalStateException e) {
      return List.of();
    }
  }

  private static java.time.DayOfWeek digestDay(String day) {
    if (day == null || day.isBlank()) {
      return null;
    }
    try {
      return java.time.DayOfWeek.valueOf(day.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("devpanel.alerts.weekly-digest-day must be a day like"
          + " MONDAY, or empty");
    }
  }

  private static LocalTime summaryTime(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return LocalTime.parse(value.trim());
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("devpanel.alerts.daily-summary-at must look like 09:00");
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "ch.qos.logback.classic.LoggerContext")
  static class LogbackInsights {

    @Bean
    LogPrivacy devPanelLogPrivacy(Sensitive devPanelSensitive) {
      return new LogPrivacy(devPanelSensitive);
    }

    @Bean
    LogEvents devPanelLogEvents(ExceptionGroups devPanelExceptionGroups,
        LogPrivacy devPanelLogPrivacy, DevPanelProperties properties) {
      return new LogEvents(devPanelExceptionGroups,
          properties.getInsights().isLogPrivacy() ? devPanelLogPrivacy : null);
    }

    @Bean
    LogbackCapture devPanelLogbackCapture(LogEvents devPanelLogEvents,
        DevPanelProperties properties) {
      return new LogbackCapture(devPanelLogEvents, properties.getTrace().getMdcKey());
    }

    @Bean
    LiveLogs devPanelLiveLogs(LogEvents devPanelLogEvents,
        InsightScheduler devPanelInsightScheduler) {
      return new LiveLogs(devPanelLogEvents, devPanelInsightScheduler.executor());
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "com.sun.management.GarbageCollectionNotificationInfo")
  static class MemoryInsights {

    @Bean
    MemoryWatch devPanelMemoryWatch() {
      return new MemoryWatch();
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "feign.Capability")
  static class FeignInsights {

    @Bean
    FeignOutboundCapability devPanelFeignCapability(OutboundStats devPanelOutboundStats) {
      return new FeignOutboundCapability(devPanelOutboundStats);
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "org.springframework.boot.web.embedded.tomcat.TomcatWebServer")
  static class TomcatInsights {

    @Bean
    TomcatCapacity devPanelTomcatCapacity(ApplicationContext context) {
      return context instanceof ServletWebServerApplicationContext web
          ? new TomcatCapacity(web) : null;
    }
  }

  /** Static bean: a post-processor must exist before the DataSource it wraps is created. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(prefix = "devpanel.insights", name = "sql-tracing", havingValue = "true",
      matchIfMissing = true)
  static class TracingInsights {

    @Bean
    static JdbcTracing devPanelJdbcTracing() {
      return new JdbcTracing();
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(prefix = "devpanel.insights", name = "dependency-checks",
      havingValue = "true", matchIfMissing = true)
  static class DependencyInsights {

    @Bean
    DependencyChecks devPanelDependencyChecks(DataSource dataSource,
        OutboundStats devPanelOutboundStats, DevPanelProperties properties) {
      return new DependencyChecks(dataSource, devPanelOutboundStats,
          properties.getInsights().getDependencies());
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class JobInsights {

    @Bean
    JobStats devPanelJobStats(ObjectProvider<ScheduledTaskHolder> holders) {
      return new JobStats(holders);
    }

    @Bean
    @ConditionalOnClass(name = "org.aspectj.lang.annotation.Aspect")
    @ConditionalOnProperty(prefix = "devpanel.insights", name = "jobs", havingValue = "true",
        matchIfMissing = true)
    JobRunAspect devPanelJobRunAspect(JobStats devPanelJobStats) {
      return new JobRunAspect(devPanelJobStats);
    }
  }
}
