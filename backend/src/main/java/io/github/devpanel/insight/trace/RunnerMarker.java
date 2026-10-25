package io.github.devpanel.insight.trace;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Requests sent by the panel's own API runner and smoke checks carry {@value #HEADER} with a
 * random value known only inside this JVM, so their waterfall is always kept. An outside client
 * cannot guess it.
 */
public final class RunnerMarker {

  public static final String HEADER = "X-DevPanel-Runner";
  private static final String TOKEN;

  static {
    byte[] bytes = new byte[16];
    new SecureRandom().nextBytes(bytes);
    TOKEN = HexFormat.of().formatHex(bytes);
  }

  private RunnerMarker() {
  }

  public static String token() {
    return TOKEN;
  }

  public static boolean matches(String value) {
    return TOKEN.equals(value);
  }
}
