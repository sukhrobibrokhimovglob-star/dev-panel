package io.github.devpanel.insight.risk;

import io.github.devpanel.insight.database.DatabaseAdvisor;
import io.github.devpanel.insight.database.DatabaseInsights;
import io.github.devpanel.insight.risk.Risk.Category;
import io.github.devpanel.insight.risk.Risk.Severity;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * Database weak spots from {@link DatabaseAdvisor} and the table statistics: missing indexes
 * (on foreign keys, and on the columns full-scanned tables are filtered by), possible duplicates,
 * cache candidates, statistics the planner lacks, bloat, large unused indexes.
 */
public class DatabaseDetector implements RiskDetector {

  private final DatabaseAdvisor advisor;
  private final DatabaseInsights database;
  private final Supplier<Collection<String>> recentSql;

  public DatabaseDetector(DatabaseAdvisor advisor, DatabaseInsights database,
      Supplier<Collection<String>> recentSql) {
    this.advisor = advisor;
    this.database = database;
    this.recentSql = recentSql;
  }

  @Override
  public String name() {
    return "db";
  }

  @Override
  public List<Risk> detect() {
    List<Risk> risks = new ArrayList<>();
    if (!advisor.available()) {
      return risks;
    }
    for (DatabaseAdvisor.ForeignKey key : advisor.foreignKeysWithoutIndex()) {
      if (key.rows() < 500) {
        continue;
      }
      risks.add(new Risk("db.fk:" + key.table() + "." + key.columns(), Category.WEAKNESS,
          "database", key.rows() >= 10_000 ? Severity.WARNING : Severity.INFO,
          key.table() + "." + key.columns() + " has no index",
          "It points to " + key.referenced() + ". Looking up the rows of one " + key.referenced()
              + " (and every delete of one) reads all of " + key.table()
              + "; it gets slower as the table grows.",
          key.table() + ": " + key.rows() + " rows, foreign key " + key.constraint() + ".",
          "Create the index (CONCURRENTLY does not lock the table).",
          key.table(), key.createIndex(), null, "database",
          Math.max(0.5, Math.min(3, Math.log10(key.rows()) / 2))));
    }
    for (DatabaseAdvisor.IndexSuggestion suggestion : advisor.indexSuggestions(recentSql)) {
      risks.add(new Risk("db.index:" + suggestion.table() + "." + suggestion.column(),
          Category.WEAKNESS, "database",
          suggestion.rows() >= 50_000 ? Severity.WARNING : Severity.INFO,
          suggestion.like() ? "Text search on " + suggestion.table() + "." + suggestion.column()
              + " reads the whole table"
              : suggestion.table() + " is filtered by " + suggestion.column()
                  + " without an index",
          "Each such query reads all " + suggestion.rows() + " rows; the time grows with the"
              + " table and adds up with every call.",
          "Full scans " + suggestion.seqScans() + ", index scans " + suggestion.indexScans()
              + ". Seen in: " + suggestion.exampleSql(),
          suggestion.like() ? "A trigram index makes LIKE '%text%' fast (needs pg_trgm)."
              : "Check the plan with Explain, then create the index.",
          suggestion.table(), suggestion.createIndex(), null, "database",
          Math.max(0.5, Math.min(3, Math.log10(suggestion.rows()) / 2))));
    }
    for (DatabaseAdvisor.Duplicates duplicates : advisor.duplicates()) {
      long repeated = duplicates.total() - duplicates.distinct();
      risks.add(new Risk("db.duplicates:" + duplicates.table() + "." + duplicates.column(),
          Category.WEAKNESS, "data",
          repeated * 2 >= duplicates.total() ? Severity.WARNING : Severity.INFO,
          duplicates.table() + "." + duplicates.column() + " holds repeated values",
          "Lists show the same item several times, references point to different copies, and"
              + " the table keeps growing if something inserts them again.",
          duplicates.total() + " rows but only " + duplicates.distinct() + " different values"
              + (duplicates.examples().isEmpty() ? "" : "; most repeated: "
                  + String.join(", ", duplicates.examples())) + ".",
          "Find what inserts them (often reference data loaded on every start), merge the"
              + " copies and add a unique index.", duplicates.table(),
          "SELECT lower(trim(" + duplicates.column() + ")), count(*) FROM " + duplicates.schema()
              + "." + duplicates.table() + " GROUP BY 1 HAVING count(*) > 1 ORDER BY 2 DESC;",
          null, "database", 1.5));
    }
    for (DatabaseAdvisor.CacheCandidate candidate : advisor.cacheCandidates()) {
      risks.add(new Risk("db.cache:" + candidate.table(), Category.WEAKNESS, "database",
          Severity.INFO, candidate.table() + " is read " + candidate.readsPerDay()
              + " times a day but almost never changes",
          "Every request that needs it goes to the database again for the same rows.",
          candidate.rows() + " rows; about " + candidate.readsPerDay() + " reads and "
              + candidate.writesPerDay() + " changes per day.",
          "Keep it in memory in the application (e.g. @Cacheable with a refresh, or load it once"
              + " at start).", candidate.table(), null, null, "database", 1));
    }
    for (DatabaseAdvisor.StaleStatistics stale : advisor.staleStatistics()) {
      risks.add(new Risk("db.stats:" + stale.table(), Category.WEAKNESS, "database",
          stale.lastAnalyzed() == null && stale.rows() >= 10_000 ? Severity.WARNING
              : Severity.INFO,
          "PostgreSQL has " + (stale.lastAnalyzed() == null ? "no" : "outdated")
              + " statistics for " + stale.table(),
          "The planner guesses how many rows the table has; with a wrong guess it picks slow"
              + " plans (full scans, wrong join order).",
          (stale.lastAnalyzed() == null ? "Never analyzed" : "Last analyzed "
              + stale.lastAnalyzed()) + "; " + stale.changedSinceAnalyze()
              + " rows changed since; " + (stale.bytes() / 1024 / 1024) + " MB.",
          "Run ANALYZE once; if it happens again, autovacuum may be off for this table.",
          stale.table(), "ANALYZE " + stale.schema() + "." + stale.table() + ";", null,
          "database", 1));
    }
    for (DatabaseInsights.TableStat table : database.tables()) {
      if (table.bloated() && !table.table().startsWith("devpanel_")) {
        risks.add(new Risk("db.bloat:" + table.table(), Category.WEAKNESS, "database",
            Severity.WARNING, table.table() + " is full of dead rows",
            "Vacuum falls behind: the table and its indexes take more space and every scan"
                + " reads the dead rows too.",
            table.deadRows() + " dead rows next to " + table.liveRows() + " live ones; last"
                + " vacuum " + (table.lastVacuum() == null ? "never" : table.lastVacuum()) + ".",
            "Run VACUUM (ANALYZE) on it; look for transactions left open for long (Database →"
                + " Running now), which keep vacuum from cleaning.", table.table(),
            "VACUUM (ANALYZE) " + table.schema() + "." + table.table() + ";", null, "database",
            1));
      }
    }
    for (DatabaseInsights.UnusedIndex index : database.unusedIndexes()) {
      if (index.bytes() < 10 * 1024 * 1024 || index.table().startsWith("devpanel_")) {
        continue;
      }
      risks.add(new Risk("db.unused-index:" + index.index(), Category.WEAKNESS, "database",
          Severity.INFO, "Index " + index.index() + " is never used",
          "It takes space and slows down every insert and update of " + index.table()
              + " for nothing.",
          (index.bytes() / 1024 / 1024) + " MB, no scans since the statistics were reset.",
          "If no rare report needs it, drop it.", index.table(),
          "DROP INDEX CONCURRENTLY " + index.schema() + "." + index.index() + ";", null,
          "database", 0.5));
    }
    return risks;
  }
}
