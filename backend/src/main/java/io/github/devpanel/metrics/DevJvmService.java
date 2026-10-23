package io.github.devpanel.metrics;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MonitorInfo;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Runtime facts, GC, memory pools and a full thread dump of the running JVM. */
public class DevJvmService {

  /** {@code -Dspring.datasource.password=...} and the like are shown without their value. */
  private static final Pattern SECRET_ARGUMENT =
      Pattern.compile("(?i)^(-D[^=]*(password|secret|token|credential|key)[^=]*=).+$");

  public Jvm jvm() {
    RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
    List<Gc> gc = ManagementFactory.getGarbageCollectorMXBeans().stream()
        .map(DevJvmService::gc)
        .toList();
    List<MemoryPool> pools = ManagementFactory.getMemoryPoolMXBeans().stream()
        .filter(MemoryPoolMXBean::isValid)
        .map(pool -> new MemoryPool(pool.getName(), pool.getType().name(),
            pool.getUsage().getUsed(), pool.getUsage().getMax()))
        .toList();
    Map<String, Integer> states = new TreeMap<>();
    for (Thread.State state : Thread.State.values()) {
      states.put(state.name(), 0);
    }
    ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    for (ThreadInfo info : threads.getThreadInfo(threads.getAllThreadIds())) {
      if (info != null) {
        states.merge(info.getThreadState().name(), 1, Integer::sum);
      }
    }
    return new Jvm(runtime.getPid(), System.getProperty("java.version"),
        runtime.getVmName() + " " + runtime.getVmVersion(),
        Instant.ofEpochMilli(runtime.getStartTime()), runtime.getUptime(),
        runtime.getInputArguments().stream().map(DevJvmService::maskSecret).toList(),
        ManagementFactory.getClassLoadingMXBean().getLoadedClassCount(), gc, pools, states);
  }

  /**
   * Like {@code jstack}: every thread with its full stack and locks. {@link ThreadInfo#toString()}
   * is not used because it cuts each stack after 8 frames.
   */
  public String threadDump() {
    ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    ThreadInfo[] infos = threads.dumpAllThreads(threads.isObjectMonitorUsageSupported(),
        threads.isSynchronizerUsageSupported());
    StringBuilder dump = new StringBuilder();
    long[] deadlocked = threads.findDeadlockedThreads();
    if (deadlocked != null) {
      dump.append("Deadlocked thread ids: ").append(Arrays.toString(deadlocked)).append("\n\n");
    }
    Arrays.stream(infos)
        .sorted(Comparator.comparing(ThreadInfo::getThreadName))
        .forEach(info -> append(dump, info));
    return dump.toString();
  }

  private static void append(StringBuilder dump, ThreadInfo info) {
    dump.append('"').append(info.getThreadName()).append('"')
        .append(" #").append(info.getThreadId())
        .append(info.isDaemon() ? " daemon" : "")
        .append(" prio=").append(info.getPriority())
        .append('\n')
        .append("   java.lang.Thread.State: ").append(info.getThreadState());
    if (info.getLockName() != null) {
      dump.append(" on ").append(info.getLockName());
    }
    if (info.getLockOwnerName() != null) {
      dump.append(" owned by \"").append(info.getLockOwnerName()).append("\" #")
          .append(info.getLockOwnerId());
    }
    dump.append('\n');
    StackTraceElement[] stack = info.getStackTrace();
    MonitorInfo[] monitors = info.getLockedMonitors();
    for (int depth = 0; depth < stack.length; depth++) {
      dump.append("\tat ").append(stack[depth]).append('\n');
      if (depth == 0 && info.getLockInfo() != null) {
        dump.append("\t- waiting on ").append(info.getLockInfo()).append('\n');
      }
      for (MonitorInfo monitor : monitors) {
        if (monitor.getLockedStackDepth() == depth) {
          dump.append("\t- locked ").append(monitor).append('\n');
        }
      }
    }
    LockInfo[] synchronizers = info.getLockedSynchronizers();
    if (synchronizers.length > 0) {
      dump.append("\n   Locked ownable synchronizers:\n");
      for (LockInfo lock : synchronizers) {
        dump.append("\t- ").append(lock).append('\n');
      }
    }
    dump.append('\n');
  }

  static String maskSecret(String argument) {
    return SECRET_ARGUMENT.matcher(argument).replaceFirst("$1******");
  }

  private static Gc gc(GarbageCollectorMXBean bean) {
    return new Gc(bean.getName(), Math.max(0, bean.getCollectionCount()),
        Math.max(0, bean.getCollectionTime()));
  }

  public record Jvm(long pid, String javaVersion, String vmName, Instant startTime,
                    long uptimeMs, List<String> inputArguments, int loadedClasses, List<Gc> gc,
                    List<MemoryPool> memoryPools, Map<String, Integer> threadStates) {
  }

  public record Gc(String name, long count, long timeMs) {
  }

  /** {@code max} is -1 when the pool has no limit. */
  public record MemoryPool(String name, String type, long used, long max) {
  }
}
