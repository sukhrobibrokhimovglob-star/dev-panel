import { nextTick } from "vue";
import { createI18n } from "vue-i18n";
import en from "./locale/en.json";
import ru from "./locale/ru.json";
import uz from "./locale/uz.json";
import uzCyrl from "./locale/uz_cyrl.json";

const STORAGE_KEY = "dev_panel_lang";

export const SUPPORT_LOCALES = ["ru", "uz", "uz_cyrl", "en"];

const readLocale = () => {
  try {
    const saved = localStorage.getItem(STORAGE_KEY);
    if (saved && SUPPORT_LOCALES.includes(saved)) return saved;
  } catch {
    // storage bloklangan bo'lsa brauzer tilidan olamiz
  }
  const browser = navigator.language?.slice(0, 2);
  return SUPPORT_LOCALES.includes(browser) ? browser : "en";
};

export const i18n = createI18n({
  legacy: false,
  locale: readLocale(),
  fallbackLocale: { uz_cyrl: ["uz", "en"], default: ["en"] },
  messages: { en, ru, uz, uz_cyrl: uzCyrl },
});

export function setI18nLanguage(locale: string) {
  if (!SUPPORT_LOCALES.includes(locale)) locale = "en";
  i18n.global.locale.value = locale as typeof i18n.global.locale.value;
  document.documentElement.setAttribute("lang", locale);
  try {
    localStorage.setItem(STORAGE_KEY, locale);
  } catch {
    // saqlanmasa ham joriy sahifada ishlaydi
  }
  return nextTick();
}

export function getCurrentLocale() {
  return i18n.global.locale.value;
}

/** `useI18n()` faqat setup ichida ishlaydi; boshqa joylarda shu funksiya ishlatiladi. */
export function translate(key: string, params?: Record<string, unknown>) {
  return params ? i18n.global.t(key, params) : i18n.global.t(key);
}
