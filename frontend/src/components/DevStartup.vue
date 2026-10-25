<template>
  <div v-loading="loading">
    <div class="mb-4 flex items-center gap-3">
      <p class="m-0 text-sm text-gray-500">{{ $t("devPanel.startup.hint") }}</p>
      <el-button class="ml-auto" :loading="loading" @click="load">{{
        $t("actions.refresh")
      }}</el-button>
    </div>
    <el-alert v-if="errorText" :title="errorText" type="error" show-icon :closable="false"
      class="mb-4" />
    <el-empty v-if="!loading && !report" :description="$t('devPanel.startup.none')" />

    <template v-if="report">
      <div class="startup-tiles mb-4">
        <div class="startup-tile">
          <span>{{ $t("devPanel.startup.total") }} <DevHint k="startupTotal" /></span>
          <b>{{ formatMs(report.uptimeMs) }}</b>
        </div>
        <div class="startup-tile">
          <span>{{ $t("devPanel.startup.spring") }}</span>
          <b>{{ formatMs(report.contextMs) }}</b>
        </div>
        <div class="startup-tile">
          <span>{{ $t("devPanel.startup.sql") }}</span>
          <b>{{ report.sqlCount }}</b>
          <small>{{ formatMs(report.sqlMs) }}</small>
        </div>
        <div class="startup-tile">
          <span>{{ $t("devPanel.startup.migrations") }}</span>
          <b>{{ report.migrations.length }}</b>
        </div>
      </div>

      <el-alert v-for="table in repeatedInserts" :key="table" type="warning" show-icon
        :closable="false" class="mb-3"
        :title="$t('devPanel.startup.everyStart', { table, rows: report.inserts[table] })"
        :description="$t('devPanel.startup.everyStartHint')" />

      <el-row :gutter="16">
        <el-col :xs="24" :lg="14">
          <h4 class="startup-h">{{ $t("devPanel.startup.slowBeans") }}
            <DevHint k="startupBeans" /></h4>
          <el-alert v-if="report.ownStartupRecorder" type="info" :closable="false" class="mb-2"
            :title="$t('devPanel.startup.ownRecorder')" />
          <el-table :data="report.beans" size="small" max-height="420">
            <el-table-column :label="$t('devPanel.startup.bean')" min-width="220">
              <template #default="{ row }">
                <b>{{ row.name }}</b>
                <div v-if="row.type" class="truncate text-xs text-gray-500">{{ row.type }}</div>
              </template>
            </el-table-column>
            <el-table-column :label="$t('devPanel.startup.own')" width="110" align="right">
              <template #default="{ row }">
                <span :class="{ 'font-bold text-orange-500': row.ownMs >= 1000 }">{{
                  formatMs(row.ownMs)
                }}</span>
              </template>
            </el-table-column>
            <el-table-column :label="$t('devPanel.startup.withDependencies')" width="140"
              align="right">
              <template #default="{ row }">{{ formatMs(row.totalMs) }}</template>
            </el-table-column>
          </el-table>
        </el-col>
        <el-col :xs="24" :lg="10">
          <h4 class="startup-h">{{ $t("devPanel.startup.inserts") }}</h4>
          <el-empty v-if="!insertRows.length" :image-size="50"
            :description="$t('devPanel.startup.noInserts')" />
          <el-table v-else :data="insertRows" size="small">
            <el-table-column prop="table" :label="$t('devPanel.startup.table')" />
            <el-table-column :label="$t('devPanel.startup.rows')" width="90" align="right"
              prop="rows" />
            <el-table-column :label="$t('devPanel.startup.starts')" width="120" align="right">
              <template #default="{ row }">
                <span :class="{ 'font-bold text-orange-500': row.starts >= 2 }">{{
                  row.starts }} / {{ data?.inserts.length }}</span>
              </template>
            </el-table-column>
          </el-table>

          <h4 class="startup-h mt-5">{{ $t("devPanel.startup.previous") }}</h4>
          <div class="startup-bars">
            <div v-for="start in previous" :key="start.startedAt" class="startup-bar"
              :title="`${formatTime(start.startedAt)} — ${formatMs(start.ms)}`">
              <span :style="{ height: `${(start.ms / longest) * 100}%` }" />
            </div>
          </div>
        </el-col>
      </el-row>

      <template v-if="report.statements.length">
        <h4 class="startup-h mt-5">{{ $t("devPanel.startup.repeated") }}</h4>
        <el-table :data="report.statements" size="small">
          <el-table-column :label="$t('devPanel.waterfall.statement')" min-width="400">
            <template #default="{ row }"><code class="text-xs">{{ row.statement }}</code></template>
          </el-table-column>
          <el-table-column prop="count" :label="$t('devPanel.startup.times')" width="90"
            align="right" />
          <el-table-column :label="$t('devPanel.api.duration')" width="110" align="right">
            <template #default="{ row }">{{ formatMs(row.totalMs) }}</template>
          </el-table-column>
        </el-table>
      </template>

      <template v-if="report.migrations.length">
        <h4 class="startup-h mt-5">{{ $t("devPanel.startup.migrations") }}</h4>
        <el-table :data="report.migrations" size="small">
          <el-table-column prop="tool" width="100" />
          <el-table-column prop="id" label="ID" min-width="200" />
          <el-table-column prop="file" :label="$t('devPanel.startup.file')" min-width="260" />
          <el-table-column prop="author" :label="$t('devPanel.startup.author')" width="140" />
        </el-table>
      </template>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useDevApi } from "../dev.api";
import { IDevStartup } from "../dev.types";
import { devErrorMessage, formatMs, formatTime } from "../dev.utils";
import DevHint from "./DevHint.vue";

const { GET_STARTUP } = useDevApi();
const data = ref<IDevStartup | null>(null);
const loading = ref(false);
const errorText = ref("");

const report = computed(() => data.value?.report ?? null);

// Har bir jadval oxirgi startlarning nechtasida qator qo'shgan
const startsPerTable = computed(() => {
  const counts: Record<string, number> = {};
  for (const start of data.value?.inserts ?? []) {
    for (const [table, rows] of Object.entries(start)) {
      if (rows > 0) counts[table] = (counts[table] ?? 0) + 1;
    }
  }
  return counts;
});

const insertRows = computed(() =>
  Object.entries(report.value?.inserts ?? {})
    .map(([table, rows]) => ({ table, rows, starts: startsPerTable.value[table] ?? 1 }))
    .sort((a, b) => b.rows - a.rows),
);

const repeatedInserts = computed(() =>
  insertRows.value.filter((row) => row.starts >= 2).map((row) => row.table),
);

const previous = computed(() => [...(data.value?.previous ?? [])].reverse());
const longest = computed(() => Math.max(1, ...previous.value.map((start) => start.ms)));

const load = async () => {
  loading.value = true;
  try {
    data.value = (await GET_STARTUP()).data;
    errorText.value = "";
  } catch (error) {
    errorText.value = await devErrorMessage(error);
  } finally {
    loading.value = false;
  }
};

onMounted(load);
</script>

<style scoped>
.startup-tiles {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 10px;
}

.startup-tile {
  display: flex;
  flex-direction: column;
  padding: 12px 14px;
  border-radius: 12px;
  border: 1px solid var(--el-border-color);
  background: var(--el-fill-color-lighter);
}

.startup-tile span,
.startup-tile small {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.startup-tile b {
  font-size: 22px;
  font-variant-numeric: tabular-nums;
}

.startup-h {
  margin: 0 0 8px;
  font-size: 14px;
}

.startup-bars {
  display: flex;
  align-items: flex-end;
  gap: 4px;
  height: 90px;
  padding: 6px;
  border-radius: 10px;
  border: 1px solid var(--el-border-color-lighter);
}

.startup-bar {
  display: flex;
  align-items: flex-end;
  flex: 1;
  height: 100%;
  cursor: help;
}

.startup-bar span {
  display: block;
  width: 100%;
  min-height: 2px;
  border-radius: 3px 3px 0 0;
  background: #8b5cf6;
}
</style>
