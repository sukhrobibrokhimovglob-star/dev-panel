import { defineStore } from "pinia";
import { computed, ref } from "vue";
import { IDevAccount, IDevInfo } from "./dev.types";

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

  const reset = () => {
    account.value = null;
    info.value = null;
  };

  return {
    account,
    info,
    canWrite,
    canUpdateQuery,
    canManageAccounts,
    isSuperAdmin,
    reset,
  };
});
