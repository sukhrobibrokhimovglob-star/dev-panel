package io.github.devpanel;

import io.github.devpanel.account.DevAccountController;
import io.github.devpanel.account.DevAccountRepository;
import io.github.devpanel.account.DevAccountService;
import io.github.devpanel.account.DevLoginAttempts;
import io.github.devpanel.audit.DevAuditController;
import io.github.devpanel.audit.DevRequestAuditFilter;
import io.github.devpanel.audit.DevRequestAuditRepository;
import io.github.devpanel.files.DevFileBrowserService;
import io.github.devpanel.files.DevFilesController;
import io.github.devpanel.files.DevLogTraceService;
import io.github.devpanel.metrics.DevJvmService;
import io.github.devpanel.metrics.DevMetricsController;
import io.github.devpanel.metrics.DevMetricsHistory;
import io.github.devpanel.metrics.DevMetricsService;
import io.github.devpanel.metrics.HikariPools;
import io.github.devpanel.query.DevQueryController;
import io.github.devpanel.query.DevQueryExcelWriter;
import io.github.devpanel.query.DevQueryService;
import io.github.devpanel.support.DevPanelInitializer;
import io.github.devpanel.support.DevPanelSchema;
import io.github.devpanel.support.TraceIds;
import io.github.devpanel.web.DevPanelAuthFilter;
import io.github.devpanel.web.DevPanelExceptionHandler;
import io.github.devpanel.web.DevPanelTraceIdFilter;
import io.github.devpanel.web.DevPanelWebConfigurer;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/**
 * Everything the panel needs, created only with {@code devpanel.enabled=true} in a servlet web
 * application that has one {@link DataSource}. Nothing here is visible to the application under a
 * name or type it would look up itself: no {@code PasswordEncoder}, {@code JdbcTemplate} or
 * transaction manager bean is registered, so the application's own auto-configuration and
 * injection points are not affected.
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "devpanel", name = "enabled", havingValue = "true")
@ConditionalOnSingleCandidate(DataSource.class)
@EnableConfigurationProperties(DevPanelProperties.class)
@Import({DevPanelExceptionHandler.class, DevAccountController.class, DevMetricsController.class,
    DevPanelAutoConfiguration.AuditConfiguration.class,
    DevPanelAutoConfiguration.QueryConfiguration.class,
    DevPanelAutoConfiguration.FilesConfiguration.class,
    DevPanelAutoConfiguration.HikariConfiguration.class,
    DevPanelAutoConfiguration.ExcelConfiguration.class,
    DevPanelAutoConfiguration.SecurityConfiguration.class})
public class DevPanelAutoConfiguration {

  /** Ahead of Spring Security's chain (order -100) and of most application filters. */
  static final int TRACE_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;
  static final int AUDIT_ORDER = Ordered.HIGHEST_PRECEDENCE + 11;
  static final int AUTH_ORDER = Ordered.HIGHEST_PRECEDENCE + 12;

  private final DevPanelProperties properties;
  private final JdbcTemplate jdbcTemplate;
  private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

  public DevPanelAutoConfiguration(DevPanelProperties properties, DataSource dataSource) {
    this.properties = properties;
    this.jdbcTemplate = new JdbcTemplate(dataSource);
    new TraceIds.Configurer(properties.getTrace().getHeader(),
        properties.getTrace().getMdcKey());
  }

  @Bean
  DevAccountRepository devPanelAccountRepository() {
    return new DevAccountRepository(jdbcTemplate);
  }

  @Bean
  DevAccountService devPanelAccountService(DevAccountRepository devPanelAccountRepository) {
    return new DevAccountService(devPanelAccountRepository, passwordEncoder);
  }

  @Bean
  DevRequestAuditRepository devPanelAuditRepository() {
    return new DevRequestAuditRepository(jdbcTemplate);
  }

  @Bean
  DevMetricsService devPanelMetricsService(ObjectProvider<HikariPools> hikariPools) {
    return new DevMetricsService(hikariPools.getIfAvailable());
  }

  @Bean
  DevMetricsHistory devPanelMetricsHistory(DevMetricsService devPanelMetricsService) {
    return new DevMetricsHistory(devPanelMetricsService,
        properties.getMetrics().getSampleInterval(), properties.getMetrics().getRetention());
  }

  @Bean
  DevJvmService devPanelJvmService() {
    return new DevJvmService();
  }

  @Bean
  DevPanelInitializer devPanelInitializer(DevAccountRepository devPanelAccountRepository,
      DevAccountService devPanelAccountService, DevMetricsHistory devPanelMetricsHistory,
      DevRequestAuditRepository devPanelAuditRepository) {
    return new DevPanelInitializer(properties, new DevPanelSchema(jdbcTemplate),
        devPanelAccountRepository, devPanelAccountService, devPanelMetricsHistory,
        devPanelAuditRepository);
  }

  @Bean
  DevPanelWebConfigurer devPanelWebConfigurer() {
    return new DevPanelWebConfigurer(properties.getBasePath());
  }

  @Bean
  FilterRegistrationBean<DevPanelAuthFilter> devPanelAuthFilter(
      DevAccountRepository devPanelAccountRepository) {
    FilterRegistrationBean<DevPanelAuthFilter> registration = new FilterRegistrationBean<>(
        new DevPanelAuthFilter(properties.getApiPath(), devPanelAccountRepository,
            passwordEncoder, new DevLoginAttempts()));
    registration.addUrlPatterns(properties.getApiPath() + "/*");
    registration.setOrder(AUTH_ORDER);
    registration.setName("devPanelAuthFilter");
    return registration;
  }

  @Bean
  @ConditionalOnProperty(prefix = "devpanel.trace", name = "enabled", havingValue = "true",
      matchIfMissing = true)
  FilterRegistrationBean<DevPanelTraceIdFilter> devPanelTraceIdFilter() {
    FilterRegistrationBean<DevPanelTraceIdFilter> registration =
        new FilterRegistrationBean<>(new DevPanelTraceIdFilter());
    registration.setOrder(TRACE_ORDER);
    registration.setName("devPanelTraceIdFilter");
    return registration;
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(prefix = "devpanel.audit", name = "enabled", havingValue = "true",
      matchIfMissing = true)
  @Import(DevAuditController.class)
  static class AuditConfiguration {

    @Bean
    FilterRegistrationBean<DevRequestAuditFilter> devPanelAuditFilter(
        DevRequestAuditRepository devPanelAuditRepository, DevPanelProperties properties) {
      FilterRegistrationBean<DevRequestAuditFilter> registration = new FilterRegistrationBean<>(
          new DevRequestAuditFilter(devPanelAuditRepository, properties.getBasePath()));
      registration.setOrder(AUDIT_ORDER);
      registration.setName("devPanelAuditFilter");
      return registration;
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(prefix = "devpanel.query", name = "enabled", havingValue = "true",
      matchIfMissing = true)
  @Import(DevQueryController.class)
  static class QueryConfiguration {

    @Bean
    DevQueryService devPanelQueryService(DataSource dataSource, DevPanelProperties properties) {
      DevPanelProperties.Query query = properties.getQuery();
      return new DevQueryService(dataSource, query.getTimeout(), query.getMaxRows(),
          query.getMaxUpdatedRows(), query.isUpdateEnabled());
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(prefix = "devpanel.files", name = "enabled", havingValue = "true",
      matchIfMissing = true)
  @Import(DevFilesController.class)
  static class FilesConfiguration {

    @Bean
    DevFileBrowserService devPanelFileBrowserService(DevPanelProperties properties) {
      return new DevFileBrowserService(properties.getFiles().getDocsPath(),
          properties.getFiles().getLogsPath());
    }

    @Bean
    DevLogTraceService devPanelLogTraceService(DevFileBrowserService devPanelFileBrowserService) {
      return new DevLogTraceService(devPanelFileBrowserService.logsDir());
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "com.zaxxer.hikari.HikariDataSource")
  static class HikariConfiguration {

    @Bean
    HikariPools devPanelHikariPools(List<DataSource> dataSources) {
      return new HikariPools(dataSources);
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "org.apache.poi.xssf.streaming.SXSSFWorkbook")
  static class ExcelConfiguration {

    @Bean
    DevQueryExcelWriter devPanelQueryExcelWriter() {
      return new DevQueryExcelWriter();
    }
  }

  /**
   * Tells the application's Spring Security to leave the panel's paths alone; the panel checks
   * its own logins in {@link DevPanelAuthFilter}. A {@link WebSecurityCustomizer} works both with
   * a {@code WebSecurityConfigurerAdapter} and with {@code SecurityFilterChain} beans.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = {
      "org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer",
      "org.springframework.security.web.util.matcher.AntPathRequestMatcher"})
  static class SecurityConfiguration {

    @Bean
    WebSecurityCustomizer devPanelWebSecurityCustomizer(DevPanelProperties properties) {
      return web -> web.ignoring()
          .requestMatchers(new AntPathRequestMatcher(properties.getBasePath()),
              new AntPathRequestMatcher(properties.getBasePath() + "/**"));
    }
  }
}
