package io.github.devpanel.insight.risk;

import io.github.devpanel.insight.database.DatabaseInsights;
import io.github.devpanel.insight.risk.Risk.Category;
import io.github.devpanel.insight.risk.Risk.Severity;
import io.github.devpanel.insight.startup.StartupReport;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rows inserted on every start: reference data (countries, regions ...) loaded again each time
 * the application starts, so the table grows by the same amount with every deploy and fills with
 * copies. Seen by comparing what the last starts inserted.
 */
public class StartupDetector implements RiskDetector {

  private static final int STARTS = 5;

  private final StartupReport startup;
  private final DatabaseInsights database;

  public StartupDetector(StartupReport startup, DatabaseInsights database) {
    this.startup = startup;
    this.database = database;
  }

  @Override
  public String name() {
    return "startup";
  }

  @Override
  public List<Risk> detect() {
    List<Risk> risks = new ArrayList<>();
    List<Map<String, Integer>> starts = startup.insertsOfRecentStarts(STARTS);
    if (starts.size() < 2) {
      return risks;
    }
    Map<String, Integer> seen = new HashMap<>();
    Map<String, Integer> latest = new HashMap<>();
    for (Map<String, Integer> start : starts) {
      start.forEach((table, rows) -> {
        if (rows > 0) {
          seen.merge(table, 1, Integer::sum);
          latest.putIfAbsent(table, rows);
        }
      });
    }
    Map<String, Long> liveRows = new HashMap<>();
    if (database.isPostgres()) {
      try {
        database.tables().forEach(table -> liveRows.put(table.table(), table.liveRows()));
      } catch (RuntimeException ignored) {
        // without row counts
      }
    }
    seen.forEach((table, times) -> {
      if (times < 2) {
        return;
      }
      int rows = latest.get(table);
      String bare = table.contains(".") ? table.substring(table.lastIndexOf('.') + 1) : table;
      Long now = liveRows.get(bare);
      boolean piling = now != null && now >= (long) rows * times;
      risks.add(new Risk("startup.inserts:" + table, Category.WEAKNESS, "data",
          piling ? Severity.WARNING : Severity.INFO,
          "Every start inserts rows into " + table,
          "The table grows by about " + rows + " rows with each deploy or restart; if the rows"
              + " are the same reference data each time, it fills with copies and every list"
              + " built from it shows duplicates.",
          rows + " rows inserted at the last start; inserts at " + times + " of the last "
              + starts.size() + " starts" + (now == null ? "." : "; the table now holds " + now
              + " rows."),
          "Find the start-up code that inserts them (a @PostConstruct or CommandLineRunner"
              + " seeding data; see the Startup tab) and make it insert only missing rows.",
          table, null, null, "startup", 1.5));
    });
    return risks;
  }
}
