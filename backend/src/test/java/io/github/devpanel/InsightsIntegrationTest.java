package io.github.devpanel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

/**
 * The monitoring side inside a host application with failing, slow and calling endpoints and a
 * scheduled job: each must show up where the panel's tabs read it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = InsightsIntegrationTest.HostApplication.class,
    properties = {
        "spring.datasource.url=jdbc:h2:mem:insights;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.application.name=insight-test",
        "devpanel.insights.slow-request=100ms",
        "devpanel.insights.clock-reference-url=",
        "some.service.password=do-not-show-me"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InsightsIntegrationTest {

  @Autowired
  private TestRestTemplate rest;

  @Autowired
  private HostApplication.Ticker ticker;

  @Autowired
  private io.github.devpanel.insight.deps.DependencyChecks dependencyChecks;

  private PanelClient admin;

  @BeforeAll
  void loginAndMakeTraffic() throws Exception {
    admin = new PanelClient(rest);
    admin.firstLogin("superadmin", "Initial-Pass-1", "Changed-Pass-12");
    for (int i = 0; i < 3; i++) {
      rest.getForEntity("/boom", String.class);
      rest.getForEntity("/items/" + i, String.class);
    }
    rest.getForEntity("/slow", String.class);
    rest.getForEntity("/call-out", String.class);
    rest.getForEntity("/orgs/42/report", String.class);
    rest.getForEntity("/orgs/42/report", String.class);
    // A scanner behind the proxy: many different missing pages, and an attack path
    org.springframework.http.HttpHeaders scanner = new org.springframework.http.HttpHeaders();
    scanner.set("X-Forwarded-For", "203.0.113.7");
    for (int i = 0; i < 31; i++) {
      rest.exchange("/missing-" + i, org.springframework.http.HttpMethod.GET,
          new org.springframework.http.HttpEntity<>(scanner), String.class);
    }
    rest.exchange("/.env", org.springframework.http.HttpMethod.GET,
        new org.springframework.http.HttpEntity<>(scanner), String.class);
    dependencyChecks.checkAll();
    for (int i = 0; i < 50 && ticker.runs() == 0; i++) {
      Thread.sleep(100);
    }
  }

  @Test
  void healthReportCoversTheAreas() {
    Map<String, Object> health = admin.get("/health").getBody();
    @SuppressWarnings("unchecked")
    Map<String, Object> report = (Map<String, Object>) health.get("report");
    assertThat(report.get("overall")).isNotNull();
    List<?> checks = (List<?>) report.get("checks");
    List<Object> areas = checks.stream().map(check -> (Object) ((Map<?, ?>) check).get("area")).toList();
    assertThat(areas).contains("server", "memory", "threads", "disk", "api", "errors");
    assertThat(health.get("deploy")).isNotNull();
  }

  @Test
  void endpointsAreGroupedByPatternWithPercentiles() {
    List<Map<String, Object>> rows = endpoints();
    assertThat(rows).anySatisfy(row -> {
      Map<?, ?> stats = (Map<?, ?>) row.get("stats");
      assertThat(stats.get("endpoint")).isEqualTo("GET /items/{id}");
      assertThat(((Number) stats.get("calls")).intValue()).isEqualTo(3);
    });
    assertThat(rows).anySatisfy(row -> {
      Map<?, ?> stats = (Map<?, ?>) row.get("stats");
      assertThat(stats.get("endpoint")).isEqualTo("GET /boom");
      assertThat(((Number) stats.get("serverErrors")).intValue()).isEqualTo(3);
    });
    List<?> slow = list("/requests/slow");
    assertThat(slow).anySatisfy(item -> assertThat(((Map<?, ?>) item).get("endpoint"))
        .isEqualTo("GET /slow"));
  }

  @Test
  void theSameFailureIsOneGroupPointingAtTheCode() {
    List<?> groups = list("/exceptions");
    assertThat(groups).anySatisfy(group -> {
      Map<?, ?> view = (Map<?, ?>) group;
      assertThat(view.get("type")).isEqualTo("java.lang.IllegalStateException");
      assertThat((String) view.get("location")).contains("InsightsIntegrationTest");
      assertThat(((Number) view.get("count")).intValue()).isEqualTo(3);
      assertThat(view.get("isNew")).isEqualTo(true);
    });
  }

  @Test
  void scheduledJobsAndOutboundCallsAreTracked() {
    List<?> jobs = list("/jobs");
    assertThat(jobs).anySatisfy(job -> {
      Map<?, ?> view = (Map<?, ?>) job;
      assertThat(view.get("name")).isEqualTo("Ticker.tick");
      assertThat(((Number) view.get("runs")).intValue()).isPositive();
    });
    List<?> targets = list("/integrations");
    assertThat(targets).anySatisfy(target -> {
      Map<?, ?> view = (Map<?, ?>) target;
      assertThat((String) view.get("host")).contains("127.0.0.1:9");
      assertThat(((Number) view.get("errors")).intValue()).isPositive();
    });
  }

  @Test
  void memoryLogLevelsConfigAndDeploys() {
    assertThat(admin.get("/memory").getBody()).containsKey("analysis");

    assertThat(admin.post("/logging/levels",
        Map.of("logger", "com.example", "level", "DEBUG", "minutes", 5)).getStatusCode())
        .isEqualTo(HttpStatus.OK);
    List<?> loggers = list("/logging/levels?filter=com.example");
    assertThat(loggers).anySatisfy(logger -> {
      Map<?, ?> view = (Map<?, ?>) logger;
      assertThat(view.get("effective")).isEqualTo("DEBUG");
      assertThat(view.get("changedUntil")).isNotNull();
    });

    Map<String, Object> config = admin.get("/config?filter=some.service").getBody();
    assertThat(config.toString()).contains("******").doesNotContain("do-not-show-me");

    assertThat(admin.get("/deploys").getBody()).containsKeys("current", "recent");
    assertThat(admin.get("/alerts").getBody()).containsKeys("settings", "history");
  }

  @Test
  void ownSessionsAreListedAndCanBeEnded() {
    List<?> sessions = list("/me/sessions");
    assertThat(sessions).anySatisfy(session ->
        assertThat(((Map<?, ?>) session).get("current")).isEqualTo(true));
    assertThat(admin.get("/me").getBody().get("totpEnabledAt")).isNotNull();
  }

  @Test
  void repeatedQueriesAreAnNPlusOneWithTheirFullWaterfall() {
    List<?> suspects = list("/requests/n-plus-one");
    assertThat(suspects).anySatisfy(item -> {
      Map<?, ?> suspect = (Map<?, ?>) item;
      assertThat(suspect.get("endpoint")).isEqualTo("GET /orgs/{organizationId}/report");
      // 16 statements of one shape ("select ? as id"): 15 of them are repeats
      assertThat(((Number) suspect.get("wasted")).intValue()).isEqualTo(15);
    });

    Map<?, ?> nPlusOne = (Map<?, ?>) suspects.get(0);
    Map<String, Object> trace = admin.get("/requests/traces/" + nPlusOne.get("traceId")).getBody();
    assertThat(trace.get("reason")).isEqualTo("N_PLUS_ONE");
    assertThat(trace.get("tenant")).isEqualTo("42");
    List<?> spans = (List<?>) trace.get("spans");
    assertThat(spans).hasSize(16).allSatisfy(span ->
        assertThat(((Map<?, ?>) span).get("kind")).isEqualTo("SQL"));
    assertThat(((Number) trace.get("rows")).intValue()).isEqualTo(16);
    assertThat(((Number) trace.get("responseBytes")).intValue()).isPositive();
  }

  @Test
  void endpointsSplitTheirTimeAndCountRowsAndBytes() {
    assertThat(endpoints()).anySatisfy(row -> {
      Map<?, ?> stats = (Map<?, ?>) row.get("stats");
      assertThat(stats.get("endpoint")).isEqualTo("GET /orgs/{organizationId}/report");
      assertThat(((Number) stats.get("avgRows")).intValue()).isEqualTo(16);
      assertThat(((Number) stats.get("avgBytes")).intValue()).isPositive();
      assertThat(((Number) stats.get("sqlPerCall")).doubleValue()).isEqualTo(16.0);
    });
  }

  @Test
  void usageIsCountedPerOrganizationAndUnusedEndpointsAreListed() {
    Map<String, Object> usage = admin.get("/usage").getBody();
    assertThat((List<?>) usage.get("tenants")).anySatisfy(item -> {
      Map<?, ?> tenant = (Map<?, ?>) item;
      assertThat(tenant.get("key")).isEqualTo("42");
      assertThat(((Number) tenant.get("total")).intValue()).isEqualTo(2);
    });
    Map<String, Object> unused = admin.get("/usage/unused").getBody();
    assertThat(unused.toString()).contains("GET /never-called").doesNotContain("GET /boom");
  }

  @Test
  void scanningFromOneAddressIsDetected() {
    Map<String, Object> security = admin.get("/security").getBody();
    assertThat(security.toString()).contains("203.0.113.7");
    assertThat((List<?>) security.get("findings")).anySatisfy(item -> {
      Map<?, ?> finding = (Map<?, ?>) item;
      assertThat(finding.get("ip")).isEqualTo("203.0.113.7");
      assertThat(finding.get("type")).isIn("SCAN", "PROBE");
    });
    // Refused with a token is a failed login; anonymous refusals are not
    assertThat((List<?>) security.get("findings")).noneSatisfy(item -> {
      Map<?, ?> finding = (Map<?, ?>) item;
      assertThat(finding.get("ip")).isEqualTo("203.0.113.7");
      assertThat(finding.get("type")).isEqualTo("BRUTE_FORCE");
    });
  }

  @Test
  void capacityDependenciesAndDeployReport() {
    Map<String, Object> tomcat = admin.get("/server/tomcat").getBody();
    assertThat(tomcat).containsKey("state");
    assertThat(list("/dependencies")).anySatisfy(item -> {
      Map<?, ?> target = (Map<?, ?>) item;
      assertThat(target.get("name")).isEqualTo("Database");
      assertThat(target.get("up")).isEqualTo(true);
    });
    assertThat(admin.get("/deploys/report").getBody()).containsKeys("faster", "slower",
        "newErrors");
  }

  private List<Map<String, Object>> endpoints() {
    ResponseEntity<List<Map<String, Object>>> response = rest.exchange(
        PanelClient.API + "/requests/endpoints", org.springframework.http.HttpMethod.GET,
        new org.springframework.http.HttpEntity<>(admin.headers(true)),
        new org.springframework.core.ParameterizedTypeReference<>() {});
    return response.getBody();
  }

  private List<?> list(String path) {
    ResponseEntity<List<Object>> response = rest.exchange(PanelClient.API + path,
        org.springframework.http.HttpMethod.GET,
        new org.springframework.http.HttpEntity<>(admin.headers(true)),
        new org.springframework.core.ParameterizedTypeReference<>() {});
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    return response.getBody();
  }

  @Configuration
  @EnableAutoConfiguration
  @EnableScheduling
  static class HostApplication {

    @Bean
    org.springframework.security.web.SecurityFilterChain openSecurity(
        org.springframework.security.config.annotation.web.builders.HttpSecurity http)
        throws Exception {
      return http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
          .csrf().disable().build();
    }

    // Built the way gp builds it, without Boot's RestTemplateBuilder bean
    @Bean
    RestTemplate restTemplate() {
      return new RestTemplateBuilder().build();
    }

    @RestController
    static class Endpoints {

      private final RestTemplate restTemplate;
      private final javax.sql.DataSource dataSource;

      Endpoints(RestTemplate restTemplate, javax.sql.DataSource dataSource) {
        this.restTemplate = restTemplate;
        this.dataSource = dataSource;
      }

      @GetMapping("/boom")
      String boom() {
        throw new IllegalStateException("broken on purpose");
      }

      @GetMapping("/items/{id}")
      String item(@PathVariable int id) {
        return "item " + id;
      }

      @GetMapping("/slow")
      String slow() throws InterruptedException {
        Thread.sleep(150);
        return "slow";
      }

      // The classic N+1: one list query, then one query per row
      @GetMapping("/orgs/{organizationId}/report")
      String report(@PathVariable long organizationId) {
        org.springframework.jdbc.core.JdbcTemplate jdbc =
            new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        jdbc.queryForList("select 1 as id");
        for (int i = 1; i <= 15; i++) {
          jdbc.queryForObject("select " + i + " as id", Integer.class);
        }
        return "report of " + organizationId;
      }

      @GetMapping("/never-called")
      String neverCalled() {
        return "nobody calls me";
      }

      @GetMapping("/call-out")
      String callOut() {
        try {
          return restTemplate.getForObject("http://127.0.0.1:9/nothing", String.class);
        } catch (RuntimeException e) {
          return "failed as expected";
        }
      }
    }

    @Component
    static class Ticker {

      private final AtomicInteger runs = new AtomicInteger();

      @Scheduled(fixedRate = 200)
      public void tick() {
        runs.incrementAndGet();
      }

      // A method, not the field: the panel's job aspect makes this bean a proxy
      public int runs() {
        return runs.get();
      }
    }
  }
}
