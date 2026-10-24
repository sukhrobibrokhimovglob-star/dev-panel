package io.github.devpanel.insight;

/**
 * A small ring of counters, one per time slot (e.g. 60 one-minute slots, or 24 hourly ones).
 * Cheap enough to keep one per user or per IP address. Not thread-safe; callers synchronize.
 */
public final class SlotCounter {

  private final long slotMillis;
  private final long[] counts;
  private final long[] slots;

  public SlotCounter(int size, long slotMillis) {
    this.slotMillis = slotMillis;
    this.counts = new long[size];
    this.slots = new long[size];
  }

  public void add(long now, long amount) {
    long slot = now / slotMillis;
    int index = (int) (slot % counts.length);
    if (slots[index] != slot) {
      slots[index] = slot;
      counts[index] = 0;
    }
    counts[index] += amount;
  }

  /** Over the last {@code lastSlots} slots, the current one included. */
  public long sum(long now, int lastSlots) {
    long current = now / slotMillis;
    long sum = 0;
    for (int back = 0; back < Math.min(lastSlots, counts.length); back++) {
      long slot = current - back;
      int index = (int) (slot % counts.length);
      if (slots[index] == slot) {
        sum += counts[index];
      }
    }
    return sum;
  }
}
