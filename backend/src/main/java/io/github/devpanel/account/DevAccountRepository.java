package io.github.devpanel.account;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * {@code devpanel_account} through plain JDBC: the panel is a library, and JPA entities in a
 * library would have to be added to the application's entity scan and schema validation.
 */
public class DevAccountRepository {

  private static final String COLUMNS = "id, username, password_hash, role, access, enabled,"
      + " must_change_password, created_at, created_by, totp_secret, totp_enabled, totp_last_step";

  private static final RowMapper<DevAccount> ROW = DevAccountRepository::map;

  private final JdbcTemplate jdbcTemplate;

  public DevAccountRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Optional<DevAccount> findByUsername(String username) {
    return jdbcTemplate.query("SELECT " + COLUMNS + " FROM devpanel_account WHERE username = ?",
        ROW, username).stream().findFirst();
  }

  public Optional<DevAccount> findById(long id) {
    return jdbcTemplate.query("SELECT " + COLUMNS + " FROM devpanel_account WHERE id = ?", ROW, id)
        .stream().findFirst();
  }

  public List<DevAccount> findAll() {
    return jdbcTemplate.query("SELECT " + COLUMNS + " FROM devpanel_account ORDER BY id", ROW);
  }

  public List<DevAccount> findAllByRole(DevRole role) {
    return jdbcTemplate.query(
        "SELECT " + COLUMNS + " FROM devpanel_account WHERE role = ? ORDER BY id", ROW,
        role.name());
  }

  public boolean existsByUsername(String username) {
    return count("SELECT COUNT(*) FROM devpanel_account WHERE username = ?", username) > 0;
  }

  public boolean existsByRole(DevRole role) {
    return count("SELECT COUNT(*) FROM devpanel_account WHERE role = ?", role.name()) > 0;
  }

  public void insert(DevAccount account) {
    jdbcTemplate.update("INSERT INTO devpanel_account (username, password_hash, role, access,"
            + " enabled, must_change_password, created_at, created_by)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        account.username(), account.passwordHash(), account.role().name(),
        account.access().name(), account.enabled(), account.mustChangePassword(),
        Timestamp.valueOf(account.createdAt()), account.createdBy());
  }

  public void update(DevAccount account) {
    jdbcTemplate.update("UPDATE devpanel_account SET password_hash = ?, role = ?, access = ?,"
            + " enabled = ?, must_change_password = ? WHERE id = ?",
        account.passwordHash(), account.role().name(), account.access().name(),
        account.enabled(), account.mustChangePassword(), account.id());
  }

  public Optional<DevAccount> findByRole(DevRole role) {
    return findAllByRole(role).stream().findFirst();
  }

  /** Stores a new, not yet confirmed authenticator secret. */
  public void startTotpSetup(long id, String secret) {
    jdbcTemplate.update("UPDATE devpanel_account SET totp_secret = ?, totp_enabled = FALSE,"
        + " totp_last_step = 0 WHERE id = ?", secret, id);
  }

  public void confirmTotp(long id) {
    jdbcTemplate.update("UPDATE devpanel_account SET totp_enabled = TRUE WHERE id = ?", id);
  }

  public void resetTotp(long id) {
    jdbcTemplate.update("UPDATE devpanel_account SET totp_secret = NULL, totp_enabled = FALSE,"
        + " totp_last_step = 0 WHERE id = ?", id);
  }

  /**
   * Records {@code step} as used. The condition makes it atomic: of two requests with the same
   * code, even on two instances, only one gets {@code true}.
   */
  public boolean useTotpStep(long id, long step) {
    return jdbcTemplate.update("UPDATE devpanel_account SET totp_last_step = ?"
        + " WHERE id = ? AND totp_last_step < ?", step, id, step) == 1;
  }

  private long count(String sql, Object... args) {
    Long count = jdbcTemplate.queryForObject(sql, Long.class, args);
    return count == null ? 0 : count;
  }

  private static DevAccount map(ResultSet rs, int rowNum) throws SQLException {
    Timestamp createdAt = rs.getTimestamp("created_at");
    return new DevAccount(
        rs.getLong("id"),
        rs.getString("username"),
        rs.getString("password_hash"),
        DevRole.valueOf(rs.getString("role")),
        DevAccess.valueOf(rs.getString("access")),
        rs.getBoolean("enabled"),
        rs.getBoolean("must_change_password"),
        createdAt == null ? null : createdAt.toLocalDateTime(),
        rs.getString("created_by"),
        rs.getString("totp_secret"),
        rs.getBoolean("totp_enabled"),
        rs.getLong("totp_last_step"));
  }
}
