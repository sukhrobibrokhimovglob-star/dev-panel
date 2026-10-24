<template>
  <div class="dev-layout">
    <!-- Mobil ekranda sidebar chetdan chiqadigan menyu bo'ladi -->
    <Transition name="dev-fade">
      <div
        v-if="menuOpen"
        class="dev-backdrop"
        aria-hidden="true"
        @click="menuOpen = false"
      />
    </Transition>

    <aside class="dev-sidebar" :class="{ 'is-open': menuOpen }">
      <div class="dev-sidebar__brand">
        <span class="dev-sidebar__logo"
          >&gt;<span class="dev-sidebar__caret">_</span></span
        >
        <div class="dev-sidebar__brand-text">
          <b>DEV PANEL</b>
          <span>{{ $t("devPanel.subtitle") }}</span>
        </div>
      </div>

      <nav class="dev-sidebar__nav">
        <button
          type="button"
          class="dev-sidebar__item"
          :class="{ 'is-active': activeTab === 'overview' }"
          @click="activeTab = 'overview'"
        >
          <el-icon :size="16"><Grid /></el-icon>
          <span>{{ $t("devPanel.tabs.overview") }}</span>
        </button>

        <div
          v-for="group in groups"
          :key="group.name"
          class="dev-sidebar__group"
        >
          <p class="dev-sidebar__label">
            {{ $t(`devPanel.groups.${group.name}`) }}
          </p>
          <div class="dev-sidebar__group-items">
            <button
              v-for="item in group.items"
              :key="item.name"
              type="button"
              class="dev-sidebar__item"
              :class="{ 'is-active': activeTab === item.name }"
              @click="selectTab(item.name)"
            >
              <el-icon :size="16"><component :is="item.icon" /></el-icon>
              <span>{{ $t(`devPanel.tabs.${item.name}`) }}</span>
            </button>
          </div>
        </div>
      </nav>

      <button type="button" class="dev-sidebar__logout" @click="emit('logout')">
        <el-icon :size="16"><SwitchButton /></el-icon>
        <span>{{ $t("logout") }}</span>
      </button>
    </aside>

    <div class="dev-main">
      <header class="dev-topbar">
        <button
          type="button"
          class="dev-topbar__btn dev-topbar__btn--icon dev-topbar__menu"
          :aria-label="$t('devPanel.menu')"
          :aria-expanded="menuOpen"
          @click="menuOpen = !menuOpen"
        >
          <el-icon :size="18"><Expand /></el-icon>
        </button>

        <Transition name="dev-title" mode="out-in">
          <h1 class="dev-topbar__title" :key="activeTab">
            {{ $t(`devPanel.tabs.${activeTab}`) }}
          </h1>
        </Transition>

        <div class="dev-topbar__actions">
          <button
            type="button"
            class="dev-topbar__btn"
            :aria-label="$t('devPanel.search')"
            @click="openSearch"
          >
            <el-icon :size="16" class="dev-topbar__search-icon"
              ><Search
            /></el-icon>
            <span>{{ $t("devPanel.search") }}</span>
            <kbd>Ctrl K</kbd>
          </button>

          <el-dropdown trigger="click" @command="setI18nLanguage">
            <button type="button" class="dev-topbar__btn dev-topbar__btn--mono">
              {{ LOCALE_LABELS[locale] || locale }}
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item
                  v-for="code in SUPPORT_LOCALES"
                  :key="code"
                  :command="code"
                  :disabled="code === locale"
                >
                  {{ LOCALE_LABELS[code] }}
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>

          <button
            type="button"
            class="dev-topbar__btn dev-topbar__btn--icon"
            :aria-label="$t('devPanel.theme')"
            @click="isDark = !isDark"
          >
            <el-icon :size="16">
              <Transition name="dev-spin" mode="out-in">
                <Moon v-if="!isDark" key="moon" />
                <Sunny v-else key="sun" />
              </Transition>
            </el-icon>
          </button>

          <div class="dev-topbar__account" v-if="store.account">
            <b>{{ store.account.username }}</b>
            <span class="dev-badge">{{ store.account.role }}</span>
          </div>
        </div>
      </header>

      <main class="dev-content">
        <Transition name="dev-tab" mode="out-in">
          <!-- Umumiy ko'rinish: akkaunt, muhit va barcha bo'limlarga tezkor o'tish -->
          <div v-if="activeTab === 'overview'" class="dev-overview">
            <div class="dev-overview__top">
              <section class="dev-card" v-if="store.account">
                <h2 class="dev-card__title">
                  {{ $t("devPanel.overview.account") }}
                </h2>
                <dl class="dev-props">
                  <div>
                    <dt>{{ $t("devPanel.login") }}</dt>
                    <dd>{{ store.account.username }}</dd>
                  </div>
                  <div>
                    <dt>{{ $t("devPanel.role") }}</dt>
                    <dd>{{ store.account.role }}</dd>
                  </div>
                  <div>
                    <dt>{{ $t("devPanel.access") }}</dt>
                    <dd>{{ store.account.access }}</dd>
                  </div>
                  <div>
                    <dt>{{ $t("devPanel.accounts.createdBy") }}</dt>
                    <dd>{{ store.account.createdBy || "—" }}</dd>
                  </div>
                  <div>
                    <dt>{{ $t("devPanel.accounts.createdAt") }}</dt>
                    <dd>{{ formatDate(store.account.createdAt) }}</dd>
                  </div>
                </dl>
              </section>

              <section class="dev-card">
                <h2 class="dev-card__title">
                  {{ $t("devPanel.overview.environment") }}
                </h2>
                <dl class="dev-props">
                  <div>
                    <dt>{{ $t("devPanel.overview.application") }}</dt>
                    <dd>{{ info?.application || "—" }}</dd>
                  </div>
                  <div>
                    <dt>{{ $t("devPanel.overview.mode") }}</dt>
                    <dd>{{ info?.profiles?.join(", ") || "default" }}</dd>
                  </div>
                  <div>
                    <dt>Java</dt>
                    <dd>{{ info?.javaVersion || "—" }}</dd>
                  </div>
                  <div>
                    <dt>{{ $t("devPanel.overview.auth") }}</dt>
                    <dd>HTTP Basic</dd>
                  </div>
                  <div>
                    <dt>{{ $t("devPanel.overview.sections") }}</dt>
                    <dd>{{ flatItems.length }}</dd>
                  </div>
                </dl>
              </section>
            </div>

            <template v-for="group in groups" :key="group.name">
              <p class="dev-overview__label">
                {{ $t(`devPanel.groups.${group.name}`) }}
              </p>
              <div class="dev-overview__grid">
                <button
                  v-for="item in group.items"
                  :key="item.name"
                  type="button"
                  class="dev-card dev-card--link"
                  :style="{ '--i': flatItems.indexOf(item) }"
                  @click="selectTab(item.name)"
                >
                  <span class="dev-card__title">
                    {{ $t(`devPanel.tabs.${item.name}`) }}
                  </span>
                  <code class="dev-card__endpoint">{{ item.endpoint }}</code>
                </button>
              </div>
            </template>
          </div>

          <div v-else class="dev-content__body" :key="activeTab">
            <!-- v-if: menyu almashganda bo'lim qayta yaratilib, ma'lumot yangidan olinadi;
               logs/docs bir komponent, shuning uchun key bilan ajratilgan -->
            <DevHealth v-if="activeTab === 'health'" @open="selectTab" />
            <DevMetrics v-else-if="activeTab === 'metrics'" />
            <DevDatabase v-else-if="activeTab === 'database'" />
            <DevApi v-else-if="activeTab === 'api'" />
            <DevErrors v-else-if="activeTab === 'errors'" />
            <DevUsage v-else-if="activeTab === 'usage'" />
            <DevThreats v-else-if="activeTab === 'threats'" />
            <DevIntegrations v-else-if="activeTab === 'integrations'" />
            <DevJobs v-else-if="activeTab === 'jobs'" />
            <DevAlerts v-else-if="activeTab === 'alerts'" />
            <DevLiveLogs v-else-if="activeTab === 'liveLogs'" />
            <DevLogLevels v-else-if="activeTab === 'logLevels'" />
            <DevConfig v-else-if="activeTab === 'config'" />
            <DevSecurity v-else-if="activeTab === 'security'" />
            <DevJvm v-else-if="activeTab === 'jvm'" />
            <DevAudit v-else-if="activeTab === 'audit'" />
            <DevQuery v-else-if="activeTab === 'query'" />
            <DevFiles
              v-else-if="activeTab === 'logs'"
              key="logs"
              folder="logs"
            />
            <DevFiles
              v-else-if="activeTab === 'docs'"
              key="docs"
              folder="docs"
            />
            <DevAccounts v-else-if="activeTab === 'accounts'" />
          </div>
        </Transition>
      </main>
    </div>

    <!-- Ctrl K: bo'limlar bo'yicha tezkor qidiruv -->
    <Transition name="dev-search">
      <div
        v-if="searchOpen"
        class="dev-search"
        @click.self="searchOpen = false"
        @keydown.esc="searchOpen = false"
      >
        <div class="dev-search__box" role="dialog">
          <input
            ref="searchInput"
            v-model="searchText"
            class="dev-search__input"
            :placeholder="$t('devPanel.searchPlaceholder')"
            @keydown.down.prevent="moveCursor(1)"
            @keydown.up.prevent="moveCursor(-1)"
            @keydown.enter.prevent="pick(searchResults[cursor])"
          />
          <ul class="dev-search__list" v-if="searchResults.length">
            <li
              v-for="(item, index) in searchResults"
              :key="item.name"
              :class="{ 'is-active': index === cursor }"
              @mouseenter="cursor = index"
              @click="pick(item)"
            >
              <el-icon :size="16"><component :is="item.icon" /></el-icon>
              <span>{{ $t(`devPanel.tabs.${item.name}`) }}</span>
              <code>{{ item.endpoint }}</code>
            </li>
          </ul>
          <p class="dev-search__empty" v-else>{{ $t("devPanel.noResults") }}</p>
        </div>
      </div>
    </Transition>
  </div>
</template>

<script setup lang="ts">
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
  type Component,
} from "vue";
import { useI18n } from "vue-i18n";
import { useDark } from "@vueuse/core";
import {
  Aim,
  Bell,
  DataAnalysis,
  Coin,
  Connection,
  Cpu,
  Document,
  Expand,
  FirstAidKit,
  Grid,
  Lock,
  Monitor,
  Moon,
  Odometer,
  Operation,
  Search,
  Setting,
  Sunny,
  SwitchButton,
  Tickets,
  Timer,
  User,
  VideoPlay,
  Warning,
  WarningFilled,
} from "@element-plus/icons-vue";
import { SUPPORT_LOCALES, setI18nLanguage } from "../i18n";
import { useDevApi } from "../dev.api";
import { useDevStore } from "../dev.store";
import DevMetrics from "./DevMetrics.vue";
import DevJvm from "./DevJvm.vue";
import DevAudit from "./DevAudit.vue";
import DevQuery from "./DevQuery.vue";
import DevFiles from "./DevFiles.vue";
import DevAccounts from "./DevAccounts.vue";
import DevHealth from "./DevHealth.vue";
import DevDatabase from "./DevDatabase.vue";
import DevApi from "./DevApi.vue";
import DevErrors from "./DevErrors.vue";
import DevIntegrations from "./DevIntegrations.vue";
import DevJobs from "./DevJobs.vue";
import DevAlerts from "./DevAlerts.vue";
import DevLiveLogs from "./DevLiveLogs.vue";
import DevLogLevels from "./DevLogLevels.vue";
import DevConfig from "./DevConfig.vue";
import DevSecurity from "./DevSecurity.vue";
import DevUsage from "./DevUsage.vue";
import DevThreats from "./DevThreats.vue";

interface IMenuItem {
  name: string;
  icon: Component;
  endpoint: string;
}

const emit = defineEmits<{ (e: "logout"): void }>();

const TAB_KEY = "dev_panel_tab";
const LOCALE_LABELS: Record<string, string> = {
  ru: "RU",
  uz: "UZ",
  uz_cyrl: "ЎЗ",
  en: "EN",
};

const store = useDevStore();
const { t, locale } = useI18n();
const { GET_INFO } = useDevApi();
// Ilovaning umumiy dark rejimi (html.dark) — Element Plus va Tailwind dark: klasslari ham shunga bog'liq
const isDark = useDark();
const info = computed(() => store.info);
const features = computed(() => store.info?.features);

const groups = computed(() =>
  [
    {
      name: "monitoring",
      items: [
        { name: "health", icon: FirstAidKit, endpoint: "GET /health" },
        { name: "metrics", icon: Monitor, endpoint: "GET /metrics" },
        { name: "jvm", icon: Cpu, endpoint: "GET /jvm · /memory" },
        { name: "database", icon: Coin, endpoint: "GET /database/*" },
        { name: "api", icon: Odometer, endpoint: "GET /requests/endpoints" },
        { name: "errors", icon: WarningFilled, endpoint: "GET /exceptions" },
        ...(features.value?.audit === false
          ? []
          : [{ name: "audit", icon: Warning, endpoint: "GET /audit" }]),
        { name: "usage", icon: DataAnalysis, endpoint: "GET /usage" },
        { name: "threats", icon: Aim, endpoint: "GET /security" },
        { name: "integrations", icon: Connection, endpoint: "GET /integrations" },
        { name: "jobs", icon: Timer, endpoint: "GET /jobs" },
        { name: "alerts", icon: Bell, endpoint: "GET /alerts" },
      ],
    },
    {
      name: "tools",
      items: [
        ...(features.value?.query === false
          ? []
          : [{ name: "query", icon: Search, endpoint: "POST /query" }]),
        { name: "liveLogs", icon: VideoPlay, endpoint: "GET /logs/live" },
        { name: "logLevels", icon: Operation, endpoint: "POST /logging/levels" },
        ...(store.canManageAccounts
          ? [{ name: "config", icon: Setting, endpoint: "GET /config" }]
          : []),
      ],
    },
    {
      name: "files",
      items:
        features.value?.files === false
          ? []
          : [
              { name: "logs", icon: Tickets, endpoint: "GET /logs" },
              { name: "docs", icon: Document, endpoint: "GET /docs" },
            ],
    },
    {
      name: "operations",
      items: [
        ...(store.canManageAccounts
          ? [{ name: "accounts", icon: User, endpoint: "GET /accounts" }]
          : []),
        { name: "security", icon: Lock, endpoint: "GET /me/sessions" },
      ],
    },
  ].filter((group) => group.items.length),
);

const flatItems = computed<IMenuItem[]>(() =>
  groups.value.flatMap((group) => group.items),
);

const readTab = () => {
  try {
    return sessionStorage.getItem(TAB_KEY) || "health";
  } catch {
    return "health";
  }
};

const activeTab = ref(readTab());
if (
  activeTab.value !== "overview" &&
  !flatItems.value.some((item) => item.name === activeTab.value)
)
  activeTab.value = "health";

const menuOpen = ref(false);

watch(activeTab, (tab) => {
  menuOpen.value = false;
  try {
    sessionStorage.setItem(TAB_KEY, tab);
  } catch {
    // saqlanmasa ham panel ishlayveradi
  }
});

const selectTab = (tab: string) => {
  activeTab.value = tab;
};

const formatDate = (value: string) =>
  value ? new Date(value).toLocaleString() : "—";

// ---------- Qidiruv ----------

const searchOpen = ref(false);
const searchText = ref("");
const cursor = ref(0);
const searchInput = ref<HTMLInputElement>();

const searchResults = computed(() => {
  const query = searchText.value.trim().toLowerCase();
  if (!query) return flatItems.value;
  return flatItems.value.filter((item) =>
    `${t(`devPanel.tabs.${item.name}`)} ${item.endpoint}`
      .toLowerCase()
      .includes(query),
  );
});

watch(searchText, () => (cursor.value = 0));

const openSearch = async () => {
  searchText.value = "";
  cursor.value = 0;
  searchOpen.value = true;
  menuOpen.value = false;
  await nextTick();
  searchInput.value?.focus();
};

const moveCursor = (step: number) => {
  const total = searchResults.value.length;
  if (total) cursor.value = (cursor.value + step + total) % total;
};

const pick = (item?: IMenuItem) => {
  if (!item) return;
  selectTab(item.name);
  searchOpen.value = false;
};

const onKeydown = (event: KeyboardEvent) => {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "k") {
    event.preventDefault();
    searchOpen.value ? (searchOpen.value = false) : openSearch();
  } else if (event.key === "Escape") {
    searchOpen.value = false;
    menuOpen.value = false;
  }
};

onMounted(async () => {
  window.addEventListener("keydown", onKeydown);
  try {
    store.info = (await GET_INFO()).data;
    // Saqlangan bo'lim endi o'chirilgan bo'lsa — umumiy ko'rinishga qaytamiz
    if (
      activeTab.value !== "overview" &&
      !flatItems.value.some((item) => item.name === activeTab.value)
    )
      activeTab.value = "health";
  } catch {
    // overview'da "—" ko'rinadi
  }
});
onBeforeUnmount(() => window.removeEventListener("keydown", onKeydown));
</script>

<style scoped>
.dev-layout {
  --bg: #f3f6fa;
  --surface: #ffffff;
  --sidebar: #ffffff;
  --border: #dde4ee;
  --border-strong: #c7d2e0;
  --text: #0f1b2d;
  --muted: #5b6b82;
  --label: #3b6ea8;
  --hover: #eef2f8;
  --active: #e4ebf5;
  --accent: #f5a524;
  --accent-ink: #1a1203;
  --mono: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;

  display: flex;
  /* style.css html/body'ga overflow: hidden beradi — sahifa o'zi scroll bo'lmaydi,
     shuning uchun panelning o'zi ekran balandligidagi scroll konteyner bo'ladi */
  height: 100vh;
  height: 100dvh;
  overflow-y: auto;
  background: var(--bg);
  color: var(--text);
}

.dark .dev-layout {
  --bg: #0b1422;
  --surface: #0f1a2b;
  --sidebar: #0d1726;
  --border: #22324a;
  --border-strong: #34496a;
  --text: #e6edf7;
  --muted: #8898b0;
  --label: #7fa7d6;
  --hover: #14233a;
  --active: #1b2b44;
}

/* ---------- Scrollbar ---------- */

/* brauzerning standart kulrang scrollbari (strelkalari bilan) navy palitraga tushmaydi —
   panel ichidagi barcha scroll bo'ladigan bloklar uchun ingichka, palitradagi scrollbar */
.dev-layout {
  --el-scrollbar-bg-color: var(--border-strong);
  --el-scrollbar-hover-bg-color: var(--muted);
  --el-scrollbar-opacity: 1;
  --el-scrollbar-hover-opacity: 1;
}

@supports not selector(::-webkit-scrollbar) {
  .dev-layout,
  .dev-layout :deep(*) {
    scrollbar-width: thin;
    scrollbar-color: var(--border-strong) transparent;
  }
}

.dev-layout :deep(::-webkit-scrollbar) {
  width: 8px;
  height: 8px;
}

.dev-layout :deep(::-webkit-scrollbar-track),
.dev-layout :deep(::-webkit-scrollbar-corner) {
  background: transparent;
}

.dev-layout :deep(::-webkit-scrollbar-thumb) {
  border: 2px solid transparent;
  border-radius: 999px;
  background: var(--border-strong) padding-box;
}

.dev-layout :deep(::-webkit-scrollbar-thumb:hover) {
  background: var(--muted) padding-box;
}

.dev-layout :deep(::-webkit-scrollbar-button) {
  display: none;
}

/* ---------- Sidebar ---------- */

.dev-sidebar {
  position: sticky;
  top: 0;
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  width: 240px;
  height: 100vh;
  border-right: 1px solid var(--border);
  background: var(--sidebar);
}

.dev-sidebar__brand {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 12px 16px 12px;
  border-bottom: 1px solid var(--border);
}

.dev-sidebar__logo {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 28px;
  height: 28px;
  border-radius: 6px;
  background: var(--accent);
  color: var(--accent-ink);
  font-family: var(--mono);
  font-size: 12px;
  font-weight: 700;
}

.dev-sidebar__brand-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.dev-sidebar__brand-text b {
  font-family: var(--mono);
  font-size: 12px;
  letter-spacing: 0.2em;
}

.dev-sidebar__brand-text span {
  font-size: 11px;
  color: var(--muted);
}

.dev-sidebar__nav {
  flex: 1;
  padding: 16px 12px;
  overflow-y: auto;
}

.dev-sidebar__group {
  margin-top: 20px;
}

.dev-sidebar__label,
.dev-overview__label {
  margin: 0 0 8px;
  padding-left: 2px;
  font-family: var(--mono);
  font-size: 9.5px;
  font-weight: 600;
  letter-spacing: 0.16em;
  text-transform: uppercase;
  color: var(--muted);
}

.dev-sidebar__group-items {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-left: 0;
  padding-left: 0;
  border-left: 1px solid var(--border);
}

.dev-sidebar__item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 8px 12px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--text);
  font-family: inherit;
  font-size: 13.5px;
  text-align: left;
  cursor: pointer;
  opacity: 0.86;
  transition:
    background 0.15s ease,
    opacity 0.15s ease;
}

.dev-sidebar__group-items .dev-sidebar__item {
  margin-left: 0;
  border-radius: 0 6px 6px 0;
}

.dev-sidebar__item:hover {
  background: var(--hover);
  opacity: 1;
}

.dev-sidebar__item.is-active {
  background: var(--active);
  font-weight: 600;
  opacity: 1;
}

.dev-sidebar__group-items .dev-sidebar__item.is-active {
  box-shadow: inset 2px 0 0 var(--accent);
}

.dev-sidebar__logout {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 12px;
  padding: 8px 12px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: transparent;
  color: var(--muted);
  font-family: inherit;
  font-size: 13px;
  cursor: pointer;
  transition:
    background 0.15s ease,
    color 0.15s ease;
}

.dev-sidebar__logout:hover {
  background: var(--hover);
  color: var(--text);
}

/* ---------- Topbar ---------- */

.dev-main {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
}

.dev-topbar {
  position: sticky;
  top: 0;
  z-index: 10;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-height: 55px;
  padding: 8px 24px;
  border-bottom: 1px solid var(--border);
  background: var(--sidebar);
}

.dev-topbar__title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
}

.dev-topbar__actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.dev-topbar__btn {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 30px;
  padding: 0 10px;
  border: 1px solid transparent;
  border-radius: 6px;
  background: transparent;
  color: var(--text);
  font-family: inherit;
  font-size: 12.5px;
  font-weight: 600;
  cursor: pointer;
  transition:
    background 0.15s ease,
    border-color 0.15s ease;
}

.dev-topbar__btn:hover {
  border-color: var(--border);
  background: var(--hover);
}

.dev-topbar__btn--mono {
  font-family: var(--mono);
}

.dev-topbar__btn--icon {
  width: 30px;
  justify-content: center;
  padding: 0;
}

.dev-topbar__btn kbd {
  font-family: var(--mono);
  font-size: 10px;
  color: var(--muted);
}

.dev-topbar__account {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: 8px;
  font-family: var(--mono);
  font-size: 13px;
}

.dev-badge {
  padding: 3px 7px;
  border-radius: 4px;
  background: rgba(245, 165, 36, 0.14);
  color: var(--accent);
  font-family: var(--mono);
  font-size: 9.5px;
  font-weight: 700;
  letter-spacing: 0.06em;
}

/* ---------- Kontent ---------- */

.dev-content {
  flex: 1;
  padding: 24px clamp(16px, 2.2vw, 32px) 40px;
}

.dev-content__body {
  padding: clamp(16px, 2vw, 24px);
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--surface);
}

/* ---------- Element Plus (dark) ---------- */

/* Element Plus'ning dark css-vars'i ulanmagan, style.css esa jadval/inputlarga !important bilan
   kulrang (#323238) fon beradi — navy palitrada xunuk ko'rinadi, shuning uchun panel ichida qayta belgilaymiz */
.dark .dev-layout {
  --el-bg-color: var(--surface);
  --el-bg-color-overlay: var(--surface);
  --el-fill-color-blank: var(--surface);
  --el-fill-color-light: var(--hover);
  --el-fill-color-lighter: var(--sidebar);
  --el-border-color: var(--border-strong);
  --el-border-color-light: var(--border);
  --el-border-color-lighter: var(--border);
  --el-text-color-primary: var(--text);
  --el-text-color-regular: var(--text);
  --el-text-color-secondary: var(--muted);
  --el-text-color-placeholder: var(--muted);
  --el-mask-color: rgba(11, 20, 34, 0.7);
}

.dark .dev-layout :deep(.el-input__wrapper),
.dark .dev-layout :deep(.el-textarea__inner),
.dark .dev-layout :deep(.el-select__wrapper) {
  background: var(--bg) !important;
}

.dark .dev-layout :deep(.el-breadcrumb__inner),
.dark .dev-layout :deep(.el-breadcrumb__separator) {
  color: var(--muted);
}

.dark .dev-layout :deep(.el-breadcrumb__item:last-child .el-breadcrumb__inner) {
  color: var(--text);
}

.dark .dev-layout :deep(.el-table) {
  --el-table-border-color: var(--border);
  --el-table-bg-color: var(--surface);
  --el-table-tr-bg-color: var(--surface);
  --el-table-header-bg-color: var(--sidebar);
  --el-table-header-text-color: var(--muted);
  --el-table-text-color: var(--text);
  --el-table-row-hover-bg-color: var(--hover);
  --el-table-current-row-bg-color: var(--active);

  border-radius: 8px;
  background: var(--surface);
}

.dark .dev-layout :deep(.el-table th.el-table__cell) {
  background: var(--sidebar) !important;
  color: var(--muted) !important;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.02em;
}

.dark .dev-layout :deep(.el-table td.el-table__cell) {
  background: var(--surface) !important;
  color: var(--text) !important;
  transition: background-color 0.15s ease;
}

.dark
  .dev-layout
  :deep(
    .el-table--striped
      .el-table__body
      tr.el-table__row--striped
      td.el-table__cell
  ) {
  background: #0d1828 !important;
}

.dark .dev-layout :deep(.el-table__body tr:hover > td.el-table__cell),
.dark .dev-layout :deep(.el-table__body tr.current-row > td.el-table__cell) {
  background: var(--hover) !important;
}

.dark .dev-layout :deep(.el-table__empty-block) {
  background: var(--surface) !important;
}

.dark .dev-layout :deep(.el-table .sort-caret.ascending) {
  border-bottom-color: var(--border-strong);
}

.dark .dev-layout :deep(.el-table .sort-caret.descending) {
  border-top-color: var(--border-strong);
}

.dark .dev-layout :deep(.el-table .ascending .sort-caret.ascending) {
  border-bottom-color: var(--label);
}

.dark .dev-layout :deep(.el-table .descending .sort-caret.descending) {
  border-top-color: var(--label);
}

.dark .dev-layout :deep(.el-loading-mask) {
  background: rgba(11, 20, 34, 0.7);
}

.dev-overview {
  max-width: 1160px;
}

.dev-overview__top {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 16px;
  margin-bottom: 28px;
}

.dev-overview__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 12px;
  margin-bottom: 20px;
}

.dev-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 20px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--surface);
  color: var(--text);
  text-align: left;
  font-family: inherit;
}

.dev-card__title {
  margin: 0 0 8px;
  font-size: 14px;
  font-weight: 600;
}

.dev-card--link {
  gap: 4px;
  padding: 18px 16px;
  cursor: pointer;
  transition:
    border-color 0.15s ease,
    background 0.15s ease;
}

.dev-card--link .dev-card__title {
  margin-bottom: 6px;
}

.dev-card--link:hover {
  border-color: var(--border-strong);
  background: var(--hover);
}

.dev-card__endpoint {
  font-family: var(--mono);
  font-size: 10.5px;
  color: var(--muted);
}

.dev-props {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px 24px;
  margin: 0;
}

.dev-props dt {
  margin-bottom: 6px;
  font-family: var(--mono);
  font-size: 9.5px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--label);
}

.dev-props dd {
  margin: 0;
  font-family: var(--mono);
  font-size: 13px;
  word-break: break-word;
}

/* ---------- Qidiruv oynasi ---------- */

.dev-search {
  position: fixed;
  inset: 0;
  z-index: 2000;
  display: flex;
  justify-content: center;
  align-items: flex-start;
  padding: 12vh 16px 16px;
  background: rgba(3, 8, 16, 0.55);
}

.dev-search__box {
  width: min(560px, 100%);
  overflow: hidden;
  border: 1px solid var(--border-strong);
  border-radius: 8px;
  background: var(--surface);
  box-shadow: 0 24px 60px -20px rgba(0, 0, 0, 0.6);
}

.dev-search__input {
  width: 100%;
  height: 48px;
  padding: 0 16px;
  border: none;
  border-bottom: 1px solid var(--border);
  background: transparent;
  color: var(--text);
  font-family: inherit;
  font-size: 14px;
  outline: none;
}

.dev-search__list {
  max-height: 320px;
  margin: 0;
  padding: 6px;
  overflow-y: auto;
  list-style: none;
}

.dev-search__list li {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 10px;
  border-radius: 6px;
  font-size: 13.5px;
  cursor: pointer;
}

.dev-search__list li.is-active {
  background: var(--active);
}

.dev-search__list code {
  margin-left: auto;
  font-family: var(--mono);
  font-size: 10.5px;
  color: var(--muted);
}

.dev-search__empty {
  margin: 0;
  padding: 16px;
  font-size: 13px;
  color: var(--muted);
}

/* ---------- Animatsiyalar ---------- */

.dev-layout {
  --ease-out: cubic-bezier(0.22, 1, 0.36, 1);
}

/* Mobil ekranda sidebar transform bilan ochiladi — kirish animatsiyasi faqat keng ekranda */
@media (min-width: 900px) {
  .dev-sidebar {
    animation: dev-slide-right 0.5s var(--ease-out) both;
  }
}

.dev-topbar {
  animation: dev-drop 0.45s var(--ease-out) 0.1s both;
}

.dev-sidebar__nav > .dev-sidebar__item,
.dev-sidebar__group {
  animation: dev-fade-right 0.45s var(--ease-out) both;
}
.dev-sidebar__nav > :nth-child(1) {
  animation-delay: 0.15s;
}
.dev-sidebar__nav > :nth-child(2) {
  animation-delay: 0.21s;
}
.dev-sidebar__nav > :nth-child(3) {
  animation-delay: 0.27s;
}
.dev-sidebar__nav > :nth-child(4) {
  animation-delay: 0.33s;
}
.dev-sidebar__nav > :nth-child(5) {
  animation-delay: 0.39s;
}

.dev-sidebar__caret {
  animation: dev-blink 1.1s steps(1) infinite;
}

.dev-sidebar__item .el-icon {
  transition: transform 0.2s var(--ease-out);
}

.dev-sidebar__item:hover .el-icon {
  transform: translateX(2px) scale(1.1);
}

.dev-sidebar__item.is-active .el-icon {
  color: var(--accent);
}

.dev-sidebar__item,
.dev-sidebar__group-items .dev-sidebar__item.is-active {
  transition:
    background 0.15s ease,
    opacity 0.15s ease,
    box-shadow 0.25s var(--ease-out);
}

.dev-sidebar__logout .el-icon {
  transition: transform 0.3s var(--ease-out);
}

.dev-sidebar__logout:hover .el-icon {
  transform: rotate(90deg);
}

/* Umumiy ko'rinish kartalari birin-ketin chiqadi */
.dev-overview__top > .dev-card {
  animation: dev-rise 0.5s var(--ease-out) both;
}
.dev-overview__top > .dev-card:nth-child(2) {
  animation-delay: 0.07s;
}

.dev-overview__label {
  animation: dev-fade 0.4s ease 0.12s both;
}

.dev-card--link {
  animation: dev-rise 0.5s var(--ease-out) both;
  animation-delay: calc(0.14s + var(--i, 0) * 0.05s);
  transition:
    border-color 0.15s ease,
    background 0.15s ease,
    transform 0.2s var(--ease-out),
    box-shadow 0.2s var(--ease-out);
}

.dev-card--link:hover {
  transform: translateY(-3px);
  box-shadow: 0 12px 24px -16px rgba(7, 30, 51, 0.45);
}

.dev-card--link:active {
  transform: translateY(-1px);
}

.dev-card--link .dev-card__endpoint {
  transition: color 0.15s ease;
}

.dev-card--link:hover .dev-card__endpoint {
  color: var(--accent);
}

/* Bo'limlar orasida o'tish */
.dev-tab-enter-active,
.dev-tab-leave-active {
  transition:
    opacity 0.22s ease,
    transform 0.22s var(--ease-out);
}
.dev-tab-enter-from {
  opacity: 0;
  transform: translateY(10px);
}
.dev-tab-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}

.dev-title-enter-active,
.dev-title-leave-active {
  transition:
    opacity 0.18s ease,
    transform 0.18s ease;
}
.dev-title-enter-from {
  opacity: 0;
  transform: translateY(6px);
}
.dev-title-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}

.dev-spin-enter-active,
.dev-spin-leave-active {
  transition:
    opacity 0.2s ease,
    transform 0.2s ease;
}
.dev-spin-enter-from {
  opacity: 0;
  transform: rotate(-90deg) scale(0.6);
}
.dev-spin-leave-to {
  opacity: 0;
  transform: rotate(90deg) scale(0.6);
}

.dev-fade-enter-active,
.dev-fade-leave-active {
  transition: opacity 0.22s ease;
}
.dev-fade-enter-from,
.dev-fade-leave-to {
  opacity: 0;
}

/* Ctrl K oynasi */
.dev-search-enter-active,
.dev-search-leave-active {
  transition: opacity 0.2s ease;
}
.dev-search-enter-active .dev-search__box,
.dev-search-leave-active .dev-search__box {
  transition: transform 0.22s var(--ease-out);
}
.dev-search-enter-from,
.dev-search-leave-to {
  opacity: 0;
}
.dev-search-enter-from .dev-search__box,
.dev-search-leave-to .dev-search__box {
  transform: translateY(-12px) scale(0.97);
}

.dev-search__list li {
  transition: background 0.12s ease;
}

.dev-search__list li .el-icon {
  transition: transform 0.2s var(--ease-out);
}

.dev-search__list li.is-active .el-icon {
  transform: translateX(3px);
  color: var(--accent);
}

/* ---------- Bo'lim ichidagi elementlar ---------- */

/* Metrics, Audit, Query, Logs, Docs, Accounts shu konteyner ichida chiziladi —
   umumiy animatsiyalar bir joyda, har bir komponentda takrorlanmaydi */
.dev-content__body :deep(.el-table__body tr) {
  animation: dev-fade 0.35s ease both;
}
.dev-content__body :deep(.el-table__body tr:nth-child(2)) {
  animation-delay: 0.03s;
}
.dev-content__body :deep(.el-table__body tr:nth-child(3)) {
  animation-delay: 0.06s;
}
.dev-content__body :deep(.el-table__body tr:nth-child(4)) {
  animation-delay: 0.09s;
}
.dev-content__body :deep(.el-table__body tr:nth-child(5)) {
  animation-delay: 0.12s;
}
.dev-content__body :deep(.el-table__body tr:nth-child(6)) {
  animation-delay: 0.15s;
}
.dev-content__body :deep(.el-table__body tr:nth-child(7)) {
  animation-delay: 0.18s;
}
.dev-content__body :deep(.el-table__body tr:nth-child(n + 8)) {
  animation-delay: 0.21s;
}

.dev-content__body :deep(.el-alert) {
  animation: dev-shake 0.45s cubic-bezier(0.36, 0.07, 0.19, 0.97) both;
}

.dev-content__body :deep(.el-progress-bar__inner) {
  transform-origin: left;
  animation: dev-bar 0.9s var(--ease-out) 0.15s both;
}

.dev-content__body :deep(.el-empty) {
  animation: dev-rise 0.4s var(--ease-out) both;
}

.dev-content__body :deep(.el-button:not(.is-link):not(.is-disabled)) {
  transition:
    transform 0.15s var(--ease-out),
    background-color 0.15s ease,
    border-color 0.15s ease,
    color 0.15s ease;
}

.dev-content__body :deep(.el-button:not(.is-link):not(.is-disabled):active) {
  transform: scale(0.96);
}

@keyframes dev-slide-right {
  from {
    opacity: 0;
    transform: translateX(-24px);
  }
}

@keyframes dev-drop {
  from {
    opacity: 0;
    transform: translateY(-10px);
  }
}

@keyframes dev-fade-right {
  from {
    opacity: 0;
    transform: translateX(-10px);
  }
}

@keyframes dev-rise {
  from {
    opacity: 0;
    transform: translateY(12px);
  }
}

@keyframes dev-fade {
  from {
    opacity: 0;
  }
}

@keyframes dev-blink {
  50% {
    opacity: 0;
  }
}

@keyframes dev-bar {
  from {
    transform: scaleX(0);
  }
}

@keyframes dev-shake {
  0% {
    opacity: 0;
  }
  10%,
  90% {
    transform: translateX(-1px);
  }
  20%,
  80% {
    transform: translateX(3px);
  }
  30%,
  50%,
  70% {
    transform: translateX(-5px);
  }
  40%,
  60% {
    transform: translateX(5px);
  }
  100% {
    opacity: 1;
  }
}

/* Harakatni kamaytirishni so'ragan foydalanuvchilar uchun animatsiyalar o'chadi */
@media (prefers-reduced-motion: reduce) {
  .dev-layout *,
  .dev-layout :deep(*),
  .dev-search,
  .dev-search * {
    animation: none !important;
    transition-duration: 0.01ms !important;
  }
}

.dev-topbar__menu,
.dev-topbar__search-icon,
.dev-backdrop {
  display: none;
}

@media (max-width: 899px) {
  .dev-backdrop {
    position: fixed;
    inset: 0;
    z-index: 40;
    display: block;
    background: rgba(3, 8, 16, 0.5);
  }

  .dev-sidebar {
    position: fixed;
    top: 0;
    left: 0;
    z-index: 50;
    width: min(280px, 84vw);
    height: 100dvh;
    box-shadow: 0 0 40px rgba(0, 0, 0, 0.25);
    transform: translateX(-100%);
    visibility: hidden;
    transition:
      transform 0.22s ease,
      visibility 0.22s;
  }

  .dev-sidebar.is-open {
    transform: none;
    visibility: visible;
  }

  .dev-sidebar__item {
    padding: 10px 12px;
  }

  .dev-topbar {
    justify-content: flex-start;
    gap: 8px;
    padding: 8px 12px;
  }

  .dev-topbar__menu {
    display: flex;
    flex-shrink: 0;
  }

  .dev-topbar__title {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .dev-topbar__actions {
    gap: 2px;
  }

  .dev-topbar__search-icon {
    display: inline-flex;
  }

  .dev-topbar__btn span,
  .dev-topbar__btn kbd,
  .dev-topbar__account {
    display: none;
  }

  .dev-topbar__btn:not(.dev-topbar__btn--mono) {
    width: 32px;
    justify-content: center;
    padding: 0;
  }

  .dev-content {
    padding: 12px 12px 32px;
  }

  .dev-content__body {
    padding: 12px;
  }

  .dev-overview__top {
    grid-template-columns: minmax(0, 1fr);
  }

  .dev-overview__grid {
    grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  }
}

@media (max-width: 479px) {
  .dev-props {
    grid-template-columns: minmax(0, 1fr);
  }

  .dev-card {
    padding: 16px;
  }

  .dev-search {
    padding-top: 8vh;
  }

  .dev-search__list code {
    display: none;
  }
}
</style>
