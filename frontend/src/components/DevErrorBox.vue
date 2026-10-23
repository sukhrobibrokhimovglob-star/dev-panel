<template>
  <el-alert type="error" show-icon :closable="false">
    <template #title>{{ error.message }}</template>
    <div v-if="error.traceId" class="mt-1 flex flex-wrap items-center gap-2">
      <span class="font-mono text-xs">traceId: {{ error.traceId }}</span>
      <el-button link type="primary" size="small" @click="logsVisible = true">{{
        $t("devPanel.users.showLogs")
      }}</el-button>
    </div>
  </el-alert>

  <DevTraceLogs
    v-if="error.traceId"
    v-model="logsVisible"
    :trace-id="error.traceId"
  />
</template>

<script setup lang="ts">
import { ref } from "vue";
import { IDevError } from "../dev.utils";
import DevTraceLogs from "./DevTraceLogs.vue";

defineProps<{ error: IDevError }>();

const logsVisible = ref(false);
</script>
