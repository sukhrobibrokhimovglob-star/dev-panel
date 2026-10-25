package io.github.devpanel.insight.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import org.junit.jupiter.api.Test;

class ExplainServiceTest {

  @Test
  void inlinesTheRealValuesOutsideQuotes() {
    String sql = "select * from t where name = ? and note = 'why?' and at > ? and id in (?, ?)";
    assertThat(ExplainService.countPlaceholders(sql)).isEqualTo(4);
    assertThat(ExplainService.inline(sql, new Object[] {"O'Neil",
        Timestamp.valueOf("2026-05-08 00:00:00"), 1L, null}))
        .isEqualTo("select * from t where name = 'O''Neil' and note = 'why?' and at >"
            + " '2026-05-08 00:00:00.0' and id in (1, NULL)");
  }
}
