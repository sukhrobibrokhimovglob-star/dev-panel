package io.github.devpanel.support;

import io.github.devpanel.DevPanelProperties;
import io.github.devpanel.account.DevAccountRepository;
import io.github.devpanel.account.DevAccountService;
import io.github.devpanel.account.DevRole;
import io.github.devpanel.audit.DevRequestAuditRepository;
import io.github.devpanel.metrics.DevMetricsHistory;
import io.github.devpanel.session.DevSessions;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.dao.DataAccessException;

/**
 * Startup and background work of the panel: creates its tables and the super admin before the web
 * server takes requests, then samples metrics and prunes the audit on a single daemon thread of
 * its own (the application does not need {@code @EnableScheduling}).
 */
public class DevPanelInitializer implements SmartInitializingSingleton, DisposableBean {

  private static final Logger log = LoggerFactory.getLogger(DevPanelInitializer.class);

  private final DevPanelProperties properties;
  private final DevPanelSchema schema;
  private final DevAccountRepository accountRepository;
  private final DevAccountService accountService;
  private final DevMetricsHistory metricsHistory;
  private final DevRequestAuditRepository auditRepository;
  private final DevSessions sessions;
  private ScheduledExecutorService scheduler;

  public DevPanelInitializer(DevPanelProperties properties, DevPanelSchema schema,
      DevAccountRepository accountRepository, DevAccountService accountService,
      DevMetricsHistory metricsHistory, DevRequestAuditRepository auditRepository,
      DevSessions sessions) {
    this.properties = properties;
    this.schema = schema;
    this.accountRepository = accountRepository;
    this.accountService = accountService;
    this.metricsHistory = metricsHistory;
    this.auditRepository = auditRepository;
    this.sessions = sessions;
  }

  @Override
  public void afterSingletonsInstantiated() {
    schema.create();
    createSuperAdmin();
    DevPanelProperties.SuperAdmin superAdmin = properties.getSuperAdmin();
    if (superAdmin.isReset() && superAdmin.hasInitialPassword()
        && accountService.resetSuperAdmin(superAdmin.getInitialPassword())) {
      log.warn("Dev panel super admin was reset to the initial password and must set up 2FA"
          + " again. Remove devpanel.super-admin.reset now.");
    } else if (superAdmin.isResetTwoFactor() && accountService.resetSuperAdminTwoFactor()) {
      log.warn("Dev panel super admin two-factor login was reset; it is set up again at the next"
          + " login. Remove devpanel.super-admin.reset-two-factor now.");
    }
    startScheduler();
    log.info("Dev panel is enabled at {}/", properties.getBasePath());
  }

  private void createSuperAdmin() {
    if (accountRepository.existsByRole(DevRole.SUPER_ADMIN)) {
      return;
    }
    DevPanelProperties.SuperAdmin config = properties.getSuperAdmin();
    if (!config.hasInitialPassword()) {
      log.warn("Dev panel has no super admin and devpanel.super-admin.initial-password is empty;"
          + " set it and restart to create '{}'", config.getUsername());
      return;
    }
    try {
      accountService.createSuperAdmin(config.getUsername(), config.getInitialPassword());
    } catch (DataAccessException e) {
      // Another instance started at the same time and won; the unique index allows only one.
      log.info("Dev panel super admin was created by another instance");
      return;
    }
    // The password itself is never logged
    log.info("Dev panel super admin '{}' created with the configured temporary password;"
        + " it must be changed on the first login", config.getUsername());
  }

  private void startScheduler() {
    scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
      Thread thread = new Thread(runnable, "devpanel-scheduler");
      thread.setDaemon(true);
      return thread;
    });
    long interval = Math.max(1, metricsHistory.interval().toSeconds());
    scheduler.scheduleAtFixedRate(metricsHistory::sample, 0, interval, TimeUnit.SECONDS);
    scheduler.scheduleAtFixedRate(this::pruneSessions, 5, 60, TimeUnit.MINUTES);
    if (properties.getAudit().isEnabled()) {
      scheduler.scheduleAtFixedRate(this::pruneAudit, 1, Duration.ofDays(1).toMinutes(),
          TimeUnit.MINUTES);
    }
  }

  private void pruneSessions() {
    try {
      sessions.deleteExpired();
    } catch (RuntimeException e) {
      log.warn("Dev panel session cleanup failed: {}", e.getMessage());
    }
  }

  private void pruneAudit() {
    try {
      int deleted = auditRepository.deleteOlderThan(
          LocalDateTime.now().minus(properties.getAudit().getRetention()));
      if (deleted > 0) {
        log.info("Dev panel removed {} audit rows older than {}", deleted,
            properties.getAudit().getRetention());
      }
    } catch (RuntimeException e) {
      log.warn("Dev panel audit cleanup failed: {}", e.getMessage());
    }
  }

  @Override
  public void destroy() {
    if (scheduler != null) {
      scheduler.shutdownNow();
    }
  }
}
