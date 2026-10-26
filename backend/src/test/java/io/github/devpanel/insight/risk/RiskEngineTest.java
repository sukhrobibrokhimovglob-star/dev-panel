package io.github.devpanel.insight.risk;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.devpanel.insight.risk.Risk.Category;
import io.github.devpanel.insight.risk.Risk.Severity;
import io.github.devpanel.support.DevPanelSchema;
import java.util.ArrayList;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class RiskEngineTest {

  @Test
  void aRiskStaysOpenWhileItsDetectorCannotTellYet() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:risk-engine;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    new DevPanelSchema(jdbc).create();

    Fake detector = new Fake();
    RiskEngine engine = new RiskEngine(List.of(detector), new RiskStore(jdbc));
    detector.risks.add(new Risk("code.batch:POST /items", Category.WEAKNESS, "code",
        Severity.INFO, "writes one by one", null, null, null, null, null, null, "api", 1));
    engine.run();
    assertThat(engine.overview().open()).hasSize(1);

    // Just restarted: no requests seen yet, so the risk is missing but not fixed
    detector.risks.clear();
    detector.knows = false;
    engine.run();
    assertThat(engine.overview().open()).hasSize(1);
    assertThat(engine.overview().resolved()).isEmpty();

    // Enough traffic seen and still not reported: now it is resolved
    detector.knows = true;
    engine.run();
    assertThat(engine.overview().open()).isEmpty();
    assertThat(engine.overview().resolved()).hasSize(1);
  }

  private static final class Fake implements RiskDetector {

    final List<Risk> risks = new ArrayList<>();
    boolean knows = true;

    @Override
    public String name() {
      return "code";
    }

    @Override
    public List<Risk> detect() {
      return new ArrayList<>(risks);
    }

    @Override
    public boolean canResolve() {
      return knows;
    }
  }
}
