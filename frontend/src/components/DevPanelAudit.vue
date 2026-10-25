<template>
  <div>
    <p class="mb-3 mt-0 text-sm text-gray-500">{{ $t("devPanel.panelAudit.hint") }}</p>
    <div class="mb-3 flex flex-wrap gap-2">
      <el-input v-model="username" clearable class="!w-48" :placeholder="$t('devPanel.login')"
        @keydown.enter="search" @clear="search" />
      <el-select v-model="action" clearable filterable class="!w-64"
        :placeholder="$t('devPanel.panelAudit.action')" @change="search">
        <el-option v-for="name in ACTIONS" :key="name" :value="name" :label="name" />
      </el-select>
      <el-button @click="search">{{ $t("actions.refresh") }}</el-button>
    </div>
    <el-alert v-if="errorText" :title="errorText" type="error" show-icon :closable="false" class="mb-4" />
    <el-table v-loading="loading" :data="rows" size="small">
      <el-table-column :label="$t('devPanel.panelAudit.when')" width="160">
        <template #default="{ row }">{{ formatTime(row.time) }}</template>
      </el-table-column>
      <el-table-column prop="username" :label="$t('devPanel.login')" width="140" />
      <el-table-column :label="$t('devPanel.panelAudit.action')" width="200">
        <template #default="{ row }"><el-tag size="small" :type="tagOf(row.action)">{{ row.action }}</el-tag></template>
      </el-table-column>
      <el-table-column :label="$t('devPanel.panelAudit.what')" min-width="380">
        <template #default="{ row }">
          <b v-if="row.target">{{ row.target }}</b>
          <div v-if="row.detail" class="break-all text-xs text-gray-500">{{ row.detail }}</div>
        </template>
      </el-table-column>
      <el-table-column prop="clientIp" label="IP" width="140" />
    </el-table>
    <el-pagination v-if="total > size" class="mt-3" layout="prev, pager, next" :total="total"
      :page-size="size" :current-page="page + 1" @current-change="(n: number) => { page = n - 1; load(); }" />
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useDevApi } from "../dev.api";
import { IDevAction } from "../dev.types";
import { devErrorMessage, formatTime } from "../dev.utils";

const ACTIONS = [
  "query.select", "query.excel", "query.update", "data.browse", "database.cancel",
  "database.terminate", "database.explain", "database.explain-analyze", "jvm.heap-histogram",
  "logging.set", "logging.reset", "config.view", "account.create", "account.update",
  "account.reset-password", "account.reset-two-factor", "runner.send", "smoke.create",
  "smoke.update", "smoke.delete", "smoke.run", "slo.create", "slo.delete", "risks.run",
  "risks.snooze", "risks.accept", "risks.reopen", "profiler.start", "tests.upload",
  "alerts.test", "report.weekly",
];

const { GET_PANEL_AUDIT } = useDevApi();
const rows = ref<IDevAction[]>([]);
const total = ref(0);
const page = ref(0);
const size = 50;
const username = ref("");
const action = ref("");
const loading = ref(false);
const errorText = ref("");

// O'zgartiradigan amallar ko'zga tashlanadi
const tagOf = (name: string) =>
  /update|terminate|cancel|delete|reset|analyze|accept/.test(name) ? "danger"
    : /send|create|set|run|start|upload|snooze/.test(name) ? "warning" : "info";

const load = async () => {
  loading.value = true;
  try {
    const data = (await GET_PANEL_AUDIT({ username: username.value || undefined,
      action: action.value || undefined, page: page.value, size })).data;
    rows.value = data.content;
    total.value = data.total;
    errorText.value = "";
  } catch (error) {
    errorText.value = await devErrorMessage(error);
  } finally {
    loading.value = false;
  }
};

const search = () => {
  page.value = 0;
  load();
};

onMounted(load);
</script>
