package io.github.devpanel.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class SensitiveTest {

  private final Sensitive sensitive = Sensitive.defaults();

  @Test
  void recognizesPersonalAndSecretData() {
    assertThat(sensitive.kinds("user with username: 43001780190052 successfully loaded"))
        .containsExactly("PNFL");
    assertThat(sensitive.kinds("Authorization: Bearer abcdefghijklmnopqrstuvwxyz123456"))
        .contains("BEARER_TOKEN");
    assertThat(sensitive.kinds("login failed, password=hunter2!")).contains("PASSWORD");
    assertThat(sensitive.kinds("card 4111 1111 1111 1111 declined")).contains("CARD");
    assertThat(sensitive.kinds("mail me at ali@example.uz")).contains("EMAIL");
    assertThat(sensitive.kinds("call +998 90 123 45 67")).contains("PHONE");
  }

  @Test
  void leavesOrdinaryNumbersAlone() {
    assertThat(sensitive.kinds("select * from users where id = 1234567 limit 20")).isEmpty();
    assertThat(sensitive.kinds("took 12345678901234567890 ns")).isEmpty();
    assertThat(sensitive.kinds("order 1234 5678 9012 3456")).isEmpty();
  }

  @Test
  void masksKeepingEnoughToRecognize() {
    assertThat(sensitive.mask("pnfl 43001780190052 loaded")).isEqualTo("pnfl 43***52 loaded");
    assertThat(sensitive.mask("{\"password\":\"secret-value-1\"}"))
        .isEqualTo("{\"password\":\"se***-1\"}");
    assertThat(sensitive.maskSecretsOnly("{\"name\":\"Ali\",\"password\":\"secret-value-1\"}"))
        .isEqualTo("{\"name\":\"Ali\",\"password\":\"***\"}");
  }

  @Test
  void takesTheApplicationsOwnPatterns() {
    Sensitive custom = new Sensitive(Map.of("passport", "\\b[A-Z]{2}\\d{7}\\b"));
    assertThat(custom.kinds("passport AB1234567 checked")).containsExactly("PASSPORT");
  }
}
