package io.github.devpanel.insight.risk;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.devpanel.insight.risk.Risk.Category;
import io.github.devpanel.insight.risk.Risk.Severity;
import org.junit.jupiter.api.Test;

class RiskTest {

  @Test
  void soonAndCriticalComesFirst() {
    Risk diskInFiveDays = risk(Severity.CRITICAL, 5.0, 1);
    Risk missingIndex = risk(Severity.WARNING, null, 2);
    Risk note = risk(Severity.INFO, null, 1);
    assertThat(diskInFiveDays.score()).isGreaterThan(missingIndex.score());
    assertThat(missingIndex.score()).isGreaterThan(note.score());
    assertThat(Risk.bySoonness(3)).isEqualTo(Severity.CRITICAL);
    assertThat(Risk.bySoonness(40)).isEqualTo(Severity.WARNING);
    assertThat(Risk.bySoonness(200)).isEqualTo(Severity.INFO);
  }

  private static Risk risk(Severity severity, Double daysLeft, double weight) {
    return new Risk("test:" + severity, Category.FORECAST, "disk", severity, "t", "i", "e", "f",
        null, null, daysLeft, "metrics", weight);
  }
}
