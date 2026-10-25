export type TDevRole = "SUPER_ADMIN" | "ADMIN" | "USER";
export type TDevAccess = "READ_ONLY" | "READ_WRITE";

export type TDevLoginStage =
  | "PASSWORD_CHANGE"
  | "TOTP_SETUP"
  | "TOTP"
  | "ACTIVE";

export interface IDevLoginState {
  stage: TDevLoginStage;
  username: string;
  // Faqat TOTP_SETUP bosqichida: QR kod uchun URI va qo'lda kiritish uchun kalit
  setup: { secret: string; uri: string } | null;
}

export interface IDevAccount {
  id: number;
  username: string;
  role: TDevRole;
  access: TDevAccess;
  enabled: boolean;
  mustChangePassword: boolean;
  totpEnabled: boolean;
  totpEnabledAt: string | null;
  totpEnabledIp: string | null;
  createdAt: string;
  createdBy: string;
}

export interface IDevAccountCredentials {
  account: IDevAccount;
  temporaryPassword: string;
}

export interface IDevAccountCreateReq {
  username: string;
  role: TDevRole;
  access?: TDevAccess;
}

export interface IDevAccountUpdateReq {
  role?: TDevRole;
  access?: TDevAccess;
  enabled?: boolean;
}

export interface IDevHikariPool {
  pool: string;
  active: number;
  idle: number;
  total: number;
  pending: number;
  max: number;
  min: number;
  connectionTimeoutMs: number;
}

export interface IDevMetrics {
  cpu: {
    availableProcessors: number;
    processLoad: number;
    systemLoad: number;
    systemLoadAverage: number;
  };
  memory: {
    heapUsed: number;
    heapCommitted: number;
    heapMax: number;
    nonHeapUsed: number;
    systemTotal: number;
    systemFree: number;
  };
  disks: {
    path: string;
    totalSpace: number;
    usableSpace: number;
  }[];
  hikari: IDevHikariPool[];
  threads: {
    live: number;
    daemon: number;
    peak: number;
    deadlocked: number;
  };
}

// Bitta nuqta: backend har N soniyada oladi, uzoq oraliqlar o'rtachalanib qaytadi
export interface IDevMetricsPoint {
  time: string;
  cpuProcess: number;
  cpuSystem: number;
  heapUsed: number;
  heapMax: number;
  threads: number;
  dbActive: number;
  dbIdle: number;
  dbPending: number;
  dbMax: number;
  diskUsedRatio: number;
  nonHeapUsed: number;
  loadedClasses: number;
  openFiles: number;
}

export interface IDevMetricsHistory {
  sampleSeconds: number;
  points: IDevMetricsPoint[];
}

export interface IDevGc {
  name: string;
  count: number;
  timeMs: number;
}

export interface IDevMemoryPool {
  name: string;
  type: string;
  used: number;
  max: number;
}

export interface IDevJvm {
  pid: number;
  javaVersion: string;
  vmName: string;
  startTime: string;
  uptimeMs: number;
  inputArguments: string[];
  loadedClasses: number;
  gc: IDevGc[];
  memoryPools: IDevMemoryPool[];
  threadStates: Record<string, number>;
}

export interface IDevInfo {
  application: string;
  profiles: string[];
  javaVersion: string;
  devPanelVersion: string;
  startTime: string;
  features: {
    query: boolean;
    queryUpdate: boolean;
    files: boolean;
    audit: boolean;
    apiRunner?: boolean;
    profiler?: boolean;
    insights?: boolean;
  };
}

export interface IDevFileEntry {
  path: string;
  sizeBytes: number;
  lastModified: string;
}

export type TDevFileFolder = "docs" | "logs";

export interface IDevAuditEntry {
  id: number;
  traceId: string;
  httpMethod: string;
  path: string;
  statusCode: number;
  durationMs: number;
  clientIp: string;
  occurredAt: string;
  responseBody?: string | null;
}

export interface IDevAuditFilter {
  traceId?: string;
  httpMethod?: string;
  path?: string;
  statusCode?: number;
  from?: string;
  to?: string;
  page: number;
  size: number;
}

export interface IDevLogEntry {
  file: string;
  line: number;
  text: string;
}

export interface IDevLogTrace {
  traceId: string;
  entries: IDevLogEntry[];
  truncated: boolean;
  source?: "files" | "memory";
}

export interface IDevPage<T> {
  list: T[];
  total: number;
}

export type TDevQueryRow = Record<string, unknown>;

export interface IDevQueryUpdateResult {
  updatedRows: number;
}

export interface IDevSchemaTable {
  schema: string;
  table: string;
  columns: string[];
}


// ---------- 0.2.0: monitoring ----------

export type TDevCheckStatus = "OK" | "WARNING" | "CRITICAL" | "UNKNOWN";

export interface IDevCheck {
  key: string;
  area: string;
  status: TDevCheckStatus;
  title: string;
  detail: string | null;
  tab: string | null;
}

export interface IDevDeploy {
  application: string;
  version: string | null;
  commit: string | null;
  branch: string | null;
  buildTime: string | null;
  host: string;
  startedAt: string;
}

export interface IDevHealth {
  report: {
    overall: TDevCheckStatus;
    checks: IDevCheck[];
    serverTime: string;
    started: string;
  };
  clock: {
    referenceUrl: string;
    offsetMs: number | null;
    checkedAt: string | null;
    error: string | null;
  };
  deploy: IDevDeploy;
}

export interface IDevRate {
  end: string;
  requests: number[];
  serverErrors: number[];
  loggedErrors: number[];
}

export interface IDevEndpointRow {
  stats: {
    endpoint: string;
    calls: number;
    serverErrors: number;
    clientErrors: number;
    avgMs: number;
    p50Ms: number;
    p95Ms: number;
    p99Ms: number;
    maxMs: number;
    p95LastHourMs: number;
    sqlPerCall: number;
    sqlMax: number;
    avgDbMs: number;
    avgExternalMs: number;
    avgCodeMs: number;
    avgRows: number;
    maxRows: number;
    avgBytes: number;
    maxBytes: number;
    wastedPerCall: number;
    writeRepeatsPerCall?: number;
    exactRepeatsPerCall?: number;
    sequenceCallsPerCall?: number;
    externalInTransaction?: number;
  };
  p95SameHourYesterdayMs: number | null;
  p95BeforeDeployMs: number | null;
}

export interface IDevSlowRequest {
  time: string;
  endpoint: string;
  status: number;
  ms: number;
  sql: number;
  dbMs: number;
  externalMs: number;
  rows: number;
  traceId: string;
}

export interface IDevNPlusOne {
  time: string;
  endpoint: string;
  sqlTotal: number;
  wasted: number;
  repeated: IDevRepeated[];
  traceId: string;
}

export interface IDevExceptionGroup {
  fingerprint: string;
  type: string;
  location: string;
  message: string;
  sample: string;
  firstSeen: string;
  lastSeen: string;
  count: number;
  lastTraceId: string | null;
  isNew: boolean;
}

export interface IDevMemory {
  analysis: {
    heapMax: number;
    floor: { time: number; bytes: number }[];
    floorGrowthPerHour: number;
    confidence: number;
    leakSuspected: boolean;
    hoursToFull: number;
    gcShare: number;
    maxPauseMs: number;
    collectionsLast5Min: number;
  };
  collections: {
    time: number;
    name: string;
    action: string;
    cause: string;
    durationMs: number;
    heapAfter: number;
  }[];
}

export interface IDevHistogramRow {
  rank: number;
  instances: number;
  bytes: number;
  className: string;
}

export interface IDevDbOverview {
  version: string;
  database: string;
  sizeBytes: number;
  maxConnections: number;
  connections: number;
  ownConnections: number;
  active: number;
  idleInTransaction: number;
  blocked: number;
  cacheHitRatio: number;
  deadlocks: number;
  tempBytes: number;
  commits: number;
  rollbacks: number;
  statementsAvailable: boolean;
}

export interface IDevDbActivity {
  pid: number;
  user: string;
  application: string;
  client: string;
  state: string;
  waitType: string | null;
  waitEvent: string | null;
  transactionStart: string | null;
  queryStart: string | null;
  ms: number;
  transactionMs: number;
  blockedBy: number[];
  query: string;
}

export interface IDevDbStatement {
  query: string;
  calls: number;
  totalMs: number;
  meanMs: number;
  maxMs: number;
  rows: number;
  cacheHitRatio: number;
}

export interface IDevDbTable {
  schema: string;
  table: string;
  liveRows: number;
  deadRows: number;
  seqScans: number;
  indexScans: number;
  totalBytes: number;
  lastVacuum: string | null;
  lastAnalyze: string | null;
  missingIndex: boolean;
  bloated: boolean;
}

export interface IDevUnusedIndex {
  schema: string;
  table: string;
  index: string;
  bytes: number;
}

export interface IDevLeak {
  time: string;
  thread: string;
  message: string;
  stack: string;
}

export interface IDevIntegration {
  host: string;
  calls: number;
  errors: number;
  timeouts: number;
  avgMs: number;
  p95Ms: number;
  maxMs: number;
  down: boolean;
  downSince: string | null;
  lastCall: string | null;
  lastError: string | null;
  lastErrorMessage: string | null;
}

export interface IDevJob {
  name: string;
  schedule: string;
  nextExpected: string | null;
  lastStart: string | null;
  lastMs: number;
  runs: number;
  failures: number;
  lastError: string | null;
  lastFailure: string | null;
  running: boolean;
  missed: boolean;
}

export interface IDevAlertEvent {
  time: string;
  key: string;
  area: string;
  status: TDevCheckStatus;
  title: string;
  detail: string | null;
  resolved: boolean;
}

export interface IDevAlerts {
  settings: {
    telegram: boolean;
    webhook: boolean;
    instance: string;
    dailySummaryAt: string | null;
    lastDeliveryError: string | null;
  };
  history: IDevAlertEvent[];
}

export interface IDevLogLine {
  seq: number;
  time: number;
  level: string;
  logger: string;
  thread: string;
  message: string;
  traceId: string | null;
  error: string | null;
}

export interface IDevLoggerLevel {
  name: string;
  configured: string | null;
  effective: string | null;
  changedUntil: string | null;
}

export interface IDevConfigProperty {
  name: string;
  value: string;
  source: string;
}

export interface IDevSession {
  id: string;
  createdAt: string;
  lastSeenAt: string;
  clientIp: string | null;
  userAgent: string | null;
  current: boolean;
}

// ---------- 0.3.0 ----------

export interface IDevSpan {
  kind: "SQL" | "HTTP";
  text: string;
  startMs: number;
  durationMs: number;
  error: string | null;
  rows: number;
  status: number;
  location?: string | null;
  inTransaction?: boolean;
  hasParameters?: boolean;
}

export type TDevSqlKind = "SELECT" | "INSERT" | "UPDATE" | "DELETE" | "SEQUENCE" | "OTHER";

export interface IDevRepeated {
  statement: string;
  count: number;
  totalMs: number;
  kind?: TDevSqlKind;
  location?: string | null;
  exactRepeats?: number;
}

export interface IDevTraceSummary {
  id: string;
  time: string;
  endpoint: string;
  status: number;
  totalMs: number;
  sqlMs: number;
  externalMs: number;
  sqlCount: number;
  rows: number;
  reason: "SLOW" | "ERROR" | "N_PLUS_ONE" | "SAMPLE" | "RUNNER";
}

export interface IDevTrace extends IDevTraceSummary {
  path: string;
  responseBytes: number;
  user: string | null;
  tenant: string | null;
  clientIp: string;
  truncated: boolean;
  spans: IDevSpan[];
  repeated: IDevRepeated[];
  request?: IDevRequestInfo | null;
  response?: string | null;
  writeRepeats?: number;
  exactRepeats?: number;
  sequenceCalls?: number;
  externalInTransaction?: number;
}

export interface IDevRequestInfo {
  method: string;
  query: string | null;
  contentType: string | null;
  headers: Record<string, string>;
  body: string | null;
  authorized: boolean;
}

export interface IDevUsage {
  key: string;
  total: number;
  last24h: number;
  lastHour: number;
  serverErrors: number;
  p50Ms: number;
  p95Ms: number;
  lastSeen: string;
  lastEndpoint: string;
  topEndpoints: Record<string, number>;
  related: string[];
}

export interface IDevUnused {
  total: number;
  since: string;
  unused: { endpoint: string; handler: string }[];
}

export interface IDevIpStats {
  ip: string;
  requests: number;
  unauthorized: number;
  notFound: number;
  lastMinute: number;
  users: string[];
  lastSeen: string;
  lastPath: string;
  probes: number;
  notFoundPaths: string[];
}

export interface IDevSecurity {
  top10min: IDevIpStats[];
  top1h: IDevIpStats[];
  events: {
    time: string;
    ip: string;
    path: string;
    status: number;
    user: string | null;
    type: string;
  }[];
  findings: { type: string; ip: string; critical: boolean; detail: string }[];
}

export interface IDevTomcat {
  busyThreads: number;
  poolThreads: number;
  maxThreads: number;
  queued: number;
  connections: number;
  maxConnections: number;
}

export interface IDevDependency {
  name: string;
  address: string;
  up: boolean;
  lastMs: number;
  lastCheck: string | null;
  lastOk: string | null;
  downSince: string | null;
  lastError: string | null;
  uptime: number;
  history: boolean[];
}

export interface IDevDeployChange {
  endpoint: string;
  callsBefore: number;
  callsNow: number;
  p95BeforeMs: number;
  p95NowMs: number;
  sqlBefore: number;
  sqlNow: number;
}

export interface IDevDeployReport {
  deployedAt: string;
  minutesRunning: number;
  faster: IDevDeployChange[];
  slower: IDevDeployChange[];
  unchanged: number;
  callsBefore: number;
  errorsBefore: number;
  callsNow: number;
  errorsNow: number;
  newErrors: IDevExceptionGroup[];
  noHistory: boolean;
}

// ---------- 0.4.0: risks, startup, advisor, tools ----------

export type TDevSeverity = "INFO" | "WARNING" | "CRITICAL";

export interface IDevRisk {
  key: string;
  category: "FORECAST" | "WEAKNESS";
  area: string;
  severity: TDevSeverity;
  title: string;
  impact: string | null;
  evidence: string | null;
  fix: string | null;
  where: string | null;
  sql: string | null;
  daysLeft: number | null;
  tab: string | null;
  weight: number;
}

export interface IDevRiskItem {
  risk: IDevRisk;
  score: number;
  worst: TDevSeverity;
  firstSeen: string;
  lastSeen: string;
  resolvedAt: string | null;
  snoozedUntil: string | null;
  acceptedBy: string | null;
  acceptedReason: string | null;
  acceptedAt: string | null;
  silenced: boolean;
  flags: string[];
  changedFrom: TDevSeverity | null;
  changedAt: string | null;
}

export interface IDevRisks {
  open: IDevRiskItem[];
  resolved: IDevRiskItem[];
  lastRun: string | null;
  lastRunMs: number;
  failures: Record<string, string>;
  counts: Record<string, number>;
}

export interface IDevStartupBean {
  name: string;
  type: string | null;
  totalMs: number;
  ownMs: number;
}

export interface IDevStartup {
  report: {
    jvmStartedAt: string;
    uptimeMs: number;
    contextMs: number;
    sqlCount: number;
    sqlMs: number;
    beans: IDevStartupBean[];
    statements: IDevRepeated[];
    inserts: Record<string, number>;
    migrations: { tool: string; id: string; file: string; author: string }[];
    ownStartupRecorder: boolean;
  } | null;
  previous: { startedAt: string; ms: number }[];
  inserts: Record<string, number>[];
}

export interface IDevAdvisor {
  foreignKeys: {
    schema: string;
    table: string;
    constraint: string;
    columns: string;
    referenced: string;
    rows: number;
    bytes: number;
    createIndex: string;
  }[];
  ids: {
    schema: string;
    sequence: string;
    sequenceType: string;
    table: string | null;
    column: string | null;
    columnType: string | null;
    lastValue: number;
    limit: number;
    used: number;
  }[];
  duplicates: {
    schema: string;
    table: string;
    column: string;
    total: number;
    distinct: number;
    examples: string[];
  }[];
  cacheCandidates: {
    schema: string;
    table: string;
    rows: number;
    readsPerDay: number;
    writesPerDay: number;
    bytes: number;
  }[];
  indexes: {
    schema: string;
    table: string;
    column: string;
    like: boolean;
    rows: number;
    seqScans: number;
    indexScans: number;
    createIndex: string;
    exampleSql: string;
  }[];
  statistics: {
    schema: string;
    table: string;
    rows: number;
    changedSinceAnalyze: number;
    lastAnalyzed: string | null;
    bytes: number;
    plannerRows: number;
  }[];
}

export interface IDevPlanNode {
  depth: number;
  type: string;
  relation: string | null;
  index: string | null;
  alias: string | null;
  startupCost: number;
  totalCost: number;
  planRows: number;
  actualRows: number;
  actualMs: number;
  loops: number;
  filter: string | null;
  indexCondition: string | null;
  removedByFilter: number;
  sortMethod: string | null;
  joinType: string | null;
  hashCondition: string | null;
}

export interface IDevPlan {
  statement: string;
  json: string;
  nodes: IDevPlanNode[];
  warnings: string[];
  hints: string[];
  analyzed: boolean;
  planningMs: number;
  executionMs: number;
}

export interface IDevExplainReq {
  sql?: string;
  traceId?: string;
  span?: number;
  analyze?: boolean;
}

export interface IDevPrivacyFinding {
  logger: string;
  kind: string;
  level: string;
  count: number;
  firstSeen: string;
  lastSeen: string;
  example: string;
}

export interface IDevSloGoal {
  id: number;
  endpoint: string;
  targetMs: number;
  objective: number;
  createdBy: string;
  createdAt: string;
}

export interface IDevSloStatus {
  goal: IDevSloGoal;
  weekWithin: number;
  weekCalls: number;
  weekShare: number;
  monthWithin: number;
  monthCalls: number;
  monthShare: number;
  budgetLeft: number;
  burnRate: number;
  budgetDaysLeft: number;
  met: boolean;
}

export interface IDevCatalogEntry {
  endpoint: string;
  handler: string;
}

export interface IDevEndpointParameter {
  name: string;
  required: boolean;
  type: string;
  example: string | null;
}

export interface IDevEndpointDetails {
  endpoint: string;
  method: string;
  pattern: string;
  handler: string;
  pathVariables: IDevEndpointParameter[];
  query: IDevEndpointParameter[];
  headers: IDevEndpointParameter[];
  bodyType: string | null;
  bodyExample: unknown;
}

export interface IDevUsual {
  samples: number;
  calls: number;
  p95Ms: number;
  serverErrors: number;
}

export interface IDevRunnerReq {
  method: string;
  path: string;
  headers: Record<string, string>;
  body?: string | null;
  confirmWrite?: boolean;
}

export interface IDevRunnerResponse {
  status: number;
  ms: number;
  headers: Record<string, string>;
  body: string | null;
  bytes: number;
  truncated: boolean;
  traceId: string | null;
  error: string | null;
}

export interface IDevSmokeCheck {
  id?: number;
  name: string;
  method: string;
  path: string;
  headers: string | null;
  expectedStatus: number;
  maxMs: number;
  bodyContains: string | null;
  enabled: boolean;
  createdBy?: string;
  createdAt?: string;
}

export interface IDevSmokeRun {
  time: string;
  ok: boolean;
  status: number;
  ms: number;
  error: string | null;
  trigger: string;
}

export interface IDevSmokeView {
  check: IDevSmokeCheck;
  lastRun: IDevSmokeRun | null;
  history: IDevSmokeRun[];
  success: number;
}

export interface IDevFlameNode {
  name: string;
  total: number;
  self: number;
  application: boolean;
  children: IDevFlameNode[];
}

export interface IDevProfileSummary {
  id: string;
  startedAt: string;
  seconds: number;
  scope: string;
  by: string;
  status: "RUNNING" | "DONE";
  threadSamples: number;
}

export interface IDevProfile extends IDevProfileSummary {
  samples: number;
  threads: number;
  root: IDevFlameNode;
  hottest: { method: string; self: number; total: number; application: boolean }[];
  error: string | null;
}

export interface IDevTestResult {
  id: string;
  displayName: string | null;
  status: string;
  durationMs: number | null;
  failure: string | null;
}

export interface IDevEndpointCoverage {
  endpoint: string;
  handler: string;
  tests: string[];
  testCount: number;
  statuses: number[];
  failingTests: string[];
  callsPerDay: number;
  serverErrors: number;
  clientErrors: number;
  untestedInProduction: string[];
  risk: number;
}

export interface IDevTests {
  summary: {
    source: string;
    generatedAt: string;
    gitCommit: string | null;
    gitBranch: string | null;
    deployedCommit: string | null;
    sameCommit: boolean | null;
    tests: number;
    passed: number;
    failed: number;
    skipped: number;
  } | null;
  endpoints: number;
  testedEndpoints: number;
  endpointsCovered: number;
  trafficCovered: number;
  list: IDevEndpointCoverage[];
  failingTests: IDevTestResult[];
  coverage: {
    source: string;
    at: string;
    lines: number;
    branches: number;
    packages: {
      name: string;
      lines: number;
      branches: number;
      classes: number;
      totalLines: number;
    }[];
    uncovered: { name: string; lines: number }[];
  } | null;
}

export interface IDevDataPage {
  columns: string[];
  rows: TDevQueryRow[];
  total: number;
  page: number;
  size: number;
}

export interface IDevAction {
  id: number;
  time: string;
  username: string | null;
  action: string;
  target: string | null;
  detail: string | null;
  clientIp: string | null;
}
