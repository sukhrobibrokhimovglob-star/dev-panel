package io.github.devpanel;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.devpanel.account.Totp;
import java.util.List;
import java.util.Map;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** A browser of the panel in tests: keeps the session cookie and plays the authenticator app. */
class PanelClient {

  static final String API = "/dev-panel/api";

  private final TestRestTemplate rest;
  String cookie;
  String secret;
  private long lastStep = -1;

  PanelClient(TestRestTemplate rest) {
    this.rest = rest;
  }

  ResponseEntity<Map<String, Object>> get(String path) {
    return exchange(HttpMethod.GET, path, null);
  }

  ResponseEntity<String> getText(String path) {
    return rest.exchange(API + path, HttpMethod.GET, new HttpEntity<>(headers(true)),
        String.class);
  }

  ResponseEntity<Map<String, Object>> post(String path, Object body) {
    return exchange(HttpMethod.POST, path, body);
  }

  ResponseEntity<Map<String, Object>> exchange(HttpMethod method, String path, Object body) {
    ResponseEntity<Map<String, Object>> response = rest.exchange(API + path, method,
        new HttpEntity<>(body, headers(true)), new ParameterizedTypeReference<>() {});
    List<String> setCookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
    if (setCookies != null) {
      for (String setCookie : setCookies) {
        if (setCookie.startsWith("DEVPANEL_SESSION=")) {
          cookie = setCookie.substring(0, setCookie.indexOf(';'));
        }
      }
    }
    return response;
  }

  HttpHeaders headers(boolean csrf) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (csrf) {
      headers.set("X-DevPanel-Request", "1");
    }
    if (cookie != null) {
      headers.set(HttpHeaders.COOKIE, cookie);
    }
    return headers;
  }

  /** The next code the app would accept: never one already used. */
  String nextCode() {
    long step = Math.max(Totp.stepAt(System.currentTimeMillis()) - 1, lastStep + 1);
    lastStep = step;
    return Totp.codeForStep(secret, step);
  }

  /** The whole first login: password, new password, authenticator setup, code. */
  void firstLogin(String username, String temporaryPassword, String newPassword) {
    ResponseEntity<Map<String, Object>> login = post("/login",
        Map.of("username", username, "password", temporaryPassword));
    assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(login.getBody().get("stage")).isEqualTo("PASSWORD_CHANGE");

    ResponseEntity<Map<String, Object>> changed = post("/login/password",
        Map.of("newPassword", newPassword));
    assertThat(changed.getBody().get("stage")).isEqualTo("TOTP_SETUP");
    @SuppressWarnings("unchecked")
    Map<String, Object> setup = (Map<String, Object>) changed.getBody().get("setup");
    secret = (String) setup.get("secret");
    assertThat((String) setup.get("uri")).startsWith("otpauth://totp/");

    ResponseEntity<Map<String, Object>> done = post("/login/code", Map.of("code", nextCode()));
    assertThat(done.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(done.getBody().get("totpEnabled")).isEqualTo(true);
  }

  /** A later login: password, then a code. */
  ResponseEntity<Map<String, Object>> login(String username, String password) {
    ResponseEntity<Map<String, Object>> login = post("/login",
        Map.of("username", username, "password", password));
    assertThat(login.getBody().get("stage")).isEqualTo("TOTP");
    return post("/login/code", Map.of("code", nextCode()));
  }
}
