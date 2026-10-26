package io.github.devpanel.insight.risk;

import java.util.List;

/**
 * Finds one family of risks. Runs on the panel's own thread every few minutes; slow sources
 * cache their results. Throwing means "could not tell": its earlier findings stay open.
 */
public interface RiskDetector {

  /** The prefix of all keys this detector produces, e.g. {@code db}. */
  String name();

  List<Risk> detect();

  /**
   * Whether a risk this detector no longer reports may count as resolved. Detectors that read
   * in-memory data (requests, log lines since the start) cannot tell right after a restart: the
   * risk is only absent because nothing has been seen yet.
   */
  default boolean canResolve() {
    return true;
  }

  /** How long the JVM has been running. */
  static java.time.Duration uptime() {
    return java.time.Duration.ofMillis(
        java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime());
  }
}
