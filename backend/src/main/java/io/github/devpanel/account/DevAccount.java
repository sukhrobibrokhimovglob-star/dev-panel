package io.github.devpanel.account;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDateTime;

/**
 * A login of the dev panel. The username is stored lower-case; the password only as a BCrypt
 * hash. Neither the hash nor the authenticator secret is ever serialized.
 *
 * @param totpSecret     Base32 secret of the authenticator app; set while the account is setting
 *                       up two-factor login, confirmed once {@code totpEnabled} is true
 * @param totpLastStep   the 30-second step of the last accepted code, so it cannot be reused
 * @param totpEnabledAt  when and from where ({@code totpEnabledIp}) the app was set up, so a setup
 *                       nobody remembers doing stands out
 */
public record DevAccount(
    Long id,
    String username,
    @JsonIgnore String passwordHash,
    DevRole role,
    DevAccess access,
    boolean enabled,
    boolean mustChangePassword,
    LocalDateTime createdAt,
    String createdBy,
    @JsonIgnore String totpSecret,
    boolean totpEnabled,
    @JsonIgnore long totpLastStep,
    LocalDateTime totpEnabledAt,
    String totpEnabledIp) {

  public boolean canWrite() {
    return access == DevAccess.READ_WRITE;
  }

  DevAccount withPassword(String hash, boolean mustChange) {
    return new DevAccount(id, username, hash, role, access, enabled, mustChange, createdAt,
        createdBy, totpSecret, totpEnabled, totpLastStep, totpEnabledAt, totpEnabledIp);
  }

  DevAccount withRoleAccessEnabled(DevRole newRole, DevAccess newAccess, boolean newEnabled) {
    return new DevAccount(id, username, passwordHash, newRole, newAccess, newEnabled,
        mustChangePassword, createdAt, createdBy, totpSecret, totpEnabled, totpLastStep,
        totpEnabledAt, totpEnabledIp);
  }
}
