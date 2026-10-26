package io.github.devpanel.insight.risk;

import io.github.devpanel.insight.logs.LogPrivacy;
import io.github.devpanel.insight.risk.Risk.Category;
import io.github.devpanel.insight.risk.Risk.Severity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Personal or secret data written to the logs, one risk per logger: passwords and tokens are
 * critical (whoever reads the logs can log in), personal identifiers and contacts a warning.
 */
public class PrivacyDetector implements RiskDetector {

  private static final Set<String> SECRETS = Set.of("PASSWORD", "JWT", "BEARER_TOKEN", "CARD");
  private static final Set<String> PERSONAL = Set.of("PNFL");

  private final LogPrivacy privacy;

  public PrivacyDetector(LogPrivacy privacy) {
    this.privacy = privacy;
  }

  @Override
  public String name() {
    return "privacy";
  }

  /** Only a day without such log lines says the logging was really fixed. */
  @Override
  public boolean canResolve() {
    return RiskDetector.uptime().toHours() >= 24;
  }

  @Override
  public List<Risk> detect() {
    Map<String, List<LogPrivacy.Finding>> byLogger = new LinkedHashMap<>();
    for (LogPrivacy.Finding finding : privacy.findings()) {
      byLogger.computeIfAbsent(finding.logger(), key -> new ArrayList<>()).add(finding);
    }
    List<Risk> risks = new ArrayList<>();
    byLogger.forEach((logger, findings) -> {
      boolean secret = findings.stream().anyMatch(f -> SECRETS.contains(f.kind()));
      boolean personal = findings.stream().anyMatch(f -> PERSONAL.contains(f.kind()));
      long lines = findings.stream().mapToLong(LogPrivacy.Finding::count).sum();
      List<String> kinds = findings.stream().map(LogPrivacy.Finding::kind).toList();
      LogPrivacy.Finding example = findings.get(0);
      risks.add(new Risk("privacy.logs:" + logger, Category.WEAKNESS, "privacy",
          secret ? Severity.CRITICAL : personal ? Severity.WARNING : Severity.INFO,
          simpleName(logger) + " writes " + String.join(", ", kinds).toLowerCase()
              + " to the logs",
          secret ? "Anyone who can read the log files can use these credentials."
              : "Log files are read by many people, copied and kept for long: personal data in"
                  + " them leaks quietly and breaks data-protection rules.",
          lines + " lines since the start; example (masked): " + example.example(),
          "Log ids instead of personal data, or mask the values before logging (e.g. the last"
              + " 4 digits); never log request bodies or tokens as they are.",
          logger, null, null, "liveLogs", secret ? 2 : 1));
    });
    return risks;
  }

  private static String simpleName(String logger) {
    return logger.substring(logger.lastIndexOf('.') + 1);
  }
}
