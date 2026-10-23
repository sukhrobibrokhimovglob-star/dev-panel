package io.github.devpanel.account;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory brute-force guard for the panel's HTTP Basic login: after {@value #MAX_ATTEMPTS}
 * failures in a row the username is locked out for {@value #LOCKOUT_MINUTES} minutes, reset by a
 * successful login. Per instance and in memory; a restart is an acceptable reset of the counters.
 */
public class DevLoginAttempts {

  private static final int MAX_ATTEMPTS = 5;
  private static final int LOCKOUT_MINUTES = 15;

  private final ConcurrentHashMap<String, Attempt> attemptsByUsername = new ConcurrentHashMap<>();

  /** @return the remaining lockout, or {@code null} if the account may attempt a login now. */
  public Duration lockedFor(String username) {
    Attempt attempt = attemptsByUsername.get(normalize(username));
    if (attempt == null || attempt.lockedUntil == null) {
      return null;
    }
    Duration remaining = Duration.between(Instant.now(), attempt.lockedUntil);
    return remaining.compareTo(Duration.ZERO) > 0 ? remaining : null;
  }

  public void onFailure(String username) {
    attemptsByUsername.compute(normalize(username), (key, existing) -> {
      Attempt attempt = existing != null ? existing : new Attempt();
      attempt.failureCount++;
      if (attempt.failureCount >= MAX_ATTEMPTS) {
        attempt.lockedUntil = Instant.now().plus(Duration.ofMinutes(LOCKOUT_MINUTES));
        attempt.failureCount = 0;
      }
      return attempt;
    });
  }

  public void onSuccess(String username) {
    attemptsByUsername.remove(normalize(username));
  }

  private static String normalize(String username) {
    return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
  }

  private static final class Attempt {

    private int failureCount;
    private Instant lockedUntil;
  }
}
