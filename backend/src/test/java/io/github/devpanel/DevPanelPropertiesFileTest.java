package io.github.devpanel;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

class DevPanelPropertiesFileTest {

  @Test
  void loadsDevpanelPropertiesWithTheLowestPriority() {
    MockEnvironment environment = new MockEnvironment()
        .withProperty("devpanel.files.docs-path", "from-application-properties");

    new DevPanelPropertiesFile().postProcessEnvironment(environment, new SpringApplication());

    assertThat(environment.getProperty("devpanel.enabled")).isEqualTo("true");
    assertThat(environment.getProperty("devpanel.files.logs-path")).isEqualTo("target/test-logs");
    assertThat(environment.getProperty("devpanel.files.docs-path"))
        .isEqualTo("from-application-properties");
  }
}
