package io.github.devpanel.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Panel logins, in {@code devpanel_session} so they survive a restart and work behind a load
 * balancer with several instances. The browser holds a random token in an {@code HttpOnly},
 * {@code SameSite=Strict} cookie that page scripts cannot read; the table holds only its SHA-256.
 */
public class DevSessions {

  public static final String COOKIE = "DEVPANEL_SESSION";

  /** A login that has not finished (password change, 2FA) must do so within this time. */
  static final Duration PENDING_TIMEOUT = Duration.ofMinutes(10);
  /** {@code last_seen_at} is written at most this often, not on every request. */
  private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);
  private static final int TOKEN_BYTES = 32;

  private final JdbcTemplate jdbcTemplate;
  private final String cookiePath;
  private final Duration timeout;
  private final Duration idleTimeout;
  private final SecureRandom random = new SecureRandom();

  public DevSessions(JdbcTemplate jdbcTemplate, String basePath, Duration timeout,
      Duration idleTimeout) {
    this.jdbcTemplate = jdbcTemplate;
    this.cookiePath = basePath;
    this.timeout = timeout;
    this.idleTimeout = idleTimeout;
  }

  /** Starts a login in {@code stage} and sets its cookie; any earlier session cookie is replaced. */
  public void start(long accountId, DevSessionStage stage, HttpServletRequest request,
      HttpServletResponse response) {
    end(request, response);
    byte[] bytes = new byte[TOKEN_BYTES];
    random.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    Timestamp now = Timestamp.valueOf(LocalDateTime.now());
    jdbcTemplate.update("INSERT INTO devpanel_session (token_hash, account_id, stage, created_at,"
        + " last_seen_at) VALUES (?, ?, ?, ?, ?)", hash(token), accountId, stage.name(), now, now);
    writeCookie(request, response, token, -1);
  }

  /** The session of the request's cookie, when there is one and it has not expired. */
  public Optional<Session> current(HttpServletRequest request) {
    String token = cookie(request);
    if (token == null) {
      return Optional.empty();
    }
    String tokenHash = hash(token);
    Optional<Session> found = jdbcTemplate.query("SELECT account_id, stage, created_at,"
            + " last_seen_at FROM devpanel_session WHERE token_hash = ?",
        (rs, rowNum) -> new Session(tokenHash, rs.getLong("account_id"),
            DevSessionStage.valueOf(rs.getString("stage")),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("last_seen_at").toLocalDateTime()), tokenHash)
        .stream().findFirst();
    if (found.isEmpty()) {
      return Optional.empty();
    }
    Session session = found.get();
    LocalDateTime now = LocalDateTime.now();
    if (isExpired(session, now)) {
      delete(tokenHash);
      return Optional.empty();
    }
    if (session.stage() == DevSessionStage.ACTIVE
        && session.lastSeenAt().plus(TOUCH_INTERVAL).isBefore(now)) {
      jdbcTemplate.update("UPDATE devpanel_session SET last_seen_at = ? WHERE token_hash = ?",
          Timestamp.valueOf(now), tokenHash);
    }
    return found;
  }

  /**
   * Moves the login on. Reaching {@link DevSessionStage#ACTIVE} issues a new token, so a token
   * seen before the second factor was given is worth nothing afterwards.
   */
  public void advance(Session session, DevSessionStage stage, HttpServletRequest request,
      HttpServletResponse response) {
    if (stage == DevSessionStage.ACTIVE) {
      start(session.accountId(), stage, request, response);
      return;
    }
    jdbcTemplate.update("UPDATE devpanel_session SET stage = ? WHERE token_hash = ?",
        stage.name(), session.tokenHash());
  }

  public void end(HttpServletRequest request, HttpServletResponse response) {
    String token = cookie(request);
    if (token != null) {
      delete(hash(token));
      writeCookie(request, response, "", 0);
    }
  }

  /** Logs the account out everywhere, e.g. after a password or 2FA reset. */
  public void endAll(long accountId) {
    jdbcTemplate.update("DELETE FROM devpanel_session WHERE account_id = ?", accountId);
  }

  public int deleteExpired() {
    LocalDateTime now = LocalDateTime.now();
    return jdbcTemplate.update("DELETE FROM devpanel_session WHERE (stage <> ? AND created_at < ?)"
            + " OR created_at < ? OR last_seen_at < ?",
        DevSessionStage.ACTIVE.name(), Timestamp.valueOf(now.minus(PENDING_TIMEOUT)),
        Timestamp.valueOf(now.minus(timeout)), Timestamp.valueOf(now.minus(idleTimeout)));
  }

  private boolean isExpired(Session session, LocalDateTime now) {
    if (session.stage() != DevSessionStage.ACTIVE) {
      return session.createdAt().plus(PENDING_TIMEOUT).isBefore(now);
    }
    return session.createdAt().plus(timeout).isBefore(now)
        || session.lastSeenAt().plus(idleTimeout).isBefore(now);
  }

  private void delete(String tokenHash) {
    jdbcTemplate.update("DELETE FROM devpanel_session WHERE token_hash = ?", tokenHash);
  }

  private static String cookie(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return null;
    }
    for (Cookie cookie : cookies) {
      if (COOKIE.equals(cookie.getName()) && !cookie.getValue().isBlank()) {
        return cookie.getValue();
      }
    }
    return null;
  }

  /**
   * Written by hand because the servlet API of Boot 2.7 has no {@code SameSite}. Marked
   * {@code Secure} on HTTPS, including behind a proxy that sets {@code X-Forwarded-Proto}.
   */
  private void writeCookie(HttpServletRequest request, HttpServletResponse response, String value,
      int maxAge) {
    StringBuilder cookie = new StringBuilder(COOKIE).append('=').append(value)
        .append("; Path=").append(request.getContextPath()).append(cookiePath)
        .append("; HttpOnly; SameSite=Strict");
    if (maxAge >= 0) {
      cookie.append("; Max-Age=").append(maxAge);
    }
    if (request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"))) {
      cookie.append("; Secure");
    }
    response.addHeader("Set-Cookie", cookie.toString());
  }

  private static String hash(String token) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public record Session(String tokenHash, long accountId, DevSessionStage stage,
                        LocalDateTime createdAt, LocalDateTime lastSeenAt) {
  }
}
