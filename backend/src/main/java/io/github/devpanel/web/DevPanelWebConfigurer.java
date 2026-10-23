package io.github.devpanel.web;

import java.time.Duration;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.EncodedResourceResolver;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the bundled Vue UI from {@code classpath:/META-INF/devpanel-ui/} at
 * {@code <base-path>/}, gzipped when the browser accepts it. The UI uses relative URLs only, so
 * any context path and base path work. Static files need no login; everything they show comes
 * from the API, which does.
 */
public class DevPanelWebConfigurer implements WebMvcConfigurer {

  private static final String LOCATION = "classpath:/META-INF/devpanel-ui/";

  private final String basePath;

  public DevPanelWebConfigurer(String basePath) {
    this.basePath = basePath;
  }

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    // File names under assets/ carry a content hash, so they never change
    registry.addResourceHandler(basePath + "/assets/**")
        .addResourceLocations(LOCATION + "assets/")
        .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic())
        .resourceChain(true)
        .addResolver(new EncodedResourceResolver())
        .addResolver(new PathResourceResolver());
    // index.html and anything else at the top level; the API controllers are matched before this
    registry.addResourceHandler(basePath + "/**")
        .addResourceLocations(LOCATION)
        .setCacheControl(CacheControl.noCache())
        .resourceChain(true)
        .addResolver(new EncodedResourceResolver())
        .addResolver(new PathResourceResolver());
  }

  @Override
  public void addViewControllers(ViewControllerRegistry registry) {
    // Without the trailing slash the relative "api" and "assets/" URLs would miss the base path
    registry.addRedirectViewController(basePath, basePath + "/");
    registry.addViewController(basePath + "/").setViewName("forward:" + basePath + "/index.html");
  }

  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(new CurrentAccountResolver());
  }
}
