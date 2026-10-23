package io.github.devpanel.account;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDateTime;

/**
 * A login of the dev panel. The username is stored lower-case; the password only as a BCrypt
 * hash, which is never serialized.
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
    String createdBy) {

  public boolean canWrite() {
    return access == DevAccess.READ_WRITE;
  }

  DevAccount withPassword(String hash, boolean mustChange) {
    return new DevAccount(id, username, hash, role, access, enabled, mustChange, createdAt,
        createdBy);
  }

  DevAccount withRoleAccessEnabled(DevRole newRole, DevAccess newAccess, boolean newEnabled) {
    return new DevAccount(id, username, passwordHash, newRole, newAccess, newEnabled,
        mustChangePassword, createdAt, createdBy);
  }
}
