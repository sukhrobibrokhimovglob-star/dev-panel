package io.github.devpanel.insight;

import java.util.List;

/**
 * A least-squares line through (time, value) points: how fast something grows and how sure we are
 * it really does (R², 0..1). The basis of "heap keeps growing", "disk full in N days".
 */
public record Trend(double slopePerHour, double r2, double lastValue, int points) {

  public static final Trend NONE = new Trend(0, 0, 0, 0);

  /** @param timesMillis and values have the same size */
  public static Trend of(List<Long> timesMillis, List<Double> values) {
    int n = values.size();
    if (n < 3) {
      return new Trend(0, 0, n == 0 ? 0 : values.get(n - 1), n);
    }
    double t0 = timesMillis.get(0);
    double sumX = 0;
    double sumY = 0;
    double sumXX = 0;
    double sumXY = 0;
    for (int i = 0; i < n; i++) {
      double x = (timesMillis.get(i) - t0) / 3_600_000.0;
      double y = values.get(i);
      sumX += x;
      sumY += y;
      sumXX += x * x;
      sumXY += x * y;
    }
    double denominator = n * sumXX - sumX * sumX;
    if (denominator == 0) {
      return new Trend(0, 0, values.get(n - 1), n);
    }
    double slope = (n * sumXY - sumX * sumY) / denominator;
    double intercept = (sumY - slope * sumX) / n;
    double meanY = sumY / n;
    double totalSquares = 0;
    double residualSquares = 0;
    for (int i = 0; i < n; i++) {
      double x = (timesMillis.get(i) - t0) / 3_600_000.0;
      double y = values.get(i);
      totalSquares += (y - meanY) * (y - meanY);
      residualSquares += (y - (intercept + slope * x)) * (y - (intercept + slope * x));
    }
    double r2 = totalSquares == 0 ? 0 : 1 - residualSquares / totalSquares;
    return new Trend(slope, Math.max(0, r2), values.get(n - 1), n);
  }

  /** Hours until the line reaches {@code limit}, or -1 when it does not grow towards it. */
  public double hoursUntil(double limit) {
    if (slopePerHour <= 0 || lastValue >= limit) {
      return slopePerHour <= 0 ? -1 : 0;
    }
    return (limit - lastValue) / slopePerHour;
  }
}
