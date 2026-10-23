package io.github.devpanel.insight.health;

import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Compares the server's clock with the {@code Date} header of a well-known web server (1-second
 * precision, enough to catch the minutes-off clocks that break 2FA codes, token expiry and log
 * times). Without internet access, or with no reference URL configured, the offset stays unknown.
 */
public class ClockCheck {

  private final String referenceUrl;
  private volatile Long offsetMs;
  private volatile Instant checkedAt;
  private volatile String error;

  public ClockCheck(String referenceUrl) {
    this.referenceUrl = referenceUrl;
  }

  /** Called by the panel's scheduler every few minutes. */
  public void check() {
    if (referenceUrl == null || referenceUrl.isBlank()) {
      return;
    }
    try {
      HttpURLConnection connection = (HttpURLConnection) new URL(referenceUrl).openConnection();
      connection.setRequestMethod("HEAD");
      connection.setConnectTimeout(5000);
      connection.setReadTimeout(5000);
      long before = System.currentTimeMillis();
      connection.connect();
      String date = connection.getHeaderField("Date");
      long after = System.currentTimeMillis();
      connection.disconnect();
      if (date == null) {
        error = "No Date header from " + referenceUrl;
        return;
      }
      long reference = ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME)
          .toInstant().toEpochMilli();
      // The header is cut to whole seconds: compare with the middle of that second
      offsetMs = (before + after) / 2 - (reference + 500);
      checkedAt = Instant.now();
      error = null;
    } catch (Exception e) {
      error = e.getClass().getSimpleName() + ": " + e.getMessage();
    }
  }

  /** Positive: the server is ahead. Null until a check succeeded. */
  public Long offsetMs() {
    return offsetMs;
  }

  public State state() {
    return new State(referenceUrl, offsetMs, checkedAt, error);
  }

  public record State(String referenceUrl, Long offsetMs, Instant checkedAt, String error) {
  }
}
