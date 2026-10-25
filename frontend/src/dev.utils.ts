import { computed, Ref } from "vue";
import axios from "axios";
import moment from "moment";
import { ElMessage } from "element-plus";
import { translate } from "./i18n";
import { useDevStore } from "./dev.store";
import { useDevApi } from "./dev.api";

const UNITS = ["B", "KB", "MB", "GB", "TB"];

export type TDevRange = [string | undefined, string | undefined] | null;

// Oraliq tanlagich ikki oylik panel ochadi (~650px) — tor ekranda sig'maydi, shuning uchun
// u yerda ikkita alohida tanlagich ishlatiladi va ular shu bitta oraliqni boshqaradi
export const useRangeEnds = (range: Ref<TDevRange>) => {
  const end = (index: 0 | 1) =>
    computed<string | null>({
      get: () => range.value?.[index] ?? null,
      set: (value) => {
        const next: [string | undefined, string | undefined] = [
          range.value?.[0],
          range.value?.[1],
        ];
        next[index] = value || undefined;
        range.value = next[0] || next[1] ? next : null;
      },
    });
  return { rangeFrom: end(0), rangeTo: end(1) };
};

export const formatBytes = (bytes: number) => {
  if (!bytes || bytes < 0) return "0 B";
  const exp = Math.min(
    Math.floor(Math.log(bytes) / Math.log(1024)),
    UNITS.length - 1,
  );
  const value = bytes / Math.pow(1024, exp);
  return `${value.toFixed(exp === 0 ? 0 : 1)} ${UNITS[exp]}`;
};

// Backend yuk qiymatini 0..1 oralig'ida beradi; mavjud bo'lmasa manfiy son keladi
export const toPercent = (fraction: number) =>
  fraction == null || fraction < 0 ? 0 : Math.round(fraction * 1000) / 10;

export const usagePercent = (used: number, total: number) =>
  total > 0 ? Math.round((used / total) * 1000) / 10 : 0;

export const formatDateTime = (value: string) =>
  value ? moment(value).format("DD.MM.YYYY HH:mm:ss") : "—";

/**
 * Blob so'rovlarda (fayl yuklash) xato javobi ham Blob bo'lib keladi,
 * shuning uchun avval uni JSON'ga aylantirib, keyin xabarni olamiz.
 */
export const devErrorMessage = async (error: unknown): Promise<string> => {
  if (axios.isAxiosError(error)) {
    // Faqat parol bosqichida "login yoki parol noto'g'ri"; boshqa 401 larda backend sababini aytadi
    if (error.response?.status === 401 && error.config?.url === "/login")
      return translate("devPanel.invalidCredentials");
    const data = error.response?.data;
    if (data instanceof Blob) {
      try {
        const parsed = JSON.parse(await data.text());
        const message = parsed?.error?.message ?? parsed?.message;
        if (typeof message === "string" && message.trim()) return message;
      } catch {
        // JSON emas — umumiy xabarga tushamiz
      }
    }
  }
  return getBackendErrorMessage(error) ?? translate("devPanel.serverError");
};

// Backend xatosi: { error: { message }, traceId } yoki eski { message }
const getBackendErrorMessage = (error: unknown): string | undefined => {
  if (!axios.isAxiosError(error)) return undefined;
  const data = error.response?.data;
  const message = data?.error?.message ?? data?.message;
  return typeof message === "string" && message.trim() ? message : undefined;
};

// Backend sanani vaqt zonasisiz (Toshkent vaqti) beradi — moment uni mahalliy deb o'qiydi,
// shuning uchun brauzer zonasiga o'girilmay, kelganicha chiqadi
export const formatDevDate = (value: string | null, withTime = true) =>
  value
    ? moment(value).format(withTime ? "DD.MM.YYYY HH:mm" : "DD.MM.YYYY")
    : "—";

export interface IDevError {
  status?: number;
  message: string;
  traceId?: string;
}

// Xabar yonida traceId ham kerak — u bilan loglar /logs/trace orqali topiladi
export const toDevError = async (error: unknown): Promise<IDevError> => {
  const response = axios.isAxiosError(error) ? error.response : undefined;
  const traceId = response?.data?.traceId;
  return {
    status: response?.status,
    message: await devErrorMessage(error),
    traceId: typeof traceId === "string" && traceId ? traceId : undefined,
  };
};

export const copyToClipboard = async (text: string) => {
  try {
    await navigator.clipboard.writeText(text);
    ElMessage.success(translate("devPanel.copied"));
  } catch {
    ElMessage.error(translate("devPanel.serverError"));
  }
};

// ---------- 0.2.0: monitoring yordamchilari ----------

export const STATUS_COLORS: Record<string, string> = {
  OK: "#3ec972",
  WARNING: "#e6a23c",
  CRITICAL: "#d14343",
  UNKNOWN: "#909399",
};

export const statusTag = (status: string) =>
  status === "CRITICAL"
    ? "danger"
    : status === "WARNING"
      ? "warning"
      : status === "OK"
        ? "success"
        : "info";

export const formatMs = (ms: number | null | undefined) => {
  if (ms == null || ms < 0) return "—";
  if (ms < 1000) return `${Math.round(ms)} ms`;
  if (ms < 60000) return `${(ms / 1000).toFixed(1)} s`;
  if (ms < 3600000) return `${Math.round(ms / 60000)} min`;
  return `${(ms / 3600000).toFixed(1)} h`;
};

export const formatTime = (value: string | number | null | undefined) =>
  value == null ? "—" : moment(value).format("DD.MM HH:mm:ss");

export const fromNow = (value: string | number | null | undefined) =>
  value == null ? "—" : moment(value).fromNow();

// Har N soniyada qayta yuklash; tab ko'rinmay qolsa so'rov yubormaydi
export const useAutoRefresh = (load: () => unknown, seconds: number) => {
  let timer: ReturnType<typeof setInterval> | null = null;
  const start = () => {
    stop();
    timer = setInterval(() => {
      if (document.visibilityState === "visible") load();
    }, seconds * 1000);
  };
  const stop = () => {
    if (timer) clearInterval(timer);
    timer = null;
  };
  return { start, stop };
};

// ---------- 0.4.0 ----------

/** Deploys for chart markers, fetched once per login and shared by every chart. */
export const useDeploys = () => {
  const store = useDevStore();
  const list = computed(() => store.deploys);
  let loading = false;
  const load = async () => {
    if (store.deploys || loading) return;
    loading = true;
    try {
      store.deploys = (await useDevApi().GET_DEPLOYS()).data.recent;
    } catch {
      store.deploys = [];
    } finally {
      loading = false;
    }
  };
  return { list, load };
};

export const SEVERITY_TAG: Record<string, "danger" | "warning" | "info"> = {
  CRITICAL: "danger",
  WARNING: "warning",
  INFO: "info",
};

export const formatPercent = (share: number | null | undefined, digits = 1) =>
  share == null || share < 0 ? "—" : `${(share * 100).toFixed(digits)}%`;

export const formatNumber = (value: number | null | undefined) =>
  value == null ? "—" : Math.round(value).toLocaleString();

/** A runner request as curl, for the terminal. The token is a placeholder. */
export const toCurl = (
  baseUrl: string,
  method: string,
  path: string,
  headers: Record<string, string>,
  body?: string | null,
) => {
  const quote = (value: string) => `'${value.replace(/'/g, "'\\''")}'`;
  const parts = [`curl -X ${method} ${quote(baseUrl + path)}`];
  Object.entries(headers).forEach(([name, value]) =>
    parts.push(`  -H ${quote(`${name}: ${value}`)}`),
  );
  if (body) parts.push(`  --data-raw ${quote(body)}`);
  return parts.join(" \\\n");
};

/** Where the application itself is served: the panel's address without /dev-panel/... */
export const applicationBaseUrl = () => {
  const href = window.location.href.split("#")[0].split("?")[0];
  const index = href.lastIndexOf("/dev-panel");
  return index >= 0 ? href.substring(0, index) : window.location.origin;
};

export const prettyJson = (text: string | null | undefined) => {
  if (!text) return "";
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return text;
  }
};

/** Opens a server-made HTML page (the weekly report) in a new tab. */
export const openBlob = (blob: Blob) => {
  const url = URL.createObjectURL(blob);
  window.open(url, "_blank", "noopener");
  setTimeout(() => URL.revokeObjectURL(url), 60000);
};
