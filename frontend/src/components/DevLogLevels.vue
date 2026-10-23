<template>
  <div>
    <p class="m-0 mb-3 text-sm text-gray-500">{{ $t("devPanel.logLevels.hint") }}</p>
    <div class="mb-3 flex flex-wrap items-center gap-2">
      <el-input
        v-model="filter"
        clearable
        :placeholder="$t('devPanel.logLevels.filter')"
        style="width: 320px"
        @keyup.enter="load"
        @clear="load"
      />
      <el-button @click="load">{{ $t("devPanel.search") }}</el-button>
    </div>
    <el-alert
      v-if="errorText"
      :title="errorText"
      type="error"
      show-icon
      :closable="false"
      class="mb-4"
    />
    <el-table v-loading="loading" :data="loggers" size="small" border>
      <el-table-column label="Logger" min-width="300">
        <template #default="{ row }"><code class="text-xs">{{ row.name }}</code></template>
      </el-table-column>
      <el-table-column :label="$t('devPanel.logLevels.configured')" width="120" prop="configured" />
      <el-table-column :label="$t('devPanel.logLevels.effective')" width="120">
        <template #default="{ row }">
          <b>{{ row.effective }}</b>
          <el-tag v-if="row.changedUntil" size="small" type="warning" class="ml-1"
            >→ {{ formatTime(row.changedUntil) }}</el-tag
          >
        </template>
      </el-table-column>
      <el-table-column v-if="store.canWrite" :label="$t('devPanel.logLevels.set')" min-width="330">
        <template #default="{ row }">
          <div class="flex flex-wrap items-center gap-1">
            <el-button
              v-for="l in ['DEBUG', 'INFO', 'WARN', 'ERROR']"
              :key="l"
              size="small"
              :type="row.effective === l ? 'primary' : 'default'"
              @click="set(row.name, l)"
              >{{ l }}</el-button
            >
            <el-button v-if="row.changedUntil" size="small" link type="danger" @click="reset(row.name)">{{
              $t("devPanel.logLevels.restore")
            }}</el-button>
          </div>
        </template>
      </el-table-column>
    </el-table>
    <div class="mt-3 flex items-center gap-2 text-sm text-gray-500">
      {{ $t("devPanel.logLevels.for") }}
      <el-select v-model="minutes" style="width: 110px" size="small">
        <el-option v-for="m in [10, 30, 60, 240]" :key="m" :label="`${m} min`" :value="m" />
      </el-select>
      {{ $t("devPanel.logLevels.thenBack") }}
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import { useDevApi } from "../dev.api";
import { useDevStore } from "../dev.store";
import { IDevLoggerLevel } from "../dev.types";
import { devErrorMessage, formatTime } from "../dev.utils";

const store = useDevStore();
const { GET_LOG_LEVELS, SET_LOG_LEVEL, RESET_LOG_LEVEL } = useDevApi();
const loggers = ref<IDevLoggerLevel[]>([]);
const filter = ref("");
const minutes = ref(30);
const loading = ref(false);
const errorText = ref("");

const load = async () => {
  loading.value = true;
  try {
    loggers.value = (await GET_LOG_LEVELS(filter.value || undefined)).data;
    errorText.value = "";
  } catch (error) {
    errorText.value = await devErrorMessage(error);
  } finally {
    loading.value = false;
  }
};

const set = async (logger: string, level: string) => {
  try {
    await SET_LOG_LEVEL(logger, level, minutes.value);
    ElMessage.success(`${logger} → ${level}`);
    load();
  } catch (error) {
    ElMessage.error(await devErrorMessage(error));
  }
};

const reset = async (logger: string) => {
  try {
    await RESET_LOG_LEVEL(logger);
    load();
  } catch (error) {
    ElMessage.error(await devErrorMessage(error));
  }
};

onMounted(load);
</script>
