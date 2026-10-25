package io.github.devpanel.insight.trace;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Where in the application's own code something happened: the first frames of the current
 * stack that belong to the application, e.g.
 * {@code AccountingUnitService.get:196 ← PatientTreatmentHistoryServiceImpl.create:115}.
 * The application's packages come from {@code @SpringBootApplication} (Spring Boot's
 * auto-configuration packages); without them, anything that is not a known library counts.
 *
 * <p>Uses {@link StackWalker}, which stops at the frames it needs; the recorder asks once per
 * statement shape per request, not for every statement.
 */
public final class CallSites {

  private static final int FRAMES = 3;
  private static final String[] LIBRARIES = {
      "java.", "javax.", "jakarta.", "jdk.", "sun.", "com.sun.", "org.springframework.",
      "org.hibernate.", "com.zaxxer.", "org.apache.", "org.postgresql.",
      "com.fasterxml.", "feign.", "net.bytebuddy.", "org.h2.", "org.aspectj.", "kotlin.",
      "reactor.", "io.micrometer.", "org.slf4j.", "ch.qos.", "com.mysql.", "oracle.",
      "org.mariadb.", "io.undertow.", "org.eclipse.", "org.jboss.", "liquibase.", "org.flywaydb.",
      "com.google.", "io.netty.", "org.junit.", "org.mockito.", "org.assertj.", "com.querydsl.",
      "io.swagger.", "org.springdoc.", "io.opentelemetry.", "io.github.resilience4j."};

  /** The panel's own code; its root package is left to test applications. */
  private static final String[] PANEL = {
      "io.github.devpanel.insight.", "io.github.devpanel.web.", "io.github.devpanel.audit.",
      "io.github.devpanel.account.", "io.github.devpanel.session.", "io.github.devpanel.support.",
      "io.github.devpanel.query.", "io.github.devpanel.metrics.", "io.github.devpanel.files.",
      "io.github.devpanel.testing.", "io.github.devpanel.DevPanel"};

  private static volatile List<String> applicationPackages = List.of();

  private CallSites() {
  }

  /** @param packages e.g. {@code com.abdulatif.gp}; empty to fall back to "not a library" */
  public static void applicationPackages(List<String> packages) {
    applicationPackages = packages == null ? List.of() : List.copyOf(packages);
  }

  /** The innermost application frames, innermost first; empty when there are none. */
  public static List<String> current() {
    try {
      return StackWalker.getInstance().walk(frames -> frames
          .filter(frame -> isApplication(frame.getClassName()))
          .limit(FRAMES)
          .map(frame -> simpleName(frame.getClassName()) + "." + frame.getMethodName() + ":"
              + frame.getLineNumber())
          .collect(Collectors.toList()));
    } catch (RuntimeException e) {
      return List.of();
    }
  }

  /** {@link #current()} joined with arrows, or null when there are no application frames. */
  public static String describe(List<String> frames) {
    return frames == null || frames.isEmpty() ? null : String.join(" ← ", frames);
  }

  /** The panel's own classes (not test applications in its root package). */
  public static boolean isPanel(String className) {
    for (String panel : PANEL) {
      if (className.startsWith(panel)) {
        return true;
      }
    }
    return false;
  }

  public static boolean isApplication(String className) {
    if (className.contains("$$") || className.startsWith("jdk.proxy")
        || className.contains("$Proxy") || className.contains("$HibernateProxy")) {
      return false;
    }
    for (String panel : PANEL) {
      if (className.startsWith(panel)) {
        return false;
      }
    }
    List<String> packages = applicationPackages;
    if (!packages.isEmpty()) {
      for (String name : packages) {
        if (className.startsWith(name + ".")) {
          return true;
        }
      }
      return false;
    }
    for (String library : LIBRARIES) {
      if (className.startsWith(library)) {
        return false;
      }
    }
    return true;
  }

  private static String simpleName(String className) {
    String name = className.substring(className.lastIndexOf('.') + 1);
    int lambda = name.indexOf("$$Lambda");
    return lambda > 0 ? name.substring(0, lambda) : name;
  }
}
