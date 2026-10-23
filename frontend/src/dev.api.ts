import axios from "axios";
import { getCurrentLocale } from "./i18n";
import { useDevStore } from "./dev.store";
import {
  IDevAccount,
  IDevAccountCreateReq,
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

  return {
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
