package io.github.devpanel;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The panel inside an application whose JSON settings differ from Spring Boot's defaults: names
 * in snake_case and dates as numbers. The panel's API keeps its own camelCase names and ISO
 * dates (the UI reads them); the application's endpoints keep the application's settings.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = JsonIndependenceIntegrationTest.HostApplication.class,
    properties = {
        "spring.datasource.url=jdbc:h2:mem:json;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jackson.property-naming-strategy=SNAKE_CASE",
        "spring.jackson.serialization.write-dates-as-timestamps=true"})
class JsonIndependenceIntegrationTest {

  @Autowired
  private TestRestTemplate rest;

  @Test
  void thePanelKeepsItsOwnJsonWhateverTheApplicationConfigures() {
    PanelClient admin = new PanelClient(rest);
    admin.firstLogin("superadmin", "Initial-Pass-1", "Changed-Pass-12");

    Map<String, Object> me = admin.get("/me").getBody();
    assertThat(me).containsKey("totpEnabled").doesNotContainKey("totp_enabled");
    // LocalDateTime as ISO text, not [2026, 11, 10, ...]
    assertThat(me.get("createdAt")).isInstanceOf(String.class);
    assertThat(LocalDateTime.parse((String) me.get("createdAt"))).isNotNull();

    @SuppressWarnings("unchecked")
    Map<String, Object> app = rest.getForObject("/app-json", Map.class);
    assertThat(app).containsKey("created_at");
    assertThat(app.get("created_at")).isNotInstanceOf(String.class);
  }

  public record AppView(LocalDateTime createdAt) {
  }

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  static class HostApplication {

    @Bean
    SecurityFilterChain openSecurity(HttpSecurity http) throws Exception {
      return http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
          .csrf(csrf -> csrf.disable()).build();
    }

    @RestController
    static class AppController {

      @GetMapping("/app-json")
      AppView appJson() {
        return new AppView(LocalDateTime.of(2026, 11, 10, 9, 30));
      }
    }
  }
}
