<template>
  <div v-loading="loading && !health">
    <el-alert
      v-if="errorText"
      :title="errorText"
      type="error"
      show-icon
      :closable="false"
      class="mb-4"
    />

    <template v-if="health">
      <!-- Umumiy holat: bir qarashda -->
      <section class="health-banner" :class="`is-${overall.toLowerCase()}`">
        <span class="health-banner__dot" />
        <div class="min-w-0 flex-1">
          <h2 class="m-0 text-lg font-semibold">
            {{
              problems.length
                ? $t("devPanel.health.problems", { count: problems.length })
                : $t("devPanel.health.allGood")
            }}
          </h2>
          <p class="m-0 mt-1 text-sm opacity-80">
            {{ health.deploy.application }} · {{ health.deploy.host }} ·
            {{ $t("devPanel.health.upSince") }}
            {{ formatTime(health.deploy.startedAt) }}
            <template v-if="health.deploy.version">
              · v{{ health.deploy.version }}</template
            >
            <template v-if="health.deploy.commit">
              · {{ health.deploy.commit }}</template
            >
          </p>
        </div>
        <el-button :loading="loading" @click="load">{{
          $t("actions.refresh")
        }}</el-button>
      </section>

      <!-- Muammolar birinchi -->
      <div v-if="problems.length" class="mt-4 grid gap-3">
        <button
          v-for="check in problems"
          :key="check.key"
          type="button"
          class="health-problem"
          :class="`is-${check.status.toLowerCase()}`"
          @click="check.tab && emit('open', check.tab)"
        >
          <el-tag :type="statusTag(check.status)" effect="dark" size="small">{{
            check.status
          }}</el-tag>
          <div class="min-w-0 flex-1 text-left">
            <b class="block">{{ check.title }}</b>
            <span class="text-sm opacity-80">{{ check.detail }}</span>
          </div>
          <el-icon v-if="check.tab" class="shrink-0"><ArrowRight /></el-icon>
        </button>
      </div>

      <!-- Shu deploy nimani o'zgartirdi -->
      <section
        v-if="report && !report.noHistory && report.minutesRunning >= 30"
        class="mt-4 rounded-xl border border-gray-200 p-4 dark:border-gray-700"
      >
        <h3 class="m-0 mb-1 text-base font-semibold dark:text-white">
          {{ $t("devPanel.deployReport.title", { minutes: report.minutesRunning }) }}
        </h3>
        <p class="m-0 mb-3 text-sm text-gray-500">
          {{ $t("devPanel.deployReport.errors", {
            before: errorRate(report.errorsBefore, report.callsBefore),
            now: errorRate(report.errorsNow, report.callsNow),
          }) }}
          · {{ $t("devPanel.deployReport.unchanged", { count: report.unchanged }) }}
        </p>
        <div class="grid gap-4 md:grid-cols-3">
          <div>
            <b class="text-sm text-green-600">✅ {{ $t("devPanel.deployReport.faster") }} ({{ report.faster.length }})</b>
            <div v-for="c in report.faster.slice(0, 6)" :key="c.endpoint" class="text-xs">
              <code>{{ c.endpoint }}</code> {{ formatMs(c.p95BeforeMs) }} → <b>{{ formatMs(c.p95NowMs) }}</b>
            </div>
          </div>
          <div>
            <b class="text-sm text-orange-500">⚠️ {{ $t("devPanel.deployReport.slower") }} ({{ report.slower.length }})</b>
            <div v-for="c in report.slower.slice(0, 6)" :key="c.endpoint" class="text-xs">
              <code>{{ c.endpoint }}</code> {{ formatMs(c.p95BeforeMs) }} → <b>{{ formatMs(c.p95NowMs) }}</b>
            </div>
          </div>
          <div>
            <b class="text-sm text-red-500">🆕 {{ $t("devPanel.deployReport.newErrors") }} ({{ report.newErrors.length }})</b>
            <div v-for="e in report.newErrors.slice(0, 6)" :key="e.fingerprint" class="text-xs">
              <b>{{ e.type.substring(e.type.lastIndexOf(".") + 1) }}</b> ×{{ e.count }}
            </div>
          </div>
        </div>
      </section>

      <!-- Hamma tekshiruvlar, soha bo'yicha -->
      <div class="mt-4 grid gap-3 md:grid-cols-2 xl:grid-cols-3">
        <section
          v-for="area in areas"
          :key="area.name"
          class="rounded-xl border border-gray-200 p-4 dark:border-gray-700"
        >
          <h3
            class="m-0 mb-2 flex items-center gap-2 text-sm font-semibold uppercase tracking-wide text-gray-500"
          >
            <span
              class="health-dot"
              :style="{ background: STATUS_COLORS[area.worst] }"
            />
            {{ $t(`devPanel.health.areas.${area.name}`) }}
          </h3>
          <div
            v-for="check in area.checks"
            :key="check.key"
            class="health-line"
            :class="{ 'cursor-pointer': check.tab }"
            @click="check.tab && emit('open', check.tab)"
          >
            <span
              class="health-dot mt-1.5"
              :style="{ background: STATUS_COLORS[check.status] }"
            />
            <div class="min-w-0">
              <div class="text-sm dark:text-gray-200">{{ check.title }}</div>
              <div
                v-if="check.detail"
                class="text-xs text-gray-500 dark:text-gray-400"
              >
                {{ check.detail }}
              </div>
            </div>
          </div>
        </section>
      </div>

      <section
        class="mt-4 rounded-xl border border-gray-200 p-4 dark:border-gray-700"
      >
        <div class="grid gap-6 lg:grid-cols-2">
          <DevLineChart
            :title="$t('devPanel.health.requestsPerMinute')"
            :times="rateTimes"
            :series="[
              {
                name: $t('devPanel.health.requests'),
                color: '#106297',
                values: rate?.requests ?? [],
              },
            ]"
          />
          <DevLineChart
            :title="$t('devPanel.health.errorsPerMinute')"
            :times="rateTimes"
            :series="[
              {
                name: '5xx',
                color: '#d14343',
                values: rate?.serverErrors ?? [],
              },
              {
                name: $t('devPanel.health.loggedErrors'),
                color: '#e6a23c',
                values: rate?.loggedErrors ?? [],
              },
            ]"
          />
        </div>
        <p class="m-0 mt-3 text-xs text-gray-500">
          {{ $t("devPanel.health.clock") }}:
          <template v-if="health.clock.offsetMs != null">
            {{ clockText }}
          </template>
          <template v-else>{{ $t("devPanel.health.clockUnknown") }}</template>
          · {{ $t("devPanel.health.browserDiff") }}: {{ browserDiff }}
        </p>
      </section>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import moment from "moment";
import { ArrowRight } from "@element-plus/icons-vue";
import { useDevApi } from "../dev.api";
import {
  IDevCheck,
  IDevDeployReport,
  IDevHealth,
  IDevRate,
  TDevCheckStatus,
} from "../dev.types";
import {
  STATUS_COLORS,
  devErrorMessage,
  formatMs,
  formatTime,
  statusTag,
  useAutoRefresh,
} from "../dev.utils";
import DevLineChart from "./DevLineChart.vue";

const emit = defineEmits<{ (e: "open", tab: string): void }>();

const AREA_ORDER = [
  "server",
  "memory",
  "threads",
  "disk",
  "database",
  "api",
  "errors",
  "external",
  "security",
  "jobs",
  "clock",
];
const RANK: Record<TDevCheckStatus, number> = {
  UNKNOWN: 0,
  OK: 1,
  WARNING: 2,
  CRITICAL: 3,
};

const { GET_HEALTH, GET_RATE, GET_DEPLOY_REPORT } = useDevApi();
const report = ref<IDevDeployReport | null>(null);
const errorRate = (errors: number, calls: number) =>
  calls ? `${((errors / calls) * 100).toFixed(2)}%` : "—";
const health = ref<IDevHealth | null>(null);
const rate = ref<IDevRate | null>(null);
const loading = ref(false);
const errorText = ref("");
const loadedAt = ref(Date.now());

const overall = computed(() => health.value?.report.overall ?? "UNKNOWN");
const problems = computed(() =>
  (health.value?.report.checks ?? [])
    .filter((c) => c.status === "WARNING" || c.status === "CRITICAL")
    .sort((a, b) => RANK[b.status] - RANK[a.status]),
);

const areas = computed(() => {
  const groups = new Map<string, IDevCheck[]>();
  for (const check of health.value?.report.checks ?? []) {
    if (!groups.has(check.area)) groups.set(check.area, []);
    groups.get(check.area).push(check);
  }
  return [...groups.entries()]
    .sort(
      ([a], [b]) =>
        (AREA_ORDER.indexOf(a) + 100) % 100 - (AREA_ORDER.indexOf(b) + 100) % 100,
    )
    .map(([name, checks]) => ({
      name,
      checks,
      worst: checks.reduce<TDevCheckStatus>(
        (worst, c) => (RANK[c.status] > RANK[worst] ? c.status : worst),
        "UNKNOWN",
      ),
    }));
});

const rateTimes = computed(() => {
  if (!rate.value) return [];
  const end = moment(rate.value.end);
  const count = rate.value.requests.length;
  return rate.value.requests.map((_, i) =>
    end.clone().subtract(count - 1 - i, "minutes").toISOString(),
  );
});

const clockText = computed(() => {
  const offset = health.value?.clock.offsetMs ?? 0;
  if (Math.abs(offset) < 2000) return "OK";
  return `${formatMs(Math.abs(offset))} ${offset > 0 ? "ahead" : "behind"}`;
});

// Server vaqti va brauzer vaqti farqi (tarmoq kechikishi ham kiradi, ~1 s aniqlik)
const browserDiff = computed(() => {
  if (!health.value) return "—";
  const diff = Date.parse(health.value.report.serverTime) - loadedAt.value;
  return Math.abs(diff) < 2000
    ? "< 2 s"
    : `${formatMs(Math.abs(diff))} ${diff > 0 ? "ahead" : "behind"}`;
});

const load = async () => {
  loading.value = true;
  try {
    const [h, r] = await Promise.all([GET_HEALTH(), GET_RATE(60)]);
    loadedAt.value = Date.now();
    health.value = h.data;
    rate.value = r.data;
    try {
      report.value = (await GET_DEPLOY_REPORT()).data;
    } catch {
      report.value = null;
    }
    errorText.value = "";
  } catch (error) {
    errorText.value = await devErrorMessage(error);
  } finally {
    loading.value = false;
  }
};

const refresh = useAutoRefresh(load, 30);
onMounted(() => {
  load();
  refresh.start();
});
onBeforeUnmount(refresh.stop);
</script>

<style scoped>
.health-banner {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 16px 18px;
  border-radius: 14px;
  border: 1px solid var(--el-border-color);
  background: var(--el-fill-color-lighter);
}

.health-banner.is-ok {
  border-color: rgba(62, 201, 114, 0.5);
  background: rgba(62, 201, 114, 0.08);
}

.health-banner.is-warning {
  border-color: rgba(230, 162, 60, 0.6);
  background: rgba(230, 162, 60, 0.1);
}

.health-banner.is-critical {
  border-color: rgba(209, 67, 67, 0.6);
  background: rgba(209, 67, 67, 0.1);
}

.health-banner__dot {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  flex-shrink: 0;
  background: #909399;
}

.is-ok .health-banner__dot {
  background: #3ec972;
}

.is-warning .health-banner__dot {
  background: #e6a23c;
  animation: pulse 2s infinite;
}

.is-critical .health-banner__dot {
  background: #d14343;
  animation: pulse 1s infinite;
}

.health-problem {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 12px 14px;
  border-radius: 10px;
  border: 1px solid var(--el-border-color);
  border-left-width: 4px;
  background: var(--el-bg-color);
  color: var(--el-text-color-primary);
  cursor: pointer;
}

.health-problem.is-warning {
  border-left-color: #e6a23c;
}

.health-problem.is-critical {
  border-left-color: #d14343;
}

.health-line {
  display: flex;
  gap: 8px;
  padding: 6px 0;
  border-top: 1px dashed var(--el-border-color-lighter);
}

.health-line:first-of-type {
  border-top: none;
}

.health-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}

@keyframes pulse {
  50% {
    opacity: 0.35;
  }
}
</style>
