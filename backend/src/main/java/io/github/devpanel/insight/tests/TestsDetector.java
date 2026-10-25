package io.github.devpanel.insight.tests;

import io.github.devpanel.insight.risk.Risk;
import io.github.devpanel.insight.risk.Risk.Category;
import io.github.devpanel.insight.risk.Risk.Severity;
import io.github.devpanel.insight.risk.RiskDetector;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * From the test report: failing tests, busy endpoints without any test, and a report too old to
 * say much. Nothing without a report.
 */
public class TestsDetector implements RiskDetector {

  private final TestReports reports;

  public TestsDetector(TestReports reports) {
    this.reports = reports;
  }

  @Override
  public String name() {
    return "tests";
  }

  @Override
  public List<Risk> detect() {
    List<Risk> risks = new ArrayList<>();
    TestReports.Overview overview = reports.overview();
    if (overview.summary() == null) {
      return risks;
    }
    if (overview.summary().failed() > 0) {
      risks.add(new Risk("tests.failing", Category.WEAKNESS, "tests", Severity.WARNING,
          overview.summary().failed() + " test(s) failed in the last test report",
          "Code that the tests say is broken may already be deployed.",
          overview.failingTests().stream().limit(5).map(TestReports.TestResult::id)
              .collect(Collectors.joining(", ")),
          "Fix them before the next deploy (see the Tests tab).", null, null, null, "tests",
          1.5));
    }
    List<TestReports.EndpointCoverage> busyUntested = overview.list().stream()
        .filter(e -> e.testCount() == 0 && e.callsPerDay() >= 50).toList();
    if (!busyUntested.isEmpty()) {
      risks.add(new Risk("tests.untested", Category.WEAKNESS, "tests", Severity.INFO,
          busyUntested.size() + " busy endpoint(s) have no test",
          "A change that breaks them is noticed by users, not by the build.",
          busyUntested.stream().limit(5).map(e -> e.endpoint() + " (" + Math.round(
              e.callsPerDay()) + "/day)").collect(Collectors.joining(", ")),
          "Start with the top of the Tests tab: busiest and failing in production first.", null,
          null, null, "tests", 1));
    }
    java.time.Duration age = TestReports.age(overview.summary());
    if (Boolean.FALSE.equals(overview.summary().sameCommit())
        || (age != null && age.toDays() > 30)) {
      risks.add(new Risk("tests.outdated", Category.WEAKNESS, "tests", Severity.INFO,
          "The test report is outdated",
          "It describes older code; coverage shown may not match what runs.",
          "Report from " + overview.summary().generatedAt() + " (commit "
              + overview.summary().gitCommit() + "), deployed commit "
              + overview.summary().deployedCommit() + ".",
          "Run the tests (mvn verify) and commit or upload the new devpanel-tests.json.", null,
          null, null, "tests", 0.5));
    }
    return risks;
  }
}
