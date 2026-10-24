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
    <el-alert type="info" :closable="false" class="mb-4" :title="$t('devPanel.threats.note')" />

    <template v-if="data">
      <section class="mb-4">
        <h3 class="m-0 mb-2 text-base font-semibold dark:text-white">{{ $t("devPanel.threats.findings") }}</h3>
        <el-empty v-if="!data.findings.length" :image-size="60" :description="$t('devPanel.threats.none')" />
        <div
          v-for="(finding, index) in data.findings"
          :key="index"
          class="mb-2 flex items-start gap-3 rounded-lg border p-3"
          :class="finding.critical ? 'border-red-400' : 'border-orange-300'"
        >
          <el-tag :type="finding.critical ? 'danger' : 'warning'" effect="dark">{{ finding.type }}</el-tag>
          <span class="text-sm">{{ finding.detail }}</span>
        </div>
      </section>

      <div class="mb-3 flex items-center gap-3">
        <h3 class="m-0 text-base font-semibold dark:text-white">{{ $t("devPanel.threats.topIps") }}</h3>
        <el-radio-group v-model="range" size="small">
          <el-radio-button value="top10min">10 min</el-radio-button>
          <el-radio-button value="top1h">1 h</el-radio-button>
        </el-radio-group>
        <span class="ml-auto text-xs text-gray-500">{{ $t("devPanel.metrics.autoRefresh", { seconds: 15 }) }}</span>
      </div>
      <el-table :data="data[range]" size="small" border class="mb-5">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="p-3 text-xs">
              <div><b>{{ $t("devPanel.threats.lastPath") }}:</b> <code>{{ row.lastPath }}</code></div>
              <div v-if="row.notFoundPaths.length" class="mt-1">
                <b>404:</b> <code>{{ row.notFoundPaths.join("  ") }}</code>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="ip" label="IP" min-width="150" />
        <el-table-column prop="requests" :label="$t('devPanel.threats.requests')" width="110" align="right" sortable />
        <el-table-column prop="lastMinute" :label="$t('devPanel.threats.perMinute')" width="110" align="right" sortable />
        <el-table-column prop="unauthorized" label="401/403" width="90" align="right" sortable>
          <template #default="{ row }"><span :class="{ 'font-bold text-red-500': row.unauthorized >= 10 }">{{ row.unauthorized }}</span></template>
        </el-table-column>
        <el-table-column prop="notFound" label="404" width="80" align="right" sortable>
          <template #default="{ row }"><span :class="{ 'font-bold text-orange-500': row.notFound >= 10 }">{{ row.notFound }}</span></template>
        </el-table-column>
        <el-table-column prop="probes" :label="$t('devPanel.threats.probes')" width="90" align="right" />
        <el-table-column :label="$t('devPanel.usage.users')" min-width="140">
          <template #default="{ row }"><span class="text-xs">{{ row.users.join(", ") || "—" }}</span></template>
        </el-table-column>
        <el-table-column :label="$t('devPanel.usage.lastSeen')" width="120">
          <template #default="{ row }">{{ fromNow(row.lastSeen) }}</template>
        </el-table-column>
      </el-table>

      <h3 class="m-0 mb-2 text-base font-semibold dark:text-white">{{ $t("devPanel.threats.events") }}</h3>
      <el-table :data="data.events" size="small" border max-height="420">
        <el-table-column :label="$t('devPanel.api.time')" width="140">
          <template #default="{ row }">{{ formatTime(row.time) }}</template>
        </el-table-column>
        <el-table-column width="130">
          <template #default="{ row }"><el-tag size="small" :type="row.type === 'PROBE' ? 'danger' : 'warning'">{{ row.type }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="ip" label="IP" width="150" />
        <el-table-column prop="status" label="HTTP" width="70" />
        <el-table-column :label="$t('devPanel.threats.path')" min-width="260">
          <template #default="{ row }"><code class="break-all text-xs">{{ row.path }}</code></template>
        </el-table-column>
        <el-table-column prop="user" :label="$t('devPanel.usage.user')" width="140" />
      </el-table>
    </template>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from "vue";
import { useDevApi } from "../dev.api";
import { IDevSecurity } from "../dev.types";
import { devErrorMessage, formatTime, fromNow, useAutoRefresh } from "../dev.utils";

const { GET_SECURITY } = useDevApi();
const data = ref<IDevSecurity | null>(null);
const range = ref<"top10min" | "top1h">("top10min");
const errorText = ref("");

const load = async () => {
  try {
    data.value = (await GET_SECURITY()).data;
    errorText.value = "";
  } catch (error) {
    errorText.value = await devErrorMessage(error);
  }
};

const refresh = useAutoRefresh(load, 15);
onMounted(() => {
  load();
  refresh.start();
});
onBeforeUnmount(refresh.stop);
</script>
