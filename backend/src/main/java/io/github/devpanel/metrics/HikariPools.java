package io.github.devpanel.metrics;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/**
 * State of every HikariCP pool among the application's data sources, read from the pool's own
 * MXBean; no Actuator or Micrometer needed. Only loaded when HikariCP is on the classpath.
 */
public class HikariPools {

  private final List<DataSource> dataSources;

  public HikariPools(List<DataSource> dataSources) {
    this.dataSources = dataSources;
  }

  public List<PoolState> states() {
    List<PoolState> states = new ArrayList<>();
    for (DataSource dataSource : dataSources) {
      HikariDataSource hikari = unwrap(dataSource);
      if (hikari == null || hikari.isClosed()) {
        continue;
      }
      HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
      if (pool == null) {
        // Not started yet: Hikari opens the pool on the first getConnection()
        continue;
      }
      states.add(new PoolState(hikari.getPoolName(), pool.getActiveConnections(),
          pool.getIdleConnections(), pool.getTotalConnections(),
          pool.getThreadsAwaitingConnection(), hikari.getMaximumPoolSize(),
          hikari.getMinimumIdle(), hikari.getConnectionTimeout()));
    }
    return states;
  }

  private static HikariDataSource unwrap(DataSource dataSource) {
    if (dataSource instanceof HikariDataSource hikari) {
      return hikari;
    }
    try {
      return dataSource.isWrapperFor(HikariDataSource.class)
          ? dataSource.unwrap(HikariDataSource.class)
          : null;
    } catch (SQLException e) {
      return null;
    }
  }

  /** {@code pending}: threads waiting for a connection; above zero means the pool is too small. */
  public record PoolState(String pool, int active, int idle, int total, int pending, int max,
                          int min, long connectionTimeoutMs) {
  }
}
