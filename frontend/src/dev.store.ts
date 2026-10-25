import { defineStore } from "pinia";
import { computed, ref } from "vue";
import { IDevAccount, IDevDeploy, IDevInfo, IDevRunnerReq } from "./dev.types";

/** Another tab asks this one to open on something (a search hit, a replay). */
export interface IDevFocus {
  tab: string;
  endpoint?: string;
  table?: string;
  traceId?: string;
  runner?: IDevRunnerReq;
}

// Login holati brauzerda saqlanmaydi: backend HttpOnly cookie qo'yadi, uni JS o'qiy olmaydi.
// Sahifa ochilganda GET /me javobi kim kirganini aytadi
export const useDevStore = defineStore("dev-panel", () => {
  const account = ref<IDevAccount | null>(null);
  // Backend'da qaysi bo'limlar yoqilgani (/info) — o'chirilganlari menyuda ko'rinmaydi
  const info = ref<IDevInfo | null>(null);

  const canWrite = computed(() => account.value?.access === "READ_WRITE");
  const canUpdateQuery = computed(
    () => canWrite.value && !!info.value?.features?.queryUpdate,
  );
  // USER hisoblar ro'yxatini ko'ra olmaydi (backend 403 qaytaradi)
  const canManageAccounts = computed(
    () =>
      account.value?.role === "SUPER_ADMIN" || account.value?.role === "ADMIN",
  );
  const isSuperAdmin = computed(() => account.value?.role === "SUPER_ADMIN");
  const focus = ref<IDevFocus | null>(null);
  // Grafiklardagi deploy chiziqlari uchun; har bo'lim qayta so'ramasligi uchun shu yerda
  const deploys = ref<IDevDeploy[] | null>(null);

  /** Reads and clears what was asked for this tab. */
  const takeFocus = (tab: string) => {
    if (focus.value?.tab !== tab) return null;
    const asked = focus.value;
    focus.value = null;
    return asked;
  };

  const reset = () => {
    account.value = null;
    info.value = null;
    focus.value = null;
    deploys.value = null;
  };

  return {
    account,
    info,
    canWrite,
    canUpdateQuery,
    canManageAccounts,
    isSuperAdmin,
    focus,
    deploys,
    takeFocus,
    reset,
  };
});
