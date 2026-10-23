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
- Tests: filter-chain security host, adapter security host, SQL validation

## Next
1. Publish `0.1.0` on JitPack and switch gp to it
2. Postgres live view: running queries and locks (`pg_stat_activity`) with cancel; slow queries
   (`pg_stat_statements`); table and index sizes
3. Slowest endpoints: p50/p95 per URL from request timings
4. Runtime log levels (change a package to DEBUG without a redeploy)
5. Live log tail over SSE
6. Config viewer with masked secrets; Liquibase/Flyway status
7. Alerts to Telegram or a webhook (pending connections, disk, error rate)
8. `DevPanelModule` extension point for project-specific tabs
9. Migrate vac2 to the starter and move its organization sync into a module
10. Spring Boot 3 (jakarta) variant
11. GitHub Actions CI; later Maven Central
