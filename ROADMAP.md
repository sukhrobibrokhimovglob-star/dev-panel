# Roadmap

## Done in 0.1.0
- Starter with auto-configuration, own accounts and Basic auth, independent of the host's
  Spring Security style
- Own tables via `CREATE TABLE IF NOT EXISTS` (no migration-tool coupling)
- Server metrics, HikariCP pool, 24 h in-memory history with charts
- JVM: GC, memory pools, thread states, full thread dump
- Error audit with daily retention cleanup, trace-id filter
- Logs and docs browser, log search by trace id
- SQL console (read-only by default, optional guarded UPDATE, Excel export)
- Bundled Vue UI served from the jar, gzipped
- Login: password + Google Authenticator for every account, cookie sessions, CSRF header,
  2FA reset by the super admin (Basic auth removed)
- Tests: filter-chain security host, adapter security host, SQL validation, TOTP RFC vectors

## Done in 0.2.0
- Health page, Telegram/webhook alerts with resolve messages and a daily summary
- Memory leak detection (heap after GC trend), GC overhead, thread/class/file trends, histogram
- PostgreSQL: activity with cancel/kill, slowest statements, table health, unused indexes,
  connection leaks (Hikari leak detection)
- API per endpoint with p50/p95/p99 and comparisons, slow requests, N+1 detection
- Exception groups with "new since deploy"; external services; scheduled jobs; clock check
- Live logs, runtime log levels, configuration viewer, deploy history
- Security: fresh 2FA secret per setup login, sessions list, setup time/IP, full super admin reset

## Next
1. Publish `0.1.0` on JitPack and switch gp to it
2. Liquibase/Flyway status in the config view
3. Configurable alert thresholds and mute/acknowledge from the panel
8. `DevPanelModule` extension point for project-specific tabs
9. Migrate existing in-house panels to the starter, with their project-specific tabs as modules
10. Spring Boot 3 (jakarta) variant
11. GitHub Actions CI; later Maven Central
