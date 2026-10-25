package io.github.devpanel.insight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StatisticsTest {

  @Test
  void percentilesAreWithinOneBucket() {
    LatencyHistogram histogram = new LatencyHistogram();
    for (int ms = 1; ms <= 1000; ms++) {
      histogram.record(ms);
    }
    assertThat(histogram.count()).isEqualTo(1000);
    assertThat(histogram.percentileMs(50)).isBetween(500L, 560L);
    assertThat(histogram.percentileMs(95)).isBetween(950L, 1000L);
    assertThat(histogram.maxMs()).isEqualTo(1000);
  }

  @Test
  void histogramsSurviveStorageAndCountCallsWithinATarget() {
    LatencyHistogram histogram = new LatencyHistogram();
    for (int ms = 1; ms <= 1000; ms++) {
      histogram.record(ms);
    }
    LatencyHistogram stored = LatencyHistogram.parse(histogram.serialize());
    assertThat(stored.count()).isEqualTo(1000);
    assertThat(stored.maxMs()).isEqualTo(1000);
    assertThat(stored.percentileMs(95)).isEqualTo(histogram.percentileMs(95));
    // within one bucket (12%) of the exact 500
    assertThat(stored.countAtMost(500)).isBetween(450L, 560L);
    assertThat(LatencyHistogram.parse("garbage").count()).isZero();
  }

  @Test
  void trendFindsASteadyRiseAndWhenItHitsALimit() {
    List<Long> times = new ArrayList<>();
    List<Double> values = new ArrayList<>();
    for (int i = 0; i <= 12; i++) {
      times.add(i * 600_000L);
      values.add(100.0 + i * 5);
    }
    Trend trend = Trend.of(times, values);
    assertThat(trend.slopePerHour()).isCloseTo(30, within(0.001));
    assertThat(trend.r2()).isCloseTo(1, within(0.001));
    assertThat(trend.hoursUntil(250)).isCloseTo(3, within(0.001));
  }

  @Test
  void noisyFlatDataIsNotATrend() {
    List<Long> times = new ArrayList<>();
    List<Double> values = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      times.add(i * 600_000L);
      values.add(i % 2 == 0 ? 100.0 : 120.0);
    }
    assertThat(Trend.of(times, values).r2()).isLessThan(0.1);
  }

  @Test
  void minuteCounterKeepsOnlyTheRequestedWindow() {
    MinuteCounter counter = new MinuteCounter();
    long now = 1_700_000_000_000L;
    counter.add(now - 30 * 60_000, 5);
    counter.add(now - 2 * 60_000, 3);
    counter.add(now, 1);
    assertThat(counter.sum(now, 10)).isEqualTo(4);
    assertThat(counter.sum(now, 60)).isEqualTo(9);
    assertThat(counter.series(now, 3)).containsExactly(3, 0, 1);
  }
}
