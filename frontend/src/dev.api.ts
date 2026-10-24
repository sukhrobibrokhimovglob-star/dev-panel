import axios from "axios";
import { getCurrentLocale } from "./i18n";
import { useDevStore } from "./dev.store";
import {
  IDevAccount,
  IDevAccountCreateReq,
  IDevDependency,
  IDevDeployReport,
  IDevSecurity,
  IDevTomcat,
  IDevTrace,
  IDevTraceSummary,
  IDevUnused,
  IDevUsage,
  IDevAlerts,
  IDevConfigProperty,
  IDevDbActivity,
  IDevDbOverview,
  IDevDbStatement,
  IDevDbTable,
  IDevDeploy,
  IDevEndpointRow,
  IDevExceptionGroup,
  IDevHealth,
  IDevHistogramRow,
  IDevIntegration,
  IDevJob,
  IDevLeak,
  IDevLoggerLevel,
  IDevMemory,
  IDevNPlusOne,
  IDevRate,
  IDevSession,
  IDevSlowRequest,
  IDevUnusedIndex,
  IDevAccountCredentials,
  IDevAccountUpdateReq,
  IDevAuditEntry,
  IDevAuditFilter,
  IDevFileEntry,
  IDevInfo,
  IDevJvm,
  IDevLoginState,
  IDevLogTrace,
  IDevMetrics,
  IDevMetricsHistory,
  IDevPage,
  IDevQueryUpdateResult,
  IDevSchemaTable,
  TDevFileFolder,
  TDevQueryRow,
} from "./dev.types";

export type { IDevAccount } from "./dev.types";

// Panel backend ichidan /<base>/ da beriladi, API esa /<base>/api da — nisbiy yo'l
// context-path va base-path qanday bo'lmasin to'g'ri ishlaydi. Dev rejimida Vite proxy qiladi
const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || "api";

const devInstance = axios.create({ baseURL: API_BASE_URL, timeout: 50000 });

devInstance.interceptors.request.use((config) => {
  config.headers["Accept-Language"] = getCurrentLocale();
  // Backend GET'dan boshqa har bir so'rovda shu header'ni talab qiladi: boshqa saytdagi forma
  // uni qo'sha olmaydi, shuning uchun CSRF bo'lmaydi
  config.headers["X-DevPanel-Request"] = "1";
  return config;
});

// Sessiya tugasa (muddati, chiqish, 2FA reset) — login sahifasiga qaytamiz
devInstance.interceptors.response.use(undefined, (error) => {
  const url: string = error?.config?.url ?? "";
  if (error?.response?.status === 401 && !url.startsWith("/login")) {
    useDevStore().reset();
  }
  return Promise.reject(error);
});

// EventSource (jonli loglar) uchun to'liq URL: axios'dagi kabi nisbiy yo'l
export const liveLogsUrl = (level: string, q: string) => {
  const params = new URLSearchParams();
  if (level) params.set("level", level);
  if (q) params.set("q", q);
  return `${API_BASE_URL}/logs/live?${params}`;
};

export const useDevApi = () => {
  // Login bosqichlari: parol → (vaqtinchalik parolni almashtirish) → (ilovani ulash) → kod
  const LOGIN = (username: string, password: string) =>
    devInstance.post<IDevLoginState>("/login", { username, password });

  const GET_LOGIN_STATE = () => devInstance.get<IDevLoginState>("/login/state");

  const LOGIN_NEW_PASSWORD = (newPassword: string) =>
    devInstance.post<IDevLoginState>("/login/password", { newPassword });

  const LOGIN_CODE = (code: string) =>
    devInstance.post<IDevAccount>("/login/code", { code });

  const LOGOUT = () => devInstance.post("/logout");

  const GET_ME = () => devInstance.get<IDevAccount>("/me");

  const CHANGE_PASSWORD = (body: {
    currentPassword: string;
    newPassword: string;
  }) => devInstance.post("/me/password", body);

  const GET_INFO = () => devInstance.get<IDevInfo>("/info");

  const GET_METRICS = () => devInstance.get<IDevMetrics>("/metrics");

  const GET_METRICS_HISTORY = (minutes: number) =>
    devInstance.get<IDevMetricsHistory>("/metrics/history", {
      params: { minutes },
    });

  const GET_JVM = () => devInstance.get<IDevJvm>("/jvm");

  const GET_THREAD_DUMP = () =>
    devInstance.get<string>("/jvm/threads", { responseType: "text" });

  const GET_AUDIT = (params: IDevAuditFilter) =>
    devInstance.get<IDevPage<IDevAuditEntry>>("/audit", { params });

  const RUN_QUERY = (query: string) =>
    devInstance.post<TDevQueryRow[]>("/query", { query });

  // Xuddi shu so'rov, lekin backend natijani header'li .xlsx qilib qaytaradi
  const RUN_QUERY_EXCEL = (query: string) =>
    devInstance.post<Blob>("/query/excel", { query }, { responseType: "blob" });

  // Faqat READ_WRITE akkauntlar uchun; READ_ONLY'ga backend 403 qaytaradi
  const RUN_QUERY_UPDATE = (query: string) =>
    devInstance.post<IDevQueryUpdateResult>("/query/update", { query });

  const GET_QUERY_SCHEMA = () =>
    devInstance.get<IDevSchemaTable[]>("/query/schema");

  const GET_FILES = (folder: TDevFileFolder) =>
    devInstance.get<IDevFileEntry[]>(`/${folder}`);

  const DOWNLOAD_FILE = (folder: TDevFileFolder, path: string) =>
    devInstance.get<Blob>(`/${folder}/download`, {
      params: { path },
      responseType: "blob",
    });

  // Bitta so'rovning log yozuvlari; from (audit'dagi occurredAt) eski fayllarni o'qimaslik uchun
  const GET_TRACE_LOGS = (traceId: string, from?: string) =>
    devInstance.get<IDevLogTrace>(
      `/logs/trace/${encodeURIComponent(traceId)}`,
      { params: { from } },
    );

  const GET_ACCOUNTS = () => devInstance.get<IDevAccount[]>("/accounts");

  const CREATE_ACCOUNT = (body: IDevAccountCreateReq) =>
    devInstance.post<IDevAccountCredentials>("/accounts", body);

  const UPDATE_ACCOUNT = (id: number, body: IDevAccountUpdateReq) =>
    devInstance.patch<IDevAccount>(`/accounts/${id}`, body);

  const RESET_PASSWORD = (id: number) =>
    devInstance.post<IDevAccountCredentials>(`/accounts/${id}/reset-password`);

  // Telefonini yo'qotgan foydalanuvchi keyingi kirishda ilovani qaytadan ulaydi
  const RESET_TWO_FACTOR = (id: number) =>
    devInstance.post<IDevAccount>(`/accounts/${id}/reset-two-factor`);

  // ---------- 0.2.0: monitoring ----------
  const GET_HEALTH = () => devInstance.get<IDevHealth>("/health");
  const GET_RATE = (minutes: number) =>
    devInstance.get<IDevRate>("/requests/rate", { params: { minutes } });
  const GET_ALERTS = () => devInstance.get<IDevAlerts>("/alerts");
  const TEST_ALERT = () => devInstance.post("/alerts/test");
  const GET_MEMORY = () => devInstance.get<IDevMemory>("/memory");
  // Avval to'liq GC qiladi — ilova bir lahzaga to'xtaydi
  const GET_HISTOGRAM = () =>
    devInstance.post<IDevHistogramRow[]>("/memory/histogram", null, {
      timeout: 120000,
    });
  const GET_ENDPOINTS = (hours: number) =>
    devInstance.get<IDevEndpointRow[]>("/requests/endpoints", {
      params: { hours },
    });
  const GET_SLOW = () => devInstance.get<IDevSlowRequest[]>("/requests/slow");
  const GET_N_PLUS_ONE = () =>
    devInstance.get<IDevNPlusOne[]>("/requests/n-plus-one");
  const GET_EXCEPTIONS = () =>
    devInstance.get<IDevExceptionGroup[]>("/exceptions");
  const GET_DB_OVERVIEW = () =>
    devInstance.get<IDevDbOverview>("/database/overview");
  const GET_DB_ACTIVITY = () =>
    devInstance.get<IDevDbActivity[]>("/database/activity");
  const GET_DB_STATEMENTS = () =>
    devInstance.get<IDevDbStatement[]>("/database/statements");
  const GET_DB_TABLES = () => devInstance.get<IDevDbTable[]>("/database/tables");
  const GET_DB_UNUSED = () =>
    devInstance.get<IDevUnusedIndex[]>("/database/unused-indexes");
  const GET_DB_LEAKS = () => devInstance.get<IDevLeak[]>("/database/leaks");
  const STOP_DB_QUERY = (pid: number, mode: "cancel" | "terminate") =>
    devInstance.post(`/database/activity/${pid}/${mode}`);
  const GET_INTEGRATIONS = () =>
    devInstance.get<IDevIntegration[]>("/integrations");
  const GET_JOBS = () => devInstance.get<IDevJob[]>("/jobs");
  const GET_LOG_LEVELS = (filter?: string) =>
    devInstance.get<IDevLoggerLevel[]>("/logging/levels", {
      params: { filter },
    });
  const SET_LOG_LEVEL = (logger: string, level: string, minutes: number) =>
    devInstance.post("/logging/levels", { logger, level, minutes });
  const RESET_LOG_LEVEL = (logger: string) =>
    devInstance.delete(`/logging/levels/${encodeURIComponent(logger)}`);
  const GET_CONFIG = (filter?: string) =>
    devInstance.get<{ profiles: string[]; properties: IDevConfigProperty[] }>(
      "/config",
      { params: { filter } },
    );
  const GET_DEPLOYS = () =>
    devInstance.get<{ current: IDevDeploy; recent: IDevDeploy[] }>("/deploys");
  const GET_SESSIONS = () => devInstance.get<IDevSession[]>("/me/sessions");
  const END_SESSION = (id: string) => devInstance.delete(`/me/sessions/${id}`);
  const END_OTHER_SESSIONS = () =>
    devInstance.post<{ ended: number }>("/me/sessions/end-others");

  // ---------- 0.3.0 ----------
  const GET_TRACES = () => devInstance.get<IDevTraceSummary[]>("/requests/traces");
  const GET_TRACE = (id: string) =>
    devInstance.get<IDevTrace>(`/requests/traces/${encodeURIComponent(id)}`);
  const GET_USAGE = () =>
    devInstance.get<{
      users: IDevUsage[];
      tenants: IDevUsage[];
      activeUsers: IDevUsage[];
    }>("/usage");
  const GET_UNUSED = () => devInstance.get<IDevUnused>("/usage/unused");
  const GET_SECURITY = () => devInstance.get<IDevSecurity>("/security");
  const GET_TOMCAT = () =>
    devInstance.get<{ state?: IDevTomcat }>("/server/tomcat");
  const GET_DEPENDENCIES = () =>
    devInstance.get<IDevDependency[]>("/dependencies");
  const GET_DEPLOY_REPORT = () =>
    devInstance.get<IDevDeployReport>("/deploys/report");

  return {
    GET_TRACES,
    GET_TRACE,
    GET_USAGE,
    GET_UNUSED,
    GET_SECURITY,
    GET_TOMCAT,
    GET_DEPENDENCIES,
    GET_DEPLOY_REPORT,
    GET_HEALTH,
    GET_RATE,
    GET_ALERTS,
    TEST_ALERT,
    GET_MEMORY,
    GET_HISTOGRAM,
    GET_ENDPOINTS,
    GET_SLOW,
    GET_N_PLUS_ONE,
    GET_EXCEPTIONS,
    GET_DB_OVERVIEW,
    GET_DB_ACTIVITY,
    GET_DB_STATEMENTS,
    GET_DB_TABLES,
    GET_DB_UNUSED,
    GET_DB_LEAKS,
    STOP_DB_QUERY,
    GET_INTEGRATIONS,
    GET_JOBS,
    GET_LOG_LEVELS,
    SET_LOG_LEVEL,
    RESET_LOG_LEVEL,
    GET_CONFIG,
    GET_DEPLOYS,
    GET_SESSIONS,
    END_SESSION,
    END_OTHER_SESSIONS,
    LOGIN,
    GET_LOGIN_STATE,
    LOGIN_NEW_PASSWORD,
    LOGIN_CODE,
    LOGOUT,
    GET_ME,
    CHANGE_PASSWORD,
    GET_INFO,
    GET_METRICS,
    GET_METRICS_HISTORY,
    GET_JVM,
    GET_THREAD_DUMP,
    GET_AUDIT,
    RUN_QUERY,
    RUN_QUERY_EXCEL,
    RUN_QUERY_UPDATE,
    GET_QUERY_SCHEMA,
    GET_FILES,
    DOWNLOAD_FILE,
    GET_TRACE_LOGS,
    GET_ACCOUNTS,
    CREATE_ACCOUNT,
    UPDATE_ACCOUNT,
    RESET_PASSWORD,
    RESET_TWO_FACTOR,
  };
};
