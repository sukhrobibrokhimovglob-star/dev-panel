package io.github.devpanel;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.util.ClassUtils;

/**
 * Loads {@code devpanel.properties} (or {@code devpanel.yml}) from the classpath, so the panel's
 * settings can live in a file of their own when {@code application.properties} is managed
 * elsewhere (e.g. by DevOps). It has the lowest priority: anything set in
 * {@code application.properties}, environment variables or command-line arguments wins.
 */
public class DevPanelPropertiesFile implements EnvironmentPostProcessor, Ordered {

  static final String PROPERTIES = "devpanel.properties";
  static final String YAML = "devpanel.yml";

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment environment,
      SpringApplication application) {
    ClassLoader classLoader = application.getClassLoader();
    load(environment, new ClassPathResource(PROPERTIES, classLoader), false);
    if (ClassUtils.isPresent("org.yaml.snakeyaml.Yaml", classLoader)) {
      load(environment, new ClassPathResource(YAML, classLoader), true);
    }
  }

  private static void load(ConfigurableEnvironment environment, Resource resource, boolean yaml) {
    if (!resource.exists()) {
      return;
    }
    try {
      List<PropertySource<?>> sources = yaml
          ? new YamlPropertySourceLoader().load("devpanel: " + YAML, resource)
          : new PropertiesPropertySourceLoader().load("devpanel: " + PROPERTIES, resource);
      sources.forEach(environment.getPropertySources()::addLast);
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read " + resource, e);
    }
  }

  /** After Boot has loaded application.properties, so {@code addLast} really is last. */
  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
