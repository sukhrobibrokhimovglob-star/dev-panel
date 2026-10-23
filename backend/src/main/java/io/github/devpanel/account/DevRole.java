package io.github.devpanel.account;

/**
 * Who a dev panel account is. {@link #SUPER_ADMIN} is created by the system (exactly one) and can
 * create admins and users; an {@link #ADMIN} can only create users.
 */
public enum DevRole {
  SUPER_ADMIN,
  ADMIN,
  USER
}
