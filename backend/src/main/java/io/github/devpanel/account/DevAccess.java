package io.github.devpanel.account;

/**
 * What an account may do inside the dev panel. Only a {@link DevRole#USER} can be
 * {@link #READ_ONLY}; admins and the super admin always have {@link #READ_WRITE}.
 */
public enum DevAccess {
  READ_ONLY,
  READ_WRITE
}
