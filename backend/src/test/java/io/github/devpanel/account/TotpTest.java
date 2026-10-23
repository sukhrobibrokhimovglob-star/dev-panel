package io.github.devpanel.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TotpTest {

  /** RFC 6238 appendix B, SHA-1 key "12345678901234567890", last 6 digits. */
  @Test
  void matchesTheRfcTestVectors() {
    String secret = Totp.base32("12345678901234567890".getBytes(StandardCharsets.US_ASCII));
    assertThat(Totp.codeForStep(secret, 59L / 30)).isEqualTo("287082");
    assertThat(Totp.codeForStep(secret, 1111111109L / 30)).isEqualTo("081804");
    assertThat(Totp.codeForStep(secret, 1234567890L / 30)).isEqualTo("005924");
    assertThat(Totp.codeForStep(secret, 2000000000L / 30)).isEqualTo("279037");
  }

  @Test
  void acceptsOneStepOfClockDriftAndNoMore() {
    String secret = Totp.newSecret();
    long now = 1_700_000_000_000L;
    long step = Totp.stepAt(now);
    assertThat(Totp.matchingStep(secret, Totp.codeForStep(secret, step), now)).isEqualTo(step);
    assertThat(Totp.matchingStep(secret, Totp.codeForStep(secret, step - 1), now))
        .isEqualTo(step - 1);
    assertThat(Totp.matchingStep(secret, Totp.codeForStep(secret, step + 2), now)).isEqualTo(-1);
    assertThat(Totp.matchingStep(secret, "12345", now)).isEqualTo(-1);
  }

  @Test
  void base32RoundTrips() {
    byte[] bytes = {1, 2, 3, (byte) 250, 99, 0, 17};
    assertThat(Totp.unbase32(Totp.base32(bytes))).isEqualTo(bytes);
  }
}
