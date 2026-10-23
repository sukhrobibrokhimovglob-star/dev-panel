package io.github.devpanel.account;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Time-based one-time passwords (RFC 6238) as Google Authenticator and every similar app use
 * them: HMAC-SHA1, 30-second steps, 6 digits, a Base32 secret. A code from the step before or
 * after the current one is also accepted, because phone clocks drift.
 */
public final class Totp {

  static final int PERIOD_SECONDS = 30;
  private static final int DIGITS = 6;
  private static final int WINDOW = 1;
  private static final int SECRET_BYTES = 20;
  private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  private static final SecureRandom RANDOM = new SecureRandom();

  private Totp() {
  }

  public static String newSecret() {
    byte[] bytes = new byte[SECRET_BYTES];
    RANDOM.nextBytes(bytes);
    return base32(bytes);
  }

  /** What the authenticator app's QR code holds. */
  public static String uri(String issuer, String account, String secret) {
    String label = encode(issuer) + ":" + encode(account);
    return "otpauth://totp/" + label + "?secret=" + secret + "&issuer=" + encode(issuer)
        + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + PERIOD_SECONDS;
  }

  /**
   * @return the time step {@code code} belongs to, or -1 when it matches none near
   *     {@code nowMillis}. The caller stores the step and refuses it the next time, so a code
   *     cannot be used twice.
   */
  public static long matchingStep(String secret, String code, long nowMillis) {
    if (secret == null || code == null) {
      return -1;
    }
    String digits = code.replace(" ", "");
    if (!digits.matches("\\d{" + DIGITS + "}")) {
      return -1;
    }
    byte[] key = unbase32(secret);
    long current = nowMillis / 1000 / PERIOD_SECONDS;
    for (long step = current - WINDOW; step <= current + WINDOW; step++) {
      if (MessageDigest.isEqual(code(key, step).getBytes(StandardCharsets.US_ASCII),
          digits.getBytes(StandardCharsets.US_ASCII))) {
        return step;
      }
    }
    return -1;
  }

  /** The code an authenticator app shows for {@code step}; used by tests. */
  public static String codeForStep(String secret, long step) {
    return code(unbase32(secret), step);
  }

  public static long stepAt(long millis) {
    return millis / 1000 / PERIOD_SECONDS;
  }

  static String code(byte[] key, long step) {
    try {
      Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(key, "HmacSHA1"));
      byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
      int offset = hash[hash.length - 1] & 0x0f;
      int binary = ((hash[offset] & 0x7f) << 24)
          | ((hash[offset + 1] & 0xff) << 16)
          | ((hash[offset + 2] & 0xff) << 8)
          | (hash[offset + 3] & 0xff);
      return String.format("%0" + DIGITS + "d", binary % 1_000_000);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  static String base32(byte[] bytes) {
    StringBuilder out = new StringBuilder();
    int buffer = 0;
    int bits = 0;
    for (byte b : bytes) {
      buffer = (buffer << 8) | (b & 0xff);
      bits += 8;
      while (bits >= 5) {
        out.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
        bits -= 5;
      }
    }
    if (bits > 0) {
      out.append(BASE32.charAt((buffer << (5 - bits)) & 31));
    }
    return out.toString();
  }

  static byte[] unbase32(String text) {
    String clean = text.replace("=", "").replace(" ", "").toUpperCase(Locale.ROOT);
    ByteBuffer out = ByteBuffer.allocate(clean.length() * 5 / 8);
    int buffer = 0;
    int bits = 0;
    for (char c : clean.toCharArray()) {
      int value = BASE32.indexOf(c);
      if (value < 0) {
        throw new IllegalArgumentException("Not Base32: " + c);
      }
      buffer = (buffer << 5) | value;
      bits += 5;
      if (bits >= 8) {
        out.put((byte) (buffer >> (bits - 8)));
        bits -= 8;
      }
    }
    return out.array();
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
