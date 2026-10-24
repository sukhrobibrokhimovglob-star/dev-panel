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
import io.github.devpanel.insight.logs.LogEvents;
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
@Import({InsightController.class, InsightsConfiguration.LogbackInsights.class,
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
      TraceStore devPanelTraceStore, DevPanelProperties properties) {
    FilterRegistrationBean<RequestInsightFilter> registration = new FilterRegistrationBean<>(
        new RequestInsightFilter(devPanelRequestStats, devPanelExceptionGroups,
            devPanelUsageStats, devPanelSecurityStats, devPanelTraceStore,
            properties.getBasePath(), properties.getInsights().isTrustForwardedFor()));
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
      DevPanelProperties properties) {
    return new InsightLifecycle(devPanelInsightScheduler.executor(), devPanelExceptionGroups,
        devPanelEndpointHistory, devPanelAlertEngine, devPanelClockCheck, devPanelDeployInfo,
        logback, memory, hikari, logLevels, dependencies,
        properties.getInsights().getConnectionLeakThreshold().toMillis());
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
    LogEvents devPanelLogEvents(ExceptionGroups devPanelExceptionGroups) {
      return new LogEvents(devPanelExceptionGroups);
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
