<template>
  <el-dialog
    :model-value="modelValue"
    width="min(1200px, 96vw)"
    top="4vh"
    append-to-body
    :title="$t('devPanel.waterfall.title')"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-loading="loading" class="min-h-[200px]">
      <el-alert
        v-if="errorText"
        :title="errorText"
        type="warning"
        :closable="false"
        show-icon
      />
      <template v-if="trace">
        <!-- Qisqacha: vaqt qayerga ketdi -->
        <div class="mb-3 flex flex-wrap items-center gap-2">
          <el-tag :type="reasonTag" effect="dark">{{ trace.reason }}</el-tag>
          <code class="text-sm">{{ trace.endpoint }}</code>
          <span class="text-xs text-gray-500">{{ trace.path }}</span>
          <el-tag size="small" :type="trace.status >= 500 ? 'danger' : 'info'"
            >HTTP {{ trace.status }}</el-tag
          >
        </div>
        <div class="wf-split mb-2">
          <div
            class="wf-split__db"
            :style="{ width: `${share(trace.sqlMs)}%` }"
            :title="`DB ${formatMs(trace.sqlMs)}`"
          />
          <div
            class="wf-split__ext"
            :style="{ width: `${share(trace.externalMs)}%` }"
            :title="`HTTP ${formatMs(trace.externalMs)}`"
          />
          <div
            class="wf-split__code"
            :style="{ width: `${share(codeMs)}%` }"
            :title="`Code ${formatMs(codeMs)}`"
          />
        </div>
        <div class="wf-facts mb-4">
          <span><b>{{ formatMs(trace.totalMs) }}</b> {{ $t("devPanel.waterfall.total") }}</span>
          <span><i class="dot db" /> DB <b>{{ formatMs(trace.sqlMs) }}</b> ({{ trace.sqlCount }} SQL)</span>
          <span><i class="dot ext" /> HTTP <b>{{ formatMs(trace.externalMs) }}</b></span>
          <span><i class="dot code" /> {{ $t("devPanel.waterfall.code") }} <b>{{ formatMs(codeMs) }}</b></span>
          <span>{{ $t("devPanel.waterfall.rows") }}: <b>{{ trace.rows }}</b></span>
          <span>{{ $t("devPanel.waterfall.size") }}: <b>{{ formatBytes(trace.responseBytes) }}</b></span>
          <span v-if="trace.user">👤 {{ trace.user }}</span>
          <span v-if="trace.tenant">🏢 {{ trace.tenant }}</span>
          <span>🌐 {{ trace.clientIp }}</span>
          <span>{{ formatTime(trace.time) }}</span>
        </div>

        <!-- Takrorlangan so'rovlar (N+1) -->
        <el-alert
          v-if="trace.repeated.length"
          type="warning"
          :closable="false"
          class="mb-4"
          :title="$t('devPanel.waterfall.repeated', { wasted: wasted })"
        >
          <div
            v-for="(item, index) in trace.repeated.slice(0, 5)"
            :key="index"
            class="mt-1 text-xs"
          >
            <b>{{ item.count }}×</b> ({{ formatMs(item.totalMs) }})
            <code class="break-all">{{ item.statement.slice(0, 220) }}</code>
          </div>
        </el-alert>

        <!-- Vaqt chizig'i: har bir SQL va tashqi chaqiruv -->
        <div class="wf-list">
          <div class="wf-row wf-row--head">
            <span>{{ $t("devPanel.waterfall.step") }}</span>
            <span>{{ $t("devPanel.waterfall.timeline") }}</span>
            <span class="text-right">ms</span>
            <span class="text-right">{{ $t("devPanel.waterfall.rows") }}</span>
          </div>
          <div
            v-for="(span, index) in trace.spans"
            :key="index"
            class="wf-row"
            :class="{ 'is-slow': span.durationMs >= slowSpan, 'is-error': span.error }"
            @click="open = open === index ? -1 : index"
          >
            <span class="wf-text">
              <el-tag size="small" :type="span.kind === 'SQL' ? 'primary' : 'warning'">{{
                span.kind
              }}</el-tag>
              <code>{{ span.text }}</code>
            </span>
            <span class="wf-track">
              <i
                :class="span.kind === 'SQL' ? 'db' : 'ext'"
                :style="{
                  left: `${(span.startMs / scale) * 100}%`,
                  width: `${Math.max(0.4, (span.durationMs / scale) * 100)}%`,
                }"
              />
            </span>
            <span class="text-right font-mono">{{ span.durationMs.toFixed(1) }}</span>
            <span class="text-right font-mono">{{ span.kind === "SQL" ? span.rows : span.status || "—" }}</span>
            <pre v-if="open === index" class="wf-full">{{ span.text }}{{ span.error ? `\n\n⚠ ${span.error}` : "" }}</pre>
          </div>
          <p v-if="!trace.spans.length" class="m-0 p-3 text-sm text-gray-500">
            {{ $t("devPanel.waterfall.noSpans") }}
          </p>
          <p v-if="trace.truncated" class="m-0 p-3 text-xs text-gray-500">
            {{ $t("devPanel.waterfall.truncated") }}
          </p>
        </div>
      </template>
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useDevApi } from "../dev.api";
import { IDevTrace } from "../dev.types";
import { devErrorMessage, formatBytes, formatMs, formatTime } from "../dev.utils";

const props = defineProps<{ modelValue: boolean; traceId: string | null }>();
const emit = defineEmits<{ (e: "update:modelValue", value: boolean): void }>();

const { GET_TRACE } = useDevApi();
const trace = ref<IDevTrace | null>(null);
const loading = ref(false);
const errorText = ref("");
const open = ref(-1);

const codeMs = computed(() =>
  trace.value
    ? Math.max(0, trace.value.totalMs - trace.value.sqlMs - trace.value.externalMs)
    : 0,
);
const scale = computed(() => Math.max(1, trace.value?.totalMs ?? 1));
const share = (ms: number) => Math.min(100, (ms / scale.value) * 100);
// Umumiy vaqtning 10% idan uzun qadam — ko'zga tashlanadi
const slowSpan = computed(() => Math.max(50, scale.value * 0.1));
const wasted = computed(() =>
  (trace.value?.repeated ?? []).reduce((sum, r) => sum + r.count - 1, 0),
);
const reasonTag = computed(() =>
  trace.value?.reason === "ERROR"
    ? "danger"
    : trace.value?.reason === "SAMPLE"
      ? "info"
      : "warning",
);

watch(
  () => [props.modelValue, props.traceId],
  async ([visible, id]) => {
    if (!visible || !id) return;
    loading.value = true;
    errorText.value = "";
    trace.value = null;
    open.value = -1;
    try {
      trace.value = (await GET_TRACE(id as string)).data;
    } catch (error) {
      errorText.value = await devErrorMessage(error);
    } finally {
      loading.value = false;
    }
  },
  { immediate: true },
);
</script>

<style scoped>
.wf-split {
  display: flex;
  height: 10px;
  border-radius: 5px;
  overflow: hidden;
  background: var(--el-fill-color);
}

.wf-split__db,
.dot.db,
.wf-track i.db {
  background: #106297;
}

.wf-split__ext,
.dot.ext,
.wf-track i.ext {
  background: #e6a23c;
}

.wf-split__code,
.dot.code {
  background: #7c5cff;
}

.wf-facts {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 16px;
  font-size: 13px;
  color: var(--el-text-color-regular);
}

.dot {
  display: inline-block;
  width: 9px;
  height: 9px;
  margin-right: 3px;
  border-radius: 2px;
}

.wf-list {
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  max-height: 60vh;
  overflow: auto;
}

.wf-row {
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) minmax(0, 1fr) 70px 60px;
  gap: 10px;
  align-items: center;
  padding: 5px 10px;
  font-size: 12px;
  border-top: 1px solid var(--el-border-color-lighter);
  cursor: pointer;
}

.wf-row--head {
  position: sticky;
  top: 0;
  z-index: 1;
  border-top: none;
  font-weight: 600;
  color: var(--el-text-color-secondary);
  background: var(--el-bg-color);
  cursor: default;
}

.wf-row.is-slow {
  background: rgba(230, 162, 60, 0.12);
}

.wf-row.is-error {
  background: rgba(209, 67, 67, 0.12);
}

.wf-text {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.wf-text code {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.wf-track {
  position: relative;
  height: 12px;
  background: var(--el-fill-color-lighter);
  border-radius: 3px;
}

.wf-track i {
  position: absolute;
  top: 1px;
  bottom: 1px;
  border-radius: 2px;
}

.wf-full {
  grid-column: 1 / -1;
  margin: 4px 0 2px;
  padding: 8px 10px;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 12px;
  border-radius: 6px;
  background: var(--el-fill-color-light);
}
</style>
