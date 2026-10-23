package io.github.devpanel.insight.jobs;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

/** Records each run of a {@code @Scheduled} method into {@link JobStats}. */
@Aspect
public class JobRunAspect {

  private final JobStats stats;

  public JobRunAspect(JobStats stats) {
    this.stats = stats;
  }

  @Around("@annotation(org.springframework.scheduling.annotation.Scheduled)")
  public Object run(ProceedingJoinPoint point) throws Throwable {
    String job = point.getSignature().getDeclaringType().getSimpleName() + "."
        + point.getSignature().getName();
    long start = System.currentTimeMillis();
    stats.started(job);
    try {
      Object result = point.proceed();
      stats.finished(job, System.currentTimeMillis() - start, null);
      return result;
    } catch (Throwable e) {
      stats.finished(job, System.currentTimeMillis() - start, e);
      throw e;
    }
  }
}
