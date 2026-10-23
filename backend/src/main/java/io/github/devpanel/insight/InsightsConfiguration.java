package io.github.devpanel.insight;

import io.github.devpanel.DevPanelProperties;
import io.github.devpanel.insight.alerts.AlertEngine;
import io.github.devpanel.insight.alerts.Notifier;
import io.github.devpanel.insight.database.DatabaseInsights;
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
import io.github.devpanel.insight.requests.HibernateSqlCounting;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/**
 * The monitoring part of the panel ({@code devpanel.insights.enabled}, on by default). Every
 * piece that needs a library (Logback, Hibernate, Feign, AspectJ) is created only when the
 * application has it; nothing here is a bean type the application would look up itself.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "devpanel.insights", name = "enabled", havingValue = "true",
    matchIfMissing = true)
@Import({InsightController.class, InsightsConfiguration.LogbackInsights.class,
    InsightsConfiguration.MemoryInsights.class, InsightsConfiguration.HibernateInsights.class,
    InsightsConfiguration.FeignInsights.class, InsightsConfiguration.JobInsights.class})
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
  FilterRegistrationBean<RequestInsightFilter> devPanelRequestInsightFilter(
      RequestStats devPanelRequestStats, ExceptionGroups devPanelExceptionGroups,
      DevPanelProperties properties) {
    FilterRegistrationBean<RequestInsightFilter> registration = new FilterRegistrationBean<>(
        new RequestInsightFilter(devPanelRequestStats, devPanelExceptionGroups,
            properties.getBasePath()));
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

  @Bean
  RestTemplateOutbound devPanelRestTemplateOutbound(OutboundStats devPanelOutboundStats) {
    return new RestTemplateOutbound(devPanelOutboundStats);
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
      ObjectProvider<JobStats> jobs, ClockCheck devPanelClockCheck) {
    return new HealthService(devPanelMetricsService, devPanelMetricsHistory,
        memory.getIfAvailable(), logs.getIfAvailable(), devPanelExceptionGroups,
        devPanelRequestStats, devPanelEndpointHistory, devPanelDatabaseInsights,
        devPanelOutboundStats, jobs.getIfAvailable(), devPanelClockCheck);
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
      ObjectProvider<LogLevels> logLevels, DevPanelProperties properties) {
    return new InsightLifecycle(devPanelInsightScheduler.executor(), devPanelExceptionGroups,
        devPanelEndpointHistory, devPanelAlertEngine, devPanelClockCheck, devPanelDeployInfo,
        logback, memory, hikari, logLevels,
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
  @ConditionalOnClass(name = {"org.hibernate.resource.jdbc.spi.StatementInspector",
      "org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer"})
  static class HibernateInsights {

    @Bean
    HibernateSqlCounting devPanelHibernateSqlCounting() {
      return new HibernateSqlCounting();
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
