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

  /** Calls answered within {@code ms} (to the bucket's precision, about 12%). */
  public long countAtMost(long ms) {
    long within = 0;
    int last = bucket(ms);
    for (int i = 0; i <= last && i < BUCKETS; i++) {
      within += counts[i];
    }
    return within;
  }

  /** {@code index:count,...} of the non-empty buckets, then {@code |sum|max}; fits a VARCHAR. */
  public String serialize() {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < BUCKETS; i++) {
      if (counts[i] > 0) {
        if (text.length() > 0) {
          text.append(',');
        }
        text.append(i).append(':').append(counts[i]);
      }
    }
    return text.append('|').append(sumMs).append('|').append(maxMs).toString();
  }

  /** The reverse of {@link #serialize()}; an empty histogram for null or unreadable text. */
  public static LatencyHistogram parse(String text) {
    LatencyHistogram histogram = new LatencyHistogram();
    if (text == null || text.isBlank()) {
      return histogram;
    }
    try {
      String[] parts = text.split("\\|");
      if (!parts[0].isEmpty()) {
        for (String pair : parts[0].split(",")) {
          int colon = pair.indexOf(':');
          int index = Integer.parseInt(pair.substring(0, colon));
          long count = Long.parseLong(pair.substring(colon + 1));
          if (index >= 0 && index < BUCKETS) {
            histogram.counts[index] += count;
            histogram.total += count;
          }
        }
      }
      if (parts.length > 2) {
        histogram.sumMs = Long.parseLong(parts[1]);
        histogram.maxMs = Long.parseLong(parts[2]);
      }
    } catch (RuntimeException e) {
      return new LatencyHistogram();
    }
    return histogram;
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
