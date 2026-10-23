package io.github.devpanel;

import static io.github.devpanel.PanelClient.API;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
 * login for everything: the panel must still be reachable with its own accounts only, and only
 * after password plus authenticator code.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = DevPanelIntegrationTest.HostApplication.class)
@TestMethodOrder(OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DevPanelIntegrationTest {

  private static final String ADMIN_PASSWORD = "Changed-Pass-12";
  private static final String VIEWER_PASSWORD = "Viewer-Pass-123";

  @Autowired
  private TestRestTemplate rest;

  private PanelClient admin;
  private PanelClient viewer;

  @Test
  @Order(1)
  void servesTheUiWithoutLogin() {
    ResponseEntity<String> redirect = rest.getForEntity("/dev-panel", String.class);
    assertThat(redirect.getStatusCode()).isEqualTo(HttpStatus.FOUND);
    // Relative, so a proxy prefix such as /api is kept
    assertThat(redirect.getHeaders().getFirst(HttpHeaders.LOCATION)).isEqualTo("dev-panel/");

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
  }

  @Test
  @Order(2)
  void rejectsAnonymousForgedAndWrongRequests() {
    PanelClient anonymous = new PanelClient(rest);
    ResponseEntity<Map<String, Object>> me = anonymous.get("/me");
    assertThat(me.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(me.getHeaders().containsKey(HttpHeaders.WWW_AUTHENTICATE)).isFalse();
    assertThat(me.getBody()).containsKey("traceId");

    // A cross-site form cannot add the header
    ResponseEntity<String> forged = rest.exchange(API + "/login", HttpMethod.POST,
        new HttpEntity<>(Map.of("username", "superadmin", "password", "Initial-Pass-1"),
            anonymous.headers(false)), String.class);
    assertThat(forged.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

    assertThat(anonymous.post("/login", Map.of("username", "superadmin", "password", "nope"))
        .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  @Order(3)
  void firstLoginForcesNewPasswordAndAuthenticatorSetup() {
    admin = new PanelClient(rest);
    ResponseEntity<Map<String, Object>> login = admin.post("/login",
        Map.of("username", "superadmin", "password", "Initial-Pass-1"));
    assertThat(login.getBody().get("stage")).isEqualTo("PASSWORD_CHANGE");
    assertThat(login.getHeaders().getFirst(HttpHeaders.SET_COOKIE))
        .contains("HttpOnly").contains("SameSite=Strict").contains("Path=/;");

    // Nothing but the login steps until it is finished
    assertThat(admin.get("/metrics").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(admin.post("/login/password", Map.of("newPassword", "short")).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);

    ResponseEntity<Map<String, Object>> setup = admin.post("/login/password",
        Map.of("newPassword", ADMIN_PASSWORD));
    assertThat(setup.getBody().get("stage")).isEqualTo("TOTP_SETUP");
    @SuppressWarnings("unchecked")
    Map<String, Object> totp = (Map<String, Object>) setup.getBody().get("setup");
    admin.secret = (String) totp.get("secret");

    assertThat(admin.post("/login/code", Map.of("code", "000000")).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(admin.get("/metrics").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

    String pendingCookie = admin.cookie;
    ResponseEntity<Map<String, Object>> done =
        admin.post("/login/code", Map.of("code", admin.nextCode()));
    assertThat(done.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(done.getBody()).doesNotContainKeys("passwordHash", "totpSecret");

    // A new token once logged in: the one from before the code is worthless
    assertThat(admin.cookie).isNotEqualTo(pendingCookie);
    PanelClient stale = new PanelClient(rest);
    stale.cookie = pendingCookie;
    assertThat(stale.get("/me").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(admin.get("/me").getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  @Order(4)
  void showsMetricsHikariHistoryAndJvm() {
    Map<String, Object> metrics = admin.get("/metrics").getBody();
    assertThat(metrics).containsKeys("cpu", "memory", "disks", "hikari", "threads");
    List<?> pools = (List<?>) metrics.get("hikari");
    assertThat(pools).hasSize(1);
    assertThat(((Map<?, ?>) pools.get(0)).get("pool")).isEqualTo("test-pool");

    assertThat((List<?>) admin.get("/metrics/history?minutes=5").getBody().get("points"))
        .isNotEmpty();
    assertThat(admin.get("/jvm").getBody()).containsKeys("pid", "gc", "memoryPools");
    assertThat(admin.getText("/jvm/threads").getBody()).contains("java.lang.Thread.State");
    assertThat(admin.get("/info").getBody().get("features")).isNotNull();
  }

  @Test
  @Order(5)
  void readOnlyUserCannotWrite() {
    ResponseEntity<Map<String, Object>> created = admin.post("/accounts",
        Map.of("username", "Viewer", "role", "USER"));
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(created.getHeaders().getCacheControl()).contains("no-store");

    viewer = new PanelClient(rest);
    viewer.firstLogin("viewer", (String) created.getBody().get("temporaryPassword"),
        VIEWER_PASSWORD);

    assertThat(viewer.get("/metrics").getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(viewer.post("/query/update", Map.of("query", "update x set a = 1 where true"))
        .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(viewer.get("/accounts").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  @Order(6)
  void hostSecurityStillGuardsTheApplicationAndErrorsAreAudited() {
    ResponseEntity<String> hello = rest.getForEntity("/hello", String.class);
    assertThat(hello.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    String traceId = hello.getHeaders().getFirst("X-Trace-Id");
    assertThat(traceId).isNotBlank();

    Map<String, Object> audit = admin.get("/audit?traceId=" + traceId).getBody();
    assertThat(audit.get("total")).isEqualTo(1);
    Map<?, ?> row = (Map<?, ?>) ((List<?>) audit.get("list")).get(0);
    assertThat(row.get("path")).isEqualTo("/hello");
    assertThat(row.get("statusCode")).isEqualTo(401);
  }

  @Test
  @Order(7)
  void aCodeWorksOnlyOnceAndLogoutEndsTheSession() {
    String code = admin.nextCode();
    assertThat(admin.post("/logout", null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(admin.get("/me").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

    // Use the code once, then try it again on a fresh login
    admin.post("/login", Map.of("username", "superadmin", "password", ADMIN_PASSWORD));
    assertThat(admin.post("/login/code", Map.of("code", code)).getStatusCode())
        .isEqualTo(HttpStatus.OK);
    admin.post("/login", Map.of("username", "superadmin", "password", ADMIN_PASSWORD));
    assertThat(admin.post("/login/code", Map.of("code", code)).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(admin.post("/login/code", Map.of("code", admin.nextCode())).getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  @Test
  @Order(8)
  void resettingTwoFactorLogsTheUserOutAndAsksForSetupAgain() {
    long viewerId = ((Number) viewer.get("/me").getBody().get("id")).longValue();
    assertThat(admin.post("/accounts/" + viewerId + "/reset-two-factor", null).getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(viewer.get("/me").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

    ResponseEntity<Map<String, Object>> login = viewer.post("/login",
        Map.of("username", "viewer", "password", VIEWER_PASSWORD));
    assertThat(login.getBody().get("stage")).isEqualTo("TOTP_SETUP");
  }

  @Test
  @Order(9)
  void locksOutAfterRepeatedFailures() {
    PanelClient guesser = new PanelClient(rest);
    for (int i = 0; i < 5; i++) {
      guesser.post("/login", Map.of("username", "viewer", "password", "wrong-" + i));
    }
    assertThat(guesser.post("/login", Map.of("username", "viewer", "password", VIEWER_PASSWORD))
        .getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
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
