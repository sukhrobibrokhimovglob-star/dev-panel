package io.github.devpanel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import javax.annotation.PostConstruct;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

/**
 * The 0.4 features inside a host application that has typical weak spots: lookups per row,
 * inserts one by one, personal data in its logs, an external call inside a transaction, a 400
 * with a message, reference rows inserted at start. Each must show up where the panel looks.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = RisksAndToolsIntegrationTest.HostApplication.class,
    properties = {
        "spring.datasource.url=jdbc:h2:mem:risks;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.application.name=risk-test",
        "devpanel.insights.clock-reference-url=",
        "devpanel.insights.dependency-checks=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RisksAndToolsIntegrationTest {

  @Autowired
  private TestRestTemplate rest;

  private PanelClient admin;
  private String piiTraceId;

  @BeforeAll
  void loginAndMakeTraffic() {
    admin = new PanelClient(rest);
    admin.firstLogin("superadmin", "Initial-Pass-1", "Changed-Pass-12");
    for (int i = 0; i < 5; i++) {
      rest.getForEntity("/patients", String.class);
      rest.postForEntity("/items", null, String.class);
      rest.getForEntity("/in-transaction", String.class);
    }
    piiTraceId = rest.getForEntity("/pii", String.class).getHeaders().getFirst("X-Trace-Id");
    HttpHeaders json = new HttpHeaders();
    json.setContentType(MediaType.APPLICATION_JSON);
    json.set("organizationId", "7");
    rest.exchange("/register", HttpMethod.POST, new HttpEntity<>(
        "{\"name\":\"Ali\",\"password\":\"secret-value-1\"}", json), String.class);
  }

  @Test
  void eachSqlStepKnowsItsCodeAndKindAndExactRepeats() {
    Map<String, Object> trace = traceOf("GET /patients");
    List<?> spans = (List<?>) trace.get("spans");
    assertThat(spans).allSatisfy(span ->
        assertThat((String) ((Map<?, ?>) span).get("location"))
            .contains("RisksAndToolsIntegrationTest"));
    List<?> repeated = (List<?>) trace.get("repeated");
    assertThat(repeated).anySatisfy(item -> {
      Map<?, ?> shape = (Map<?, ?>) item;
      assertThat(shape.get("kind")).isEqualTo("SELECT");
      assertThat(((Number) shape.get("count")).intValue()).isEqualTo(12);
      // the same patient (id 7) loaded 12 times
      assertThat(((Number) shape.get("exactRepeats")).intValue()).isEqualTo(11);
    });
  }

  @Test
  void repeatedInsertsAreNotAnNPlusOneButABatchingHint() {
    List<?> suspects = list("/requests/n-plus-one");
    assertThat(suspects).noneSatisfy(item ->
        assertThat(((Map<?, ?>) item).get("endpoint")).isEqualTo("POST /items"));
    Map<String, Object> risks = runRisks();
    assertThat(keys(risks)).contains("code.batch:POST /items",
        "code.n-plus-one:GET /patients", "code.same-row:GET /patients",
        "code.external-in-tx:GET /in-transaction");
  }

  @Test
  void personalDataInTheLogsBecomesARisk() {
    List<?> findings = list("/privacy/logs");
    assertThat(findings).anySatisfy(item -> {
      Map<?, ?> finding = (Map<?, ?>) item;
      assertThat(finding.get("kind")).isEqualTo("PNFL");
      assertThat((String) finding.get("example")).doesNotContain("43001780190052");
    });
    assertThat(keys(runRisks())).anyMatch(key -> key.startsWith("privacy.logs:"));
  }

  @Test
  void risksCanBeSnoozedAndComeBackWhenReopened() {
    runRisks();
    String key = "code.batch:POST /items";
    assertThat(admin.post("/risks/snooze", Map.of("key", key, "days", 3)).getStatusCode())
        .isEqualTo(HttpStatus.OK);
    Map<?, ?> snoozed = item(admin.get("/risks").getBody(), key);
    assertThat(snoozed.get("silenced")).isEqualTo(true);
    admin.post("/risks/reopen", Map.of("key", key));
    assertThat(item(admin.get("/risks").getBody(), key).get("silenced")).isEqualTo(false);
  }

  @Test
  void anErrorAnswerIsKeptWithTheRequestAndSecretsMasked() {
    Map<String, Object> trace = traceOf("POST /register");
    assertThat((String) trace.get("response")).contains("phone must not be blank");
    Map<?, ?> request = (Map<?, ?>) trace.get("request");
    assertThat((String) request.get("body")).contains("\"password\":\"***")
        .doesNotContain("secret-value-1");
    assertThat(request.get("headers").toString().toLowerCase()).contains("organizationid=7");

    Map<String, Object> audit = admin.get("/audit?path=/register").getBody();
    List<?> entries = (List<?>) audit.get("list");
    assertThat((String) ((Map<?, ?>) entries.get(0)).get("responseBody"))
        .contains("phone must not be blank");
  }

  @Test
  void logsOfARequestComeFromMemoryWhenTheFilesDoNotHaveThem() {
    Map<String, Object> logs = admin.get("/logs/trace/" + piiTraceId).getBody();
    assertThat(logs.get("source")).isEqualTo("memory");
    assertThat(logs.toString()).contains("loaded the patient");
  }

  @Test
  void theStartupReportSeesRowsInsertedAtStart() {
    Map<String, Object> startup = admin.get("/startup").getBody();
    Map<?, ?> report = (Map<?, ?>) startup.get("report");
    assertThat(((Map<?, ?>) report.get("inserts")).get("countries")).isEqualTo(3);
    assertThat(((Number) report.get("uptimeMs")).longValue()).isPositive();
  }

  @Test
  void theRunnerCallsTheApplicationAndKeepsTheWaterfall() {
    ResponseEntity<Map<String, Object>> sent = admin.post("/runner/send",
        Map.of("method", "GET", "path", "/patients"));
    assertThat(sent.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(sent.getBody().get("status")).isEqualTo(200);
    String traceId = (String) sent.getBody().get("traceId");
    assertThat(admin.get("/requests/traces/" + traceId).getBody().get("reason"))
        .isEqualTo("RUNNER");

    assertThat(admin.post("/runner/send", Map.of("method", "POST", "path", "/items"))
        .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(admin.post("/runner/send", Map.of("method", "GET", "path", "/dev-panel/api/me"))
        .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(admin.get("/panel-audit?action=runner.send").getBody().toString())
        .contains("GET /patients");
  }

  @Test
  void endpointDetailsFillTheRunnerForm() {
    Map<String, Object> details = admin.get("/endpoints/details?endpoint=POST /register")
        .getBody();
    assertThat(details.get("bodyExample").toString()).contains("name=", "password=");
    assertThat((List<?>) details.get("headers")).anySatisfy(header ->
        assertThat(((Map<?, ?>) header).get("name")).isEqualTo("organizationId"));
  }

  @Test
  void smokeChecksRunAndAFailureTurnsHealthRed() {
    ResponseEntity<Map<String, Object>> good = admin.post("/smoke", Map.of("name", "patients",
        "method", "GET", "path", "/patients", "expectedStatus", 200, "maxMs", 5000,
        "enabled", true));
    assertThat(good.getStatusCode()).isEqualTo(HttpStatus.OK);
    admin.post("/smoke", Map.of("name", "wrong", "method", "GET", "path", "/patients",
        "expectedStatus", 201, "maxMs", 5000, "enabled", true));
    assertThat(admin.post("/smoke", Map.of("name", "writes", "method", "POST", "path", "/items",
        "expectedStatus", 200, "maxMs", 5000, "enabled", true)).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);

    ResponseEntity<List<Object>> runs = rest.exchange(PanelClient.API + "/smoke/run",
        HttpMethod.POST, new HttpEntity<>(Map.of(), admin.headers(true)),
        new ParameterizedTypeReference<>() {});
    assertThat(runs.getBody()).hasSize(2);
    assertThat(admin.get("/health").getBody().toString()).contains("Smoke check failed: wrong");
  }

  @Test
  void goalsAreMeasuredFromTheLatencyHistograms() {
    ResponseEntity<Map<String, Object>> created = admin.post("/slo", Map.of(
        "endpoint", "GET /patients", "targetMs", 5000, "objective", 0.95));
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
    List<?> goals = list("/slo");
    assertThat(goals).anySatisfy(item -> {
      Map<?, ?> status = (Map<?, ?>) item;
      assertThat(((Number) status.get("weekCalls")).intValue()).isGreaterThanOrEqualTo(5);
      assertThat(((Number) status.get("weekShare")).doubleValue()).isEqualTo(1.0);
    });
  }

  @Test
  void theProfilerRecordsAFlameGraph() throws InterruptedException {
    Map<String, Object> started = admin.post("/profiler/start", Map.of("seconds", 3,
        "scope", "all")).getBody();
    String id = (String) started.get("id");
    Map<String, Object> profile = null;
    for (int i = 0; i < 60; i++) {
      Thread.sleep(200);
      profile = admin.get("/profiler/" + id).getBody();
      if ("DONE".equals(profile.get("status"))) {
        break;
      }
    }
    assertThat(profile.get("status")).isEqualTo("DONE");
    assertThat(((Number) profile.get("samples")).intValue()).isPositive();
    assertThat(profile.get("root")).isNotNull();
  }

  @Test
  void testReportsAndCoverageAreMergedWithProduction() {
    String report = "{\"format\":1,\"generatedAt\":\"2026-10-01T10:00:00Z\","
        + "\"gitCommit\":\"abc\",\"tests\":[{\"id\":\"PatientsTest#lists\",\"status\":"
        + "\"SUCCESSFUL\",\"durationMs\":5},{\"id\":\"ItemsTest#saves\",\"status\":\"FAILED\","
        + "\"durationMs\":7,\"failure\":\"AssertionError: no\"}],\"hits\":[{\"test\":"
        + "\"PatientsTest#lists\",\"endpoint\":\"GET /patients\",\"status\":200,\"count\":1},"
        + "{\"test\":\"ItemsTest#saves\",\"endpoint\":\"POST /items\",\"status\":200,"
        + "\"count\":1}]}";
    assertThat(admin.post("/tests/upload", Map.of("content", report)).getBody().get("kind"))
        .isEqualTo("tests");
    String csv = "GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,"
        + "BRANCH_COVERED,LINE_MISSED,LINE_COVERED,COMPLEXITY_MISSED,COMPLEXITY_COVERED,"
        + "METHOD_MISSED,METHOD_COVERED\napp,com.example,Patients,0,10,0,2,0,8,0,1,0,1\n"
        + "app,com.example,Unused,30,0,4,0,12,0,3,0,3,0\n";
    assertThat(admin.post("/tests/upload", Map.of("content", csv)).getBody().get("kind"))
        .isEqualTo("coverage");

    Map<String, Object> tests = admin.get("/tests").getBody();
    assertThat(((Map<?, ?>) tests.get("summary")).get("failed")).isEqualTo(1);
    List<?> endpoints = (List<?>) tests.get("list");
    assertThat(endpoints).anySatisfy(item -> {
      Map<?, ?> endpoint = (Map<?, ?>) item;
      assertThat(endpoint.get("endpoint")).isEqualTo("POST /items");
      assertThat(endpoint.get("failingTests")).isEqualTo(List.of("ItemsTest#saves"));
    });
    Map<?, ?> coverage = (Map<?, ?>) tests.get("coverage");
    assertThat(((Number) coverage.get("lines")).doubleValue()).isEqualTo(0.4);
    assertThat(coverage.toString()).contains("com.example.Unused");
  }

  @Test
  void dataBrowserReadsTablesButNeverThePanelsOwn() {
    List<?> tables = list("/data/tables");
    assertThat(tables).noneSatisfy(table -> assertThat(((String) ((Map<?, ?>) table)
        .get("table")).toLowerCase()).startsWith("devpanel_"));
    Map<String, Object> page = admin.get("/data/rows?schema=PUBLIC&table=COUNTRIES"
        + "&filter=NAME:contains:uz&sort=NAME").getBody();
    assertThat(((Number) page.get("total")).intValue()).isEqualTo(1);

    assertThat(admin.post("/query", Map.of("query", "select * from devpanel_account"))
        .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void weeklyReportAndPlansOnH2() {
    assertThat(admin.getText("/report/weekly").getBody()).contains("weekly report");
    // Plans and the advisor need PostgreSQL; on H2 they say so instead of failing
    assertThat(admin.post("/database/explain", Map.of("sql", "select 1")).getStatusCode())
        .isEqualTo(HttpStatus.NOT_IMPLEMENTED);
  }

  private Map<String, Object> runRisks() {
    ResponseEntity<Map<String, Object>> response = admin.post("/risks/run", Map.of());
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    return response.getBody();
  }

  private static List<String> keys(Map<String, Object> risks) {
    return ((List<?>) risks.get("open")).stream()
        .map(item -> (String) ((Map<?, ?>) ((Map<?, ?>) item).get("risk")).get("key"))
        .toList();
  }

  private static Map<?, ?> item(Map<String, Object> risks, String key) {
    return ((List<?>) risks.get("open")).stream().map(item -> (Map<?, ?>) item)
        .filter(item -> key.equals(((Map<?, ?>) item.get("risk")).get("key")))
        .findFirst().orElseThrow();
  }

  private Map<String, Object> traceOf(String endpoint) {
    List<?> traces = list("/requests/traces");
    Map<?, ?> summary = traces.stream().map(item -> (Map<?, ?>) item)
        .filter(item -> endpoint.equals(item.get("endpoint"))).findFirst().orElseThrow();
    return admin.get("/requests/traces/" + summary.get("id")).getBody();
  }

  private List<?> list(String path) {
    ResponseEntity<List<Object>> response = rest.exchange(PanelClient.API + path,
        HttpMethod.GET, new HttpEntity<>(admin.headers(true)),
        new ParameterizedTypeReference<>() {});
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    return response.getBody();
  }

  @Configuration
  @EnableAutoConfiguration
  static class HostApplication {

    @Bean
    org.springframework.security.web.SecurityFilterChain openSecurity(
        org.springframework.security.config.annotation.web.builders.HttpSecurity http)
        throws Exception {
      return http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
          .csrf().disable().build();
    }

    @Bean
    RestTemplate restTemplate() {
      return new RestTemplateBuilder().build();
    }

    /** Reference data loaded at every start, the way the countries were in gp. */
    @Component
    static class Countries {

      private final JdbcTemplate jdbc;

      Countries(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
      }

      @PostConstruct
      void load() {
        jdbc.execute("create table if not exists countries (id int generated by default as identity primary key,"
            + " name varchar(50))");
        jdbc.execute("create table if not exists item_log (id int generated by default as identity primary key,"
            + " name varchar(50))");
        for (String name : List.of("Uzbekistan", "Kazakhstan", "Tajikistan")) {
          jdbc.update("insert into countries (name) values (?)", name);
        }
      }
    }

    record Registration(String name, String password, String phone) {
    }

    @RestController
    static class Endpoints {

      private static final Logger log = LoggerFactory.getLogger(Endpoints.class);

      private final JdbcTemplate jdbc;
      private final RestTemplate restTemplate;
      private final TransactionTemplate transactions;

      Endpoints(DataSource dataSource, RestTemplate restTemplate,
          PlatformTransactionManager transactionManager) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.restTemplate = restTemplate;
        this.transactions = new TransactionTemplate(transactionManager);
      }

      /** One lookup per row, and always the same patient. */
      @GetMapping("/patients")
      String patients() {
        for (int i = 0; i < 12; i++) {
          jdbc.queryForObject("select count(*) from countries where id = ?", Integer.class, 7);
        }
        return "patients";
      }

      @PostMapping("/items")
      String items() {
        for (int i = 0; i < 6; i++) {
          jdbc.update("insert into item_log (name) values (?)", "item " + i);
        }
        return "saved";
      }

      @GetMapping("/in-transaction")
      String inTransaction() {
        return transactions.execute(status -> {
          jdbc.queryForObject("select 1", Integer.class);
          try {
            return restTemplate.getForObject("http://127.0.0.1:9/mis", String.class);
          } catch (RuntimeException e) {
            return "MIS did not answer";
          }
        });
      }

      @GetMapping("/pii")
      String pii() {
        log.info("API: loaded the patient with pnfl 43001780190052");
        return "ok";
      }

      @PostMapping("/register")
      ResponseEntity<Map<String, String>> register(@RequestBody Registration registration,
          @org.springframework.web.bind.annotation.RequestHeader("organizationId")
          String organizationId) {
        return ResponseEntity.badRequest().body(Map.of("message", "phone must not be blank"));
      }

      @GetMapping("/items/{id}")
      String item(@PathVariable int id) {
        return "item " + id;
      }
    }
  }
}
