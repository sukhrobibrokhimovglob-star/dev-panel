package io.github.devpanel.insight.trace;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.devpanel.insight.trace.RequestRecorder.Kind;
import org.junit.jupiter.api.Test;

class RequestRecorderTest {

  @Test
  void tellsLookupsFromWritesAndIdFetches() {
    assertThat(RequestRecorder.kind("select a.id from accounting_units a where a.id=?"))
        .isEqualTo(Kind.SELECT);
    assertThat(RequestRecorder.kind("insert into balance_log (a, b) values (?, ?)"))
        .isEqualTo(Kind.INSERT);
    assertThat(RequestRecorder.kind("select nextval ('balance_log_seq')"))
        .isEqualTo(Kind.SEQUENCE);
    assertThat(RequestRecorder.kind("(select 1) union (select 2)")).isEqualTo(Kind.SELECT);
  }

  @Test
  void onlyRepeatedLookupsCountAsNPlusOne() {
    RequestRecorder recorder = new RequestRecorder();
    for (int i = 0; i < 9; i++) {
      recorder.sql("insert into outcome_document_details (id) values (?)", 0, 1000, null,
          new Object[] {i});
      recorder.sql("select nextval ('seq')", 0, 1000, null);
    }
    for (int i = 0; i < 18; i++) {
      recorder.sql("select * from accounting_units where id = ?", 0, 1000, null,
          new Object[] {i % 9});
    }
    assertThat(recorder.wastedQueries()).isEqualTo(17);
    assertThat(recorder.writeRepeats()).isEqualTo(8);
    assertThat(recorder.sequenceCalls()).isEqualTo(9);
    // 18 lookups of 9 different ids: each one loaded twice
    assertThat(recorder.exactRepeats()).isEqualTo(9);
  }
}
