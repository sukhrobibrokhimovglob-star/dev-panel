package io.github.devpanel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The panel inside an application whose own Spring Security (filter-chain style) requires a
 * login for everything: the panel must still be reachable with its own accounts only.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = DevPanelIntegrationTest.HostApplication.class)
@TestMethodOrder(OrderAnnotation.class)
class DevPanelIntegrationTest {

  private static final String API = "/dev-panel/api";
  private static final String NEW_PASSWORD = "Changed-Pass-12";

  @Autowired
  private TestRestTemplate rest;

  @Test
  @Order(1)
  void servesTheUiWithoutLogin() {
    ResponseEntity<String> redirect = rest.getForEntity("/dev-panel", String.class);
    assertThat(redirect.getStatusCode()).isEqualTo(HttpStatus.FOUND);

    ResponseEntity<String> page = rest.getForEntity("/dev-panel/", String.class);
    assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(page.getBody()).contains("<div id=\"app\">");

    String script = page.getBody().replaceAll("(?s).*src=\"\\./(assets/[^\"]+\\.js)\".*", "$1");
    HttpHeaders gzip = new HttpHeaders();
    gzip.set(HttpHeaders.ACCEPT_ENCODING, "gzip");
    ResponseEntity<byte[]> asset = rest.exchange("/dev-panel/" + script, HttpMethod.GET,
        new HttpEntity<>(gzip), byte[].class);
    assertThat(asset.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(asset.getHeaders().getCacheControl()).contains("max-age=31536000");

    assertThat(rest.getForEntity("/dev-panel/api/nope", String.class).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  @Order(2)
  void rejectsMissingAndWrongCredentialsWithoutBrowserDialog() {
    ResponseEntity<Map> anonymous = rest.postForEntity(API + "/auth", null, Map.class);
    assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(anonymous.getHeaders().containsKey(HttpHeaders.WWW_AUTHENTICATE)).isFalse();
    assertThat(anonymous.getBody()).containsKey("traceId");

    ResponseEntity<Map> wrong = rest.withBasicAuth("superadmin", "nope")
        .postForEntity(API + "/auth", null, Map.class);
    assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  @Order(3)
  void temporaryPasswordOnlyAllowsChangingIt() {
    TestRestTemplate admin = rest.withBasicAuth("superadmin", "Initial-Pass-1");
    assertThat(admin.postForEntity(API + "/auth", null, Void.class).getStatusCode())
        .isEqualTo(HttpStatus.NO_CONTENT);

    Map<String, Object> me = admin.getForObject(API + "/me", Map.class);
    assertThat(me.get("mustChangePassword")).isEqualTo(true);
    assertThat(me).doesNotContainKey("passwordHash");

    assertThat(admin.getForEntity(API + "/metrics", Map.class).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);

    ResponseEntity<Map> weak = admin.postForEntity(API + "/me/password",
        Map.of("currentPassword", "Initial-Pass-1", "newPassword", "short"), Map.class);
    assertThat(weak.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

    assertThat(admin.postForEntity(API + "/me/password",
        Map.of("currentPassword", "Initial-Pass-1", "newPassword", NEW_PASSWORD), Void.class)
        .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
  }

  @Test
  @Order(4)
  void showsMetricsHikariHistoryAndJvm() {
    TestRestTemplate admin = rest.withBasicAuth("superadmin", NEW_PASSWORD);

    Map<String, Object> metrics = admin.getForObject(API + "/metrics", Map.class);
    assertThat(metrics).containsKeys("cpu", "memory", "disks", "hikari", "threads");
    List<?> pools = (List<?>) metrics.get("hikari");
    assertThat(pools).hasSize(1);
    assertThat(((Map<String, Object>) pools.get(0)).get("pool")).isEqualTo("test-pool");

    Map<String, Object> history = admin.getForObject(API + "/metrics/history?minutes=5", Map.class);
    assertThat((List<?>) history.get("points")).isNotEmpty();

    Map<String, Object> jvm = admin.getForObject(API + "/jvm", Map.class);
    assertThat(jvm).containsKeys("pid", "gc", "memoryPools", "threadStates");

    String dump = admin.getForObject(API + "/jvm/threads", String.class);
    assertThat(dump).contains("\"main\"").contains("java.lang.Thread.State");

    Map<String, Object> info = admin.getForObject(API + "/info", Map.class);
    assertThat(info.get("features")).isNotNull();
  }

  @Test
  @Order(5)
  void readOnlyUserCannotWrite() {
    TestRestTemplate admin = rest.withBasicAuth("superadmin", NEW_PASSWORD);
    ResponseEntity<Map> created = admin.postForEntity(API + "/accounts",
        Map.of("username", "Viewer", "role", "USER"), Map.class);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(created.getHeaders().getCacheControl()).contains("no-store");
    String temporary = (String) created.getBody().get("temporaryPassword");

    TestRestTemplate viewer = rest.withBasicAuth("viewer", temporary);
    viewer.postForEntity(API + "/me/password",
        Map.of("currentPassword", temporary, "newPassword", "Viewer-Pass-123"), Void.class);
    viewer = rest.withBasicAuth("viewer", "Viewer-Pass-123");

    assertThat(viewer.getForEntity(API + "/metrics", Map.class).getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(viewer.postForEntity(API + "/query/update", Map.of("query", "update x set a = 1"),
        Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(viewer.getForEntity(API + "/accounts", Map.class).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  @Order(6)
  void hostSecurityStillGuardsTheApplicationAndErrorsAreAudited() {
    ResponseEntity<String> hello = rest.getForEntity("/hello", String.class);
    assertThat(hello.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    String traceId = hello.getHeaders().getFirst("X-Trace-Id");
    assertThat(traceId).isNotBlank();

    TestRestTemplate admin = rest.withBasicAuth("superadmin", NEW_PASSWORD);
    ResponseEntity<Map<String, Object>> audit = admin.exchange(
        API + "/audit?traceId=" + traceId, HttpMethod.GET, HttpEntity.EMPTY,
        new ParameterizedTypeReference<>() {});
    assertThat(audit.getBody().get("total")).isEqualTo(1);
    Map<String, Object> row = (Map<String, Object>) ((List<?>) audit.getBody().get("list")).get(0);
    assertThat(row.get("path")).isEqualTo("/hello");
    assertThat(row.get("statusCode")).isEqualTo(401);
  }

  @Test
  @Order(7)
  void locksOutAfterRepeatedFailures() {
    for (int i = 0; i < 5; i++) {
      rest.withBasicAuth("viewer", "wrong-" + i).postForEntity(API + "/auth", null, Map.class);
    }
    ResponseEntity<Map> locked = rest.withBasicAuth("viewer", "Viewer-Pass-123")
        .postForEntity(API + "/auth", null, Map.class);
    assertThat(locked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
  }

  @Configuration
  @EnableAutoConfiguration
  static class HostApplication {

    @Bean
    SecurityFilterChain hostSecurity(HttpSecurity http) throws Exception {
      return http
          .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
          .httpBasic(Customizer.withDefaults())
          .build();
    }

    @RestController
    static class HelloController {

      @GetMapping("/hello")
      String hello() {
        return "hello";
      }
    }
  }
}
