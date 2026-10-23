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
import java.util.List;
import java.util.Optional;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import io.github.devpanel.support.ClientIp;
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
  private final Duration timeout;
  private final Duration idleTimeout;
  private final SecureRandom random = new SecureRandom();

  public DevSessions(JdbcTemplate jdbcTemplate, Duration timeout, Duration idleTimeout) {
    this.jdbcTemplate = jdbcTemplate;
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
    String agent = request.getHeader("User-Agent");
    jdbcTemplate.update("INSERT INTO devpanel_session (token_hash, account_id, stage, created_at,"
            + " last_seen_at, client_ip, user_agent) VALUES (?, ?, ?, ?, ?, ?, ?)", hash(token),
        accountId, stage.name(), now, now, ClientIp.of(request),
        agent == null ? null : agent.substring(0, Math.min(300, agent.length())));
    writeCookie(request, response, token, -1);
  }

  /**
   * The session of the request's cookie, when there is one and it has not expired. A browser may
   * send several cookies of this name (one left over under an older path, or from another
   * application on the same host), so each is tried.
   */
  public Optional<Session> current(HttpServletRequest request) {
    for (String token : cookies(request)) {
      Optional<Session> session = find(hash(token));
      if (session.isPresent()) {
        return session;
      }
    }
    return Optional.empty();
  }

  private Optional<Session> find(String tokenHash) {
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
    List<String> tokens = cookies(request);
    if (!tokens.isEmpty()) {
      tokens.forEach(token -> delete(hash(token)));
      writeCookie(request, response, "", 0);
    }
  }

  /** Logs the account out everywhere, e.g. after a password or 2FA reset. */
  public void endAll(long accountId) {
    jdbcTemplate.update("DELETE FROM devpanel_session WHERE account_id = ?", accountId);
  }

  /** The account's finished logins, newest first; {@code id} is a short prefix of the hash. */
  public List<SessionView> list(long accountId, String currentHash) {
    return jdbcTemplate.query("SELECT token_hash, created_at, last_seen_at, client_ip, user_agent"
            + " FROM devpanel_session WHERE account_id = ? AND stage = ?"
            + " ORDER BY last_seen_at DESC",
        (rs, i) -> new SessionView(rs.getString("token_hash").substring(0, 12),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("last_seen_at").toLocalDateTime(), rs.getString("client_ip"),
            rs.getString("user_agent"), rs.getString("token_hash").equals(currentHash)),
        accountId, DevSessionStage.ACTIVE.name());
  }

  /** Ends one of the account's own sessions by the id {@link #list} showed. */
  public boolean endOne(long accountId, String id) {
    if (id == null || !id.matches("[0-9a-f]{12}")) {
      return false;
    }
    return jdbcTemplate.update("DELETE FROM devpanel_session WHERE account_id = ?"
        + " AND token_hash LIKE ?", accountId, id + "%") > 0;
  }

  public int endOthers(long accountId, String currentHash) {
    return jdbcTemplate.update("DELETE FROM devpanel_session WHERE account_id = ?"
        + " AND token_hash <> ?", accountId, currentHash);
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

  private static List<String> cookies(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return List.of();
    }
    return java.util.Arrays.stream(cookies)
        .filter(cookie -> COOKIE.equals(cookie.getName()) && !cookie.getValue().isBlank())
        .map(Cookie::getValue)
        .limit(5)
        .toList();
  }

  /**
   * Written by hand because the servlet API of Boot 2.7 has no {@code SameSite}. Marked
   * {@code Secure} on HTTPS, including behind a proxy that sets {@code X-Forwarded-Proto}.
   *
   * <p>{@code Path=/} rather than the panel's path: behind a proxy that adds a prefix (nginx
   * forwarding {@code /api/...} to the backend without it) the browser sees the panel at
   * {@code /api/dev-panel/}, and a cookie for {@code /dev-panel} would never be sent back.
   */
  private void writeCookie(HttpServletRequest request, HttpServletResponse response, String value,
      int maxAge) {
    StringBuilder cookie = new StringBuilder(COOKIE).append('=').append(value)
        .append("; Path=/; HttpOnly; SameSite=Strict");
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

  /** {@code current}: the session of the browser asking. */
  public record SessionView(String id, LocalDateTime createdAt, LocalDateTime lastSeenAt,
                            String clientIp, String userAgent, boolean current) {
  }

  public record Session(String tokenHash, long accountId, DevSessionStage stage,
                        LocalDateTime createdAt, LocalDateTime lastSeenAt) {
  }
}
