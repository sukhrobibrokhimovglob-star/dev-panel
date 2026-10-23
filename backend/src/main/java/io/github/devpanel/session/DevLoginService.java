package io.github.devpanel.session;

import io.github.devpanel.account.DevAccount;
import io.github.devpanel.account.DevAccountRepository;
import io.github.devpanel.account.DevAccountService;
import io.github.devpanel.account.DevLoginAttempts;
import io.github.devpanel.account.Totp;
import io.github.devpanel.session.DevSessions.Session;
import io.github.devpanel.support.DevPanelException;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The login, in steps: password, then (once) replacing the temporary password, then (once)
 * setting up the authenticator app, then the 6-digit code. Every account needs the code; there is
 * no way around it. Each step moves the same session row on (see {@link DevSessionStage}), so the
 * steps may land on different instances.
 *
 * <p>Failed passwords and failed codes count together towards the lockout, and a correct password
 * does not reset the count: only a completed login does. Otherwise the code could be guessed by
 * logging in again after every few attempts.
 */
public class DevLoginService {

  /** How far the clock check of a refused code looks: 30 minutes either way. */
  private static final int CLOCK_CHECK_STEPS = 60;

  private final DevAccountRepository accounts;
  private final DevAccountService accountService;
  private final DevSessions sessions;
  private final DevLoginAttempts loginAttempts;
  private final PasswordEncoder passwordEncoder;
  private final String issuer;
  /** Steps of clock drift tolerated, from {@code devpanel.two-factor.allowed-clock-drift}. */
  private final int window;

  public DevLoginService(DevAccountRepository accounts, DevAccountService accountService,
      DevSessions sessions, DevLoginAttempts loginAttempts, PasswordEncoder passwordEncoder,
      String issuer, Duration allowedClockDrift) {
    this.accounts = accounts;
    this.accountService = accountService;
    this.sessions = sessions;
    this.loginAttempts = loginAttempts;
    this.passwordEncoder = passwordEncoder;
    this.issuer = issuer;
    this.window = Totp.stepsFor(allowedClockDrift);
  }

  public LoginState login(String username, String password, HttpServletRequest request,
      HttpServletResponse response) {
    if (username == null || username.isBlank() || password == null || password.isEmpty()) {
      throw DevPanelException.badRequest("Username and password are required");
    }
    String normalized = username.trim().toLowerCase(Locale.ROOT);
    requireNotLocked(normalized);
    Optional<DevAccount> found = accounts.findByUsername(normalized);
    if (found.isEmpty() || !passwordEncoder.matches(password, found.get().passwordHash())) {
      loginAttempts.onFailure(normalized);
      throw new DevPanelException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
    }
    DevAccount account = found.get();
    if (!account.enabled()) {
      throw new DevPanelException(HttpStatus.UNAUTHORIZED, "Account is disabled");
    }
    DevSessionStage stage = account.mustChangePassword()
        ? DevSessionStage.PASSWORD_CHANGE
        : nextAfterPassword(account);
    sessions.start(account.id(), stage, request, response);
    return state(account, stage);
  }

  public LoginState state(Session session) {
    return state(account(session), session.stage());
  }

  public LoginState changeTemporaryPassword(Session session, String newPassword,
      HttpServletRequest request, HttpServletResponse response) {
    requireStage(session, DevSessionStage.PASSWORD_CHANGE);
    DevAccount account = accountService.replaceTemporaryPassword(account(session), newPassword);
    DevSessionStage next = nextAfterPassword(account);
    sessions.advance(session, next, request, response);
    return state(account, next);
  }

  /** The 6-digit code; during setup it also confirms that the app was set up correctly. */
  public DevAccount verifyCode(Session session, String code, HttpServletRequest request,
      HttpServletResponse response) {
    if (session.stage() != DevSessionStage.TOTP && session.stage() != DevSessionStage.TOTP_SETUP) {
      throw DevPanelException.badRequest("No code is expected now");
    }
    DevAccount account = account(session);
    requireNotLocked(account.username());
    long now = System.currentTimeMillis();
    long step = Totp.matchingStep(account.totpSecret(), code, now, window);
    if (step < 0 || !accounts.useTotpStep(account.id(), step)) {
      loginAttempts.onFailure(account.username());
      throw DevPanelException.badRequest(step < 0
          ? clockHint(account.totpSecret(), code, now)
          : "This code was already used; wait for the next one");
    }
    if (session.stage() == DevSessionStage.TOTP_SETUP) {
      accounts.confirmTotp(account.id());
    }
    loginAttempts.onSuccess(account.username());
    sessions.advance(session, DevSessionStage.ACTIVE, request, response);
    return accounts.findById(account.id()).orElseThrow();
  }

  /**
   * A wrong code is usually a typo, but when it is the right code for a time a few minutes off,
   * the server's clock is wrong (the phone's is set automatically). Saying so saves a long hunt.
   * The code is still refused.
   */
  private static String clockHint(String secret, String code, long now) {
    long offered = Totp.matchingStep(secret, code, now, CLOCK_CHECK_STEPS);
    if (offered < 0) {
      return "Invalid code";
    }
    long minutes = Math.round((Totp.stepAt(now) - offered) * Totp.PERIOD_SECONDS / 60.0);
    String direction = minutes >= 0 ? "ahead" : "behind";
    return "The code is right, but the server's clock is about " + Math.abs(minutes) + " min "
        + direction + ". Ask DevOps to sync it (timedatectl set-ntp true), or allow it for now"
        + " with devpanel.two-factor.allowed-clock-drift=" + (Math.abs(minutes) + 1) + "m";
  }

  private LoginState state(DevAccount account, DevSessionStage stage) {
    if (stage != DevSessionStage.TOTP_SETUP) {
      return new LoginState(stage, account.username(), null);
    }
    String secret = account.totpSecret();
    if (secret == null) {
      // Kept until confirmed, so reloading the page shows the same QR code
      secret = Totp.newSecret();
      accounts.startTotpSetup(account.id(), secret);
    }
    return new LoginState(stage, account.username(),
        new TotpSetup(secret, Totp.uri(issuer, account.username(), secret)));
  }

  private static DevSessionStage nextAfterPassword(DevAccount account) {
    return account.totpEnabled() ? DevSessionStage.TOTP : DevSessionStage.TOTP_SETUP;
  }

  private DevAccount account(Session session) {
    return accounts.findById(session.accountId())
        .filter(DevAccount::enabled)
        .orElseThrow(() -> new DevPanelException(HttpStatus.UNAUTHORIZED, "Login required"));
  }

  private void requireNotLocked(String username) {
    Duration locked = loginAttempts.lockedFor(username);
    if (locked != null) {
      long minutes = Math.max(1, (locked.toSeconds() + 59) / 60);
      throw new DevPanelException(HttpStatus.TOO_MANY_REQUESTS,
          "Too many failed attempts; try again in " + minutes + " min");
    }
  }

  private static void requireStage(Session session, DevSessionStage stage) {
    if (session.stage() != stage) {
      throw DevPanelException.badRequest("This step is not expected now");
    }
  }

  /** {@code setup} only while the authenticator app is being set up. */
  public record LoginState(DevSessionStage stage, String username, TotpSetup setup) {
  }

  /** The secret as text (for typing it in) and as the URI the QR code holds. */
  public record TotpSetup(String secret, String uri) {
  }
}
