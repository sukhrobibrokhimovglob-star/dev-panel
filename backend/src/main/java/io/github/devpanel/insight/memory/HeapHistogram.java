package io.github.devpanel.insight.memory;

import io.github.devpanel.support.DevPanelException;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import org.springframework.http.HttpStatus;

/**
 * Which classes take the most heap right now, like {@code jmap -histo:live}, without writing a
 * heap dump. It runs a full garbage collection first, so it pauses the application briefly.
 */
public class HeapHistogram {

  private static final int TOP = 40;

  public List<Row> top() {
    String text;
    try {
      MBeanServer server = ManagementFactory.getPlatformMBeanServer();
      text = (String) server.invoke(new ObjectName("com.sun.management:type=DiagnosticCommand"),
          "gcClassHistogram", new Object[] {new String[0]},
          new String[] {String[].class.getName()});
    } catch (Exception e) {
      throw new DevPanelException(HttpStatus.NOT_IMPLEMENTED,
          "This JVM does not offer a class histogram: " + e.getMessage());
    }
    List<Row> rows = new ArrayList<>();
    for (String line : text.split("\n")) {
      String[] parts = line.trim().split("\\s+");
      // "  1:   123456   7890123  java.lang.String (java.base@17)"
      if (parts.length >= 4 && parts[0].endsWith(":")) {
        try {
          rows.add(new Row(Integer.parseInt(parts[0].replace(":", "")),
              Long.parseLong(parts[1]), Long.parseLong(parts[2]), parts[3]));
        } catch (NumberFormatException ignored) {
          // header or total line
        }
      }
      if (rows.size() >= TOP) {
        break;
      }
    }
    return rows;
  }

  public record Row(int rank, long instances, long bytes, String className) {
  }
}
