package io.github.devpanel.insight;

import io.github.devpanel.insight.alerts.AlertEngine;
import io.github.devpanel.insight.deps.DependencyChecks;
import io.github.devpanel.insight.health.ClockCheck;
import io.github.devpanel.insight.logs.ExceptionGroups;
import io.github.devpanel.insight.logs.LogbackCapture;
import io.github.devpanel.insight.memory.MemoryWatch;
import io.github.devpanel.insight.ops.DeployInfo;
import io.github.devpanel.insight.ops.LogLevels;
import io.github.devpanel.insight.requests.EndpointHistory;
import io.github.devpanel.metrics.HikariPools;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

/**
 * Starts the monitoring once the application is ready (log capture, GC listener, leak detection)
 * and runs its periodic work on the panel's own threads: alerts every minute, flushing statistics,
 * the clock check. Stops it all again on shutdown, flushing what was collected.
 */
public class InsightLifecycle implements ApplicationListener<ApplicationReadyEvent>,
    DisposableBean {

  private static final Logger log = LoggerFactory.getLogger(InsightLifecycle.class);

  private final ScheduledExecutorService scheduler;
  private final ExceptionGroups exceptions;
  private final EndpointHistory endpointHistory;
  private final AlertEngine alerts;
  private final ClockCheck clock;
  private final DeployInfo deploys;
  private final ObjectProvider<LogbackCapture> logback;
  private final ObjectProvider<MemoryWatch> memory;
  private final ObjectProvider<HikariPools> hikari;
  private final ObjectProvider<LogLevels> logLevels;
  private final ObjectProvider<DependencyChecks> dependencies;
  private final long leakThresholdMs;
  private final List<Runnable> onReady;
  private final List<Periodic> periodic;
  private volatile boolean started;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public InsightLifecycle(ScheduledExecutorService scheduler, ExceptionGroups exceptions,
      EndpointHistory endpointHistory, AlertEngine alerts, ClockCheck clock, DeployInfo deploys,
      ObjectProvider<LogbackCapture> logback, ObjectProvider<MemoryWatch> memory,
      ObjectProvider<HikariPools> hikari, ObjectProvider<LogLevels> logLevels,
      ObjectProvider<DependencyChecks> dependencies, long leakThresholdMs,
      List<Runnable> onReady, List<Periodic> periodic) {
    this.onReady = onReady;
    this.periodic = periodic;
    this.scheduler = scheduler;
    this.exceptions = exceptions;
    this.endpointHistory = endpointHistory;
    this.alerts = alerts;
    this.clock = clock;
    this.deploys = deploys;
    this.logback = logback;
    this.memory = memory;
    this.hikari = hikari;
    this.logLevels = logLevels;
    this.dependencies = dependencies;
    this.leakThresholdMs = leakThresholdMs;
  }

  @Override
  public void onApplicationEvent(ApplicationReadyEvent event) {
    if (started) {
      return;
    }
    started = true;
    LogbackCapture capture = logback.getIfAvailable();
    if (capture != null && !capture.install()) {
      log.info("Dev panel: logging backend is not Logback; live logs and error counts are off");
    }
    MemoryWatch watch = memory.getIfAvailable();
    if (watch != null) {
      watch.install();
    }
    HikariPools pools = hikari.getIfAvailable();
    if (pools != null) {
      pools.enableLeakDetection(leakThresholdMs);
    }
    deploys.record();
    for (Runnable task : onReady) {
      try {
        task.run();
      } catch (RuntimeException e) {
        log.debug("Dev panel start task failed", e);
      }
    }
    for (Periodic task : periodic) {
      scheduler.scheduleWithFixedDelay(() -> {
        try {
          task.task().run();
        } catch (RuntimeException e) {
          log.debug("Dev panel background task {} failed", task.name(), e);
        }
      }, task.firstAfterSeconds(), Math.max(1, task.everySeconds()), TimeUnit.SECONDS);
    }
    every(30, exceptions::flush);
    every(300, endpointHistory::flush);
    every(60, alerts::evaluate);
    every(600, clock::check);
    LogLevels levels = logLevels.getIfAvailable();
    if (levels != null) {
      every(30, levels::revertExpired);
    }
    DependencyChecks checks = dependencies.getIfAvailable();
    if (checks != null) {
      every(60, checks::checkAll);
    }
  }

  private void every(long seconds, Runnable task) {
    scheduler.scheduleWithFixedDelay(() -> {
      try {
        task.run();
      } catch (RuntimeException e) {
        log.debug("Dev panel background task failed", e);
      }
    }, Math.min(seconds, 20), seconds, TimeUnit.SECONDS);
  }

  @Override
  public void destroy() {
    try {
      exceptions.flush();
      endpointHistory.flush();
    } catch (RuntimeException e) {
      log.debug("Dev panel final flush failed", e);
    }
    LogbackCapture capture = logback.getIfAvailable();
    if (capture != null) {
      capture.uninstall();
    }
    MemoryWatch watch = memory.getIfAvailable();
    if (watch != null) {
      watch.uninstall();
    }
    scheduler.shutdownNow();
  }

  /** Work repeated on the panel's threads: first after {@code firstAfterSeconds}. */
  public record Periodic(String name, long firstAfterSeconds, long everySeconds, Runnable task) {
  }
}
