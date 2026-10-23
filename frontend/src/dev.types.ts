export type TDevRole = "SUPER_ADMIN" | "ADMIN" | "USER";
export type TDevAccess = "READ_ONLY" | "READ_WRITE";

export interface IDevCredentials {
  username: string;
  password: string;
}

export interface IDevAccount {
  id: number;
  username: string;
  role: TDevRole;
  access: TDevAccess;
  enabled: boolean;
  mustChangePassword: boolean;
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

