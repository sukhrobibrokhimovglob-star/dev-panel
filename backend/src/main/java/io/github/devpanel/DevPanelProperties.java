package io.github.devpanel;

import java.time.Duration;
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
  private final Metrics metrics = new Metrics();
  private final Query query = new Query();
  private final Files files = new Files();
  private final Audit audit = new Audit();
  private final Trace trace = new Trace();

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

  public static class SuperAdmin {

    private String username = "superadmin";

    /**
     * Temporary password of the super admin the panel creates on first startup; it must be
     * changed on the first login. Empty means no super admin is created. Only read while there is
     * no super admin yet, so changing it later has no effect.
     */
    private String initialPassword;

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
}
