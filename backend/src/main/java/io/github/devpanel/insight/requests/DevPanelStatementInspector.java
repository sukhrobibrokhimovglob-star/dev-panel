package io.github.devpanel.insight.requests;

import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * Lets {@link SqlCounter} see every statement Hibernate runs, then hands the SQL on unchanged
 * (to the application's own inspector, if it had one).
 */
public class DevPanelStatementInspector implements StatementInspector {

  private final StatementInspector next;

  public DevPanelStatementInspector(StatementInspector next) {
    this.next = next;
  }

  @Override
  public String inspect(String sql) {
    SqlCounter.record(sql);
    return next == null ? sql : next.inspect(sql);
  }
}
