package io.github.devpanel.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.github.devpanel.support.DevPanelException;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DevQueryServiceValidationTest {

  private final DevQueryService service =
      new DevQueryService(mock(DataSource.class), Duration.ofSeconds(30), 100, 1000, true);

  @ParameterizedTest
  @ValueSource(strings = {
      "select id, updated_at, created_by from users",
      "SELECT * FROM todo_list",
      "(select 1) union (select 2)",
      "with recent as (select * from users) select * from recent",
      "select 1;"
  })
  void acceptsPlainSelects(String query) {
    assertThat(service.validate(query)).doesNotEndWith(";");
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "select 1; delete from users",
      "select * from users where id in (delete from users returning id)",
      "select * from users; update users set enabled = false",
      "with x as (delete from users returning *) select * from x",
      "select * from users for update",
      "delete from users",
      "select * from pg_catalog.pg_user",
      "select pg_sleep(100)",
      "   ",
      ";"
  })
  void rejectsAnythingButASingleSelect(String query) {
    assertThatThrownBy(() -> service.validate(query)).isInstanceOf(DevPanelException.class);
  }

  @Test
  void acceptsAnUpdateWithWhere() {
    assertThat(service.validateUpdate("update users set enabled = false where id = 5;"))
        .isEqualTo("update users set enabled = false where id = 5");
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "update users set enabled = false",
      "update users set enabled = false where id = 1 returning id",
      "update devpanel_account set role = 'SUPER_ADMIN' where id = 2",
      "update databasechangelog set md5sum = null where true",
      "update flyway_schema_history set checksum = 0 where true",
      "update users set a = 1 where id = 1; delete from users",
      "select 1"
  })
  void rejectsUnsafeUpdates(String query) {
    assertThatThrownBy(() -> service.validateUpdate(query))
        .isInstanceOf(DevPanelException.class);
  }

  @Test
  void updateIsRefusedWhenNotEnabled() {
    DevQueryService readOnly =
        new DevQueryService(mock(DataSource.class), Duration.ofSeconds(30), 100, 1000, false);
    assertThatThrownBy(() -> readOnly.update("update users set a = 1 where id = 1"))
        .isInstanceOf(DevPanelException.class)
        .hasMessageContaining("update-enabled");
  }
}
