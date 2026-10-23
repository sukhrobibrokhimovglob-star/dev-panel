package io.github.devpanel.insight.ops;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

/**
 * The effective configuration: every property with its value and the file or source it came from,
 * secrets masked. Answers "which value is really used on the server?".
 */
public class ConfigViewer {

  private static final Pattern SECRET = Pattern.compile(
      "(?i).*(password|passwd|secret|token|credential|private|key|signature|auth).*");
  private static final String MASK = "******";

  private final ConfigurableEnvironment environment;

  public ConfigViewer(ConfigurableEnvironment environment) {
    this.environment = environment;
  }

  public List<Property> list(String filter) {
    String needle = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
    Map<String, Property> effective = new LinkedHashMap<>();
    for (PropertySource<?> source : environment.getPropertySources()) {
      if (!(source instanceof EnumerablePropertySource<?> enumerable)
          || "configurationProperties".equals(source.getName())) {
        continue;
      }
      for (String name : enumerable.getPropertyNames()) {
        if (effective.containsKey(name)
            || (!needle.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(needle))) {
          continue;
        }
        Object raw = source.getProperty(name);
        String value;
        try {
          value = SECRET.matcher(name).matches() ? MASK : environment.getProperty(name);
        } catch (RuntimeException e) {
          value = String.valueOf(raw);
        }
        effective.put(name, new Property(name, value, source.getName()));
      }
    }
    List<Property> properties = new ArrayList<>(effective.values());
    properties.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
    return properties;
  }

  public List<String> activeProfiles() {
    return List.of(environment.getActiveProfiles());
  }

  public record Property(String name, String value, String source) {
  }
}
