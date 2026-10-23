package io.github.devpanel.insight;

/** Counts per minute for the last 24 hours, in a ring of 1,440 slots. Thread-safe. */
public final class MinuteCounter {

  private static final int SLOTS = 1440;

  private final long[] counts = new long[SLOTS];
  private final long[] minutes = new long[SLOTS];

  public synchronized void add(long nowMillis, long amount) {
    long minute = nowMillis / 60_000;
    int slot = (int) (minute % SLOTS);
    if (minutes[slot] != minute) {
      minutes[slot] = minute;
      counts[slot] = 0;
    }
    counts[slot] += amount;
  }

  /** Sum over the last {@code lastMinutes} minutes, the current one included. */
  public synchronized long sum(long nowMillis, int lastMinutes) {
    long current = nowMillis / 60_000;
    long sum = 0;
    for (int back = 0; back < Math.min(lastMinutes, SLOTS); back++) {
      long minute = current - back;
      int slot = (int) (minute % SLOTS);
      if (minutes[slot] == minute) {
        sum += counts[slot];
      }
    }
    return sum;
  }

  /** One value per minute, oldest first, for charts. */
  public synchronized long[] series(long nowMillis, int lastMinutes) {
    int size = Math.min(lastMinutes, SLOTS);
    long[] series = new long[size];
    long current = nowMillis / 60_000;
    for (int i = 0; i < size; i++) {
      long minute = current - (size - 1 - i);
      int slot = (int) (minute % SLOTS);
      series[i] = minutes[slot] == minute ? counts[slot] : 0;
    }
    return series;
  }
}
