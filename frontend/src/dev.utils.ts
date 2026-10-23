import { computed, Ref } from "vue";
import axios from "axios";
import moment from "moment";
import { ElMessage } from "element-plus";
import { translate } from "./i18n";

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
    if (error.response?.status === 401)
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
