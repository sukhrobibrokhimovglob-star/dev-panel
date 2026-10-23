package io.github.devpanel.metrics;

import io.github.devpanel.DevPanelProperties;
import io.github.devpanel.metrics.DevJvmService.Jvm;
import io.github.devpanel.metrics.DevMetricsHistory.History;
import io.github.devpanel.metrics.DevMetricsService.Snapshot;
import io.github.devpanel.web.DevPanelApi;
import io.github.devpanel.web.DevPanelPaths;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** What the overview, metrics and JVM tabs show. Reading only, so any account may use it. */
@DevPanelApi
@RestController
@RequestMapping(DevPanelPaths.API)
public class DevMetricsController {

  private static final int MAX_HISTORY_MINUTES = 7 * 24 * 60;

  private final DevMetricsService metricsService;
  private final DevMetricsHistory history;
  private final DevJvmService jvmService;
  private final Environment environment;
  private final DevPanelProperties properties;

  public DevMetricsController(DevMetricsService metricsService, DevMetricsHistory history,
      DevJvmService jvmService, Environment environment, DevPanelProperties properties) {
    this.metricsService = metricsService;
    this.history = history;
    this.jvmService = jvmService;
    this.environment = environment;
    this.properties = properties;
  }

  @GetMapping("/info")
  public Info info() {
    String version = DevMetricsController.class.getPackage().getImplementationVersion();
    return new Info(environment.getProperty("spring.application.name", "application"),
        List.of(environment.getActiveProfiles()), System.getProperty("java.version"),
        version == null ? "dev" : version,
        Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime()),
        new Features(properties.getQuery().isEnabled(), properties.getQuery().isUpdateEnabled(),
            properties.getFiles().isEnabled(), properties.getAudit().isEnabled()));
  }

  @GetMapping("/metrics")
  public Snapshot metrics() {
    return metricsService.snapshot();
  }

  @GetMapping("/metrics/history")
  public History history(@RequestParam(defaultValue = "60") int minutes) {
    int clamped = Math.max(1, Math.min(minutes, MAX_HISTORY_MINUTES));
    return history.last(Duration.ofMinutes(clamped));
  }

  @GetMapping("/jvm")
  public Jvm jvm() {
    return jvmService.jvm();
  }

  @GetMapping(value = "/jvm/threads", produces = MediaType.TEXT_PLAIN_VALUE)
  public String threads() {
    return jvmService.threadDump();
  }

  public record Info(String application, List<String> profiles, String javaVersion,
                     String devPanelVersion, Instant startTime, Features features) {
  }

  /** Which optional sections are switched on, so the UI shows only those. */
  public record Features(boolean query, boolean queryUpdate, boolean files, boolean audit) {
  }
}
