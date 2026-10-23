<template>
  <div>
    <div class="mb-4 flex items-center gap-3">
      <p class="m-0 text-sm text-gray-500">{{ $t("devPanel.integrations.hint") }}</p>
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
    <el-empty
      v-if="!loading && !targets.length"
      :description="$t('devPanel.integrations.none')"
    />
    <el-table v-else v-loading="loading" :data="targets" size="small" border>
      <el-table-column :label="$t('devPanel.integrations.service')" min-width="240">
        <template #default="{ row }">
          <el-tag
            :type="row.down ? 'danger' : row.errors ? 'warning' : 'success'"
            effect="dark"
            size="small"
            class="mr-2"
            >{{
              row.down
                ? $t("devPanel.integrations.down")
                : row.errors
                  ? $t("devPanel.integrations.unstable")
                  : "OK"
            }}</el-tag
          >
          <code class="text-xs">{{ row.host }}</code>
          <div v-if="row.down" class="text-xs text-red-500">
            {{ $t("devPanel.integrations.downSince") }}
            {{ formatTime(row.downSince) }}
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="calls" :label="$t('devPanel.api.calls')" width="90" align="right" />
      <el-table-column prop="errors" :label="$t('devPanel.integrations.errors')" width="90" align="right" />
      <el-table-column prop="timeouts" :label="$t('devPanel.integrations.timeouts')" width="100" align="right" />
      <el-table-column label="avg" width="80" align="right">
        <template #default="{ row }">{{ formatMs(row.avgMs) }}</template>
      </el-table-column>
      <el-table-column label="p95" width="80" align="right">
        <template #default="{ row }"><b>{{ formatMs(row.p95Ms) }}</b></template>
      </el-table-column>
      <el-table-column :label="$t('devPanel.integrations.lastCall')" width="130">
        <template #default="{ row }">{{ fromNow(row.lastCall) }}</template>
      </el-table-column>
      <el-table-column :label="$t('devPanel.integrations.lastError')" min-width="260">
        <template #default="{ row }">
          <template v-if="row.lastError">
            <span class="text-xs text-gray-500">{{ formatTime(row.lastError) }}</span>
            <div class="break-all text-xs">{{ row.lastErrorMessage }}</div>
          </template>
          <span v-else class="text-gray-400">—</span>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from "vue";
import { useDevApi } from "../dev.api";
import { IDevIntegration } from "../dev.types";
import { devErrorMessage, formatMs, formatTime, fromNow, useAutoRefresh } from "../dev.utils";

const { GET_INTEGRATIONS } = useDevApi();
const targets = ref<IDevIntegration[]>([]);
const loading = ref(false);
const errorText = ref("");

const load = async () => {
  loading.value = true;
  try {
    targets.value = (await GET_INTEGRATIONS()).data;
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
