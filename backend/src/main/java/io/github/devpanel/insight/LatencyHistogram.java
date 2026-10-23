package io.github.devpanel.insight;

/**
 * Response times in logarithmic buckets (about 12% wide, 1 ms to 10 min), so percentiles of
 * millions of requests cost a fixed 120 counters. Not thread-safe; callers synchronize.
 */
public final class LatencyHistogram {

  private static final int BUCKETS = 120;
  private static final double GROWTH = 1.12;

  private final long[] counts = new long[BUCKETS];
  private long total;
  private long sumMs;
  private long maxMs;

  public void record(long ms) {
    counts[bucket(ms)]++;
    total++;
    sumMs += ms;
    maxMs = Math.max(maxMs, ms);
  }

  public void merge(LatencyHistogram other) {
    for (int i = 0; i < BUCKETS; i++) {
      counts[i] += other.counts[i];
    }
    total += other.total;
    sumMs += other.sumMs;
    maxMs = Math.max(maxMs, other.maxMs);
  }

  public long count() {
    return total;
  }

  public long maxMs() {
    return maxMs;
  }

  public long averageMs() {
    return total == 0 ? 0 : sumMs / total;
  }

  /** @param percentile 0..100; the upper edge of the bucket holding it */
  public long percentileMs(double percentile) {
    if (total == 0) {
      return 0;
    }
    long rank = (long) Math.ceil(total * percentile / 100.0);
    long seen = 0;
    for (int i = 0; i < BUCKETS; i++) {
      seen += counts[i];
      if (seen >= rank) {
        return Math.min(upperEdge(i), maxMs);
      }
    }
    return maxMs;
  }

  private static int bucket(long ms) {
    if (ms <= 1) {
      return 0;
    }
    return (int) Math.min(BUCKETS - 1, Math.ceil(Math.log(ms) / Math.log(GROWTH)));
  }

  private static long upperEdge(int bucket) {
    return Math.max(1, Math.round(Math.pow(GROWTH, bucket)));
  }
}
