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
- **Accounts**: its own logins (super admin, admins, users; read-only or read-write)
- **Login**: password plus a Google Authenticator code for every account, sessions in an
  `HttpOnly` cookie (8 h, 30 min idle), lockout after 5 failed attempts

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
  <version>0.1.1</version>
</dependency>
```

Turn it on in a `src/main/resources/devpanel.properties` of its own (the starter loads it by
itself, so `application.properties` stays untouched), or in `application.properties`:

```properties
devpanel.enabled=true
devpanel.super-admin.initial-password=${DEV_PANEL_SUPERADMIN_PASSWORD:}
```

Start the application and open `http://<host>:<port>/dev-panel/`. Log in as `superadmin` with the
initial password. The first login asks you to replace it and to scan a QR code with Google
Authenticator (or Authy, Microsoft Authenticator); every later login asks for the 6-digit code.
Without the property no super admin is created, so set it through an environment variable rather
than committing a password.

### Lost phone

- A user or admin: the super admin clicks **Reset 2FA** on the account in the Accounts tab. The
  account is logged out and scans a new QR code at the next login.
- The super admin: start the application once with
  `devpanel.super-admin.reset-two-factor=true`, set up the app again, then remove the property.

Settings in `devpanel.properties` (or `devpanel.yml`) have the lowest priority: a `devpanel.*`
value in `application.properties`, an environment variable or a command-line argument overrides
them.

## Requirements

- Spring Boot 2.7.x (javax.servlet), Java 17+, a servlet web application
- One `DataSource`. The panel creates three tables, `devpanel_account`, `devpanel_session` and
  `devpanel_request_audit`, with `CREATE TABLE IF NOT EXISTS`; it never touches your
  Liquibase/Flyway history
- The SQL console needs PostgreSQL; everything else works on any database that supports
  `CREATE TABLE IF NOT EXISTS` and `ADD COLUMN IF NOT EXISTS` (PostgreSQL, H2, MariaDB)
- Excel export needs `org.apache.poi:poi-ooxml` on the classpath (most projects have it)

## How it fits into your application

- **Security**: the panel checks its own logins (session cookie, see `devpanel_session`) and tells
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
| `devpanel.super-admin.reset-two-factor` | `false` | Super admin lost the phone; remove after use |
| `devpanel.session.timeout` | `8h` | A login ends this long after it started |
| `devpanel.session.idle-timeout` | `30m` | A login ends after this long without requests |
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

- Every login needs the password and a Google Authenticator code (RFC 6238); a code works only
  once, and wrong passwords and wrong codes count together towards the lockout. The QR code is
  drawn in the browser, so the secret never goes to a third-party service.
- The session lives in an `HttpOnly`, `SameSite=Strict` cookie (`Secure` on HTTPS) that page
  scripts cannot read; the database keeps only its SHA-256. A new token is issued once the code
  is accepted. Requests other than `GET` must carry an `X-DevPanel-Request` header, so other
  sites cannot forge them.
- A password reset, a 2FA reset or disabling an account logs that account out everywhere.
- Serve the panel over HTTPS.
- The SQL console runs every query in a read-only transaction with a statement timeout, so
  PostgreSQL itself refuses writes. `UPDATE` is off unless you enable it, and it can never touch
  the panel's own tables or the migration history.
- File downloads are confined to the configured logs and docs folders.
- JVM arguments whose name contains password, secret, token, credential or key are masked.

## Development

```bash
./mvnw install              # the jar with the committed UI build, runs the tests
./mvnw -Pui install         # also rebuilds the UI first (downloads Node); commit the result
cd frontend && npm run dev  # UI on http://localhost:5180, proxied to a backend on :8080
```

The built UI lives in `backend/src/main/resources/META-INF/devpanel-ui` and is committed, so
JitPack builds without Node. Rebuild and commit it whenever `frontend/` changes.

Releasing: push a git tag such as `0.1.1` (no `v` prefix, so the JitPack version matches the pom
version); the first request for it on jitpack.io triggers the build.
