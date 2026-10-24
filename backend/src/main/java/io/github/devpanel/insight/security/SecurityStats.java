package io.github.devpanel.insight.security;

import io.github.devpanel.insight.SlotCounter;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Traffic per client IP, to spot attacks that reach the application: floods from a few
 * addresses, password guessing (many 401/403), scanning (many different 404 paths, or paths only
 * attack tools ask for). It detects and reports; blocking belongs in nginx or the firewall.
 * A volumetric DDoS that saturates the network never reaches the application and cannot be seen
 * here. Behind a proxy the client IP comes from {@code X-Forwarded-For}.
 */
public class SecurityStats {

  private static final int MAX_IPS = 5000;
  private static final int KEEP_EVENTS = 300;
  private static final long MINUTE = 60_000L;
  /** Paths that only attack tools and scanners ask a Java backend for. */
  private static final Pattern PROBE = Pattern.compile(
      "(?i).*(/\\.env|/\\.git|wp-admin|wp-login|xmlrpc\\.php|phpmyadmin|/cgi-bin|\\.php$|"
          + "/actuator|/console|/admin\\.|/etc/passwd|/server-status|/\\.aws|/boaform|/HNAP1|"
          + "\\.\\./).*");

  private final int floodPerMinute;
  private final int failedLoginsPer10Min;
  private final int notFoundPathsPer10Min;
  private final Map<String, IpStats> ips = new HashMap<>();
  private final Deque<Event> events = new ArrayDeque<>();

  public SecurityStats(int floodPerMinute, int failedLoginsPer10Min, int notFoundPathsPer10Min) {
    this.floodPerMinute = floodPerMinute;
    this.failedLoginsPer10Min = failedLoginsPer10Min;
    this.notFoundPathsPer10Min = notFoundPathsPer10Min;
  }

  /**
   * @param withCredentials the request carried credentials (an Authorization header): a refusal
   *                        is then a failed login; without, refusals of many different paths are
   *                        a scan (many apps answer unknown pages with 401/403, not 404)
   */
  public synchronized void record(String ip, String path, int status, String user,
      boolean withCredentials, long now) {
    if (ip == null) {
      return;
    }
    IpStats stats = ips.get(ip);
    if (stats == null) {
      if (ips.size() >= MAX_IPS) {
        ips.entrySet().stream().min(Comparator.comparingLong(e -> e.getValue().lastSeen))
            .ifPresent(oldest -> ips.remove(oldest.getKey()));
      }
      stats = new IpStats();
      ips.put(ip, stats);
    }
    stats.add(path, status, user, withCredentials, now);
    boolean probe = PROBE.matcher(path).matches();
    if (status == 401 || status == 403 || probe) {
      events.addFirst(new Event(Instant.ofEpochMilli(now), ip, path, status, user,
          probe ? "PROBE" : withCredentials ? "FAILED_LOGIN" : "ANONYMOUS_REFUSED"));
      while (events.size() > KEEP_EVENTS) {
        events.removeLast();
      }
      if (probe) {
        stats.probes++;
      }
    }
  }

  /** Busiest addresses of the last {@code minutes} minutes. */
  public synchronized List<IpView> top(long now, int minutes, int limit) {
    List<IpView> views = new ArrayList<>();
    ips.forEach((ip, stats) -> {
      IpView view = stats.view(ip, now, minutes);
      if (view.requests() > 0) {
        views.add(view);
      }
    });
    views.sort(Comparator.comparingLong(IpView::requests).reversed());
    return views.subList(0, Math.min(limit, views.size()));
  }

  public synchronized List<Event> events() {
    return new ArrayList<>(events);
  }

  /** What looks like an attack right now; each with a severity for the health page. */
  public synchronized List<Finding> findings(long now) {
    List<Finding> findings = new ArrayList<>();
    ips.forEach((ip, stats) -> {
      long lastMinute = stats.requests.sum(now, 1);
      if (lastMinute >= floodPerMinute) {
        findings.add(new Finding("FLOOD", ip, lastMinute >= floodPerMinute * 3L,
            ip + " sent " + lastMinute + " requests in the last minute (limit " + floodPerMinute
                + ")"));
      }
      long failed = stats.failedLogins.sum(now, 10);
      if (failed >= failedLoginsPer10Min) {
        findings.add(new Finding("BRUTE_FORCE", ip, failed >= failedLoginsPer10Min * 5L,
            ip + " was refused " + failed + " times with credentials in 10 min (password or"
                + " token guessing, or a broken client?)"
                + (stats.users.isEmpty() ? "" : " — users: " + String.join(", ", stats.users))));
      }
      long refused = stats.refusedAnonymous.sum(now, 10);
      if (stats.refusedPaths.size() >= notFoundPathsPer10Min && refused >= notFoundPathsPer10Min) {
        findings.add(new Finding("SCAN", ip, false, ip + " tried " + stats.refusedPaths.size()
            + " different pages without logging in (" + refused + " refused or missing in 10"
            + " min), e.g. " + String.join(", ", stats.refusedPaths.stream().limit(3).toList())));
      }
      if (stats.probes > 0 && stats.lastProbe >= now - 10 * MINUTE) {
        findings.add(new Finding("PROBE", ip, false, ip + " probed attack paths ("
            + stats.probes + "×), e.g. " + stats.lastProbePath));
      }
    });
    return findings;
  }

  private static final class IpStats {

    final SlotCounter requests = new SlotCounter(60, MINUTE);
    final SlotCounter unauthorized = new SlotCounter(60, MINUTE);
    final SlotCounter failedLogins = new SlotCounter(60, MINUTE);
    final SlotCounter refusedAnonymous = new SlotCounter(60, MINUTE);
    final SlotCounter notFound = new SlotCounter(60, MINUTE);
    final Set<String> refusedPaths = new LinkedHashSet<>();
    final Set<String> users = new LinkedHashSet<>();
    final Map<Integer, Long> statuses = new HashMap<>();
    long lastSeen;
    long probes;
    long lastProbe;
    String lastProbePath;
    String lastPath;

    void add(String path, int status, String user, boolean withCredentials, long now) {
      requests.add(now, 1);
      boolean refused = status == 401 || status == 403;
      if (refused) {
        unauthorized.add(now, 1);
        (withCredentials ? failedLogins : refusedAnonymous).add(now, 1);
      }
      if (status == 404) {
        notFound.add(now, 1);
        if (!withCredentials) {
          refusedAnonymous.add(now, 1);
        }
      }
      if ((status == 404 || refused) && !withCredentials && refusedPaths.size() < 50) {
        refusedPaths.add(path);
      }
      if (user != null && users.size() < 5) {
        users.add(user);
      }
      statuses.merge(status / 100 * 100, 1L, Long::sum);
      if (PROBE.matcher(path).matches()) {
        lastProbe = now;
        lastProbePath = path;
      }
      lastSeen = now;
      lastPath = path;
    }

    IpView view(String ip, long now, int minutes) {
      return new IpView(ip, requests.sum(now, minutes), unauthorized.sum(now, minutes),
          notFound.sum(now, minutes), requests.sum(now, 1), new ArrayList<>(users),
          Instant.ofEpochMilli(lastSeen), lastPath, probes,
          new ArrayList<>(refusedPaths).subList(0, Math.min(10, refusedPaths.size())));
    }
  }

  public record IpView(String ip, long requests, long unauthorized, long notFound,
                       long lastMinute, List<String> users, Instant lastSeen, String lastPath,
                       long probes, List<String> notFoundPaths) {
  }

  /** {@code type}: FAILED_LOGIN (refused with credentials), ANONYMOUS_REFUSED or PROBE. */
  public record Event(Instant time, String ip, String path, int status, String user,
                      String type) {
  }

  /** {@code type}: FLOOD, BRUTE_FORCE, SCAN or PROBE. */
  public record Finding(String type, String ip, boolean critical, String detail) {
  }
}
