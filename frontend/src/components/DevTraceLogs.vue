<template>
  <el-dialog
    v-model="visible"
    :title="$t('devPanel.audit.logsTitle', { traceId })"
    :width="isMobile ? '100%' : '90%'"
    :fullscreen="isMobile"
    top="4vh"
    append-to-body
    destroy-on-close
    class="dev-preview"
    @open="load"
    @closed="reset"
  >
    <div v-loading="loading" class="min-h-[200px]">
      <el-alert
        v-if="errorText"
        :title="errorText"
        type="error"
        show-icon
        :closable="false"
      />

      <template v-else-if="result">
        <div class="mb-3 flex flex-wrap items-center gap-4">
          <el-checkbox v-model="hideSql">{{
            $t("devPanel.audit.hideSql")
          }}</el-checkbox>
          <span class="text-sm opacity-70">{{
            $t("devPanel.audit.entries", { count: shown.length })
          }}</span>
          <el-button
            size="small"
            class="ml-auto"
            :disabled="!shown.length"
            @click="copy"
            >{{ $t("devPanel.audit.copy") }}</el-button
          >
        </div>

        <el-alert
          v-if="result.truncated"
          :title="
            $t('devPanel.audit.truncated', { count: result.entries.length })
          "
          type="warning"
          show-icon
          :closable="false"
          class="mb-3"
        />

        <el-empty
          v-if="!result.entries.length"
          :description="$t('devPanel.audit.noLogs')"
        />

        <div v-else class="dev-trace">
          <div
            v-for="(entry, index) in shown"
            :key="`${entry.file}:${entry.line}`"
            :style="{ '--i': Math.min(index, 20) }"
            class="dev-trace__entry"
            :class="`is-${levelOf(entry.text)}`"
          >
            <div class="dev-trace__source">
              {{ entry.file }}:{{ entry.line }}
            </div>
            <pre
              v-if="entry.parsed"
              class="dev-trace__text"
            ><span class="dev-trace__time">{{ entry.parsed.time }}</span> <span class="dev-trace__level">{{ entry.parsed.level }}</span> <span class="dev-trace__id">{{ entry.parsed.traceId }}</span> <span class="dev-trace__thread">{{ entry.parsed.thread }}</span> <span class="dev-trace__logger">{{ entry.parsed.logger }}</span>
<template v-for="(part, i) in entry.parsed.message" :key="i"><span v-if="part.trace" class="dev-trace__id">{{ part.text }}</span><template v-else>{{ part.text }}</template></template></pre>
            <pre
              v-else
              class="dev-trace__text"
            ><template v-for="(part, i) in entry.plain" :key="i"><span v-if="part.trace" class="dev-trace__id">{{ part.text }}</span><template v-else>{{ part.text }}</template></template></pre>
          </div>
        </div>
      </template>
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { useMediaQuery } from "@vueuse/core";
import { ElMessage } from "element-plus";
import { useI18n } from "vue-i18n";
import moment from "moment";
import { useDevApi } from "../dev.api";
import { IDevLogEntry, IDevLogTrace } from "../dev.types";
import { devErrorMessage } from "../dev.utils";

// occurredAt — audit yozuvidagi vaqt; backend undan ancha oldin o'zgargan log fayllarni o'qimaydi
const props = defineProps<{ traceId: string; occurredAt?: string }>();

const visible = defineModel<boolean>({ required: true });

const { t } = useI18n();
const { GET_TRACE_LOGS } = useDevApi();
const isMobile = useMediaQuery("(max-width: 639px)");

const loading = ref(false);
const errorText = ref("");
const result = ref<IDevLogTrace | null>(null);
const hideSql = ref(true);

const LEVEL = /^\S+ \S+\s+(TRACE|DEBUG|INFO|WARN|ERROR)\b/;

const levelOf = (text: string) =>
  (LEVEL.exec(text)?.[1] ?? "info").toLowerCase();

const isSql = (entry: IDevLogEntry) =>
  entry.text.split("\n", 1)[0].includes("org.hibernate.SQL");

// Spring Boot standart formati: vaqt, daraja, [traceId], pid, ---, [thread], logger : xabar
const LINE =
  /^(\S+ \S+)\s+(TRACE|DEBUG|INFO|WARN|ERROR)\s+\[([^\]]*)\]\s+\d+\s+---\s+\[\s*([^\]]*?)\s*\]\s+(\S+)\s*:\s?([\s\S]*)$/;

type Part = { text: string; trace: boolean };

// Xabar ichida uchragan traceId ham ajralib ko'rinsin — boshqa servisga uzatilgan joylarni topish oson bo'ladi
const markTrace = (text: string): Part[] => {
  const id = props.traceId;
  if (!id || !text.includes(id)) return [{ text, trace: false }];
  return text
    .split(id)
    .flatMap((chunk, i) =>
      i
        ? [
            { text: id, trace: true },
            { text: chunk, trace: false },
          ]
        : [{ text: chunk, trace: false }],
    )
    .filter((part) => part.text);
};

const parse = (text: string) => {
  const match = LINE.exec(text);
  if (!match) return null;
  const [, time, level, traceId, thread, logger, message] = match;
  return { time, level, traceId, thread, logger, message: markTrace(message) };
};

const shown = computed(() =>
  (result.value?.entries ?? [])
    .filter((entry) => !hideSql.value || !isSql(entry))
    .map((entry) => {
      const parsed = parse(entry.text);
      return { ...entry, parsed, plain: parsed ? null : markTrace(entry.text) };
    }),
);

const load = async () => {
  loading.value = true;
  errorText.value = "";
  try {
    const from = props.occurredAt
      ? moment(props.occurredAt).format("YYYY-MM-DDTHH:mm:ss")
      : undefined;
    const { data } = await GET_TRACE_LOGS(props.traceId, from);
    result.value = data;
  } catch (error) {
    errorText.value = await devErrorMessage(error);
  } finally {
    loading.value = false;
  }
};

const copy = async () => {
  try {
    await navigator.clipboard.writeText(
      shown.value.map((entry) => entry.text).join("\n"),
    );
    ElMessage.success(t("devPanel.audit.copied"));
  } catch {
    ElMessage.error(t("devPanel.serverError"));
  }
};

const reset = () => {
  result.value = null;
  errorText.value = "";
};
</script>

<style scoped>
.dev-trace {
  max-height: 72vh;
  overflow: auto;
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  background: var(--el-fill-color-light);
}

@media (max-width: 639px) {
  .dev-trace {
    max-height: calc(100dvh - 160px);
  }
}

.dev-trace__entry {
  padding: 6px 12px;
  border-left: 3px solid transparent;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.dev-trace__entry.is-error {
  border-left-color: var(--el-color-danger);
  background: rgba(245, 108, 108, 0.08);
}

.dev-trace__entry.is-warn {
  border-left-color: var(--el-color-warning);
  background: rgba(230, 162, 60, 0.08);
}

.dev-trace__entry.is-debug,
.dev-trace__entry.is-trace {
  opacity: 0.75;
}

.dev-trace__source {
  margin-bottom: 2px;
  color: var(--el-text-color-secondary);
  font-size: 11px;
}

.dev-trace__text {
  margin: 0;
  color: var(--el-text-color-primary);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
}

.dev-trace__time,
.dev-trace__thread {
  color: var(--el-text-color-secondary);
}

.dev-trace__level {
  font-weight: 600;
  color: var(--el-color-success);
}

.is-debug .dev-trace__level,
.is-trace .dev-trace__level {
  color: var(--el-text-color-secondary);
}

.is-warn .dev-trace__level {
  color: var(--el-color-warning);
}

.is-error .dev-trace__level {
  color: var(--el-color-danger);
}

.dev-trace__id {
  padding: 0 4px;
  border-radius: 3px;
  color: #7c3aed;
  background: rgba(124, 58, 237, 0.12);
  font-weight: 600;
}

:global(.dark) .dev-trace__id {
  color: #c4b5fd;
  background: rgba(167, 139, 250, 0.18);
}

.dev-trace__logger {
  color: var(--el-color-primary);
}

/* Yozuvlar yuqoridan pastga oqib chiqadi; 20-dan keyingilari kutmasdan */
.dev-trace__entry {
  animation: dev-trace-in 0.35s ease both;
  animation-delay: calc(var(--i, 0) * 0.025s);
  transition: background 0.15s ease;
}

.dev-trace__entry:hover {
  background: var(--el-fill-color);
}

@keyframes dev-trace-in {
  from {
    opacity: 0;
    transform: translateX(-8px);
  }
}

@media (prefers-reduced-motion: reduce) {
  .dev-trace__entry {
    animation: none;
  }
}
</style>
