<template>
  <el-dialog
    :model-value="modelValue"
    :title="$t('devPanel.explain.title')"
    width="min(1100px, 96vw)"
    top="5vh"
    append-to-body
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <div v-loading="loading" class="min-h-[160px]">
      <el-alert
        v-if="errorText"
        :title="errorText"
        type="error"
        show-icon
        :closable="false"
        class="mb-3"
      />
      <template v-if="plan">
        <div class="mb-3 flex flex-wrap items-center gap-2">
          <el-tag :type="plan.analyzed ? 'success' : 'info'" effect="plain">{{
            plan.analyzed ? $t("devPanel.explain.analyzed") : $t("devPanel.explain.estimated")
          }}</el-tag>
          <span v-if="plan.planningMs >= 0" class="text-xs text-gray-500"
            >{{ $t("devPanel.explain.planning") }}: {{ plan.planningMs.toFixed(2) }} ms</span
          >
          <span v-if="plan.executionMs >= 0" class="text-xs text-gray-500"
            >{{ $t("devPanel.explain.execution") }}: {{ plan.executionMs.toFixed(2) }} ms</span
          >
          <div class="ml-auto flex gap-2">
            <el-button
              v-if="store.canWrite && !plan.analyzed"
              size="small"
              type="warning"
              plain
              @click="run(true)"
              >{{ $t("devPanel.explain.analyze") }}</el-button
            >
            <el-button size="small" @click="copyToClipboard(plan.json)">{{
              $t("devPanel.explain.copyJson")
            }}</el-button>
          </div>
        </div>

        <el-alert
          v-for="warning in plan.warnings"
          :key="warning"
          :title="warning"
          type="info"
          :closable="false"
          show-icon
          class="mb-2"
        />
        <div v-if="plan.hints.length" class="explain-hints mb-3">
          <b class="mb-1 block">💡 {{ $t("devPanel.explain.hints") }}</b>
          <ul class="m-0 pl-5">
            <li v-for="hint in plan.hints" :key="hint">{{ hint }}</li>
          </ul>
        </div>
        <el-empty v-else-if="!plan.hints.length" :image-size="50"
          :description="$t('devPanel.explain.noHints')" />

        <div class="explain-tree">
          <div
            v-for="(node, index) in plan.nodes"
            :key="index"
            class="explain-node"
            :class="{ 'is-scan': node.type === 'Seq Scan' && wide(node) }"
            :style="{ paddingLeft: `${node.depth * 22 + 10}px` }"
          >
            <div class="flex flex-wrap items-baseline gap-x-2">
              <span class="text-gray-400">{{ node.depth ? "↳" : "" }}</span>
              <b>{{ node.type }}</b>
              <span v-if="node.joinType" class="text-xs text-gray-500">({{ node.joinType }})</span>
              <span v-if="node.relation">
                {{ $t("devPanel.explain.on") }} <code>{{ node.relation }}</code>
              </span>
              <span v-if="node.index">
                {{ $t("devPanel.explain.using") }} <code>{{ node.index }}</code>
              </span>
              <span class="ml-auto whitespace-nowrap text-xs text-gray-500">
                {{ $t("devPanel.explain.rows") }}:
                <b>{{ node.actualRows >= 0 ? node.actualRows : node.planRows }}</b>
                <template v-if="node.actualRows >= 0"> / {{ node.planRows }}</template>
                <template v-if="node.loops > 1"> × {{ node.loops }}</template>
                · {{ $t("devPanel.explain.cost") }} {{ node.totalCost.toFixed(0) }}
                <template v-if="node.actualMs >= 0"> · {{ node.actualMs.toFixed(2) }} ms</template>
              </span>
            </div>
            <div v-if="node.filter || node.indexCondition || node.hashCondition"
              class="mt-1 text-xs text-gray-500">
              <div v-if="node.indexCondition">{{ $t("devPanel.explain.indexCond") }}:
                <code>{{ node.indexCondition }}</code></div>
              <div v-if="node.filter">{{ $t("devPanel.explain.filter") }}:
                <code>{{ node.filter }}</code>
                <template v-if="node.removedByFilter > 0">
                  — {{ $t("devPanel.explain.removed", { count: node.removedByFilter }) }}
                </template>
              </div>
              <div v-if="node.hashCondition">{{ $t("devPanel.explain.joinOn") }}:
                <code>{{ node.hashCondition }}</code></div>
            </div>
          </div>
        </div>

        <details class="mt-3">
          <summary class="cursor-pointer text-xs text-gray-500">
            {{ $t("devPanel.explain.statement") }}
          </summary>
          <pre class="explain-sql">{{ plan.statement }}</pre>
        </details>
      </template>
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, watch } from "vue";
import { useDevApi } from "../dev.api";
import { useDevStore } from "../dev.store";
import { IDevExplainReq, IDevPlan, IDevPlanNode } from "../dev.types";
import { copyToClipboard, devErrorMessage } from "../dev.utils";

const props = defineProps<{ modelValue: boolean; request: IDevExplainReq | null }>();
defineEmits<{ (e: "update:modelValue", value: boolean): void }>();

const store = useDevStore();
const { EXPLAIN } = useDevApi();
const plan = ref<IDevPlan | null>(null);
const loading = ref(false);
const errorText = ref("");

// Katta jadvalni to'liq o'qiyotgan qadam qizil bilan belgilanadi
const wide = (node: IDevPlanNode) => Math.max(node.planRows, node.actualRows) >= 1000;

const run = async (analyze = false) => {
  if (!props.request) return;
  loading.value = true;
  errorText.value = "";
  try {
    plan.value = (await EXPLAIN({ ...props.request, analyze })).data;
  } catch (error) {
    errorText.value = await devErrorMessage(error);
  } finally {
    loading.value = false;
  }
};

watch(
  () => [props.modelValue, props.request] as const,
  ([open]) => {
    if (open) {
      plan.value = null;
      run(false);
    }
  },
  { immediate: true },
);
</script>

<style scoped>
.explain-hints {
  padding: 10px 14px;
  border-radius: 10px;
  border: 1px solid rgba(230, 162, 60, 0.45);
  background: rgba(230, 162, 60, 0.08);
  font-size: 13px;
  line-height: 1.5;
}

.explain-tree {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  overflow: hidden;
}

.explain-node {
  padding: 8px 10px;
  font-size: 13px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.explain-node:last-child {
  border-bottom: none;
}

.explain-node.is-scan {
  background: rgba(209, 67, 67, 0.08);
}

.explain-node code,
.explain-sql {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
}

.explain-sql {
  max-height: 240px;
  overflow: auto;
  padding: 10px;
  white-space: pre-wrap;
  word-break: break-word;
  border-radius: 8px;
  background: var(--el-fill-color-light);
}
</style>
