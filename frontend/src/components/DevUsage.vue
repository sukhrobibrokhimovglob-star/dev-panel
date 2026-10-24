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
    <el-tabs v-model="tab" @tab-change="load">
      <el-tab-pane :label="`${$t('devPanel.usage.active')} (${data?.activeUsers.length ?? 0})`" name="active">
        <p class="m-0 mb-3 text-sm text-gray-500">{{ $t("devPanel.usage.activeHint") }}</p>
        <el-table :data="data?.activeUsers ?? []" size="small" border>
          <el-table-column prop="key" :label="$t('devPanel.usage.user')" min-width="160" />
          <el-table-column :label="$t('devPanel.usage.lastSeen')" width="130">
            <template #default="{ row }">{{ fromNow(row.lastSeen) }}</template>
          </el-table-column>
          <el-table-column :label="$t('devPanel.usage.lastAction')" min-width="260">
            <template #default="{ row }"><code class="text-xs">{{ row.lastEndpoint }}</code></template>
          </el-table-column>
          <el-table-column prop="lastHour" :label="$t('devPanel.usage.lastHour')" width="110" align="right" />
          <el-table-column :label="$t('devPanel.usage.orgs')" min-width="140">
            <template #default="{ row }">{{ row.related.join(", ") || "—" }}</template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane
        v-for="kind in ['users', 'tenants']"
        :key="kind"
        :label="$t(kind === 'users' ? 'devPanel.usage.users' : 'devPanel.usage.tenants')"
        :name="kind"
      >
        <el-input v-model="search" clearable :placeholder="$t('devPanel.search')" style="width: 260px" class="mb-3" />
        <el-table :data="filtered(kind)" size="small" border>
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="p-3">
                <b class="text-sm">{{ $t("devPanel.usage.topEndpoints") }}</b>
                <div v-for="(count, endpoint) in row.topEndpoints" :key="endpoint" class="text-xs">
                  <b>{{ count }}</b> <code>{{ endpoint }}</code>
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="key" :label="kind === 'users' ? $t('devPanel.usage.user') : $t('devPanel.usage.org')" min-width="150" />
          <el-table-column prop="last24h" label="24h" width="90" align="right" sortable />
          <el-table-column prop="lastHour" :label="$t('devPanel.usage.lastHour')" width="100" align="right" sortable />
          <el-table-column prop="serverErrors" label="5xx" width="70" align="right" sortable />
          <el-table-column label="p50" width="80" align="right">
            <template #default="{ row }">{{ formatMs(row.p50Ms) }}</template>
          </el-table-column>
          <el-table-column prop="p95Ms" label="p95" width="90" align="right" sortable>
            <template #default="{ row }"><b>{{ formatMs(row.p95Ms) }}</b></template>
          </el-table-column>
          <el-table-column :label="$t('devPanel.usage.lastSeen')" width="120">
            <template #default="{ row }">{{ fromNow(row.lastSeen) }}</template>
          </el-table-column>
          <el-table-column :label="kind === 'users' ? $t('devPanel.usage.orgs') : $t('devPanel.usage.users')" min-width="160">
            <template #default="{ row }"><span class="text-xs">{{ row.related.join(", ") || "—" }}</span></template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane :label="`${$t('devPanel.usage.unused')} (${unused?.unused.length ?? 0})`" name="unused">
        <p v-if="unused" class="m-0 mb-3 text-sm text-gray-500">
          {{ $t("devPanel.usage.unusedHint", { count: unused.unused.length, total: unused.total, since: formatTime(unused.since) }) }}
        </p>
        <el-table :data="unused?.unused ?? []" size="small" border>
          <el-table-column label="Endpoint" min-width="320">
            <template #default="{ row }"><code class="text-xs">{{ row.endpoint }}</code></template>
          </el-table-column>
          <el-table-column prop="handler" :label="$t('devPanel.usage.handler')" min-width="260" />
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useDevApi } from "../dev.api";
import { IDevUnused, IDevUsage } from "../dev.types";
import { devErrorMessage, formatMs, formatTime, fromNow } from "../dev.utils";

const { GET_USAGE, GET_UNUSED } = useDevApi();
const tab = ref("active");
const search = ref("");
const data = ref<{ users: IDevUsage[]; tenants: IDevUsage[]; activeUsers: IDevUsage[] } | null>(null);
const unused = ref<IDevUnused | null>(null);
const errorText = ref("");

const filtered = (kind: string) => {
  const list = kind === "users" ? data.value?.users ?? [] : data.value?.tenants ?? [];
  const query = search.value.trim().toLowerCase();
  return query ? list.filter((row) => row.key.toLowerCase().includes(query)) : list;
};

const load = async () => {
  try {
    if (tab.value === "unused") unused.value = (await GET_UNUSED()).data;
    else data.value = (await GET_USAGE()).data;
    errorText.value = "";
  } catch (error) {
    errorText.value = await devErrorMessage(error);
  }
};

onMounted(load);
</script>
