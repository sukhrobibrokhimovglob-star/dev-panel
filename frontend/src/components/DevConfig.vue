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
    <section v-if="deploys" class="mb-4 rounded-xl border border-gray-200 p-4 dark:border-gray-700">
      <h3 class="m-0 mb-3 text-base font-semibold dark:text-white">
        {{ $t("devPanel.config.deploys") }}
      </h3>
      <el-table :data="deploys.recent" size="small">
        <el-table-column :label="$t('devPanel.config.started')" width="160">
          <template #default="{ row }">{{ formatTime(row.startedAt) }}</template>
        </el-table-column>
        <el-table-column prop="host" label="Host" min-width="160" />
        <el-table-column prop="version" :label="$t('devPanel.config.version')" width="140" />
        <el-table-column prop="commit" label="Commit" width="120" />
      </el-table>
    </section>

    <div class="mb-3 flex flex-wrap items-center gap-2">
      <el-input
        v-model="filter"
        clearable
        :placeholder="$t('devPanel.config.filter')"
        style="width: 320px"
        @keyup.enter="load"
        @clear="load"
      />
      <el-button @click="load">{{ $t("devPanel.search") }}</el-button>
      <span class="ml-auto text-xs text-gray-500"
        >{{ $t("devPanel.overview.mode") }}: {{ profiles.join(", ") || "default" }}</span
      >
    </div>
    <p class="m-0 mb-3 text-sm text-gray-500">{{ $t("devPanel.config.hint") }}</p>
    <el-table v-loading="loading" :data="properties" size="small" border height="60vh">
      <el-table-column prop="name" :label="$t('devPanel.config.property')" min-width="280">
        <template #default="{ row }"><code class="text-xs">{{ row.name }}</code></template>
      </el-table-column>
      <el-table-column prop="value" :label="$t('devPanel.config.value')" min-width="260">
        <template #default="{ row }"><span class="break-all text-xs">{{ row.value }}</span></template>
      </el-table-column>
      <el-table-column prop="source" :label="$t('devPanel.config.source')" min-width="220">
        <template #default="{ row }"><span class="text-xs text-gray-500">{{ row.source }}</span></template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useDevApi } from "../dev.api";
import { IDevConfigProperty, IDevDeploy } from "../dev.types";
import { devErrorMessage, formatTime } from "../dev.utils";

const { GET_CONFIG, GET_DEPLOYS } = useDevApi();
const properties = ref<IDevConfigProperty[]>([]);
const profiles = ref<string[]>([]);
const deploys = ref<{ current: IDevDeploy; recent: IDevDeploy[] } | null>(null);
const filter = ref("");
const loading = ref(false);
const errorText = ref("");

const load = async () => {
  loading.value = true;
  try {
    const [config, recent] = await Promise.all([
      GET_CONFIG(filter.value || undefined),
      GET_DEPLOYS(),
    ]);
    properties.value = config.data.properties;
    profiles.value = config.data.profiles;
    deploys.value = recent.data;
    errorText.value = "";
  } catch (error) {
    errorText.value = await devErrorMessage(error);
  } finally {
    loading.value = false;
  }
};

onMounted(load);
</script>
