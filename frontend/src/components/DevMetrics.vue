<template>
  <div v-loading="loading && !metrics">
    <div class="mb-4 flex items-center justify-between gap-3">
      <p class="m-0 text-sm text-gray-500 dark:text-gray-400">
        {{ $t("devPanel.metrics.autoRefresh", { seconds: REFRESH_SECONDS }) }}
      </p>
      <el-button :loading="loading" @click="load">{{
        $t("actions.refresh")
      }}</el-button>
    </div>

    <el-alert
      v-if="errorText"
      :title="errorText"
      type="error"
      show-icon
      :closable="false"
      class="mb-4"
    />

    <div v-if="metrics" class="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
      <div
        class="dev-metric rounded-xl border border-gray-200 p-4 dark:border-gray-700"
      >
        <h3 class="m-0 mb-3 flex items-center justify-between gap-2 text-base font-semibold dark:text-white"><span>CPU<DevHint k="cpu" /></span><DevVerdict :level="cpuLevel" /></h3>
        <p class="m-0 mb-3 text-sm text-gray-500 dark:text-gray-400">
          {{ $t("devPanel.metrics.processors") }}:
          <b>{{ metrics.cpu.availableProcessors }}</b>
        </p>
        <div class="mb-1 text-sm dark:text-gray-300">
          {{ $t("devPanel.metrics.processLoad") }}
        </div>
        <el-progress
          :percentage="toPercent(metrics.cpu.processLoad)"
          :color="colorFor"
          class="mb-3"
        />
        <div class="mb-1 text-sm dark:text-gray-300">
          {{ $t("devPanel.metrics.systemLoad") }}
        </div>
        <el-progress
          :percentage="toPercent(metrics.cpu.systemLoad)"
          :color="colorFor"
        />
        <p
          v-if="metrics.cpu.systemLoadAverage >= 0"
          class="m-0 mt-3 text-sm text-gray-500 dark:text-gray-400"
        >
          {{ $t("devPanel.metrics.loadAverage") }}:
          <b>{{ metrics.cpu.systemLoadAverage.toFixed(2) }}</b>
        </p>
      </div>

      <div
        class="dev-metric rounded-xl border border-gray-200 p-4 dark:border-gray-700"
      >
        <h3 class="m-0 mb-3 flex items-center justify-between gap-2 text-base font-semibold dark:text-white">
          <span>{{ $t("devPanel.metrics.memory") }}<DevHint k="heap" /></span>
          <DevVerdict :level="heapLevel" />
        </h3>
        <div class="mb-1 flex justify-between text-sm dark:text-gray-300">
          <span>{{ $t("devPanel.metrics.heap") }}</span>
          <span
            >{{ formatBytes(metrics.memory.heapUsed) }} /
            {{ formatBytes(metrics.memory.heapMax) }}</span
          >
        </div>
        <el-progress
          :percentage="
            usagePercent(metrics.memory.heapUsed, metrics.memory.heapMax)
          "
          :color="colorFor"
          class="mb-3"
        />
        <div class="mb-1 flex justify-between text-sm dark:text-gray-300">
          <span>{{ $t("devPanel.metrics.systemMemory") }}</span>
          <span
            >{{ formatBytes(systemUsed) }} /
            {{ formatBytes(metrics.memory.systemTotal) }}</span
          >
        </div>
        <el-progress
          :percentage="usagePercent(systemUsed, metrics.memory.systemTotal)"
          :color="colorFor"
        />
        <p class="m-0 mt-3 text-sm text-gray-500 dark:text-gray-400">
          {{ $t("devPanel.metrics.nonHeap") }}:
          <b>{{ formatBytes(metrics.memory.nonHeapUsed) }}</b>
        </p>
      </div>

      <div
        class="dev-metric rounded-xl border border-gray-200 p-4 dark:border-gray-700"
      >
        <h3 class="m-0 mb-3 flex items-center justify-between gap-2 text-base font-semibold dark:text-white">
          <span>{{ $t("devPanel.metrics.disks") }}<DevHint k="disk" /></span>
          <DevVerdict :level="diskLevel" />
        </h3>
        <div
          v-for="disk in metrics.disks"
          :key="disk.path"
          class="mb-3 last:mb-0"
        >
          <div
            class="mb-1 flex justify-between gap-2 text-sm dark:text-gray-300"
          >
            <span class="truncate font-mono">{{ disk.path }}</span>
            <span class="shrink-0">
              {{ formatBytes(disk.totalSpace - disk.usableSpace) }} /
              {{ formatBytes(disk.totalSpace) }}
            </span>
          </div>
          <el-progress
            :percentage="
              usagePercent(disk.totalSpace - disk.usableSpace, disk.totalSpace)
            "
            :color="colorFor"
          />
        </div>
        <el-empty v-if="!metrics.disks?.length" :image-size="60" />
      </div>

      <!-- Hikari: pending > 0 — so'rovlar bazaga ulanish kutib turibdi, pul yetishmayapti -->
      <div
        class="dev-metric rounded-xl border border-gray-200 p-4 dark:border-gray-700 md:col-span-2"
      >
        <h3 class="m-0 mb-3 flex items-center justify-between gap-2 text-base font-semibold dark:text-white">
          <span>{{ $t("devPanel.metrics.hikari") }}<DevHint k="pool" /></span>
          <DevVerdict :level="poolLevel" />
        </h3>
        <div
          v-for="pool in metrics.hikari"
          :key="pool.pool"
          class="mb-4 last:mb-0"
        >
          <div class="mb-1 flex justify-between gap-2 text-sm dark:text-gray-300">
            <span class="truncate font-mono">{{ pool.pool }}</span>
            <span class="shrink-0">{{ pool.active }} / {{ pool.max }}</span>
          </div>
          <el-progress
            :percentage="usagePercent(pool.active, pool.max)"
            :color="colorFor"
            class="mb-3"
          />
          <div class="dev-stats">
            <div>
              <span>{{ $t("devPanel.metrics.active") }}</span
              ><b>{{ pool.active }}</b>
            </div>
            <div>
              <span>{{ $t("devPanel.metrics.idle") }}</span
              ><b>{{ pool.idle }}</b>
            </div>
            <div :class="{ 'is-danger': pool.pending > 0 }">
              <span>{{ $t("devPanel.metrics.pending") }}<DevHint k="pending" /></span
              ><b>{{ pool.pending }}</b>
            </div>
            <div>
              <span>{{ $t("devPanel.metrics.poolMax") }}</span
              ><b>{{ pool.max }}</b>
            </div>
            <div>
              <span>{{ $t("devPanel.metrics.poolTimeout") }}</span
              ><b>{{ pool.connectionTimeoutMs / 1000 }} s</b>
            </div>
          </div>
        </div>
        <el-empty
          v-if="!metrics.hikari?.length"
          :image-size="60"
          :description="$t('devPanel.metrics.noPool')"
        />
      </div>

      <div
        v-if="tomcat"
        class="dev-metric rounded-xl border border-gray-200 p-4 dark:border-gray-700"
      >
        <h3 class="m-0 mb-3 flex items-center justify-between gap-2 text-base font-semibold dark:text-white">
          <span>{{ $t("devPanel.metrics.capacity") }}<DevHint k="tomcat" /></span>
          <DevVerdict :level="tomcatLevel" />
        </h3>
        <div class="mb-1 flex justify-between text-sm dark:text-gray-300">
          <span>{{ $t("devPanel.metrics.requestThreads") }}</span>
          <span>{{ tomcat.busyThreads }} / {{ tomcat.maxThreads }}</span>
        </div>
        <el-progress
          :percentage="usagePercent(tomcat.busyThreads, tomcat.maxThreads)"
          :color="colorFor"
          class="mb-3"
        />
        <div class="dev-stats">
          <div :class="{ 'is-danger': tomcat.queued > 0 }">
            <span>{{ $t("devPanel.metrics.queued") }}</span><b>{{ tomcat.queued }}</b>
          </div>
          <div>
            <span>{{ $t("devPanel.metrics.connections") }}</span
            ><b>{{ tomcat.connections }} / {{ tomcat.maxConnections }}</b>
          </div>
        </div>
      </div>

      <div
        class="dev-metric rounded-xl border border-gray-200 p-4 dark:border-gray-700"
      >
        <h3 class="m-0 mb-3 flex items-center justify-between gap-2 text-base font-semibold dark:text-white">
          <span>{{ $t("devPanel.metrics.threads") }}<DevHint k="threadCount" /></span>
          <DevVerdict :level="metrics.threads.deadlocked > 0 ? 'bad' : 'ok'" />
        </h3>
        <div class="dev-stats">
          <div>
            <span>{{ $t("devPanel.metrics.threads") }}</span
            ><b>{{ metrics.threads.live }}</b>
          </div>
          <div>
            <span>{{ $t("devPanel.metrics.daemon") }}</span
            ><b>{{ metrics.threads.daemon }}</b>
          </div>
          <div>
            <span>{{ $t("devPanel.metrics.peak") }}</span
            ><b>{{ metrics.threads.peak }}</b>
          </div>
          <div :class="{ 'is-danger': metrics.threads.deadlocked > 0 }">
            <span>{{ $t("devPanel.metrics.deadlocked") }}</span
            ><b>{{ metrics.threads.deadlocked }}</b>
          </div>
        </div>
      </div>
    </div>

    <section
      class="mt-4 rounded-xl border border-gray-200 p-4 dark:border-gray-700"
    >
      <div class="mb-4 flex flex-wrap items-center justify-between gap-3">
        <div>
          <h3 class="m-0 text-base font-semibold dark:text-white">
            {{ $t("devPanel.metrics.history") }}
          </h3>
          <p
            v-if="history"
            class="m-0 mt-1 text-xs text-gray-500 dark:text-gray-400"
          >
            {{
              $t("devPanel.metrics.historyHint", {
                seconds: history.sampleSeconds,
              })
            }}
          </p>
        </div>
        <el-radio-group v-model="rangeMinutes" size="small">
          <el-radio-button
            v-for="range in RANGES"
            :key="range.minutes"
            :value="range.minutes"
            >{{ range.label }}</el-radio-button
          >
        </el-radio-group>
      </div>
      <div class="grid gap-6 lg:grid-cols-2">
        <DevLineChart
          :title="$t('devPanel.metrics.cpuChart')"
          :times="times"
          :max="100"
          :format="(v) => `${v.toFixed(1)}%`"
          :series="[
            {
              name: $t('devPanel.metrics.process'),
              color: '#106297',
              values: points.map((p) => toPercent(p.cpuProcess)),
            },
            {
              name: $t('devPanel.metrics.system'),
              color: '#e6a23c',
              values: points.map((p) => toPercent(p.cpuSystem)),
            },
          ]"
        />
        <DevLineChart
          :title="$t('devPanel.metrics.heapChart')"
          :times="times"
          :max="heapMax || undefined"
          :format="formatBytes"
          :series="[
            {
              name: $t('devPanel.metrics.used'),
              color: '#7c5cff',
              values: points.map((p) => p.heapUsed),
            },
          ]"
        />
        <DevLineChart
          :title="$t('devPanel.metrics.dbChart')"
          :times="times"
          :max="dbMax || undefined"
          :series="[
            {
              name: $t('devPanel.metrics.active'),
              color: '#106297',
              values: points.map((p) => p.dbActive),
            },
            {
              name: $t('devPanel.metrics.idle'),
              color: '#3ec972',
              values: points.map((p) => p.dbIdle),
            },
            {
              name: $t('devPanel.metrics.pending'),
              color: '#d14343',
              values: points.map((p) => p.dbPending),
            },
          ]"
        />
        <DevLineChart
          :title="$t('devPanel.metrics.threads')"
          :times="times"
          :series="[
            {
              name: $t('devPanel.metrics.threads'),
              color: '#0f9d8a',
              values: points.map((p) => p.threads),
            },
          ]"
        />
        <DevLineChart
          :title="$t('devPanel.metrics.resourcesChart')"
          :times="times"
          :series="[
            {
              name: $t('devPanel.metrics.classes'),
              color: '#7c5cff',
              values: points.map((p) => p.loadedClasses),
            },
            {
              name: $t('devPanel.metrics.openFiles'),
              color: '#e6a23c',
              values: points.map((p) => (p.openFiles >= 0 ? p.openFiles : null)),
            },
          ]"
        />
        <DevLineChart
          :title="$t('devPanel.metrics.diskChart')"
          :times="times"
          :max="100"
          :format="(v) => `${v.toFixed(1)}%`"
          :series="[
            {
              name: $t('devPanel.metrics.used'),
              color: '#e6a23c',
              values: points.map((p) => toPercent(p.diskUsedRatio)),
            },
          ]"
        />
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useDevApi } from "../dev.api";
import { IDevMetrics, IDevMetricsHistory, IDevTomcat } from "../dev.types";
import DevLineChart from "./DevLineChart.vue";
import DevHint from "./DevHint.vue";
import DevVerdict from "./DevVerdict.vue";
import {
  devErrorMessage,
  formatBytes,
  toPercent,
  usagePercent,
} from "../dev.utils";

const REFRESH_SECONDS = 15;

const RANGES = [
  { minutes: 15, label: "15m" },
  { minutes: 60, label: "1h" },
  { minutes: 360, label: "6h" },
  { minutes: 1440, label: "24h" },
];

const { GET_METRICS, GET_METRICS_HISTORY, GET_TOMCAT } = useDevApi();
const tomcat = ref<IDevTomcat | null>(null);

const metrics = ref<IDevMetrics | null>(null);
const history = ref<IDevMetricsHistory | null>(null);
const rangeMinutes = ref(60);

const points = computed(() => history.value?.points ?? []);
const times = computed(() => points.value.map((p) => p.time));
const heapMax = computed(() =>
  Math.max(0, ...points.value.map((p) => p.heapMax)),
);
const dbMax = computed(() => Math.max(0, ...points.value.map((p) => p.dbMax)));
const loading = ref(false);
const errorText = ref("");
let timer: ReturnType<typeof setInterval> | null = null;

const systemUsed = computed(() =>
  metrics.value
    ? metrics.value.memory.systemTotal - metrics.value.memory.systemFree
    : 0,
);

// Kartochka xulosasi: 75% dan sariq, 90% dan qizil (pool: kutayotgan bo'lsa qizil)
type TLevel = "ok" | "warn" | "bad" | "unknown";
const level = (share: number): TLevel =>
  share < 0 ? "unknown" : share >= 0.9 ? "bad" : share >= 0.75 ? "warn" : "ok";
const cpuLevel = computed<TLevel>(() =>
  metrics.value ? level(Math.max(metrics.value.cpu.processLoad, metrics.value.cpu.systemLoad * 0.95))
    : "unknown",
);
const heapLevel = computed<TLevel>(() =>
  metrics.value && metrics.value.memory.heapMax > 0
    ? level(metrics.value.memory.heapUsed / metrics.value.memory.heapMax * 0.9) : "unknown",
);
const diskLevel = computed<TLevel>(() =>
  metrics.value?.disks?.length
    ? level(Math.max(...metrics.value.disks.map((d) =>
      d.totalSpace > 0 ? (d.totalSpace - d.usableSpace) / d.totalSpace : 0)))
    : "unknown",
);
const poolLevel = computed<TLevel>(() => {
  const pools = metrics.value?.hikari ?? [];
  if (!pools.length) return "unknown";
  if (pools.some((p) => p.pending > 0)) return "bad";
  return level(Math.max(...pools.map((p) => (p.max ? p.active / p.max : 0))));
});
const tomcatLevel = computed<TLevel>(() =>
  !tomcat.value || tomcat.value.maxThreads <= 0 ? "unknown"
    : tomcat.value.queued > 0 ? "bad" : level(tomcat.value.busyThreads / tomcat.value.maxThreads),
);

const colorFor = (percentage: number) =>
  percentage >= 90 ? "#d14343" : percentage >= 70 ? "#e6a23c" : "#106297";

const load = async () => {
  loading.value = true;
  try {
    const [current, past] = await Promise.all([
      GET_METRICS(),
      GET_METRICS_HISTORY(rangeMinutes.value),
    ]);
    metrics.value = current.data;
    history.value = past.data;
    try {
      tomcat.value = (await GET_TOMCAT()).data.state ?? null;
    } catch {
      tomcat.value = null;
    }
    errorText.value = "";
  } catch (error) {
    errorText.value = await devErrorMessage(error);
  } finally {
    loading.value = false;
  }
};

watch(rangeMinutes, load);

onMounted(() => {
  load();
  timer = setInterval(load, REFRESH_SECONDS * 1000);
});

onBeforeUnmount(() => {
  if (timer) clearInterval(timer);
});
</script>

<style scoped>
/* Kartalar birin-ketin chiqadi, ustiga kelganda biroz ko'tariladi */
.dev-metric {
  animation: dev-metric-in 0.5s cubic-bezier(0.22, 1, 0.36, 1) both;
  transition:
    transform 0.2s cubic-bezier(0.22, 1, 0.36, 1),
    box-shadow 0.2s ease;
}

.dev-metric:nth-child(2) {
  animation-delay: 0.08s;
}

.dev-metric:nth-child(3) {
  animation-delay: 0.16s;
}

.dev-metric:hover {
  transform: translateY(-3px);
  box-shadow: 0 14px 28px -18px rgba(7, 30, 51, 0.45);
}

.dev-stats {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(130px, 1fr));
  gap: 8px;
}

.dev-stats > div {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 10px;
  border-radius: 8px;
  background: var(--el-fill-color-light);
}

.dev-stats span {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.dev-stats b {
  font-size: 18px;
  font-variant-numeric: tabular-nums;
  color: var(--el-text-color-primary);
}

.dev-stats > .is-danger b {
  color: var(--el-color-danger);
}

@keyframes dev-metric-in {
  from {
    opacity: 0;
    transform: translateY(14px);
  }
}

@media (prefers-reduced-motion: reduce) {
  .dev-metric {
    animation: none;
    transition: none;
  }
}
</style>
