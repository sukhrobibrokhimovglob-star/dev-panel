# Dev Panel

A built-in admin panel for Spring Boot backends. Add one dependency and two properties, and your
application serves a panel at `/dev-panel/` with:

- **Server**: CPU (process and system), JVM heap and system memory, disk space, threads
- **Database pool**: HikariCP active, idle, max, and threads waiting for a connection
- **History**: charts of all of the above for the last 24 h (in memory, resets on restart)
- **JVM**: GC, memory pools, thread states, full thread dump (downloadable)
- **Error audit**: every response with status >= 400, filterable by trace id, path, status, time
- **Logs and docs**: browse and download files, find all log lines of one request by trace id
- **SQL console**: read-only SELECTs with autocomplete and Excel export (optional guarded UPDATE)
- **Accounts**: its own logins (super admin, admins, users; read-only or read-write), lockout
  after 5 failed attempts, forced password change on first login

It does not need Actuator, Prometheus, or any extra server.

## Install

Add JitPack and the dependency to `pom.xml`:

```xml
<repositories>
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>

<dependency>
  <groupId>com.github.sukhrobibrokhimovglob-star.dev-panel</groupId>
  <artifactId>dev-panel-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

Turn it on in `application.properties`:

```properties
devpanel.enabled=true
devpanel.super-admin.initial-password=${DEV_PANEL_SUPERADMIN_PASSWORD:}
```

Start the application and open `http://<host>:<port>/dev-panel/`. Log in as `superadmin` with the
initial password; you must change it on the first login. Without the property no super admin is
created, so set it through an environment variable rather than committing a password.

## Requirements

- Spring Boot 2.7.x (javax.servlet), Java 17+, a servlet web application
- One `DataSource`. The panel creates two tables, `devpanel_account` and
  `devpanel_request_audit`, with `CREATE TABLE IF NOT EXISTS`; it never touches your
  Liquibase/Flyway history
- The SQL console needs PostgreSQL; everything else works on any database
- Excel export needs `org.apache.poi:poi-ooxml` on the classpath (most projects have it)

## How it fits into your application

- **Security**: the panel checks its own logins (HTTP Basic against `devpanel_account`) and tells
  your Spring Security to ignore `/dev-panel/**`. This works with both a
  `WebSecurityConfigurerAdapter` and `SecurityFilterChain` beans. Your own endpoints stay exactly
  as protected as before.
- **No bean conflicts**: it registers no `PasswordEncoder`, `JdbcTemplate` or transaction manager
  bean, so your own injection points and auto-configuration are unaffected.
- **Trace ids**: every request gets an `X-Trace-Id` header and a `traceId` MDC key. To make the
  "logs of this request" search work, print it in your log pattern, for example
  `%d{yyyy-MM-dd HH:mm:ss} %-5level [%X{traceId}] [%thread] %logger{36} - %msg%n`. If your
  application already assigns trace ids, set `devpanel.trace.enabled=false` and point
  `devpanel.trace.header` / `devpanel.trace.mdc-key` at yours.

## Configuration

| Property | Default | |
|---|---|---|
| `devpanel.enabled` | `false` | Turns the panel on |
| `devpanel.base-path` | `/dev-panel` | Where the panel is served |
| `devpanel.super-admin.username` | `superadmin` | |
| `devpanel.super-admin.initial-password` | (empty) | Only used while no super admin exists |
| `devpanel.metrics.sample-interval` | `15s` | History sample rate |
| `devpanel.metrics.retention` | `24h` | How long history is kept in memory |
| `devpanel.query.enabled` | `true` | SQL console |
| `devpanel.query.update-enabled` | `false` | Allow single `UPDATE ... WHERE` for read-write accounts |
| `devpanel.query.max-rows` | `5000` | Rows returned to the browser |
| `devpanel.query.max-updated-rows` | `1000` | Larger updates are rolled back |
| `devpanel.query.timeout` | `30s` | Statement timeout |
| `devpanel.files.enabled` | `true` | Logs and docs browser |
| `devpanel.files.logs-path` | `logs` | |
| `devpanel.files.docs-path` | `docs` | |
| `devpanel.audit.enabled` | `true` | Record responses with status >= 400 |
| `devpanel.audit.retention` | `30d` | Older audit rows are deleted daily |
| `devpanel.trace.enabled` | `true` | Assign trace ids |
| `devpanel.trace.header` | `X-Trace-Id` | |
| `devpanel.trace.mdc-key` | `traceId` | |

## Security notes

- Serve the panel over HTTPS only: HTTP Basic sends the password with every request.
- The SQL console runs every query in a read-only transaction with a statement timeout, so
  PostgreSQL itself refuses writes. `UPDATE` is off unless you enable it, and it can never touch
  the panel's own tables or the migration history.
- File downloads are confined to the configured logs and docs folders.
- JVM arguments whose name contains password, secret, token, credential or key are masked.

## Development

```bash
./mvnw install              # builds the UI (downloads Node) and the jar, runs the tests
./mvnw -Dskip.ui=true test  # backend only, reuses the last UI build
cd frontend && npm run dev  # UI on http://localhost:5180, proxied to a backend on :8080
```

Releasing: push a git tag such as `0.1.1` (no `v` prefix, so the JitPack version matches the pom
version); the first request for it on jitpack.io triggers the build.
