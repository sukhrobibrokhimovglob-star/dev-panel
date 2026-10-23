package io.github.devpanel.insight.requests;

import java.util.Map;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.springframework.beans.BeanUtils;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.util.ClassUtils;

/**
 * Installs {@link DevPanelStatementInspector} into the application's Hibernate, keeping an
 * inspector the application configured itself ({@code hibernate.session_factory.statement_inspector}).
 */
public class HibernateSqlCounting implements HibernatePropertiesCustomizer {

  private static final String SETTING = "hibernate.session_factory.statement_inspector";

  @Override
  public void customize(Map<String, Object> properties) {
    properties.put(SETTING, new DevPanelStatementInspector(existing(properties.get(SETTING))));
  }

  private static StatementInspector existing(Object configured) {
    if (configured instanceof StatementInspector inspector) {
      return inspector;
    }
    try {
      if (configured instanceof Class<?> type) {
        return (StatementInspector) BeanUtils.instantiateClass(type);
      }
      if (configured instanceof String name && !name.isBlank()) {
        return (StatementInspector) BeanUtils.instantiateClass(
            ClassUtils.forName(name, HibernateSqlCounting.class.getClassLoader()));
      }
    } catch (ClassNotFoundException | RuntimeException e) {
      throw new IllegalStateException("Could not create the configured " + SETTING, e);
    }
    return null;
  }
}
