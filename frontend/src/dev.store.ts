import { defineStore } from "pinia";
import { computed, ref } from "vue";
import { IDevAccount, IDevCredentials, IDevInfo } from "./dev.types";

// Basic auth har so'rovda login/parolni talab qiladi, shuning uchun ular odatda tab
// yopilguncha sessionStorage'da turadi. "Meni eslab qol" belgilansa — localStorage'da,
// brauzer yopilgandan keyin ham saqlanadi (chiqish tugmasi bilan o'chadi)
const SESSION_KEY = "dev_panel_auth";

const readCredentials = (storage: Storage): IDevCredentials | null => {
  try {
    const auth = JSON.parse(storage.getItem(SESSION_KEY) || "null");
    return auth?.username && auth?.password ? auth : null;
  } catch {
    return null;
  }
};

const clearCredentials = () => {
  try {
    sessionStorage.removeItem(SESSION_KEY);
    localStorage.removeItem(SESSION_KEY);
  } catch {
    // storage bloklangan bo'lsa saqlanmagan ham
  }
};

export const useDevStore = defineStore("dev-panel", () => {
  const remembered = readCredentials(localStorage);
  const credentials = ref<IDevCredentials | null>(
    remembered ?? readCredentials(sessionStorage),
  );
  const rememberMe = ref(!!remembered);
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

  const setCredentials = (auth: IDevCredentials | null) => {
    credentials.value = auth;
    clearCredentials();
    if (!auth) return;
    try {
      (rememberMe.value ? localStorage : sessionStorage).setItem(
        SESSION_KEY,
        JSON.stringify(auth),
      );
    } catch {
      // saqlab bo'lmasa sahifa yangilanguncha xotirada ishlaydi
    }
  };

  const reset = () => {
    setCredentials(null);
    account.value = null;
    info.value = null;
  };

  return {
    credentials,
    rememberMe,
    account,
    info,
    canWrite,
    canUpdateQuery,
    canManageAccounts,
    isSuperAdmin,
    setCredentials,
    reset,
  };
});
