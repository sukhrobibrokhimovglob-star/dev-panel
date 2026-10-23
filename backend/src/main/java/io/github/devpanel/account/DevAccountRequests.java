package io.github.devpanel.account;

/** Request and response bodies of the account endpoints. */
public final class DevAccountRequests {

  private DevAccountRequests() {
  }

  /** {@code role} is {@code ADMIN} or {@code USER}; {@code access} only matters for a user. */
  public record Create(String username, DevRole role, DevAccess access) {
  }

  /** Every field is optional; a missing one stays as it is. */
  public record Update(DevRole role, DevAccess access, Boolean enabled) {
  }

  public record PasswordChange(String currentPassword, String newPassword) {
  }

  /** The temporary password is shown once, right after creating or resetting the account. */
  public record Credentials(DevAccount account, String temporaryPassword) {
  }
}
