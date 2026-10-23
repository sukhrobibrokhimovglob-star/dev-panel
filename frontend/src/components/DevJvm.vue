<template>
  <div v-loading="loading && !jvm">
    <div class="mb-4 flex items-center justify-end gap-3">
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

    <!-- Xotira oqishi: GC'dan keyin qolgan xotira ("pol") o'sib borayaptimi -->
    <section
      v-if="memory"
      class="mb-4 rounded-xl border p-4"
      :class="
        memory.analysis.leakSuspected
          ? 'border-red-400 bg-red-50 dark:bg-red-950/20'
          : 'border-gray-200 dark:border-gray-700'
      "
    >
      <div class="mb-3 flex flex-wrap items-start justify-between gap-3">
        <div>
          <h3 class="m-0 text-base font-semibold dark:text-white">
            {{ $t("devPanel.memory.title") }}
          </h3>
          <p class="m-0 mt-1 text-sm" :class="memory.analysis.leakSuspected ? 'text-red-600' : 'text-gray-500'">
            {{ verdict }}
          </p>
        </div>
        <el-button
          v-if="store.canWrite && store.account?.role !== 'USER'"
          :loading="histogramLoading"
          @click="loadHistogram"
          >{{ $t("devPanel.memory.histogram") }}</el-button
        >
      </div>
      <div class="dev-mini-stats mb-3">
        <div>
          <span>{{ $t("devPanel.memory.floor") }}</span
          ><b>{{ formatBytes(lastFloor) }}</b>
        </div>
        <div>
          <span>{{ $t("devPanel.memory.growth") }}</span
          ><b>{{ growthText }}</b>
        </div>
        <div :class="{ 'is-bad': memory.analysis.gcShare >= 0.1 }">
          <span>{{ $t("devPanel.memory.gcShare") }}</span
          ><b>{{ (memory.analysis.gcShare * 100).toFixed(1) }}%</b>
        </div>
        <div>
          <span>{{ $t("devPanel.memory.maxPause") }}</span
          ><b>{{ memory.analysis.maxPauseMs }} ms</b>
        </div>
        <div>
          <span>{{ $t("devPanel.memory.collections") }}</span
          ><b>{{ memory.analysis.collectionsLast5Min }}</b>
        </div>
      </div>
      <DevLineChart
        :title="$t('devPanel.memory.floorChart')"
        :times="memory.analysis.floor.map((p) => new Date(p.time).toISOString())"
        :max="memory.analysis.heapMax || undefined"
        :format="formatBytes"
        :series="[
          {
            name: $t('devPanel.memory.floor'),
            color: '#d14343',
            values: memory.analysis.floor.map((p) => p.bytes),
          },
        ]"
      />
      <el-table v-if="histogram.length" :data="histogram" size="small" class="mt-4" max-height="420">
        <el-table-column prop="rank" label="#" width="50" />
        <el-table-column :label="$t('devPanel.memory.class')" min-width="320">
          <template #default="{ row }"><code class="text-xs">{{ row.className }}</code></template>
        </el-table-column>
        <el-table-column prop="instances" :label="$t('devPanel.memory.instances')" width="120" align="right" />
        <el-table-column :label="$t('devPanel.memory.size')" width="110" align="right">
          <template #default="{ row }"><b>{{ formatBytes(row.bytes) }}</b></template>
        </el-table-column>
      </el-table>
    </section>

    <div v-if="jvm" class="grid gap-4 xl:grid-cols-2">
      <section
        class="rounded-xl border border-gray-200 p-4 dark:border-gray-700"
      >
        <h3 class="m-0 mb-3 text-base font-semibold dark:text-white">
          {{ $t("devPanel.jvm.runtime") }}
        </h3>
        <dl class="dev-dl">
          <dt>PID</dt>
          <dd>{{ jvm.pid }}</dd>
          <dt>Java</dt>
          <dd>{{ jvm.javaVersion }} · {{ jvm.vmName }}</dd>
          <dt>{{ $t("devPanel.jvm.started") }}</dt>
          <dd>{{ formatDateTime(jvm.startTime) }}</dd>
          <dt>{{ $t("devPanel.jvm.uptime") }}</dt>
          <dd>{{ formatDuration(jvm.uptimeMs) }}</dd>
          <dt>{{ $t("devPanel.jvm.classes") }}</dt>
          <dd>{{ jvm.loadedClasses }}</dd>
        </dl>
        <details v-if="jvm.inputArguments.length" class="mt-3">
          <summary class="cursor-pointer text-sm dark:text-gray-300">
            {{ $t("devPanel.jvm.arguments") }} ({{
              jvm.inputArguments.length
            }})
          </summary>
          <pre class="dev-pre mt-2">{{ jvm.inputArguments.join("\n") }}</pre>
        </details>
      </section>

      <section
        class="rounded-xl border border-gray-200 p-4 dark:border-gray-700"
      >
        <h3 class="m-0 mb-3 text-base font-semibold dark:text-white">
          {{ $t("devPanel.jvm.gc") }}
        </h3>
        <el-table :data="jvm.gc" size="small">
          <el-table-column prop="name" :label="$t('devPanel.jvm.gc')" />
          <el-table-column
            prop="count"
            :label="$t('devPanel.jvm.collections')"
            align="right"
            width="120"
          />
          <el-table-column
            :label="$t('devPanel.jvm.gcTime')"
            align="right"
            width="120"
          >
            <template #default="{ row }">{{
              formatDuration(row.timeMs)
            }}</template>
          </el-table-column>
        </el-table>

        <h3 class="m-0 mb-3 mt-5 text-base font-semibold dark:text-white">
          {{ $t("devPanel.jvm.threadStates") }}
        </h3>
        <div class="flex flex-wrap gap-2">
          <el-tag
            v-for="(count, state) in jvm.threadStates"
            :key="state"
            :type="state === 'BLOCKED' && count > 0 ? 'danger' : 'info'"
            effect="plain"
            class="cursor-pointer"
            @click="filter = String(state)"
            >{{ state }}: {{ count }}</el-tag
          >
        </div>
      </section>

      <section
        class="rounded-xl border border-gray-200 p-4 dark:border-gray-700 xl:col-span-2"
      >
        <h3 class="m-0 mb-3 text-base font-semibold dark:text-white">
          {{ $t("devPanel.jvm.memoryPools") }}
        </h3>
        <div class="grid gap-x-6 gap-y-3 md:grid-cols-2">
          <div v-for="pool in jvm.memoryPools" :key="pool.name">
            <div
              class="mb-1 flex justify-between gap-2 text-sm dark:text-gray-300"
            >
              <span class="truncate"
                >{{ pool.name }}
                <small class="text-gray-400">{{ pool.type }}</small></span
              >
              <span class="shrink-0"
                >{{ formatBytes(pool.used) }}
                <template v-if="pool.max > 0">
                  / {{ formatBytes(pool.max) }}</template
                ></span
              >
            </div>
            <el-progress
              v-if="pool.max > 0"
              :percentage="usagePercent(pool.used, pool.max)"
              :show-text="false"
            />
          </div>
        </div>
      </section>
    </div>

    <section
      class="mt-4 rounded-xl border border-gray-200 p-4 dark:border-gray-700"
    >
      <div class="mb-3 flex flex-wrap items-center justify-between gap-3">
        <h3 class="m-0 text-base font-semibold dark:text-white">
          {{ $t("devPanel.jvm.threadDump") }}
          <small v-if="threads.length" class="font-normal text-gray-400">{{
            $t("devPanel.jvm.threadCount", { count: visibleThreads.length })
          }}</small>
        </h3>
        <div class="flex flex-wrap gap-2">
          <el-input
            v-if="threads.length"
            v-model="filter"
            clearable
            :placeholder="$t('devPanel.jvm.filter')"
            style="width: 280px"
          />
          <el-button type="primary" :loading="dumping" @click="takeDump">{{
            $t("devPanel.jvm.takeDump")
          }}</el-button>
          <el-button v-if="dump" @click="download">{{
            $t("actions.download")
          }}</el-button>
        </div>
      </div>
      <pre v-if="threads.length" class="dev-pre dev-pre--dump">{{
        visibleThreads.join("\n")
      }}</pre>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import moment from "moment";
import { useDevApi } from "../dev.api";
import { IDevHistogramRow, IDevJvm, IDevMemory } from "../dev.types";
import { useDevStore } from "../dev.store";
import { ElMessage, ElMessageBox } from "element-plus";
import { useI18n } from "vue-i18n";
import DevLineChart from "./DevLineChart.vue";
import {
  devErrorMessage,
  formatBytes,
  formatDateTime,
  usagePercent,
} from "../dev.utils";

const { GET_JVM, GET_THREAD_DUMP, GET_MEMORY, GET_HISTOGRAM } = useDevApi();
const { t } = useI18n();
const store = useDevStore();
const memory = ref<IDevMemory | null>(null);
const histogram = ref<IDevHistogramRow[]>([]);
const histogramLoading = ref(false);

const lastFloor = computed(() => {
  const floor = memory.value?.analysis.floor ?? [];
  return floor.length ? floor[floor.length - 1].bytes : 0;
});

const growthText = computed(() => {
  const growth = memory.value?.analysis.floorGrowthPerHour ?? 0;
  if ((memory.value?.analysis.floor.length ?? 0) < 6) return "—";
  return `${growth >= 0 ? "+" : "−"}${formatBytes(Math.abs(growth))}/h`;
});

const verdict = computed(() => {
  const analysis = memory.value?.analysis;
  if (!analysis) return "";
  if (analysis.leakSuspected)
    return analysis.hoursToFull >= 0
      ? t("devPanel.memory.leakEta", { hours: Math.round(analysis.hoursToFull) })
      : t("devPanel.memory.leak");
  if (analysis.floor.length < 6) return t("devPanel.memory.collecting");
  return t("devPanel.memory.stable");
});

// Avval to'liq GC bo'ladi: ilova bir lahza to'xtaydi, shuning uchun so'raymiz
const loadHistogram = async () => {
  try {
    await ElMessageBox.confirm(t("devPanel.memory.histogramConfirm"), t("devPanel.memory.histogram"), {
      type: "warning",
    });
  } catch {
    return;
  }
  histogramLoading.value = true;
  try {
    histogram.value = (await GET_HISTOGRAM()).data;
  } catch (error) {
    ElMessage.error(await devErrorMessage(error));
  } finally {
    histogramLoading.value = false;
  }
};

const jvm = ref<IDevJvm | null>(null);
const loading = ref(false);
const errorText = ref("");
const dump = ref("");
const dumping = ref(false);
const filter = ref("");

// Dampda oqimlar bo'sh qator bilan ajratilgan; filtr butun oqim bloki bo'yicha ishlaydi
const threads = computed(() =>
  dump.value ? dump.value.split(/\n(?=")/).map((block) => block.trimEnd()) : [],
);

const visibleThreads = computed(() => {
  const query = filter.value.trim().toLowerCase();
  return query
    ? threads.value.filter((block) => block.toLowerCase().includes(query))
    : threads.value;
});

const formatDuration = (ms: number) => {
  if (ms < 1000) return `${ms} ms`;
  const duration = moment.duration(ms);
  const days = Math.floor(duration.asDays());
  const parts = [
    days ? `${days}d` : "",
    duration.hours() ? `${duration.hours()}h` : "",
    duration.minutes() ? `${duration.minutes()}m` : "",
    `${duration.seconds()}s`,
  ];
  return parts.filter(Boolean).join(" ");
};

const load = async () => {
  loading.value = true;
  try {
    jvm.value = (await GET_JVM()).data;
    try {
      memory.value = (await GET_MEMORY()).data;
    } catch {
      memory.value = null;
    }
    errorText.value = "";
  } catch (error) {
    errorText.value = await devErrorMessage(error);
  } finally {
    loading.value = false;
  }
};

const takeDump = async () => {
  dumping.value = true;
  try {
    dump.value = (await GET_THREAD_DUMP()).data;
    errorText.value = "";
  } catch (error) {
    errorText.value = await devErrorMessage(error);
  } finally {
    dumping.value = false;
  }
};

const download = () => {
  const url = URL.createObjectURL(new Blob([dump.value], { type: "text/plain" }));
  const link = document.createElement("a");
  link.href = url;
  link.download = `threads-${moment().format("YYYYMMDD-HHmmss")}.txt`;
  link.click();
  URL.revokeObjectURL(url);
};

onMounted(load);
</script>

<style scoped>
.dev-mini-stats {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 8px;
}

.dev-mini-stats > div {
  display: flex;
  flex-direction: column;
  padding: 8px 10px;
  border-radius: 8px;
  background: var(--el-fill-color-light);
}

.dev-mini-stats span {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.dev-mini-stats b {
  font-size: 16px;
}

.dev-mini-stats > .is-bad b {
  color: var(--el-color-danger);
}

.dev-dl {
  display: grid;
  grid-template-columns: max-content 1fr;
  gap: 6px 16px;
  margin: 0;
  font-size: 13px;
}

.dev-dl dt {
  color: var(--el-text-color-secondary);
}

.dev-dl dd {
  margin: 0;
  word-break: break-word;
  color: var(--el-text-color-primary);
}

.dev-pre {
  margin: 0;
  padding: 10px 12px;
  overflow: auto;
  font-size: 12px;
  line-height: 1.5;
  border-radius: 8px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-primary);
}

.dev-pre--dump {
  max-height: 600px;
}
</style>
