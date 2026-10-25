package io.github.devpanel.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * Writes the test report the panel's Tests tab reads: every test with its result and duration,
 * and which endpoints each test called with which status ({@link TestHits}). JUnit Platform finds
 * this listener by itself ({@code META-INF/services}); nothing to configure.
 *
 * <p>The report goes to {@code target/devpanel-tests.json} (Maven) or
 * {@code build/devpanel-tests.json} (Gradle). {@code -Ddevpanel.tests.report=<path>} writes it
 * elsewhere, e.g. {@code src/main/resources/devpanel-tests.json} so it ships with the jar even
 * when the deploy build skips the tests; {@code -Ddevpanel.tests.report=false} turns it off.
 * It never fails a test run.
 */
public class DevPanelTestListener implements TestExecutionListener {

  static final String PROPERTY = "devpanel.tests.report";
  private static final int MAX_FAILURE = 1000;

  private final Map<String, Long> started = new ConcurrentHashMap<>();
  private final List<Map<String, Object>> results = new ArrayList<>();
  private volatile boolean anyTest;

  @Override
  public void testPlanExecutionStarted(TestPlan testPlan) {
    if (target() != null) {
      TestHits.start();
    }
  }

  @Override
  public void executionStarted(TestIdentifier identifier) {
    if (identifier.isTest()) {
      anyTest = true;
      started.put(identifier.getUniqueId(), System.currentTimeMillis());
      TestHits.currentTest(name(identifier));
    }
  }

  @Override
  public void executionSkipped(TestIdentifier identifier, String reason) {
    if (identifier.isTest()) {
      add(identifier, "SKIPPED", 0, reason);
    }
  }

  @Override
  public void executionFinished(TestIdentifier identifier, TestExecutionResult result) {
    if (!identifier.isTest()) {
      return;
    }
    Long start = started.remove(identifier.getUniqueId());
    String failure = result.getThrowable().map(error -> {
      String text = error.getClass().getSimpleName() + ": " + error.getMessage();
      return text.length() > MAX_FAILURE ? text.substring(0, MAX_FAILURE) : text;
    }).orElse(null);
    add(identifier, result.getStatus().name(),
        start == null ? 0 : System.currentTimeMillis() - start, failure);
    TestHits.currentTest(null);
  }

  @Override
  public void testPlanExecutionFinished(TestPlan testPlan) {
    Path target = target();
    TestHits.stop();
    if (target == null || !anyTest) {
      return;
    }
    try {
      Map<String, Object> report = new LinkedHashMap<>();
      report.put("format", 1);
      report.put("generatedAt", Instant.now().toString());
      Git git = Git.read(Path.of("").toAbsolutePath());
      report.put("gitCommit", git.commit());
      report.put("gitBranch", git.branch());
      synchronized (results) {
        report.put("tests", new ArrayList<>(results));
      }
      report.put("hits", TestHits.drain());
      if (target.getParent() != null) {
        Files.createDirectories(target.getParent());
      }
      Files.writeString(target, new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
          .writeValueAsString(report), StandardCharsets.UTF_8);
    } catch (IOException | RuntimeException | LinkageError e) {
      System.err.println("[dev-panel] Could not write the test report: " + e.getMessage());
    }
  }

  private void add(TestIdentifier identifier, String status, long ms, String failure) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", name(identifier));
    result.put("displayName", identifier.getDisplayName());
    result.put("status", status);
    result.put("durationMs", ms);
    result.put("failure", failure);
    synchronized (results) {
      results.add(result);
    }
  }

  /** {@code com.example.UserApiTest#createsUser}, or the display name for other engines. */
  static String name(TestIdentifier identifier) {
    if (identifier.getSource().orElse(null) instanceof MethodSource method) {
      String name = method.getClassName() + "#" + method.getMethodName();
      String display = identifier.getDisplayName();
      // Parameterized and repeated tests: keep the invocation apart
      return display.startsWith("[") ? name + " " + display : name;
    }
    return identifier.getDisplayName();
  }

  /** Where to write the report, or null when switched off or not in a build directory. */
  static Path target() {
    String configured = System.getProperty(PROPERTY, System.getenv("DEVPANEL_TESTS_REPORT"));
    if (configured != null && !configured.isBlank()) {
      return "false".equalsIgnoreCase(configured.trim()) ? null : Path.of(configured.trim());
    }
    if (Files.isDirectory(Path.of("target"))) {
      return Path.of("target", "devpanel-tests.json");
    }
    if (Files.isDirectory(Path.of("build"))) {
      return Path.of("build", "devpanel-tests.json");
    }
    return null;
  }

  /** The commit and branch from {@code .git}, without running git; nulls when unknown. */
  record Git(String commit, String branch) {

    static Git read(Path directory) {
      Path current = directory;
      while (current != null) {
        Path git = current.resolve(".git");
        if (Files.isDirectory(git)) {
          return of(git);
        }
        current = current.getParent();
      }
      return new Git(null, null);
    }

    private static Git of(Path git) {
      try {
        String head = Files.readString(git.resolve("HEAD")).trim();
        if (!head.startsWith("ref: ")) {
          return new Git(head, null);
        }
        String ref = head.substring(5).trim();
        String branch = ref.startsWith("refs/heads/") ? ref.substring(11) : ref;
        Path refFile = git.resolve(ref);
        if (Files.exists(refFile)) {
          return new Git(Files.readString(refFile).trim(), branch);
        }
        Path packed = git.resolve("packed-refs");
        if (Files.exists(packed)) {
          for (String line : Files.readAllLines(packed)) {
            if (line.endsWith(" " + ref)) {
              return new Git(line.substring(0, line.indexOf(' ')), branch);
            }
          }
        }
        return new Git(null, branch);
      } catch (IOException e) {
        return new Git(null, null);
      }
    }
  }
}
