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
  IDevCredentials,
  IDevFileEntry,
  IDevInfo,
  IDevJvm,
  IDevLogTrace,
  IDevMetrics,
  IDevMetricsHistory,
  IDevPage,
  IDevQueryUpdateResult,
  IDevSchemaTable,
  TDevFileFolder,
  TDevQueryRow,
} from "./dev.types";

export type { IDevAccount, IDevCredentials } from "./dev.types";

// Panel backend ichidan /<base>/ da beriladi, API esa /<base>/api da — nisbiy yo'l
// context-path va base-path qanday bo'lmasin to'g'ri ishlaydi. Dev rejimida Vite proxy qiladi
const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || "api";

const devInstance = axios.create({ baseURL: API_BASE_URL, timeout: 50000 });

devInstance.interceptors.request.use((config) => {
  config.headers["Accept-Language"] = getCurrentLocale();
  // Login paytida auth aniq beriladi, qolgan so'rovlar store'dagi login/parolni oladi
  if (!config.auth) {
    const credentials = useDevStore().credentials;
    if (credentials) config.auth = credentials;
  }
  return config;
});

export const useDevApi = () => {
  // Login formasi uchun tekshiruv: 204 yoki 401. Backend hech qachon WWW-Authenticate
  // qaytarmaydi — aks holda brauzer o'zining login oynasini formaning ustiga chiqaradi
  const AUTH = (auth: IDevCredentials) => devInstance.post("/auth", null, { auth });

  const GET_ME = (auth?: IDevCredentials) =>
    devInstance.get<IDevAccount>("/me", { auth });

  const CHANGE_PASSWORD = (
    auth: IDevCredentials,
    body: { currentPassword: string; newPassword: string },
  ) => devInstance.post("/me/password", body, { auth });

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

  return {
    AUTH,
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
  };
};
