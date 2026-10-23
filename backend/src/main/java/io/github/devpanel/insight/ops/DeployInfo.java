package io.github.devpanel.insight.ops;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Which build is running: version and commit (from {@code META-INF/build-info.properties} and
 * {@code git.properties} when the build writes them), host, start time. Every start is recorded,
 * so the panel can list recent deploys and compare before and after.
 */
public class DeployInfo {

  private final JdbcTemplate jdbcTemplate;
  private final Current current;

  public DeployInfo(JdbcTemplate jdbcTemplate, String application) {
    this.jdbcTemplate = jdbcTemplate;
    Properties build = load("META-INF/build-info.properties");
    Properties git = load("git.properties");
    this.current = new Current(application,
        build.getProperty("build.version"),
        git.getProperty("git.commit.id.abbrev", git.getProperty("git.commit.id")),
        git.getProperty("git.branch"),
        build.getProperty("build.time"),
        host(), Instant.now());
  }

  public Current current() {
    return current;
  }

  public void record() {
    try {
      jdbcTemplate.update("INSERT INTO devpanel_deploy (started_at, host, version, git_commit)"
              + " VALUES (?, ?, ?, ?)", Timestamp.from(current.startedAt()), cut(current.host()),
          cut(current.version()), cut(current.commit()));
    } catch (DataAccessException ignored) {
      // only informative
    }
  }

  public List<Deploy> recent() {
    return jdbcTemplate.query("SELECT started_at, host, version, git_commit FROM devpanel_deploy"
            + " ORDER BY started_at DESC LIMIT 20",
        (rs, i) -> new Deploy(rs.getTimestamp("started_at").toInstant(), rs.getString("host"),
            rs.getString("version"), rs.getString("git_commit")));
  }

  public static String host() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (IOException e) {
      return "unknown";
    }
  }

  private static Properties load(String path) {
    Properties properties = new Properties();
    ClassPathResource resource = new ClassPathResource(path);
    if (resource.exists()) {
      try (InputStream in = resource.getInputStream()) {
        properties.load(in);
      } catch (IOException ignored) {
        // shown as unknown
      }
    }
    return properties;
  }

  private static String cut(String value) {
    return value == null || value.length() <= 100 ? value : value.substring(0, 100);
  }

  public record Current(String application, String version, String commit, String branch,
                        String buildTime, String host, Instant startedAt) {
  }

  public record Deploy(Instant startedAt, String host, String version, String commit) {
  }
}
