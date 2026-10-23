package io.github.devpanel.metrics;

import com.sun.management.OperatingSystemMXBean;
import io.github.devpanel.metrics.HikariPools.PoolState;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.List;

/**
 * Host and JVM resource usage, read straight from the JDK's own management beans and
 * {@link File} filesystem roots, plus the HikariCP pools when there are any.
 */
public class DevMetricsService {

  private final HikariPools hikariPools;

  /** @param hikariPools {@code null} when HikariCP is not on the classpath */
  public DevMetricsService(HikariPools hikariPools) {
    this.hikariPools = hikariPools;
  }

  public Snapshot snapshot() {
    return new Snapshot(cpu(), memory(), disks(), hikari(), threads());
  }

  public List<PoolState> hikari() {
    return hikariPools == null ? List.of() : hikariPools.states();
  }

  private static Cpu cpu() {
    OperatingSystemMXBean os = os();
    return new Cpu(os.getAvailableProcessors(), os.getProcessCpuLoad(), os.getCpuLoad(),
        os.getSystemLoadAverage());
  }

  private static Memory memory() {
    MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    OperatingSystemMXBean os = os();
    var heap = memory.getHeapMemoryUsage();
    return new Memory(heap.getUsed(), heap.getCommitted(), heap.getMax(),
        memory.getNonHeapMemoryUsage().getUsed(), os.getTotalMemorySize(),
        os.getFreeMemorySize());
  }

  public static List<Disk> disks() {
    return Arrays.stream(File.listRoots())
        .map(root -> new Disk(root.getAbsolutePath(), root.getTotalSpace(), root.getUsableSpace()))
        .toList();
  }

  static Threads threads() {
    ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    long[] deadlocked = threads.findDeadlockedThreads();
    return new Threads(threads.getThreadCount(), threads.getDaemonThreadCount(),
        threads.getPeakThreadCount(), deadlocked == null ? 0 : deadlocked.length);
  }

  public static OperatingSystemMXBean os() {
    return (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
  }

  public record Snapshot(Cpu cpu, Memory memory, List<Disk> disks, List<PoolState> hikari,
                         Threads threads) {
  }

  /** Loads are 0..1, negative when the platform does not report them. */
  public record Cpu(int availableProcessors, double processLoad, double systemLoad,
                    double systemLoadAverage) {
  }

  public record Memory(long heapUsed, long heapCommitted, long heapMax, long nonHeapUsed,
                       long systemTotal, long systemFree) {
  }

  public record Disk(String path, long totalSpace, long usableSpace) {
  }

  public record Threads(int live, int daemon, int peak, int deadlocked) {
  }
}
