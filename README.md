# Dev Panel

A built-in admin and early-warning panel for Spring Boot backends. Add one dependency and two
properties, and your application serves a panel at `/dev-panel/` that tells you what is wrong
**before** users notice, and sends it to Telegram.

### Monitoring and early warnings (0.2)
- **Health page**: every area as a traffic light with one plain sentence each ("Requests wait for
  a database connection: 3 waiting, 10 of 10 busy"), compared with the application's own normal
- **Alerts** to Telegram and/or a webhook: once when something turns bad, once when it is fine
  again, plus a daily summary at 09:00
- **Memory leaks**: the heap left after each garbage collection, its trend and "full in ~N h",
  GC overhead, threads/classes/open files that keep rising, a heap class histogram
- **Database (PostgreSQL)**: what runs right now and who blocks whom (with cancel/kill), slowest
  statements (pg_stat_statements), tables missing an index or bloated, unused indexes,
  connections vs max_connections, **connection leaks** with the code that took the connection
- **API**: per endpoint calls, errors, p50/p95/p99, compared with yesterday and with before the
  deploy; slowest requests; **N+1 query detection** (same SQL many times in one request)
- **Errors**: exceptions grouped by type and code location, "new since this deploy"
- **External services** (Feign, RestTemplate): latency, errors, timeouts, "down since"
- **Background jobs** (`@Scheduled`): last run, duration, failures, missed runs
- **Request waterfall** (0.3): every SQL statement and external call of a slow, failed or N+1
  request with its duration and rows, so you see where the time went
- **Time split per endpoint**: DB / external calls / own code, rows read, response size
- **Usage**: active users, requests per user and per organization, endpoints never called
- **Security**: floods from one address, password/token guessing, scanning and attack-path
  probes, per client IP (detection only; real DDoS must be stopped in nginx/firewall)
- **Availability checks** every minute (database and external services, even without traffic),
  **Tomcat capacity** (busy request threads, queue), a **deploy report** (faster/slower
  endpoints and new errors since this start)
- **Server clock** check, **live logs**, **log levels at runtime** (auto-revert),
  **configuration viewer** (secrets masked), recent deploys

### Tools

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
  <version>0.3.0</version>
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

### "The code is right, but the server's clock is ... off"

Authenticator codes depend on the time, so the server's clock must be correct. Turn on time sync
on the server (`sudo timedatectl set-ntp true`). Until that is done,
`devpanel.two-factor.allowed-clock-drift=10m` tolerates the difference.

### Telegram alerts in 3 minutes

1. In Telegram, open @BotFather, send `/newbot`, copy the token.
2. Send any message to your new bot (or add it to a group).
3. Get your chat id from @userinfobot (a group id is negative).
4. Add to `devpanel.properties`, deploy, then press **Send test message** on the Alerts tab:
   ```properties
   devpanel.alerts.telegram-bot-token=123456:ABC...
   devpanel.alerts.telegram-chat-id=123456789
   ```

### Slowest queries need pg_stat_statements

Add `shared_preload_libraries = 'pg_stat_statements'` to `postgresql.conf`, restart PostgreSQL and
run `CREATE EXTENSION pg_stat_statements;` in the application's database (DevOps, once).

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
| `devpanel.super-admin.reset` | `false` | Someone took over the super admin: back to the initial password and new 2FA; remove after use |
| `devpanel.two-factor.allowed-clock-drift` | `30s` | Raise (max `15m`) only while a server clock is being synced |
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
| `devpanel.insights.enabled` | `true` | The whole monitoring part |
| `devpanel.insights.slow-request` | `1s` | Requests slower than this are listed |
| `devpanel.insights.n-plus-one-threshold` | `10` | Wasted (repeated) SQL in one request = N+1 |
| `devpanel.insights.sql-tracing` | `true` | Time each SQL statement (wraps the DataSource bean) |
| `devpanel.insights.tenant-path-variables` | `organizationId, orgId, organization_id, tenantId` | Path variables naming the organization |
| `devpanel.insights.trust-forwarded-for` | `true` | Client IP from X-Forwarded-For (only behind a proxy) |
| `devpanel.insights.dependency-checks` | `true` | Ping DB and external services every minute |
| `devpanel.insights.dependencies.<name>` | | Extra URL to ping, e.g. `.MIS=https://...` |
| `devpanel.insights.flood-per-minute` | `300` | Requests per minute from one IP = flood |
| `devpanel.insights.failed-logins-per10-min` | `20` | Refusals with credentials per IP = guessing |
| `devpanel.insights.scan-paths-per10-min` | `30` | Different refused/missing pages per IP = scan |
| `devpanel.insights.connection-leak-threshold` | `60s` | Hikari leak detection, if the app has none (0 = off) |
| `devpanel.insights.jobs` | `true` | Count `@Scheduled` runs (proxies those beans via AspectJ) |
| `devpanel.insights.clock-reference-url` | `https://www.google.com` | Clock check; empty = off |
| `devpanel.alerts.telegram-bot-token` | | From @BotFather |
| `devpanel.alerts.telegram-chat-id` | | Your id (@userinfobot) or a group id |
| `devpanel.alerts.webhook-url` | | Every alert as JSON |
| `devpanel.alerts.daily-summary-at` | `09:00` | Server time; empty = no summary |
| `devpanel.alerts.instance-name` | app @ host | How messages name this application |
| `devpanel.trace.header` | `X-Trace-Id` | |
| `devpanel.trace.mdc-key` | `traceId` | |

## What the monitoring adds to your application

- One filter that times requests, counts response bytes and records their SQL and external calls
- The DataSource bean is wrapped to time statements and count rows (pass-through; `unwrap` still
  reaches the pool). Code that injects the concrete pool class (e.g. `HikariDataSource`) by type
  would no longer find it: set `devpanel.insights.sql-tracing=false` then
- One extra Logback appender (in memory; your logging is unchanged)
- Hikari leak detection, only if you did not configure it
- With AspectJ on the classpath, `@Scheduled` beans become proxies (turn off with
  `devpanel.insights.jobs=false`); code reading their fields directly from outside would see the
  proxy's empty fields
- Tables `devpanel_exception`, `devpanel_endpoint_hour` (14 days) and `devpanel_deploy`
- Two daemon threads; nothing calls out except the clock check and your alert channels

## Security notes

- Every login needs the password and a Google Authenticator code (RFC 6238); a code works only
  once, and wrong passwords and wrong codes count together towards the lockout. The QR code is
  drawn in the browser, so the secret never goes to a third-party service.
- The session lives in an `HttpOnly`, `SameSite=Strict` cookie (`Secure` on HTTPS) that page
  scripts cannot read; the database keeps only its SHA-256. A new token is issued once the code
  is accepted. Requests other than `GET` must carry an `X-DevPanel-Request` header, so other
  sites cannot forge them.
- A password reset, a 2FA reset or disabling an account logs that account out everywhere.
- During 2FA setup every password login gets a new QR secret, so a QR code someone else saw
  stops working when the owner logs in. The time and IP of the setup are shown, and each user
  sees where they are logged in (My security) and can end those sessions.
- Killing database queries, the heap histogram and the configuration viewer need an admin.
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
