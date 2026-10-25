package io.github.devpanel.insight.startup;

import io.github.devpanel.insight.trace.RequestRecorder;
import org.springframework.boot.context.metrics.buffering.BufferingApplicationStartup;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.metrics.ApplicationStartup;

/**
 * Starts recording the application's start before its context is refreshed: Spring's own
 * startup steps (how long each bean took to create, its {@code @PostConstruct} included) and every
 * SQL statement the start runs on the main thread. {@link StartupReport} reads both once the
 * application is ready. Does nothing unless {@code devpanel.enabled=true}.
 */
public class StartupRecorder
    implements ApplicationContextInitializer<ConfigurableApplicationContext>, Ordered {

  private static final int CAPACITY = 20_000;

  private static volatile Recording current;

  @Override
  public void initialize(ConfigurableApplicationContext context) {
    Environment environment = context.getEnvironment();
    if (!"true".equalsIgnoreCase(environment.getProperty("devpanel.enabled"))
        || "false".equalsIgnoreCase(environment.getProperty("devpanel.insights.enabled"))
        || "false".equalsIgnoreCase(environment.getProperty("devpanel.insights.startup-report"))
        || context.getParent() != null) {
      return;
    }
    BufferingApplicationStartup startup = null;
    if (context.getApplicationStartup() == ApplicationStartup.DEFAULT) {
      startup = new BufferingApplicationStartup(CAPACITY);
      context.setApplicationStartup(startup);
    } else if (context.getApplicationStartup() instanceof BufferingApplicationStartup own) {
      startup = own;
    }
    RequestRecorder recorder = RequestRecorder.current() == null ? RequestRecorder.start() : null;
    current = new Recording(System.currentTimeMillis(), Thread.currentThread(), startup,
        recorder);
  }

  /** The recording of this start, taken once; null when nothing was recorded. */
  static Recording take() {
    Recording recording = current;
    current = null;
    return recording;
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }

  /** {@code startup} is null when the application installed a startup recorder of its own. */
  record Recording(long contextStartedAt, Thread thread, BufferingApplicationStartup startup,
                   RequestRecorder recorder) {
  }
}
