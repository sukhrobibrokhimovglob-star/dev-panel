package io.github.devpanel.session;

/**
 * Where a login stands. Only {@link #ACTIVE} reaches the panel; the others can only finish the
 * login, and they expire after a few minutes.
 */
public enum DevSessionStage {
  /** Password accepted, the temporary password must be replaced. */
  PASSWORD_CHANGE,
  /** Password accepted, the authenticator app has to be set up (first login or after a reset). */
  TOTP_SETUP,
  /** Password accepted, the 6-digit code is next. */
  TOTP,
  ACTIVE
}
