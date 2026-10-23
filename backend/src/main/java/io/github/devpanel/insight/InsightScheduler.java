package io.github.devpanel.insight;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The panel's two background threads, behind a type of its own: a {@code ScheduledExecutorService}
 * bean would be taken by Spring as the application's {@code @Scheduled} executor and would also
 * switch off Boot's default task scheduler.
 */
public final class InsightScheduler {

  private final ScheduledExecutorService executor;

  public InsightScheduler() {
    AtomicInteger number = new AtomicInteger();
    this.executor = Executors.newScheduledThreadPool(2, runnable -> {
      Thread thread = new Thread(runnable, "devpanel-insights-" + number.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    });
  }

  public ScheduledExecutorService executor() {
    return executor;
  }
}
