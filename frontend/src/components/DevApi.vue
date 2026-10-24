<template>
  <div>
    <el-alert
      v-if="errorText"
      :title="errorText"
      type="error"
      show-icon
      :closable="false"
      class="mb-4"
    />

    <el-tabs v-model="tab">
      <el-tab-pane :label="$t('devPanel.api.endpoints')" name="endpoints">
        <div class="mb-3 flex flex-wrap items-center gap-3">
          <el-radio-group v-model="hours" size="small" @change="load">
            <el-radio-button :value="1">1h</el-radio-button>
            <el-radio-button :value="6">6h</el-radio-button>
            <el-radio-button :value="24">24h</el-radio-button>
            <el-radio-button :value="48">48h</el-radio-button>
          </el-radio-group>
          <el-input
            v-model="search"
            clearable
            :placeholder="$t('devPanel.search')"
            style="width: 260px"
          />
          <span class="ml-auto text-xs text-gray-500">{{
            $t("devPanel.api.hint")
          }}</span>
        </div>
        <el-table
          v-loading="loading"
          :data="visibleEndpoints"
          size="small"
          border
          :default-sort="{ prop: 'stats.calls', order: 'descending' }"
        >
          <el-table-column
            prop="stats.endpoint"
            label="Endpoint"
            min-width="260"
            sortable
          >
            <template #default="{ row }"
              ><code class="text-xs">{{ row.stats.endpoint }}</code></template
            >
          </el-table-column>
          <el-table-column
            prop="stats.calls"
            :label="$t('devPanel.api.calls')"
            width="90"
            align="right"
            sortable
          />
          <el-table-column
            prop="stats.serverErrors"
            label="5xx"
            width="70"
            align="right"
            sortable
          >
            <template #default="{ row }">
              <span
                :class="{ 'font-bold text-red-600': row.stats.serverErrors }"
                >{{ row.stats.serverErrors }}</span
              >
            </template>
          </el-table-column>
          <el-table-column
            prop="stats.clientErrors"
            label="4xx"
            width="70"
            align="right"
            sortable
          />
          <el-table-column
            prop="stats.p50Ms"
            label="p50"
            width="80"
            align="right"
            sortable
          >
            <template #default="{ row }">{{ formatMs(row.stats.p50Ms) }}</template>
          </el-table-column>
          <el-table-column
            prop="stats.p95Ms"
            label="p95"
            width="90"
            align="right"
            sortable
          >
            <template #default="{ row }"
              ><b>{{ formatMs(row.stats.p95Ms) }}</b></template
            >
          </el-table-column>
          <el-table-column
            prop="stats.p99Ms"
            label="p99"
            width="80"
            align="right"
            sortable
          >
            <template #default="{ row }">{{ formatMs(row.stats.p99Ms) }}</template>
          </el-table-column>
          <el-table-column
            prop="stats.maxMs"
            label="max"
            width="80"
            align="right"
            sortable
          >
            <template #default="{ row }">{{ formatMs(row.stats.maxMs) }}</template>
          </el-table-column>
          <el-table-column
            :label="$t('devPanel.api.compare')"
            min-width="170"
          >
            <template #default="{ row }">
              <div class="flex flex-col gap-0.5 text-xs">
                <span v-if="row.p95BeforeDeployMs != null">
                  {{ $t("devPanel.api.beforeDeploy") }}:
                  {{ formatMs(row.p95BeforeDeployMs) }}
                  <el-tag
                    v-if="slower(row.stats.p95LastHourMs, row.p95BeforeDeployMs)"
                    type="danger"
                    size="small"
                    effect="plain"
                    >×{{
                      ratio(row.stats.p95LastHourMs, row.p95BeforeDeployMs)
                    }}</el-tag
                  >
                </span>
                <span v-if="row.p95SameHourYesterdayMs != null">
                  {{ $t("devPanel.api.yesterday") }}:
                  {{ formatMs(row.p95SameHourYesterdayMs) }}
                  <el-tag
                    v-if="
                      slower(row.stats.p95LastHourMs, row.p95SameHourYesterdayMs)
                    "
                    type="warning"
                    size="small"
                    effect="plain"
                    >×{{
                      ratio(row.stats.p95LastHourMs, row.p95SameHourYesterdayMs)
                    }}</el-tag
                  >
                </span>
                <span
                  v-if="
                    row.p95BeforeDeployMs == null &&
                    row.p95SameHourYesterdayMs == null
                  "
                  class="text-gray-400"
                  >—</span
                >
              </div>
            </template>
          </el-table-column>
          <el-table-column :label="$t('devPanel.api.timeSplit')" min-width="150">
            <template #default="{ row }">
              <div class="split" :title="splitTitle(row.stats)">
                <i class="db" :style="{ flex: row.stats.avgDbMs }" />
                <i class="ext" :style="{ flex: row.stats.avgExternalMs }" />
                <i class="code" :style="{ flex: row.stats.avgCodeMs }" />
              </div>
              <div class="mt-0.5 text-[11px] text-gray-500">
                DB {{ formatMs(row.stats.avgDbMs) }} · HTTP
                {{ formatMs(row.stats.avgExternalMs) }}
              </div>
            </template>
          </el-table-column>
          <el-table-column
            prop="stats.sqlPerCall"
            :label="$t('devPanel.api.sqlPerCall')"
            width="100"
            align="right"
            sortable
          >
            <template #default="{ row }">
              <span :class="{ 'font-bold text-orange-500': row.stats.wastedPerCall >= 10 }"
                >{{ row.stats.sqlPerCall.toFixed(1) }}
                <small class="text-gray-400"
                  >/ {{ row.stats.sqlMax }}</small
                ></span
              >
            </template>
          </el-table-column>
          <el-table-column
            prop="stats.avgRows"
            :label="$t('devPanel.waterfall.rows')"
            width="90"
            align="right"
            sortable
          >
            <template #default="{ row }">
              <span :class="{ 'font-bold text-orange-500': row.stats.maxRows >= 1000 }"
                >{{ row.stats.avgRows }}
                <small class="text-gray-400">/ {{ row.stats.maxRows }}</small></span
              >
            </template>
          </el-table-column>
          <el-table-column
            prop="stats.avgBytes"
            :label="$t('devPanel.waterfall.size')"
            width="100"
            align="right"
            sortable
          >
            <template #default="{ row }">
              <span :class="{ 'font-bold text-orange-500': row.stats.maxBytes >= 1048576 }">{{
                formatBytes(row.stats.avgBytes)
              }}</span>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane
        :label="`${$t('devPanel.api.slow')} (${slow.length})`"
        name="slow"
      >
        <el-table :data="slow" size="small" border>
          <el-table-column :label="$t('devPanel.api.time')" width="140">
            <template #default="{ row }">{{ formatTime(row.time) }}</template>
          </el-table-column>
          <el-table-column label="Endpoint" min-width="240">
            <template #default="{ row }"
              ><code class="text-xs">{{ row.endpoint }}</code></template
            >
          </el-table-column>
          <el-table-column prop="status" label="HTTP" width="70" />
          <el-table-column :label="$t('devPanel.api.duration')" width="100">
            <template #default="{ row }"
              ><b>{{ formatMs(row.ms) }}</b></template
            >
          </el-table-column>
          <el-table-column label="DB" width="90" align="right">
            <template #default="{ row }">{{ formatMs(row.dbMs) }}</template>
          </el-table-column>
          <el-table-column label="HTTP" width="90" align="right">
            <template #default="{ row }">{{ formatMs(row.externalMs) }}</template>
          </el-table-column>
          <el-table-column prop="sql" label="SQL" width="70" align="right" />
          <el-table-column :label="$t('devPanel.waterfall.rows')" prop="rows" width="80" align="right" />
          <el-table-column width="170" align="center">
            <template #default="{ row }">
              <el-button link type="primary" @click="openTrace(row.traceId)">{{
                $t("devPanel.waterfall.open")
              }}</el-button>
              <el-button
                v-if="row.traceId"
                link
                type="primary"
                @click="openLogs(row.traceId, row.time)"
                >{{ $t("devPanel.audit.logs") }}</el-button
              >
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane :label="`N+1 (${nPlusOne.length})`" name="n-plus-one">
        <p class="m-0 mb-3 text-sm text-gray-500">
          {{ $t("devPanel.api.nPlusOneHint") }}
        </p>
        <el-table :data="nPlusOne" size="small" border>
          <el-table-column :label="$t('devPanel.api.time')" width="140">
            <template #default="{ row }">{{ formatTime(row.time) }}</template>
          </el-table-column>
          <el-table-column label="Endpoint" min-width="200">
            <template #default="{ row }"
              ><code class="text-xs">{{ row.endpoint }}</code></template
            >
          </el-table-column>
          <el-table-column :label="$t('devPanel.api.wasted')" width="120" align="right">
            <template #default="{ row }"
              ><b class="text-orange-500">{{ row.wasted }}</b> /
              {{ row.sqlTotal }}</template
            >
          </el-table-column>
          <el-table-column :label="$t('devPanel.api.statement')" min-width="360">
            <template #default="{ row }">
              <div v-for="(item, i) in row.repeated" :key="i" class="mb-1 text-xs">
                <b>{{ item.count }}×</b>
                <code class="break-all">{{ item.statement.slice(0, 180) }}</code>
              </div>
            </template>
          </el-table-column>
          <el-table-column width="110" align="center">
            <template #default="{ row }">
              <el-button link type="primary" @click="openTrace(row.traceId)">{{
                $t("devPanel.waterfall.open")
              }}</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane
        :label="`${$t('devPanel.api.traces')} (${traces.length})`"
        name="traces"
      >
        <p class="m-0 mb-3 text-sm text-gray-500">{{ $t("devPanel.api.tracesHint") }}</p>
        <el-table :data="traces" size="small" border @row-click="(row) => openTrace(row.id)">
          <el-table-column :label="$t('devPanel.api.time')" width="140">
            <template #default="{ row }">{{ formatTime(row.time) }}</template>
          </el-table-column>
          <el-table-column width="120">
            <template #default="{ row }">
              <el-tag size="small" :type="row.reason === 'ERROR' ? 'danger' : row.reason === 'SAMPLE' ? 'info' : 'warning'">{{ row.reason }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="Endpoint" min-width="240">
            <template #default="{ row }"><code class="text-xs">{{ row.endpoint }}</code></template>
          </el-table-column>
          <el-table-column prop="status" label="HTTP" width="70" />
          <el-table-column :label="$t('devPanel.api.duration')" width="100" align="right">
            <template #default="{ row }"><b>{{ formatMs(row.totalMs) }}</b></template>
          </el-table-column>
          <el-table-column label="DB" width="90" align="right">
            <template #default="{ row }">{{ formatMs(row.sqlMs) }}</template>
          </el-table-column>
          <el-table-column label="HTTP" width="90" align="right">
            <template #default="{ row }">{{ formatMs(row.externalMs) }}</template>
          </el-table-column>
          <el-table-column prop="sqlCount" label="SQL" width="70" align="right" />
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <DevWaterfall v-model="traceVisible" :trace-id="traceId" />

    <DevTraceLogs
      v-if="logsOf"
      v-model="logsVisible"
      :trace-id="logsOf.traceId"
      :occurred-at="logsOf.time"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { useDevApi } from "../dev.api";
import {
  IDevEndpointRow,
  IDevNPlusOne,
  IDevSlowRequest,
  IDevTraceSummary,
} from "../dev.types";
import {
  devErrorMessage,
  formatBytes,
  formatMs,
  formatTime,
  useAutoRefresh,
} from "../dev.utils";
import DevTraceLogs from "./DevTraceLogs.vue";
import DevWaterfall from "./DevWaterfall.vue";

const { GET_ENDPOINTS, GET_SLOW, GET_N_PLUS_ONE, GET_TRACES } = useDevApi();
const traces = ref<IDevTraceSummary[]>([]);
const traceId = ref<string | null>(null);
const traceVisible = ref(false);

const openTrace = (id: string) => {
  traceId.value = id;
  traceVisible.value = true;
};

const splitTitle = (stats: IDevEndpointRow["stats"]) =>
  `DB ${formatMs(stats.avgDbMs)} · HTTP ${formatMs(stats.avgExternalMs)} · code ${formatMs(stats.avgCodeMs)}`;

const tab = ref("endpoints");
const hours = ref(24);
const search = ref("");
const endpoints = ref<IDevEndpointRow[]>([]);
const slow = ref<IDevSlowRequest[]>([]);
const nPlusOne = ref<IDevNPlusOne[]>([]);
const loading = ref(false);
const errorText = ref("");
const logsOf = ref<{ traceId: string; time: string } | null>(null);
const logsVisible = ref(false);

const visibleEndpoints = computed(() => {
  const query = search.value.trim().toLowerCase();
  return query
    ? endpoints.value.filter((row) =>
        row.stats.endpoint.toLowerCase().includes(query),
      )
    : endpoints.value;
});

// Oxirgi soat p95 bazaviydan 2 baravar va 300 ms dan ko'p oshgan bo'lsa
const slower = (now: number, before: number) =>
  now > 0 && before > 0 && now >= before * 2 && now - before >= 300;
const ratio = (now: number, before: number) => (now / before).toFixed(1);

const openLogs = (traceId: string, time: string) => {
  logsOf.value = { traceId, time };
  logsVisible.value = true;
};

const load = async () => {
  loading.value = true;
  try {
    const [e, s, n, t] = await Promise.all([
      GET_ENDPOINTS(hours.value),
      GET_SLOW(),
      GET_N_PLUS_ONE(),
      GET_TRACES(),
    ]);
    endpoints.value = e.data;
    slow.value = s.data;
    nPlusOne.value = n.data;
    traces.value = t.data;
    errorText.value = "";
  } catch (error) {
    errorText.value = await devErrorMessage(error);
  } finally {
    loading.value = false;
  }
};

const refresh = useAutoRefresh(load, 60);
onMounted(() => {
  load();
  refresh.start();
});
onBeforeUnmount(refresh.stop);
</script>

<style scoped>
.split {
  display: flex;
  height: 8px;
  border-radius: 4px;
  overflow: hidden;
  background: var(--el-fill-color);
}

.split .db {
  background: #106297;
}

.split .ext {
  background: #e6a23c;
}

.split .code {
  background: #7c5cff;
}
</style>
