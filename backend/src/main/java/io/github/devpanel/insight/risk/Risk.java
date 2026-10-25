package io.github.devpanel.insight.risk;

/**
 * One thing that will become a problem, or a weak spot that hurts as data and traffic grow, said
 * in full: what will happen ({@code impact}), the numbers behind it ({@code evidence}), what to do
 * ({@code fix}, with {@code sql} when it is a statement to run) and where ({@code where}: a line of
 * code, a table, an endpoint).
 *
 * @param key      stable across runs, so the panel knows when it was first seen; starts with the
 *                 detector's name ({@code db.fk:...})
 * @param daysLeft for forecasts: days until the limit is reached, null otherwise
 * @param weight   how much it matters besides severity and urgency (traffic, size), around 1
 */
public record Risk(String key, Category category, String area, Severity severity, String title,
                   String impact, String evidence, String fix, String where, String sql,
                   Double daysLeft, String tab, double weight) {

  public enum Category {
    /** A limit that will be reached: disk, pool, heap, ids, certificates, slowing endpoints. */
    FORECAST,
    /** Fine today, a problem as data and traffic grow: indexes, N+1, duplicates, config. */
    WEAKNESS
  }

  public enum Severity {
    INFO, WARNING, CRITICAL;

    public int rank() {
      return ordinal();
    }
  }

  /**
   * Higher first: severity, how soon (forecasts) and the detector's weight together. A critical
   * risk due within a week tops everything; an informational one with no date sits at the end.
   */
  public double score() {
    double base = switch (severity) {
      case CRITICAL -> 100;
      case WARNING -> 40;
      case INFO -> 10;
    };
    double urgency = daysLeft == null ? 1
        : daysLeft <= 7 ? 3 : daysLeft <= 30 ? 2 : daysLeft <= 90 ? 1.3 : 1;
    return Math.round(base * urgency * Math.max(0.2, Math.min(5, weight)) * 10) / 10.0;
  }

  public Risk withSeverity(Severity newSeverity) {
    return new Risk(key, category, area, newSeverity, title, impact, evidence, fix, where, sql,
        daysLeft, tab, weight);
  }

  /** Severity of a forecast from the days left: within a week critical, a quarter warning. */
  public static Severity bySoonness(double days) {
    return days <= 7 ? Severity.CRITICAL : days <= 90 ? Severity.WARNING : Severity.INFO;
  }

  static String days(double days) {
    if (days < 1) {
      return "less than a day";
    }
    if (days < 60) {
      return Math.round(days) + " days";
    }
    return Math.round(days / 30) + " months";
  }
}
