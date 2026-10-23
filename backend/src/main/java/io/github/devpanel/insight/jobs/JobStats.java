package io.github.devpanel.insight.jobs;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.FixedRateTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.Task;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.ScheduledMethodRunnable;

/**
 * Every {@code @Scheduled} job: its schedule (from Spring), and its runs (from
 * {@link JobRunAspect}): last start, duration, success or failure. A job whose next run is well
 * overdue is reported as missed; jobs that die silently are among the hardest things to notice.
 */
public class JobStats {

  private final ObjectProvider<ScheduledTaskHolder> holders;
  private final Map<String, Runs> runs = new ConcurrentHashMap<>();
  private final Instant started = Instant.now();

  public JobStats(ObjectProvider<ScheduledTaskHolder> holders) {
    this.holders = holders;
  }

  void started(String job) {
    runs.computeIfAbsent(job, key -> new Runs()).start();
  }

  void finished(String job, long ms, Throwable error) {
    runs.computeIfAbsent(job, key -> new Runs()).finish(ms, error);
  }

  public List<JobView> list() {
    List<JobView> views = new ArrayList<>();
    Instant now = Instant.now();
    holders.orderedStream().forEach(holder -> {
      for (ScheduledTask scheduled : holder.getScheduledTasks()) {
        Task task = scheduled.getTask();
        if (!(task.getRunnable() instanceof ScheduledMethodRunnable method)) {
          continue;
        }
        String name = method.getMethod().getDeclaringClass().getSimpleName() + "."
            + method.getMethod().getName();
        views.add(view(name, task, runs.get(name), now));
      }
    });
    views.sort(Comparator.comparing(JobView::name));
    return views;
  }

  private JobView view(String name, Task task, Runs jobRuns, Instant now) {
    String schedule;
    Duration interval = null;
    CronExpression cron = null;
    if (task instanceof CronTask cronTask) {
      schedule = "cron " + cronTask.getExpression();
      try {
        cron = CronExpression.parse(cronTask.getExpression());
      } catch (IllegalArgumentException ignored) {
        // shown as is
      }
    } else if (task instanceof FixedRateTask rate) {
      schedule = "every " + Duration.ofMillis(rate.getInterval());
      interval = Duration.ofMillis(rate.getInterval());
    } else if (task instanceof FixedDelayTask delay) {
      schedule = "every " + Duration.ofMillis(delay.getInterval()) + " after the previous run";
      interval = Duration.ofMillis(delay.getInterval());
    } else {
      schedule = task.getClass().getSimpleName();
    }

    Instant lastStart = jobRuns == null ? null : jobRuns.lastStart;
    Instant from = lastStart != null ? lastStart : started;
    Instant expected = null;
    if (cron != null) {
      LocalDateTime next = cron.next(LocalDateTime.ofInstant(from, ZoneId.systemDefault()));
      expected = next == null ? null : next.atZone(ZoneId.systemDefault()).toInstant();
    } else if (interval != null) {
      long lastMs = jobRuns == null ? 0 : jobRuns.lastMs;
      expected = from.plus(interval).plusMillis(lastMs);
    }
    boolean running = jobRuns != null && jobRuns.running > 0;
    Duration grace = interval != null
        ? Duration.ofMillis(Math.max(60_000, interval.toMillis() / 10))
        : Duration.ofMinutes(2);
    boolean missed = !running && expected != null && now.isAfter(expected.plus(grace));
    return jobRuns == null
        ? new JobView(name, schedule, expected, null, 0, 0, 0, null, null, false, missed)
        : jobRuns.view(name, schedule, expected, running, missed);
  }

  private static final class Runs {

    int running;
    Instant lastStart;
    Instant lastFailure;
    String lastError;
    long lastMs;
    long count;
    long failures;
    long totalMs;

    synchronized void start() {
      running++;
      lastStart = Instant.now();
    }

    synchronized void finish(long ms, Throwable error) {
      running = Math.max(0, running - 1);
      lastMs = ms;
      count++;
      totalMs += ms;
      if (error != null) {
        failures++;
        lastFailure = Instant.now();
        lastError = error.getClass().getSimpleName() + ": " + error.getMessage();
      }
    }

    synchronized JobView view(String name, String schedule, Instant expected, boolean isRunning,
        boolean missed) {
      boolean lastFailed = lastFailure != null && lastStart != null
          && !lastFailure.isBefore(lastStart);
      return new JobView(name, schedule, expected, lastStart, lastMs, count, failures,
          lastFailed ? lastError : null, lastFailure, isRunning, missed);
    }
  }

  /** {@code lastError} is set only when the last run failed. */
  public record JobView(String name, String schedule, Instant nextExpected, Instant lastStart,
                        long lastMs, long runs, long failures, String lastError,
                        Instant lastFailure, boolean running, boolean missed) {
  }
}
