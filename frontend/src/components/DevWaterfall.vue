<template>
  <el-dialog
    :model-value="modelValue"
    width="min(1240px, 96vw)"
    top="4vh"
    append-to-body
    class="wf-dialog"
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
        <!-- So'rov: nima, qayerga, natija -->
        <div class="wf-head">
          <el-tag :type="reasonTag" effect="dark" round>{{ trace.reason }}</el-tag>
          <code class="wf-head__endpoint">{{ trace.endpoint }}</code>
          <el-tag
            size="small"
            round
            :type="trace.status >= 500 ? 'danger' : trace.status >= 400 ? 'warning' : 'success'"
            >HTTP {{ trace.status }}</el-tag
          >
          <span class="wf-head__path" :title="trace.path">{{ trace.path }}</span>
        </div>

        <!-- Vaqt taqsimoti: kursor bilan har bo'lakni ko'rish -->
        <div class="wf-split">
          <el-tooltip
            v-for="part in parts"
            :key="part.key"
            placement="top"
            :show-after="80"
            :content="`${part.label}: ${formatMs(part.ms)} · ${part.percent}%`"
          >
            <div
              class="wf-split__part"
              :class="[`is-${part.key}`, { 'is-dim': hoverKind && hoverKind !== part.key }]"
              :style="{ width: `${part.percent}%` }"
            />
          </el-tooltip>
        </div>

        <!-- Asosiy raqamlar -->
        <div class="wf-tiles">
          <div class="wf-tile is-total">
            <span>{{ $t("devPanel.waterfall.total") }}</span>
            <b :class="durationClass(trace.totalMs, 1000)">{{ formatMs(trace.totalMs) }}</b>
          </div>
          <div
            v-for="part in parts"
            :key="part.key"
            class="wf-tile"
            :class="`is-${part.key}`"
            @mouseenter="hoverKind = part.key"
            @mouseleave="hoverKind = null"
          >
            <span><i class="wf-dot" :class="`is-${part.key}`" />{{ part.label }}</span>
            <b>{{ formatMs(part.ms) }}</b>
            <small>{{ part.percent }}%{{ part.extra ? ` · ${part.extra}` : "" }}</small>
          </div>
          <div class="wf-tile">
            <span>{{ $t("devPanel.waterfall.rows") }}</span>
            <b>{{ trace.rows.toLocaleString() }}</b>
          </div>
          <div class="wf-tile">
            <span>{{ $t("devPanel.waterfall.size") }}</span>
            <b>{{ formatBytes(trace.responseBytes) }}</b>
          </div>
        </div>

        <div class="wf-meta">
          <span v-if="trace.user" title="User">👤 {{ trace.user }}</span>
          <span v-if="trace.tenant" title="Organization">🏢 {{ trace.tenant }}</span>
          <span title="Client IP">🌐 {{ trace.clientIp }}</span>
          <span>🕒 {{ formatTime(trace.time) }}</span>
        </div>

        <!-- Takrorlangan so'rovlar (N+1) -->
        <el-alert
          v-if="trace.repeated.length"
          type="warning"
          :closable="false"
          class="mb-4"
          :title="$t('devPanel.waterfall.repeated', { wasted })"
        >
          <div v-for="(item, index) in trace.repeated.slice(0, 5)" :key="index" class="mt-1 text-xs">
            <b>{{ item.count }}×</b> ({{ formatMs(item.totalMs) }})
            <code class="break-all">{{ item.statement.slice(0, 220) }}</code>
          </div>
        </el-alert>

        <!-- Vaqt chizig'i -->
        <div class="wf-list">
          <div class="wf-row wf-row--head">
            <span>{{ $t("devPanel.waterfall.step") }}</span>
            <span class="wf-scale">
              <i v-for="tick in ticks" :key="tick.left" :style="{ left: `${tick.left}%` }">{{
                tick.label
              }}</i>
            </span>
            <span class="text-right">ms</span>
            <span class="text-right">%</span>
            <span class="text-right">{{ $t("devPanel.waterfall.rows") }}</span>
          </div>

          <template v-for="(span, index) in trace.spans" :key="index">
            <el-tooltip placement="top-start" :show-after="250" :disabled="open === index" popper-class="wf-tip">
              <template #content>
                <div class="wf-tip__head">
                  <b>{{ span.kind }}</b> · {{ span.durationMs.toFixed(2) }} ms ·
                  {{ percentOf(span.durationMs) }}% ·
                  {{ $t("devPanel.waterfall.at") }} +{{ span.startMs.toFixed(1) }} ms
                  <template v-if="span.kind === 'SQL'"> · {{ span.rows }} {{ $t("devPanel.waterfall.rows").toLowerCase() }}</template>
                  <template v-else-if="span.status"> · HTTP {{ span.status }}</template>
                </div>
                <div class="wf-tip__text">{{ span.text.slice(0, 600) }}</div>
                <div v-if="span.error" class="wf-tip__error">⚠ {{ span.error }}</div>
              </template>
              <div
                class="wf-row"
                :class="{
                  'is-hover': hover === index,
                  'is-open': open === index,
                  'is-slow': span.durationMs >= slowSpan,
                  'is-error': span.error,
                  'is-dim': hoverKind && hoverKind !== (span.kind === 'SQL' ? 'db' : 'ext'),
                }"
                :style="{ '--i': Math.min(index, 30) }"
                @mouseenter="hover = index"
                @mouseleave="hover = -1"
                @click="open = open === index ? -1 : index"
              >
                <span class="wf-text">
                  <span class="wf-kind" :class="span.kind === 'SQL' ? 'is-db' : 'is-ext'">{{ span.kind }}</span>
                  <code>{{ span.text }}</code>
                </span>
                <span class="wf-track">
                  <i v-for="tick in ticks" :key="tick.left" class="wf-grid" :style="{ left: `${tick.left}%` }" />
                  <i
                    class="wf-bar"
                    :class="[span.kind === 'SQL' ? 'is-db' : 'is-ext', { 'is-error': span.error }]"
                    :style="{
                      left: `${(span.startMs / scale) * 100}%`,
                      width: `${Math.max(0.5, (span.durationMs / scale) * 100)}%`,
                    }"
                  />
                </span>
                <span class="text-right font-mono" :class="durationClass(span.durationMs, slowSpan)">{{
                  span.durationMs.toFixed(1)
                }}</span>
                <span class="text-right font-mono text-gray-400">{{ percentOf(span.durationMs) }}</span>
                <span class="text-right font-mono">{{
                  span.kind === "SQL" ? span.rows : span.status || "—"
                }}</span>
              </div>
            </el-tooltip>

            <!-- To'liq matn: SQL ranglangan, nusxa olish mumkin -->
            <div v-if="open === index" class="wf-full">
              <div class="wf-full__bar">
                <span>{{ span.kind }} · {{ span.durationMs.toFixed(2) }} ms</span>
                <el-button size="small" text @click.stop="copy(span.text)">{{ $t("devPanel.waterfall.copy") }}</el-button>
              </div>
              <pre v-if="span.kind === 'SQL'" v-html="highlight(span.text)" />
              <pre v-else>{{ span.text }}</pre>
              <div v-if="span.error" class="wf-full__error">⚠ {{ span.error }}</div>
            </div>
          </template>

          <p v-if="!trace.spans.length" class="m-0 p-4 text-center text-sm text-gray-500">
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
import { useI18n } from "vue-i18n";
import { useDevApi } from "../dev.api";
import { IDevTrace } from "../dev.types";
import { copyToClipboard, devErrorMessage, formatBytes, formatMs, formatTime } from "../dev.utils";

const props = defineProps<{ modelValue: boolean; traceId: string | null }>();
const emit = defineEmits<{ (e: "update:modelValue", value: boolean): void }>();

const { t } = useI18n();
const { GET_TRACE } = useDevApi();
const trace = ref<IDevTrace | null>(null);
const loading = ref(false);
const errorText = ref("");
const open = ref(-1);
const hover = ref(-1);
// Plitka ustida turganda shu turdagi qadamlar ajralib ko'rinadi
const hoverKind = ref<string | null>(null);

const SQL_WORDS =
  /\b(select|from|where|and|or|not|in|is|null|as|on|join|left|right|inner|outer|cross|group|by|order|having|limit|offset|union|all|distinct|insert|into|values|update|set|delete|case|when|then|else|end|exists|between|like|ilike|asc|desc|count|sum|avg|min|max|coalesce|filter|with|returning)\b/gi;

const scale = computed(() => Math.max(1, trace.value?.totalMs ?? 1));
const codeMs = computed(() =>
  trace.value ? Math.max(0, trace.value.totalMs - trace.value.sqlMs - trace.value.externalMs) : 0,
);
const percentOf = (ms: number) => Math.round((ms / scale.value) * 1000) / 10;
// Umumiy vaqtning 10% idan (kamida 50 ms) uzun qadam — ko'zga tashlanadi
const slowSpan = computed(() => Math.max(50, scale.value * 0.1));
const wasted = computed(() => (trace.value?.repeated ?? []).reduce((sum, r) => sum + r.count - 1, 0));

const parts = computed(() => {
  if (!trace.value) return [];
  const sqlCount = trace.value.sqlCount;
  const httpCount = trace.value.spans.filter((s) => s.kind === "HTTP").length;
  return [
    { key: "db", label: "DB", ms: trace.value.sqlMs, percent: percentOf(trace.value.sqlMs), extra: `${sqlCount} SQL` },
    { key: "ext", label: "HTTP", ms: trace.value.externalMs, percent: percentOf(trace.value.externalMs), extra: httpCount ? `${httpCount}×` : "" },
    { key: "code", label: t("devPanel.waterfall.code"), ms: codeMs.value, percent: percentOf(codeMs.value), extra: "" },
  ];
});

// Vaqt shkalasi: 0, 25, 50, 75, 100% va ularning ms qiymati
const ticks = computed(() =>
  [0, 25, 50, 75, 100].map((left) => ({ left, label: formatMs((scale.value * left) / 100) })),
);

const reasonTag = computed(() =>
  trace.value?.reason === "ERROR" ? "danger" : trace.value?.reason === "SAMPLE" ? "info" : "warning",
);

const durationClass = (ms: number, slow: number) =>
  ms >= slow * 2 ? "is-red" : ms >= slow ? "is-orange" : "";

const escapeHtml = (text: string) =>
  text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

// SQL kalit so'zlari, satrlar va raqamlarni ranglash (avval HTML xavfsiz qilinadi)
const highlight = (sql: string) =>
  escapeHtml(sql)
    .replace(/('[^']*')/g, '<span class="sql-str">$1</span>')
    .replace(SQL_WORDS, (word) => `<span class="sql-kw">${word.toUpperCase()}</span>`)
    .replace(/(\?)/g, '<span class="sql-param">?</span>');

const copy = (text: string) => copyToClipboard(text);

watch(
  () => [props.modelValue, props.traceId],
  async ([visible, id]) => {
    if (!visible || !id) return;
    loading.value = true;
    errorText.value = "";
    trace.value = null;
    open.value = -1;
    hover.value = -1;
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
.wf-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 14px;
}

.wf-head__endpoint {
  font-size: 14px;
  font-weight: 600;
}

.wf-head__path {
  max-width: 100%;
  overflow: hidden;
  font-size: 12px;
  white-space: nowrap;
  text-overflow: ellipsis;
  color: var(--el-text-color-secondary);
}

/* --- vaqt taqsimoti --- */
.wf-split {
  display: flex;
  height: 12px;
  margin-bottom: 14px;
  overflow: hidden;
  border-radius: 6px;
  background: var(--el-fill-color);
}

.wf-split__part {
  height: 100%;
  transition:
    opacity 0.2s,
    filter 0.2s;
  animation: grow 0.6s cubic-bezier(0.22, 1, 0.36, 1) both;
  transform-origin: left;
}

.wf-split__part:hover {
  filter: brightness(1.2);
}

.wf-split__part.is-dim {
  opacity: 0.25;
}

.is-db.wf-split__part,
.wf-dot.is-db {
  background: linear-gradient(90deg, #0d4f7a, #1677b3);
}

.is-ext.wf-split__part,
.wf-dot.is-ext {
  background: linear-gradient(90deg, #c7851f, #f0b04a);
}

.is-code.wf-split__part,
.wf-dot.is-code {
  background: linear-gradient(90deg, #6a49f0, #9a80ff);
}

/* --- plitkalar --- */
.wf-tiles {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(130px, 1fr));
  gap: 10px;
  margin-bottom: 10px;
}

.wf-tile {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 12px;
  background: var(--el-fill-color-lighter);
  transition:
    transform 0.2s cubic-bezier(0.22, 1, 0.36, 1),
    box-shadow 0.2s,
    border-color 0.2s;
}

.wf-tile:hover {
  transform: translateY(-2px);
  box-shadow: 0 10px 22px -16px rgba(7, 30, 51, 0.55);
}

.wf-tile.is-db:hover {
  border-color: #1677b3;
}

.wf-tile.is-ext:hover {
  border-color: #f0b04a;
}

.wf-tile.is-code:hover {
  border-color: #9a80ff;
}

.wf-tile span {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.wf-tile b {
  font-size: 20px;
  font-variant-numeric: tabular-nums;
  color: var(--el-text-color-primary);
}

.wf-tile small {
  font-size: 11px;
  color: var(--el-text-color-placeholder);
}

.wf-dot {
  display: inline-block;
  width: 9px;
  height: 9px;
  border-radius: 3px;
}

.wf-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 18px;
  margin-bottom: 14px;
  font-size: 13px;
  color: var(--el-text-color-regular);
}

.is-orange {
  color: #d98a1c !important;
  font-weight: 600;
}

.is-red {
  color: #d14343 !important;
  font-weight: 700;
}

/* --- vaqt chizig'i --- */
.wf-list {
  max-height: 58vh;
  overflow: auto;
  border: 1px solid var(--el-border-color);
  border-radius: 12px;
}

.wf-row {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(0, 1fr) 70px 54px 56px;
  gap: 12px;
  align-items: center;
  padding: 7px 12px;
  font-size: 12px;
  border-top: 1px solid var(--el-border-color-lighter);
  cursor: pointer;
  transition:
    background 0.15s,
    opacity 0.2s;
  animation: row-in 0.35s cubic-bezier(0.22, 1, 0.36, 1) both;
  animation-delay: calc(var(--i, 0) * 18ms);
}

.wf-row--head {
  position: sticky;
  top: 0;
  z-index: 2;
  padding-top: 9px;
  padding-bottom: 9px;
  border-top: none;
  font-weight: 600;
  color: var(--el-text-color-secondary);
  background: var(--el-bg-color);
  cursor: default;
  animation: none;
}

.wf-row.is-hover,
.wf-row:not(.wf-row--head):hover {
  background: var(--el-fill-color-light);
}

.wf-row.is-open {
  background: var(--el-color-primary-light-9);
}

.wf-row.is-slow {
  box-shadow: inset 3px 0 0 #e6a23c;
}

.wf-row.is-error {
  box-shadow: inset 3px 0 0 #d14343;
  background: rgba(209, 67, 67, 0.07);
}

.wf-row.is-dim {
  opacity: 0.35;
}

.wf-text {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.wf-text code {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.wf-kind {
  flex-shrink: 0;
  padding: 1px 7px;
  font-size: 11px;
  font-weight: 700;
  border-radius: 6px;
}

.wf-kind.is-db {
  color: #1677b3;
  background: rgba(22, 119, 179, 0.12);
}

.wf-kind.is-ext {
  color: #c7851f;
  background: rgba(240, 176, 74, 0.16);
}

.wf-scale {
  position: relative;
  height: 14px;
}

.wf-scale i {
  position: absolute;
  top: 0;
  font-size: 10px;
  font-style: normal;
  font-weight: 500;
  white-space: nowrap;
  transform: translateX(-50%);
  color: var(--el-text-color-placeholder);
}

.wf-scale i:first-child {
  transform: none;
}

.wf-scale i:last-child {
  transform: translateX(-100%);
}

.wf-track {
  position: relative;
  height: 14px;
  border-radius: 4px;
  background: var(--el-fill-color-lighter);
}

.wf-grid {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 1px;
  background: var(--el-border-color-lighter);
}

.wf-bar {
  position: absolute;
  top: 2px;
  bottom: 2px;
  min-width: 3px;
  border-radius: 3px;
  transition:
    transform 0.15s,
    box-shadow 0.15s;
  animation: grow 0.5s cubic-bezier(0.22, 1, 0.36, 1) both;
  transform-origin: left;
}

.wf-bar.is-db {
  background: linear-gradient(90deg, #0d4f7a, #1f8fd4);
}

.wf-bar.is-ext {
  background: linear-gradient(90deg, #c7851f, #f7c06a);
}

.wf-bar.is-error {
  background: linear-gradient(90deg, #a92e2e, #e35d5d);
}

.wf-row.is-hover .wf-bar,
.wf-row:hover .wf-bar {
  transform: scaleY(1.35);
  box-shadow: 0 0 0 2px var(--el-bg-color), 0 0 10px rgba(22, 119, 179, 0.55);
}

/* --- to'liq matn --- */
.wf-full {
  margin: 0 12px 10px;
  overflow: hidden;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  background: var(--el-fill-color-lighter);
  animation: row-in 0.25s ease both;
}

.wf-full__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 6px 4px 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.wf-full pre {
  max-height: 300px;
  margin: 0;
  padding: 10px 12px;
  overflow: auto;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.wf-full__error {
  padding: 8px 12px;
  font-size: 12px;
  color: #d14343;
  border-top: 1px solid var(--el-border-color-lighter);
}

.wf-full :deep(.sql-kw) {
  font-weight: 700;
  color: #1677b3;
}

.wf-full :deep(.sql-str) {
  color: #2f9e5b;
}

.wf-full :deep(.sql-param) {
  font-weight: 700;
  color: #d98a1c;
}

@keyframes grow {
  from {
    transform: scaleX(0);
  }
}

@keyframes row-in {
  from {
    opacity: 0;
    transform: translateY(4px);
  }
}

@media (prefers-reduced-motion: reduce) {
  .wf-row,
  .wf-bar,
  .wf-split__part,
  .wf-full {
    animation: none;
  }
}
</style>

<style>
/* Tooltip — dialog'dan tashqarida chiziladi, shuning uchun scoped emas */
.wf-tip {
  max-width: 560px;
}

.wf-tip__head {
  margin-bottom: 4px;
  font-size: 12px;
  opacity: 0.85;
}

.wf-tip__text {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}

.wf-tip__error {
  margin-top: 4px;
  color: #ff9b9b;
}
</style>
