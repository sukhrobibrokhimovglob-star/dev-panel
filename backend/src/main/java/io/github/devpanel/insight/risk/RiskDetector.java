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
}
