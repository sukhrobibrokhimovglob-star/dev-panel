package io.github.devpanel.insight.database;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DatabaseAdvisorTest {

  @Test
  void findsTheColumnsAHibernateQueryFiltersATableBy() {
    String sql = "select icd10x0_.id as id1_9_, icd10x0_.code as code2_9_ from icd_10 icd10x0_"
        + " where lower(icd10x0_.name_ru) like ? or icd10x0_.code=? order by icd10x0_.id";
    assertThat(DatabaseAdvisor.filteredColumns(sql, "icd_10"))
        .containsEntry("name_ru", "like").containsEntry("code", "equals");
  }

  @Test
  void ignoresOtherTablesOfAJoin() {
    String sql = "select p.id from prescribed_medications p join accounting_units a on"
        + " a.id = p.accounting_unit_id where a.organization_id = $1 and p.status = $2";
    assertThat(DatabaseAdvisor.filteredColumns(sql, "prescribed_medications"))
        .containsOnlyKeys("status");
    assertThat(DatabaseAdvisor.filteredColumns(sql, "accounting_units"))
        .containsOnlyKeys("organization_id");
    assertThat(DatabaseAdvisor.filteredColumns(sql, "countries")).isEmpty();
  }

  @Test
  void unqualifiedColumnsBelongToTheOnlyTable() {
    assertThat(DatabaseAdvisor.filteredColumns(
        "select * from outcome_document_details where outcome_document_id in ($1, $2)",
        "outcome_document_details")).containsOnlyKeys("outcome_document_id");
  }
}
