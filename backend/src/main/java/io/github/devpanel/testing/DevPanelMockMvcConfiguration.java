package io.github.devpanel.testing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Imported by {@code @AutoConfigureMockMvc} (also inside {@code @WebMvcTest}) through
 * {@code META-INF/spring/...AutoConfigureMockMvc.imports}: adds {@link TestHitFilter}, so the
 * test report knows which endpoints web slice tests call. Only exists in test runs.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = {
    "org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer",
    "org.junit.platform.launcher.TestExecutionListener"})
public class DevPanelMockMvcConfiguration {

  @Bean
  MockMvcBuilderCustomizer devPanelTestHitCustomizer() {
    return builder -> builder.addFilters(new TestHitFilter());
  }
}
