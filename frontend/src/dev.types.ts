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
  traceId: string;
}

export interface IDevNPlusOne {
  time: string;
  endpoint: string;
  sqlTotal: number;
  repeats: number;
  statement: string;
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
