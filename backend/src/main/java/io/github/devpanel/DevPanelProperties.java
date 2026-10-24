package io.github.devpanel;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything under {@code devpanel.*}. Only {@code devpanel.enabled=true} and
 * {@code devpanel.super-admin.initial-password} are needed; the rest has defaults.
 */
@ConfigurationProperties(prefix = "devpanel")
public class DevPanelProperties {

  /** Turns the whole panel on. Off by default, so adding the dependency alone changes nothing. */
  private boolean enabled;

  /** Where the panel is served; the API lives under {@code <base-path>/api}. */
  private String basePath = "/dev-panel";

  private final SuperAdmin superAdmin = new SuperAdmin();
  private final Session session = new Session();
  private final TwoFactor twoFactor = new TwoFactor();
  private final Metrics metrics = new Metrics();
  private final Query query = new Query();
  private final Files files = new Files();
  private final Audit audit = new Audit();
  private final Trace trace = new Trace();
  private final Insights insights = new Insights();
  private final Alerts alerts = new Alerts();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getBasePath() {
    return basePath;
  }

  /**
   * Must look like {@code /dev-panel}: the controllers read the same property through a
   * {@code @RequestMapping} placeholder, so it is checked here rather than silently normalized.
   */
  public void setBasePath(String basePath) {
    if (basePath == null || !basePath.matches("/[A-Za-z0-9._~-]+(/[A-Za-z0-9._~-]+)*")) {
      throw new IllegalArgumentException(
          "devpanel.base-path must start with '/' and not end with it, e.g. /dev-panel");
    }
    this.basePath = basePath;
  }

  public String getApiPath() {
    return basePath + "/api";
  }

  public SuperAdmin getSuperAdmin() {
    return superAdmin;
  }

  public Session getSession() {
    return session;
  }

  public TwoFactor getTwoFactor() {
    return twoFactor;
  }

  public Metrics getMetrics() {
    return metrics;
  }

  public Query getQuery() {
    return query;
  }

  public Files getFiles() {
    return files;
  }

  public Audit getAudit() {
    return audit;
  }

  public Trace getTrace() {
    return trace;
  }

  public Insights getInsights() {
    return insights;
  }

  public Alerts getAlerts() {
    return alerts;
  }

  public static class SuperAdmin {

    private String username = "superadmin";

    /**
     * Temporary password of the super admin the panel creates on first startup; it must be
     * changed on the first login. Empty means no super admin is created. Only read while there is
     * no super admin yet, so changing it later has no effect.
     */
    private String initialPassword;

    /**
     * For a super admin who lost the phone: on startup, makes the super admin set up the
     * authenticator app again at the next login. Remove it again once done.
     */
    private boolean resetTwoFactor;

    /**
     * When someone else took over the super admin: on startup, sets its password back to
     * {@code initial-password} (to be changed again) and resets its 2FA. Remove it once done.
     */
    private boolean reset;

    public boolean isReset() {
      return reset;
    }

    public void setReset(boolean reset) {
      this.reset = reset;
    }

    public boolean isResetTwoFactor() {
      return resetTwoFactor;
    }

    public void setResetTwoFactor(boolean resetTwoFactor) {
      this.resetTwoFactor = resetTwoFactor;
    }

    public String getUsername() {
      return username;
    }

    public void setUsername(String username) {
      this.username = username;
    }

    public String getInitialPassword() {
      return initialPassword;
    }

    public void setInitialPassword(String initialPassword) {
      this.initialPassword = initialPassword;
    }

    public boolean hasInitialPassword() {
      return initialPassword != null && !initialPassword.isBlank();
    }
  }

  public static class TwoFactor {

    /**
     * How far apart the server's and the phone's clocks may be. The default suits phone clocks;
     * raise it only while a server clock without time sync is being fixed, since a wider window
     * makes a guessed code slightly likelier to hit.
     */
    private Duration allowedClockDrift = Duration.ofSeconds(30);

    public Duration getAllowedClockDrift() {
      return allowedClockDrift;
    }

    public void setAllowedClockDrift(Duration allowedClockDrift) {
      if (allowedClockDrift.compareTo(Duration.ofMinutes(15)) > 0) {
        throw new IllegalArgumentException(
            "devpanel.two-factor.allowed-clock-drift must not exceed 15m; sync the server clock");
      }
      this.allowedClockDrift = allowedClockDrift;
    }
  }

  public static class Session {

    /** A login ends this long after it started, whatever happens. */
    private Duration timeout = Duration.ofHours(8);

    /** A login ends after this long without any request. */
    private Duration idleTimeout = Duration.ofMinutes(30);

    public Duration getTimeout() {
      return timeout;
    }

    public void setTimeout(Duration timeout) {
      this.timeout = timeout;
    }

    public Duration getIdleTimeout() {
      return idleTimeout;
    }

    public void setIdleTimeout(Duration idleTimeout) {
      this.idleTimeout = idleTimeout;
    }
  }

  public static class Metrics {

    /** How often a history sample (CPU, heap, pool, threads, disk) is taken. */
    private Duration sampleInterval = Duration.ofSeconds(15);

    /** How far back the in-memory history goes. */
    private Duration retention = Duration.ofHours(24);

    public Duration getSampleInterval() {
      return sampleInterval;
    }

    public void setSampleInterval(Duration sampleInterval) {
      this.sampleInterval = sampleInterval;
    }

    public Duration getRetention() {
      return retention;
    }

    public void setRetention(Duration retention) {
      this.retention = retention;
    }
  }

  public static class Query {

    /** The SQL console. Read-only SELECTs, run in a read-only transaction. */
    private boolean enabled = true;

    /**
     * Allows single {@code UPDATE ... WHERE} statements for read-write accounts. Off by default:
     * a library should not write to your data unless you ask for it.
     */
    private boolean updateEnabled;

    /** Rows returned to the browser; Excel export is not limited by this. */
    private int maxRows = 5000;

    /** An update touching more rows than this is rolled back, as a guard against a wrong WHERE. */
    private int maxUpdatedRows = 1000;

    private Duration timeout = Duration.ofSeconds(30);

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public boolean isUpdateEnabled() {
      return updateEnabled;
    }

    public void setUpdateEnabled(boolean updateEnabled) {
      this.updateEnabled = updateEnabled;
    }

    public int getMaxRows() {
      return maxRows;
    }

    public void setMaxRows(int maxRows) {
      this.maxRows = maxRows;
    }

    public int getMaxUpdatedRows() {
      return maxUpdatedRows;
    }

    public void setMaxUpdatedRows(int maxUpdatedRows) {
      this.maxUpdatedRows = maxUpdatedRows;
    }

    public Duration getTimeout() {
      return timeout;
    }

    public void setTimeout(Duration timeout) {
      this.timeout = timeout;
    }
  }

  public static class Files {

    /** Listing and downloading files under the two folders below. */
    private boolean enabled = true;

    private String docsPath = "docs";

    private String logsPath = "logs";

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public String getDocsPath() {
      return docsPath;
    }

    public void setDocsPath(String docsPath) {
      this.docsPath = docsPath;
    }

    public String getLogsPath() {
      return logsPath;
    }

    public void setLogsPath(String logsPath) {
      this.logsPath = logsPath;
    }
  }

  public static class Audit {

    /** Records every response with status >= 400 of the whole application. */
    private boolean enabled = true;

    /** Rows older than this are deleted once a day. */
    private Duration retention = Duration.ofDays(30);

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public Duration getRetention() {
      return retention;
    }

    public void setRetention(Duration retention) {
      this.retention = retention;
    }
  }

  public static class Trace {

    /**
     * Gives every request a trace id (MDC + response header). Turn it off when the application has
     * its own; the panel then reads the id from the same header and MDC key.
     */
    private boolean enabled = true;

    private String header = "X-Trace-Id";

    private String mdcKey = "traceId";

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public String getHeader() {
      return header;
    }

    public void setHeader(String header) {
      this.header = header;
    }

    public String getMdcKey() {
      return mdcKey;
    }

    public void setMdcKey(String mdcKey) {
      this.mdcKey = mdcKey;
    }
  }

  public static class Insights {

    /** The monitoring part: health page, API/error/database/job statistics, leak detection. */
    private boolean enabled = true;

    /** Requests slower than this are listed one by one. */
    private Duration slowRequest = Duration.ofSeconds(1);

    /**
     * Repeated SQL in one request that counts as N+1: the repeats beyond the first, over every
     * statement run 3+ times (e.g. 15 address loads + 10 caregiver loads = 23 wasted).
     */
    private int nPlusOneThreshold = 10;

    /**
     * Times each SQL statement and counts rows by wrapping the DataSource bean (for the request
     * waterfall and the DB/code time split). Turn off if code injects the concrete pool class.
     */
    private boolean sqlTracing = true;

    /** Path variables that name the organization/tenant, for usage per organization. */
    private List<String> tenantPathVariables =
        new ArrayList<>(List.of("organizationId", "orgId", "organization_id",
            "tenantId"));

    /**
     * Take the client address from X-Forwarded-For / X-Real-IP (behind nginx). Only safe when a
     * proxy sets the header; directly exposed, a client could fake it.
     */
    private boolean trustForwardedFor = true;

    /** Pings the database and external services once a minute, even without traffic. */
    private boolean dependencyChecks = true;

    /** Extra URLs to ping, name → URL, e.g. devpanel.insights.dependencies.MIS=https://... */
    private Map<String, String> dependencies = new LinkedHashMap<>();

    /** Requests per minute from one address that count as a flood. */
    private int floodPerMinute = 300;

    /** 401/403 answers to one address in 10 minutes that count as password guessing. */
    private int failedLoginsPer10Min = 20;

    /** Different missing pages (404) asked by one address in 10 minutes that count as a scan. */
    private int scanPathsPer10Min = 30;

    public boolean isSqlTracing() {
      return sqlTracing;
    }

    public void setSqlTracing(boolean sqlTracing) {
      this.sqlTracing = sqlTracing;
    }

    public List<String> getTenantPathVariables() {
      return tenantPathVariables;
    }

    public void setTenantPathVariables(List<String> tenantPathVariables) {
      this.tenantPathVariables = tenantPathVariables;
    }

    public boolean isTrustForwardedFor() {
      return trustForwardedFor;
    }

    public void setTrustForwardedFor(boolean trustForwardedFor) {
      this.trustForwardedFor = trustForwardedFor;
    }

    public boolean isDependencyChecks() {
      return dependencyChecks;
    }

    public void setDependencyChecks(boolean dependencyChecks) {
      this.dependencyChecks = dependencyChecks;
    }

    public Map<String, String> getDependencies() {
      return dependencies;
    }

    public void setDependencies(Map<String, String> dependencies) {
      this.dependencies = dependencies;
    }

    public int getFloodPerMinute() {
      return floodPerMinute;
    }

    public void setFloodPerMinute(int floodPerMinute) {
      this.floodPerMinute = floodPerMinute;
    }

    public int getFailedLoginsPer10Min() {
      return failedLoginsPer10Min;
    }

    public void setFailedLoginsPer10Min(int failedLoginsPer10Min) {
      this.failedLoginsPer10Min = failedLoginsPer10Min;
    }

    public int getScanPathsPer10Min() {
      return scanPathsPer10Min;
    }

    public void setScanPathsPer10Min(int scanPathsPer10Min) {
      this.scanPathsPer10Min = scanPathsPer10Min;
    }

    /**
     * Hikari reports a connection held longer than this, with the code that took it. Only set
     * when the application did not configure its own; 0 turns it off.
     */
    private Duration connectionLeakThreshold = Duration.ofSeconds(60);

    /** Counts runs of {@code @Scheduled} methods (needs AspectJ, as with Spring Data JPA). */
    private boolean jobs = true;

    /** Whose {@code Date} header the server clock is compared with; empty turns it off. */
    private String clockReferenceUrl = "https://www.google.com";

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public Duration getSlowRequest() {
      return slowRequest;
    }

    public void setSlowRequest(Duration slowRequest) {
      this.slowRequest = slowRequest;
    }

    public int getNPlusOneThreshold() {
      return nPlusOneThreshold;
    }

    public void setNPlusOneThreshold(int nPlusOneThreshold) {
      this.nPlusOneThreshold = nPlusOneThreshold;
    }

    public Duration getConnectionLeakThreshold() {
      return connectionLeakThreshold;
    }

    public void setConnectionLeakThreshold(Duration connectionLeakThreshold) {
      this.connectionLeakThreshold = connectionLeakThreshold;
    }

    public boolean isJobs() {
      return jobs;
    }

    public void setJobs(boolean jobs) {
      this.jobs = jobs;
    }

    public String getClockReferenceUrl() {
      return clockReferenceUrl;
    }

    public void setClockReferenceUrl(String clockReferenceUrl) {
      this.clockReferenceUrl = clockReferenceUrl;
    }
  }

  public static class Alerts {

    /** From @BotFather; the bot must be in the chat (or have been messaged by you). */
    private String telegramBotToken;

    /** Your user id or a group id (negative), e.g. from @userinfobot. */
    private String telegramChatId;

    /** Gets every alert as JSON (Slack/Teams/own service). */
    private String webhookUrl;

    /** Server local time of the daily summary; empty for none. */
    private String dailySummaryAt = "09:00";

    /** How messages name this application; defaults to spring.application.name @ host. */
    private String instanceName;

    public String getTelegramBotToken() {
      return telegramBotToken;
    }

    public void setTelegramBotToken(String telegramBotToken) {
      this.telegramBotToken = telegramBotToken;
    }

    public String getTelegramChatId() {
      return telegramChatId;
    }

    public void setTelegramChatId(String telegramChatId) {
      this.telegramChatId = telegramChatId;
    }

    public String getWebhookUrl() {
      return webhookUrl;
    }

    public void setWebhookUrl(String webhookUrl) {
      this.webhookUrl = webhookUrl;
    }

    public String getDailySummaryAt() {
      return dailySummaryAt;
    }

    public void setDailySummaryAt(String dailySummaryAt) {
      this.dailySummaryAt = dailySummaryAt;
    }

    public String getInstanceName() {
      return instanceName;
    }

    public void setInstanceName(String instanceName) {
      this.instanceName = instanceName;
    }
  }
}
