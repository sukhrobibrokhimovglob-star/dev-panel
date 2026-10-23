package io.github.devpanel.account;

import io.github.devpanel.account.DevAccountRequests.Create;
import io.github.devpanel.account.DevAccountRequests.Credentials;
import io.github.devpanel.account.DevAccountRequests.PasswordChange;
import io.github.devpanel.account.DevAccountRequests.Update;
import io.github.devpanel.support.DevPanelException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Account management of the dev panel. The rules, in one place:
 * <ul>
 *   <li>the super admin is created by the system and is unique; it can create admins and users,
 *       change their role, access and enabled flag, and reset their password;</li>
 *   <li>an admin can create users (and list them), nothing else;</li>
 *   <li>a user can only manage their own password;</li>
 *   <li>a new or reset account gets a temporary password that must be replaced on first login.</li>
 * </ul>
 * The actor is always the account re-read from the database for the current request, so a role or
 * access change applies to the very next request.
 */
public class DevAccountService {

  private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{2,63}$");
  private static final int MIN_PASSWORD_LENGTH = 12;
  private static final int MAX_PASSWORD_LENGTH = 128;

  /** Temporary passwords, without look-alike characters (0/O, 1/l/I). */
  private static final String ALPHABET =
      "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
  private static final int TEMPORARY_PASSWORD_LENGTH = 20;

  private final DevAccountRepository repository;
  private final PasswordEncoder passwordEncoder;
  private final SecureRandom random = new SecureRandom();

  public DevAccountService(DevAccountRepository repository, PasswordEncoder passwordEncoder) {
    this.repository = repository;
    this.passwordEncoder = passwordEncoder;
  }

  public void changePassword(DevAccount actor, PasswordChange request) {
    if (request == null || isBlank(request.currentPassword()) || isBlank(request.newPassword())) {
      throw DevPanelException.badRequest("Current and new password are required");
    }
    if (!passwordEncoder.matches(request.currentPassword(), actor.passwordHash())) {
      throw DevPanelException.badRequest("Current password is incorrect");
    }
    validateNewPassword(actor, request.currentPassword(), request.newPassword());
    repository.update(actor.withPassword(passwordEncoder.encode(request.newPassword()), false));
  }

  public List<DevAccount> list(DevAccount actor) {
    return switch (actor.role()) {
      case SUPER_ADMIN -> repository.findAll();
      case ADMIN -> repository.findAllByRole(DevRole.USER);
      case USER -> throw DevPanelException.forbidden("Users cannot list dev accounts");
    };
  }

  public Credentials create(DevAccount actor, Create request) {
    if (request == null || request.role() == null || isBlank(request.username())) {
      throw DevPanelException.badRequest("username and role are required");
    }
    DevRole role = request.role();
    if (role == DevRole.SUPER_ADMIN) {
      throw DevPanelException.badRequest(
          "The super admin is created by the system; there is only one");
    }
    if (!mayCreate(actor.role(), role)) {
      throw DevPanelException.forbidden(
          "A " + actor.role() + " cannot create a " + role + " account");
    }
    String username = request.username().trim().toLowerCase(Locale.ROOT);
    if (!USERNAME.matcher(username).matches()) {
      throw DevPanelException.badRequest(
          "Username must be 3-64 characters: letters, digits, '.', '_' or '-'");
    }
    if (repository.existsByUsername(username)) {
      throw new DevPanelException(HttpStatus.CONFLICT, "Username is already taken");
    }

    String temporaryPassword = temporaryPassword();
    try {
      repository.insert(newAccount(username, role, accessFor(role, request.access()),
          temporaryPassword, actor.username()));
    } catch (DataIntegrityViolationException e) {
      throw new DevPanelException(HttpStatus.CONFLICT, "Username is already taken");
    }
    return new Credentials(repository.findByUsername(username).orElseThrow(), temporaryPassword);
  }

  public DevAccount update(DevAccount actor, long id, Update request) {
    requireSuperAdmin(actor);
    DevAccount account = requireManageable(id);
    Update changes = request == null ? new Update(null, null, null) : request;

    DevRole role = changes.role() != null ? changes.role() : account.role();
    if (role == DevRole.SUPER_ADMIN) {
      throw DevPanelException.badRequest(
          "The super admin is created by the system; there is only one");
    }
    DevAccess access = changes.access() != null ? changes.access() : account.access();
    if (role == DevRole.ADMIN) {
      if (changes.access() == DevAccess.READ_ONLY) {
        throw DevPanelException.badRequest("Admins always have full access");
      }
      access = DevAccess.READ_WRITE;
    }
    boolean enabled = changes.enabled() != null ? changes.enabled() : account.enabled();

    repository.update(account.withRoleAccessEnabled(role, access, enabled));
    return repository.findById(id).orElseThrow();
  }

  public Credentials resetPassword(DevAccount actor, long id) {
    requireSuperAdmin(actor);
    DevAccount account = requireManageable(id);

    String temporaryPassword = temporaryPassword();
    repository.update(account.withPassword(passwordEncoder.encode(temporaryPassword), true));
    return new Credentials(repository.findById(id).orElseThrow(), temporaryPassword);
  }

  /**
   * Creates the one super admin. Called at startup only, and only when none exists; on PostgreSQL
   * a partial unique index makes sure two instances starting together cannot both succeed.
   */
  public void createSuperAdmin(String username, String temporaryPassword) {
    repository.insert(newAccount(username.trim().toLowerCase(Locale.ROOT), DevRole.SUPER_ADMIN,
        DevAccess.READ_WRITE, temporaryPassword, "system"));
  }

  private DevAccount newAccount(String username, DevRole role, DevAccess access,
      String temporaryPassword, String createdBy) {
    return new DevAccount(null, username, passwordEncoder.encode(temporaryPassword), role, access,
        true, true, LocalDateTime.now(), createdBy);
  }

  private String temporaryPassword() {
    StringBuilder password = new StringBuilder(TEMPORARY_PASSWORD_LENGTH);
    for (int i = 0; i < TEMPORARY_PASSWORD_LENGTH; i++) {
      password.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
    }
    return password.toString();
  }

  private static boolean mayCreate(DevRole actor, DevRole target) {
    return switch (actor) {
      case SUPER_ADMIN -> target == DevRole.ADMIN || target == DevRole.USER;
      case ADMIN -> target == DevRole.USER;
      case USER -> false;
    };
  }

  private static DevAccess accessFor(DevRole role, DevAccess requested) {
    if (role == DevRole.ADMIN) {
      if (requested == DevAccess.READ_ONLY) {
        throw DevPanelException.badRequest("Admins always have full access");
      }
      return DevAccess.READ_WRITE;
    }
    return requested != null ? requested : DevAccess.READ_ONLY;
  }

  private static void validateNewPassword(DevAccount account, String current, String candidate) {
    if (candidate.length() < MIN_PASSWORD_LENGTH || candidate.length() > MAX_PASSWORD_LENGTH) {
      throw DevPanelException.badRequest("Password must be " + MIN_PASSWORD_LENGTH + "-"
          + MAX_PASSWORD_LENGTH + " characters long");
    }
    if (candidate.equals(current)) {
      throw DevPanelException.badRequest("New password must differ from the current one");
    }
    if (candidate.equalsIgnoreCase(account.username())) {
      throw DevPanelException.badRequest("Password must not be the username");
    }
    boolean hasLetter = candidate.chars().anyMatch(Character::isLetter);
    boolean hasDigit = candidate.chars().anyMatch(Character::isDigit);
    if (!hasLetter || !hasDigit) {
      throw DevPanelException.badRequest(
          "Password must contain at least one letter and one digit");
    }
  }

  private static void requireSuperAdmin(DevAccount actor) {
    if (actor.role() != DevRole.SUPER_ADMIN) {
      throw DevPanelException.forbidden("Only the super admin can do this");
    }
  }

  /** An existing account the super admin may modify: everything except the super admin itself. */
  private DevAccount requireManageable(long id) {
    DevAccount account = repository.findById(id)
        .orElseThrow(() -> DevPanelException.notFound("Dev account not found"));
    if (account.role() == DevRole.SUPER_ADMIN) {
      throw DevPanelException.forbidden("The super admin account cannot be modified");
    }
    return account;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
