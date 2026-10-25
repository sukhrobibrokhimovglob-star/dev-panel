package io.github.devpanel.insight.risk;

import io.github.devpanel.insight.requests.RequestStats;
import io.github.devpanel.insight.requests.RequestStats.EndpointView;
import io.github.devpanel.insight.risk.Risk.Category;
import io.github.devpanel.insight.risk.Risk.Severity;
import io.github.devpanel.insight.trace.RequestRecorder;
import io.github.devpanel.insight.trace.RequestRecorder.Kind;
import io.github.devpanel.insight.trace.RequestRecorder.Repeated;
import io.github.devpanel.insight.trace.TraceStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.env.Environment;

/**
 * Code that is fine with today's data and traffic and hurts later, from the requests of the last
 * 24 hours: lookups repeated per row (N+1) with the line of code that does them, inserts sent one
 * by one, the same row loaded several times in one request, calls to other services while a
 * database transaction holds a connection, very large answers.
 */
public class CodeDetector implements RiskDetector {

  private static final int MIN_CALLS = 5;

  private final RequestStats requests;
  private final TraceStore traces;
  private final Environment environment;
  private final int nPlusOneThreshold;

  public CodeDetector(RequestStats requests, TraceStore traces, Environment environment,
      int nPlusOneThreshold) {
    this.requests = requests;
    this.traces = traces;
    this.environment = environment;
    this.nPlusOneThreshold = nPlusOneThreshold;
  }

  @Override
  public String name() {
    return "code";
  }

  @Override
  public List<Risk> detect() {
    List<Risk> risks = new ArrayList<>();
    long now = System.currentTimeMillis();
    Map<String, RequestStats.NPlusOne> latestNPlusOne = new HashMap<>();
    for (RequestStats.NPlusOne suspect : requests.nPlusOneSuspects()) {
      latestNPlusOne.putIfAbsent(suspect.endpoint(), suspect);
    }
    boolean batching = batchingConfigured();
    for (EndpointView view : requests.endpoints(now, 24)) {
      if (view.calls() < MIN_CALLS) {
        continue;
      }
      double weight = ForecastDetector.weight(view.calls());
      if (view.wastedPerCall() >= Math.max(5, nPlusOneThreshold / 2.0)) {
        RequestStats.NPlusOne suspect = latestNPlusOne.get(view.endpoint());
        Repeated worst = suspect == null ? null : suspect.repeated().stream()
            .filter(repeated -> repeated.kind() == Kind.SELECT).findFirst().orElse(null);
        risks.add(new Risk("code.n-plus-one:" + view.endpoint(), Category.WEAKNESS, "code",
            view.wastedPerCall() >= nPlusOneThreshold * 3 ? Severity.WARNING : Severity.INFO,
            view.endpoint() + " loads rows one by one (N+1)",
            "The number of queries grows with the number of rows shown: fine with 10 rows, slow"
                + " with 1,000. Each extra query is a round trip to the database.",
            "On average " + Math.round(view.sqlPerCall()) + " queries per call, "
                + Math.round(view.wastedPerCall()) + " of them repeats of the same lookup"
                + (worst == null ? "." : "; worst: " + worst.count() + "× "
                    + cut(worst.statement(), 160)),
            "Load the related rows together: JOIN FETCH / @EntityGraph for to-one relations,"
                + " hibernate.default_batch_fetch_size for collections, or one IN (...) query.",
            worst != null && worst.location() != null ? worst.location() : view.endpoint(),
            null, null, "api", weight));
      }
      if (view.writeRepeatsPerCall() >= 5) {
        risks.add(new Risk("code.batch:" + view.endpoint(), Category.WEAKNESS, "code",
            Severity.INFO, view.endpoint() + " writes rows one round trip at a time",
            "Saving N items costs N round trips to the database; with many items the request"
                + " gets slow and holds its connection long.",
            Math.round(view.writeRepeatsPerCall()) + " repeated inserts/updates per call"
                + (view.sequenceCallsPerCall() >= 3 ? ", plus " + Math.round(
                    view.sequenceCallsPerCall()) + " id fetches (nextval) per call" : "") + ".",
            batching ? "Batching is configured; check the entity ids are not IDENTITY (which"
                + " turns batching off) and that the writes go through saveAll."
                : "Turn on JDBC batching: spring.jpa.properties.hibernate.jdbc.batch_size=50,"
                    + " hibernate.order_inserts=true, hibernate.order_updates=true.",
            view.endpoint(), null, null, "api", weight * 0.7));
      }
      if (view.exactRepeatsPerCall() >= 3) {
        risks.add(new Risk("code.same-row:" + view.endpoint(), Category.WEAKNESS, "code",
            Severity.INFO, view.endpoint() + " loads the same rows several times per call",
            "The same query with the same values runs again within one request: pure waste.",
            Math.round(view.exactRepeatsPerCall()) + " identical lookups per call.",
            "Load it once and pass the object along, or read it within one transaction (the"
                + " persistence context then returns the loaded entity).", view.endpoint(), null,
            null, "api", weight * 0.6));
      }
      if (view.externalInTransaction() > 0) {
        String location = externalInTransactionLocation(view.endpoint());
        risks.add(new Risk("code.external-in-tx:" + view.endpoint(), Category.WEAKNESS, "code",
            Severity.WARNING,
            view.endpoint() + " calls another service while holding a database connection",
            "While the other service answers, the connection stays taken. If that service is"
                + " slow, the pool runs out and the whole application stops, not just this"
                + " endpoint.",
            view.externalInTransaction() + " such calls in the last 24 hours.",
            "Make the external call before or after the @Transactional method, not inside it.",
            location != null ? location : view.endpoint(), null, null, "api", weight * 1.5));
      }
      if (view.maxBytes() >= 2_000_000) {
        risks.add(new Risk("code.large-answer:" + view.endpoint(), Category.WEAKNESS, "api",
            Severity.INFO, view.endpoint() + " sends very large answers",
            "Large JSON costs memory and CPU to build, and time to send over slow networks.",
            "Largest answer " + (view.maxBytes() / 1024) + " KB, average "
                + (view.avgBytes() / 1024) + " KB.",
            "Page the list or return only the fields the screen needs.", view.endpoint(), null,
            null, "api", 0.7));
      }
    }
    return risks;
  }

  private String externalInTransactionLocation(String endpoint) {
    for (TraceStore.TraceSummary summary : traces.list()) {
      if (!summary.endpoint().equals(endpoint)) {
        continue;
      }
      TraceStore.Trace trace = traces.find(summary.id()).orElse(null);
      if (trace == null) {
        continue;
      }
      for (RequestRecorder.Span span : trace.spans()) {
        if ("HTTP".equals(span.getKind()) && span.isInTransaction()) {
          return span.getLocation() != null ? span.getLocation() + " → " + span.getText()
              : span.getText();
        }
      }
    }
    return null;
  }

  private boolean batchingConfigured() {
    String size = environment.getProperty("spring.jpa.properties.hibernate.jdbc.batch_size");
    try {
      return size != null && Integer.parseInt(size.trim()) > 1;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  private static String cut(String text, int max) {
    return text.length() <= max ? text : text.substring(0, max) + "…";
  }
}
