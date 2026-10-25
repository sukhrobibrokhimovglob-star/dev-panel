<template>
  <div>
    <div class="mb-4 flex flex-wrap items-center gap-3">
      <el-input
        v-model="search"
        clearable
        :placeholder="$t('devPanel.search')"
        style="width: 280px"
      />
      <el-checkbox v-model="onlyNew">{{ $t("devPanel.errors.onlyNew") }}</el-checkbox>
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
    <p class="m-0 mb-3 text-sm text-gray-500">{{ $t("devPanel.errors.hint") }}</p>

    <el-table v-loading="loading" :data="visible" size="small" border>
      <el-table-column type="expand">
        <template #default="{ row }">
          <div class="p-3">
            <p class="m-0 mb-2 text-sm">
              <b>{{ $t("devPanel.errors.message") }}:</b> {{ row.message }}
            </p>
            <pre class="dev-stack">{{ row.sample }}</pre>
          </div>
        </template>
      </el-table-column>
      <el-table-column :label="$t('devPanel.errors.type')" min-width="220">
        <template #default="{ row }">
          <el-tag v-if="row.isNew" type="danger" size="small" class="mr-1"
            >NEW</el-tag
          >
          <b>{{ short(row.type) }}</b>
          <div class="truncate text-xs text-gray-500">{{ row.message }}</div>
        </template>
      </el-table-column>
      <el-table-column :label="$t('devPanel.errors.where')" min-width="260">
        <template #default="{ row }"
          ><code class="break-all text-xs">{{ row.location }}</code></template
        >
      </el-table-column>
      <el-table-column
        prop="count"
        :label="$t('devPanel.errors.count')"
        width="90"
        align="right"
        sortable
      />
      <el-table-column :label="$t('devPanel.errors.firstSeen')" width="130">
        <template #default="{ row }">{{ formatTime(row.firstSeen) }}</template>
      </el-table-column>
      <el-table-column
        :label="$t('devPanel.errors.lastSeen')"
        width="130"
        sortable
        :sort-method="(a, b) => a.lastSeen.localeCompare(b.lastSeen)"
      >
        <template #default="{ row }">{{ fromNow(row.lastSeen) }}</template>
      </el-table-column>
      <el-table-column width="90" align="center">
        <template #default="{ row }">
          <el-button
            v-if="row.lastTraceId"
            link
            type="primary"
            @click="openLogs(row)"
            >{{ $t("devPanel.audit.logs") }}</el-button
          >
        </template>
      </el-table-column>
    </el-table>

    <DevTraceLogs
      v-if="logsOf"
      v-model="logsVisible"
      :trace-id="logsOf.lastTraceId"
      :occurred-at="logsOf.lastSeen"
    />
  </div>
</template>

<script setup lang="ts">
import { useDevStore } from "../dev.store";
import { computed, onMounted, ref } from "vue";
import { useDevApi } from "../dev.api";
import { IDevExceptionGroup } from "../dev.types";
import { devErrorMessage, formatTime, fromNow } from "../dev.utils";
import DevTraceLogs from "./DevTraceLogs.vue";

const { GET_EXCEPTIONS } = useDevApi();
const groups = ref<IDevExceptionGroup[]>([]);
const loading = ref(false);
const errorText = ref("");
const search = ref(useDevStore().takeFocus("errors")?.endpoint ?? "");
const onlyNew = ref(false);
const logsOf = ref<IDevExceptionGroup | null>(null);
const logsVisible = ref(false);

const visible = computed(() => {
  const query = search.value.trim().toLowerCase();
  return groups.value.filter(
    (g) =>
      (!onlyNew.value || g.isNew) &&
      (!query ||
        `${g.type} ${g.location} ${g.message}`.toLowerCase().includes(query)),
  );
});

const short = (type: string) => type.substring(type.lastIndexOf(".") + 1);

const openLogs = (group: IDevExceptionGroup) => {
  logsOf.value = group;
  logsVisible.value = true;
};

const load = async () => {
  loading.value = true;
  try {
    groups.value = (await GET_EXCEPTIONS()).data;
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
.dev-stack {
  margin: 0;
  padding: 10px 12px;
  max-height: 420px;
  overflow: auto;
  font-size: 12px;
  line-height: 1.5;
  border-radius: 8px;
  background: var(--el-fill-color-light);
}
</style>
