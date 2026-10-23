package io.github.devpanel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;

/**
 * An application on the older {@link WebSecurityConfigurerAdapter} style, which Spring Security
 * refuses to combine with {@code SecurityFilterChain} beans. The panel registers none, so it must
 * start and work here too.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = LegacySecurityAdapterTest.HostApplication.class,
    properties = "spring.datasource.url=jdbc:h2:mem:legacy;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class LegacySecurityAdapterTest {

  @Autowired
  private TestRestTemplate rest;

  @Test
  void panelWorksNextToAnAdapter() {
    PanelClient panel = new PanelClient(rest);
    assertThat(panel.post("/login", Map.of("username", "superadmin", "password", "Initial-Pass-1"))
        .getBody().get("stage")).isEqualTo("PASSWORD_CHANGE");
    assertThat(rest.getForEntity("/dev-panel/", String.class).getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(rest.getForEntity("/anything", String.class).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Configuration
  @EnableAutoConfiguration
  @EnableWebSecurity
  @SuppressWarnings("deprecation")
  static class HostApplication extends WebSecurityConfigurerAdapter {

    @Override
    protected void configure(HttpSecurity http) throws Exception {
      http.authorizeRequests().anyRequest().authenticated();
    }
  }
}
