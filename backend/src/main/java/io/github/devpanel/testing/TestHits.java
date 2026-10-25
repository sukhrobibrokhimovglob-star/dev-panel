package io.github.devpanel.testing;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.servlet.http.HttpServletRequest;

/**
 * While tests run (JUnit Platform with {@link DevPanelTestListener}), which test called which
 * endpoint and what status it got back. Filled by the panel's request filter in full application
 * tests and by a MockMvc filter in web slice tests; in production it stays switched off and
 * {@link #record} returns at once.
 */
public final class TestHits {

  private static final String RECORDED = TestHits.class.getName() + ".RECORDED";
  private static final String TEST = TestHits.class.getName() + ".TEST";
  private static final int MAX_HITS = 200_000;

  private static volatile boolean active;
  private static volatile String currentTest;
  private static final Map<String, AtomicLong> HITS = new ConcurrentHashMap<>();

  private TestHits() {
  }

  static void start() {
    HITS.clear();
    active = true;
  }

  static void stop() {
    active = false;
    currentTest = null;
  }

  static void currentTest(String test) {
    currentTest = test;
  }

  public static boolean active() {
    return active;
  }

  /**
   * Notes which test is running when a request starts: the server may finish the request after
   * the test method already returned.
   */
  public static void enter(HttpServletRequest request) {
    if (active && request.getAttribute(TEST) == null) {
      String test = currentTest;
      request.setAttribute(TEST, test == null ? "" : test);
    }
  }

  /** @param endpoint as the statistics name it, e.g. {@code GET /v1/users/{id}} */
  public static void record(HttpServletRequest request, String endpoint, int status) {
    if (!active || request.getAttribute(RECORDED) != null) {
      return;
    }
    request.setAttribute(RECORDED, Boolean.TRUE);
    Object noted = request.getAttribute(TEST);
    String test = noted instanceof String name ? (name.isEmpty() ? null : name) : currentTest;
    String key = (test == null ? "" : test) + '\u0001' + endpoint + '\u0001' + status;
    if (HITS.size() < MAX_HITS || HITS.containsKey(key)) {
      HITS.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
    }
  }

  static List<Hit> drain() {
    List<Hit> hits = new ArrayList<>();
    HITS.forEach((key, count) -> {
      String[] parts = key.split("\u0001", 3);
      hits.add(new Hit(parts[0].isEmpty() ? null : parts[0], parts[1],
          Integer.parseInt(parts[2]), count.get()));
    });
    return hits;
  }

  /** {@code test}: null when the call happened outside a test method (e.g. in a setup). */
  public record Hit(String test, String endpoint, int status, long count) {
  }
}
