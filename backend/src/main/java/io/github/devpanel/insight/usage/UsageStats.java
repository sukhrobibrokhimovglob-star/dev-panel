package io.github.devpanel.insight.usage;

import io.github.devpanel.insight.LatencyHistogram;
import io.github.devpanel.insight.SlotCounter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Who uses the application: requests per logged-in user and per organization (the tenant path
 * variable, e.g. {@code {organizationId}}), errors, response times per organization, the
 * endpoints each one uses most, and who was active in the last minutes. In memory, since start.
 */
public class UsageStats {

  private static final int MAX_KEYS = 5000;
  private static final int TOP_ENDPOINTS = 15;
  private static final long HOUR = 3_600_000L;

  private final Map<String, Usage> users = new HashMap<>();
  private final Map<String, Usage> tenants = new HashMap<>();

  public synchronized void record(String user, String tenant, String endpoint, int status,
      long ms, long now) {
    if (user != null) {
      usage(users, user).add(endpoint, status, ms, now, tenant);
    }
    if (tenant != null) {
      usage(tenants, tenant).add(endpoint, status, ms, now, user);
    }
  }

  public synchronized List<UsageView> users(long now) {
    return views(users, now);
  }

  public synchronized List<UsageView> tenants(long now) {
    return views(tenants, now);
  }

  /** Users with a request in the last {@code minutes} minutes, most recent first. */
  public synchronized List<UsageView> activeUsers(long now, int minutes) {
    return views(users, now).stream()
        .filter(view -> view.lastSeen().toEpochMilli() >= now - minutes * 60_000L)
        .sorted(Comparator.comparing(UsageView::lastSeen).reversed())
        .toList();
  }

  private static Usage usage(Map<String, Usage> map, String key) {
    Usage usage = map.get(key);
    if (usage == null) {
      if (map.size() >= MAX_KEYS) {
        // Forget the least recently seen to stay bounded
        map.entrySet().stream().min(Comparator.comparingLong(e -> e.getValue().lastSeen))
            .ifPresent(oldest -> map.remove(oldest.getKey()));
      }
      usage = new Usage();
      map.put(key, usage);
    }
    return usage;
  }

  private static List<UsageView> views(Map<String, Usage> map, long now) {
    List<UsageView> views = new ArrayList<>();
    map.forEach((key, usage) -> views.add(usage.view(key, now)));
    views.sort(Comparator.comparingLong(UsageView::last24h).reversed());
    return views;
  }

  private static final class Usage {

    final SlotCounter hourly = new SlotCounter(24, HOUR);
    final LatencyHistogram latency = new LatencyHistogram();
    final Map<String, Long> endpoints = new HashMap<>();
    final Map<String, Long> related = new HashMap<>();
    long total;
    long errors;
    long lastSeen;
    String lastEndpoint;

    void add(String endpoint, int status, long ms, long now, String relatedKey) {
      hourly.add(now, 1);
      latency.record(ms);
      total++;
      if (status >= 500) {
        errors++;
      }
      lastSeen = now;
      lastEndpoint = endpoint;
      if (endpoints.containsKey(endpoint) || endpoints.size() < 200) {
        endpoints.merge(endpoint, 1L, Long::sum);
      }
      if (relatedKey != null && (related.containsKey(relatedKey) || related.size() < 50)) {
        related.merge(relatedKey, 1L, Long::sum);
      }
    }

    UsageView view(String key, long now) {
      List<Map.Entry<String, Long>> top = endpoints.entrySet().stream()
          .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
          .limit(TOP_ENDPOINTS).toList();
      List<String> relatedKeys = related.entrySet().stream()
          .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
          .limit(10).map(Map.Entry::getKey).toList();
      Map<String, Long> topEndpoints = new LinkedHashMap<>();
      top.forEach(entry -> topEndpoints.put(entry.getKey(), entry.getValue()));
      return new UsageView(key, total, hourly.sum(now, 24), hourly.sum(now, 1), errors,
          latency.percentileMs(50), latency.percentileMs(95), Instant.ofEpochMilli(lastSeen),
          lastEndpoint, topEndpoints, relatedKeys);
    }
  }

  /**
   * {@code related}: for a user the organizations they worked in, for an organization its users.
   */
  public record UsageView(String key, long total, long last24h, long lastHour, long serverErrors,
                          long p50Ms, long p95Ms, Instant lastSeen, String lastEndpoint,
                          Map<String, Long> topEndpoints, List<String> related) {
  }
}
