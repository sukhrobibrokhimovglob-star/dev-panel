<template>
  <div>
    <div class="mb-4 flex items-center gap-3">
      <p class="m-0 text-sm text-gray-500">{{ $t("devPanel.jobs.hint") }}</p>
      <el-button class="ml-auto" :loading="loading" @click="load">{{
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
    <el-empty v-if="!loading && !jobs.length" :description="$t('devPanel.jobs.none')" />
    <el-table v-else v-loading="loading" :data="jobs" size="small" border>
      <el-table-column :label="$t('devPanel.jobs.job')" min-width="220">
        <template #default="{ row }">
          <el-tag
            :type="row.missed || row.lastError ? 'danger' : row.running ? 'primary' : 'success'"
            effect="dark"
            size="small"
            class="mr-2"
            >{{
              row.missed
                ? $t("devPanel.jobs.missed")
                : row.lastError
                  ? $t("devPanel.jobs.failed")
                  : row.running
                    ? $t("devPanel.jobs.running")
                    : "OK"
            }}</el-tag
          >
          <b>{{ row.name }}</b>
        </template>
      </el-table-column>
      <el-table-column prop="schedule" :label="$t('devPanel.jobs.schedule')" min-width="180" />
      <el-table-column :label="$t('devPanel.jobs.lastRun')" width="140">
        <template #default="{ row }">{{ fromNow(row.lastStart) }}</template>
      </el-table-column>
      <el-table-column :label="$t('devPanel.api.duration')" width="100" align="right">
        <template #default="{ row }">{{ row.lastStart ? formatMs(row.lastMs) : "—" }}</template>
      </el-table-column>
      <el-table-column :label="$t('devPanel.jobs.next')" width="140">
        <template #default="{ row }">{{ formatTime(row.nextExpected) }}</template>
      </el-table-column>
      <el-table-column :label="$t('devPanel.jobs.runs')" width="110" align="right">
        <template #default="{ row }">
          {{ row.runs }}
          <span v-if="row.failures" class="text-red-500"> / {{ row.failures }}✗</span>
        </template>
      </el-table-column>
      <el-table-column :label="$t('devPanel.integrations.lastError')" min-width="240">
        <template #default="{ row }">
          <span v-if="row.lastError" class="break-all text-xs text-red-500">{{ row.lastError }}</span>
          <span v-else class="text-gray-400">—</span>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from "vue";
import { useDevApi } from "../dev.api";
import { IDevJob } from "../dev.types";
import { devErrorMessage, formatMs, formatTime, fromNow, useAutoRefresh } from "../dev.utils";

const { GET_JOBS } = useDevApi();
const jobs = ref<IDevJob[]>([]);
const loading = ref(false);
const errorText = ref("");

const load = async () => {
  loading.value = true;
  try {
    jobs.value = (await GET_JOBS()).data;
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
