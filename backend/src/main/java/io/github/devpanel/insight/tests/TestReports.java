package io.github.devpanel.insight.tests;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devpanel.insight.ops.DeployInfo;
import io.github.devpanel.insight.requests.EndpointHistory;
import io.github.devpanel.insight.requests.EndpointHistory.Day;
import io.github.devpanel.insight.usage.EndpointCatalog;
import io.github.devpanel.support.DevPanelException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What the tests cover, next to what production uses: every endpoint with the tests that call it
 * and the statuses they check, its calls and errors in production, and how risky it is to leave
 * it untested (busy and failing first). Plus line coverage per package from JaCoCo.
 *
 * <p>The test report ({@code devpanel-tests.json}, written by the panel's JUnit listener) and the
 * JaCoCo CSV come either inside the jar ({@code src/main/resources/devpanel-tests.json},
 * {@code devpanel-coverage.csv}), which works when the deploy build skips the tests, or uploaded
 * on the Tests tab. The newer one wins.
 */
public class TestReports {

  private static final ObjectMapper JSON = new ObjectMapper()
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  private static final int MAX_UPLOAD = 30 * 1024 * 1024;
  private static final int KEEP_UPLOADS = 6;

  private final JdbcTemplate jdbcTemplate;
  private final EndpointCatalog catalog;
  private final EndpointHistory history;
  private final DeployInfo deploys;

  public TestReports(JdbcTemplate jdbcTemplate, EndpointCatalog catalog, EndpointHistory history,
      DeployInfo deploys) {
    this.jdbcTemplate = jdbcTemplate;
    this.catalog = catalog;
    this.history = history;
    this.deploys = deploys;
  }

  /** Stores an uploaded test report (JSON) or JaCoCo CSV; returns which one it was. */
  public String upload(String content, String by) {
    if (content == null || content.isBlank()) {
      throw DevPanelException.badRequest("The file is empty");
    }
    if (content.length() > MAX_UPLOAD) {
      throw DevPanelException.badRequest("The file is larger than 30 MB");
    }
    String trimmed = content.strip();
    String kind;
    if (trimmed.startsWith("{")) {
      Report report = parse(trimmed);
      if (report == null || report.tests() == null) {
        throw DevPanelException.badRequest("Not a dev panel test report (devpanel-tests.json)");
      }
      kind = "tests";
    } else if (trimmed.startsWith("GROUP,PACKAGE,CLASS")) {
      kind = "coverage";
    } else {
      throw DevPanelException.badRequest(
          "Upload devpanel-tests.json or JaCoCo's jacoco.csv (target/site/jacoco/jacoco.csv)");
    }
    jdbcTemplate.update("INSERT INTO devpanel_test_report (uploaded_at, uploaded_by, content)"
        + " VALUES (?, ?, ?)", Timestamp.from(Instant.now()), by, trimmed);
    List<Long> old = jdbcTemplate.queryForList("SELECT id FROM devpanel_test_report"
        + " ORDER BY uploaded_at DESC", Long.class);
    for (int i = KEEP_UPLOADS; i < old.size(); i++) {
      jdbcTemplate.update("DELETE FROM devpanel_test_report WHERE id = ?", old.get(i));
    }
    return kind;
  }

  public Overview overview() {
    Loaded tests = latestReport();
    Loaded coverageSource = latestCoverage();
    Report report = tests == null ? null : tests.report();

    Map<String, List<String>> testsByEndpoint = new HashMap<>();
    Map<String, Set<Integer>> statusesByEndpoint = new HashMap<>();
    Map<String, String> testStatus = new HashMap<>();
    if (report != null) {
      for (TestResult result : report.tests()) {
        testStatus.put(result.id(), result.status());
      }
      for (Hit hit : report.hits() == null ? List.<Hit>of() : report.hits()) {
        if (hit.test() != null) {
          List<String> list = testsByEndpoint.computeIfAbsent(hit.endpoint(),
              key -> new ArrayList<>());
          if (!list.contains(hit.test())) {
            list.add(hit.test());
          }
        }
        statusesByEndpoint.computeIfAbsent(hit.endpoint(), key -> new TreeSet<>())
            .add(hit.status());
      }
    }
    Map<String, long[]> production = new HashMap<>();
    int days = 14;
    for (Map.Entry<String, List<Day>> entry : history.daily(days).entrySet()) {
      long[] totals = new long[3];
      for (Day day : entry.getValue()) {
        totals[0] += day.calls();
        totals[1] += day.serverErrors();
        totals[2] += day.clientErrors();
      }
      production.put(entry.getKey(), totals);
    }

    List<EndpointCoverage> endpoints = new ArrayList<>();
    long allCalls = 0;
    long testedCalls = 0;
    int tested = 0;
    for (EndpointCatalog.Entry entry : catalog.all()) {
      List<String> testIds = testsByEndpoint.getOrDefault(entry.endpoint(), List.of());
      Set<Integer> statuses = statusesByEndpoint.getOrDefault(entry.endpoint(), Set.of());
      long[] prod = production.getOrDefault(entry.endpoint(), new long[3]);
      List<String> failing = testIds.stream()
          .filter(id -> "FAILED".equals(testStatus.get(id))).toList();
      List<String> untestedClasses = new ArrayList<>();
      if (prod[2] > 0 && statuses.stream().noneMatch(s -> s >= 400 && s < 500)) {
        untestedClasses.add("4xx");
      }
      if (prod[1] > 0 && statuses.stream().noneMatch(s -> s >= 500)) {
        untestedClasses.add("5xx");
      }
      double perDay = prod[0] / (double) days;
      double risk = 0;
      if (testIds.isEmpty()) {
        risk = Math.log10(prod[0] + 1) * 2 + (prod[1] > 0 ? 3 : 0) + (prod[2] > 0 ? 1 : 0);
      } else {
        risk = failing.isEmpty() ? untestedClasses.size() : 5 + failing.size();
      }
      allCalls += prod[0];
      if (!testIds.isEmpty()) {
        tested++;
        testedCalls += prod[0];
      }
      endpoints.add(new EndpointCoverage(entry.endpoint(), entry.handler(),
          testIds.subList(0, Math.min(10, testIds.size())), testIds.size(),
          new ArrayList<>(statuses), failing, Math.round(perDay * 10) / 10.0, prod[1], prod[2],
          untestedClasses, Math.round(risk * 10) / 10.0));
    }
    endpoints.sort(Comparator.comparingDouble(EndpointCoverage::risk).reversed()
        .thenComparing(EndpointCoverage::endpoint));

    Summary summary = null;
    if (report != null) {
      long passed = report.tests().stream().filter(t -> "SUCCESSFUL".equals(t.status())).count();
      long failed = report.tests().stream().filter(t -> "FAILED".equals(t.status())).count();
      long skipped = report.tests().stream()
          .filter(t -> "SKIPPED".equals(t.status()) || "ABORTED".equals(t.status())).count();
      String deployed = deploys.current().commit();
      Boolean sameCommit = deployed == null || report.gitCommit() == null ? null
          : report.gitCommit().startsWith(deployed) || deployed.startsWith(report.gitCommit());
      summary = new Summary(tests.source(), report.generatedAt(), report.gitCommit(),
          report.gitBranch(), deployed, sameCommit, report.tests().size(), passed, failed,
          skipped);
    }
    List<TestResult> failingTests = report == null ? List.of() : report.tests().stream()
        .filter(t -> "FAILED".equals(t.status())).limit(50).toList();
    return new Overview(summary, endpoints.size(), tested,
        endpoints.isEmpty() ? 0 : (double) tested / endpoints.size(),
        allCalls == 0 ? -1 : (double) testedCalls / allCalls, endpoints, failingTests,
        coverageSource == null ? null : Coverage.parse(coverageSource.content(),
            coverageSource.source(), coverageSource.at()));
  }

  private Loaded latestReport() {
    Loaded bundled = null;
    String classpath = read("devpanel-tests.json");
    if (classpath != null) {
      Report report = parse(classpath);
      if (report != null && report.tests() != null) {
        bundled = new Loaded("jar", report, classpath, instant(report.generatedAt()));
      }
    }
    Loaded uploaded = null;
    for (Upload row : uploads()) {
      if (row.content().startsWith("{")) {
        Report report = parse(row.content());
        if (report != null && report.tests() != null) {
          uploaded = new Loaded("uploaded by " + row.by(), report, row.content(),
              instant(report.generatedAt()));
          break;
        }
      }
    }
    return newer(bundled, uploaded);
  }

  private Loaded latestCoverage() {
    Loaded bundled = null;
    String classpath = read("devpanel-coverage.csv");
    if (classpath != null) {
      bundled = new Loaded("jar", null, classpath, Instant.EPOCH);
    }
    Loaded uploaded = null;
    for (Upload row : uploads()) {
      if (row.content().startsWith("GROUP,PACKAGE,CLASS")) {
        uploaded = new Loaded("uploaded by " + row.by(), null, row.content(), row.at());
        break;
      }
    }
    return newer(bundled, uploaded);
  }

  private List<Upload> uploads() {
    return jdbcTemplate.query("SELECT uploaded_at, uploaded_by, content FROM devpanel_test_report"
            + " ORDER BY uploaded_at DESC",
        (rs, i) -> new Upload(rs.getTimestamp("uploaded_at").toInstant(),
            rs.getString("uploaded_by"), rs.getString("content")));
  }

  private static Loaded newer(Loaded a, Loaded b) {
    if (a == null) {
      return b;
    }
    if (b == null) {
      return a;
    }
    return a.at().isAfter(b.at()) ? a : b;
  }

  private static String read(String path) {
    ClassPathResource resource = new ClassPathResource(path);
    if (!resource.exists()) {
      return null;
    }
    try (InputStream in = resource.getInputStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      return null;
    }
  }

  static Report parse(String json) {
    try {
      return JSON.readValue(json, Report.class);
    } catch (IOException e) {
      return null;
    }
  }

  private static Instant instant(String text) {
    try {
      return text == null ? Instant.EPOCH : Instant.parse(text);
    } catch (RuntimeException e) {
      return Instant.EPOCH;
    }
  }

  /** How old the report is, for the "outdated" warning. */
  public static Duration age(Summary summary) {
    return summary == null ? null : Duration.between(instant(summary.generatedAt()),
        Instant.now());
  }

  private record Loaded(String source, Report report, String content, Instant at) {
  }

  private record Upload(Instant at, String by, String content) {
  }

  public record Report(Integer format, String generatedAt, String gitCommit, String gitBranch,
                       List<TestResult> tests, List<Hit> hits) {
  }

  public record TestResult(String id, String displayName, String status, Long durationMs,
                           String failure) {
  }

  public record Hit(String test, String endpoint, int status, long count) {
  }

  /**
   * {@code sameCommit}: the report was made from the deployed commit (null when either is
   * unknown).
   */
  public record Summary(String source, String generatedAt, String gitCommit, String gitBranch,
                       String deployedCommit, Boolean sameCommit, int tests, long passed,
                       long failed, long skipped) {
  }

  /**
   * {@code statuses}: what tests got back; {@code untestedInProduction}: status classes production
   * returned that no test checks; {@code risk}: higher means write a test here first.
   */
  public record EndpointCoverage(String endpoint, String handler, List<String> tests,
                                 int testCount, List<Integer> statuses, List<String> failingTests,
                                 double callsPerDay, long serverErrors, long clientErrors,
                                 List<String> untestedInProduction, double risk) {
  }

  /** {@code trafficCovered}: share of production calls that went to tested endpoints. */
  public record Overview(Summary summary, int endpoints, int testedEndpoints,
                         double endpointsCovered, double trafficCovered,
                         List<EndpointCoverage> list, List<TestResult> failingTests,
                         Coverage coverage) {
  }

  /** Line and branch coverage from JaCoCo's CSV, per package. */
  public record Coverage(String source, Instant at, double lines, double branches,
                         List<PackageCoverage> packages, List<ClassCoverage> uncovered) {

    static Coverage parse(String csv, String source, Instant at) {
      Map<String, long[]> packages = new LinkedHashMap<>();
      List<ClassCoverage> uncovered = new ArrayList<>();
      long[] total = new long[4];
      String[] lines = csv.split("\\r?\\n");
      for (int i = 1; i < lines.length; i++) {
        String[] cells = lines[i].split(",");
        if (cells.length < 9) {
          continue;
        }
        try {
          long branchMissed = Long.parseLong(cells[5]);
          long branchCovered = Long.parseLong(cells[6]);
          long lineMissed = Long.parseLong(cells[7]);
          long lineCovered = Long.parseLong(cells[8]);
          long[] sums = packages.computeIfAbsent(cells[1], key -> new long[5]);
          sums[0] += lineMissed;
          sums[1] += lineCovered;
          sums[2] += branchMissed;
          sums[3] += branchCovered;
          sums[4]++;
          total[0] += lineMissed;
          total[1] += lineCovered;
          total[2] += branchMissed;
          total[3] += branchCovered;
          if (lineCovered == 0 && lineMissed >= 10) {
            uncovered.add(new ClassCoverage(cells[1] + "." + cells[2], lineMissed));
          }
        } catch (NumberFormatException ignored) {
          // header or broken line
        }
      }
      List<PackageCoverage> list = new ArrayList<>();
      packages.forEach((name, sums) -> list.add(new PackageCoverage(name,
          share(sums[1], sums[0]), share(sums[3], sums[2]), (int) sums[4], sums[0] + sums[1])));
      list.sort(Comparator.comparingDouble(PackageCoverage::lines));
      uncovered.sort(Comparator.comparingLong(ClassCoverage::lines).reversed());
      return new Coverage(source, at, share(total[1], total[0]), share(total[3], total[2]), list,
          uncovered.subList(0, Math.min(50, uncovered.size())));
    }

    private static double share(long covered, long missed) {
      return covered + missed == 0 ? -1 : (double) covered / (covered + missed);
    }
  }

  /** Shares 0..1, -1 without lines/branches. */
  public record PackageCoverage(String name, double lines, double branches, int classes,
                                long totalLines) {
  }

  public record ClassCoverage(String name, long lines) {
  }
}
