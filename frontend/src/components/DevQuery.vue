<template>
  <div>
    <p class="m-0 mb-3 text-sm text-gray-500 dark:text-gray-400">
      {{ $t("devPanel.query.hint") }}
    </p>

    <DevSqlEditor
      v-model="query"
      :schema="schema"
      placeholder="SELECT * FROM ... LIMIT 100"
      @run="run"
    />

    <div class="mt-3 flex flex-wrap items-center gap-3">
      <el-button
        type="primary"
        :loading="loading"
        :disabled="!query.trim()"
        @click="run"
      >
        {{ $t("devPanel.query.run") }}
      </el-button>
      <el-button
        :icon="Download"
        :loading="exporting"
        :disabled="!rows || loading"
        @click="exportExcel"
      >
        {{ $t("devPanel.query.exportExcel") }}
      </el-button>
      <el-button
        v-if="store.canUpdateQuery"
        type="danger"
        plain
        :icon="EditPen"
        :loading="updating"
        :disabled="!query.trim() || loading"
        @click="runUpdate"
      >
        {{ $t("devPanel.query.runUpdate") }}
      </el-button>
      <span class="hidden text-xs text-gray-400 sm:inline"
        >Ctrl + Enter · Ctrl + Space</span
      >
      <span
        v-if="rows"
        class="ml-auto text-sm text-gray-500 dark:text-gray-400"
      >
        {{ $t("devPanel.query.rows", { count: rows.length }) }} ·
        {{ elapsed }} ms
      </span>
    </div>

    <details
      v-if="store.canUpdateQuery"
      class="mt-3 text-xs text-gray-500 dark:text-gray-400"
    >
      <summary class="cursor-pointer select-none">
        {{ $t("devPanel.query.updateRulesTitle") }}
      </summary>
      <ul class="m-0 mt-2 list-disc space-y-1 pl-5">
        <li v-for="rule in UPDATE_RULES" :key="rule">
          {{ $t("devPanel.query.updateRules." + rule) }}
        </li>
      </ul>
    </details>

    <el-alert
      v-if="errorText"
      :title="errorText"
      type="error"
      show-icon
      :closable="false"
      class="mt-4"
    />

    <el-table
      v-if="rows"
      :data="rows"
      border
      stripe
      max-height="520"
      class="mt-4"
    >
      <el-table-column
        v-for="column in columns"
        :key="column"
        :label="column"
        min-width="140"
        show-overflow-tooltip
      >
        <template #default="{ row }">
          <span
            class="font-mono text-xs"
            :class="{ 'text-gray-400': row[column] == null }"
          >
            {{ formatCell(row[column]) }}
          </span>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, ref, shallowRef } from "vue";
import { useI18n } from "vue-i18n";
import axios from "axios";
import { dayjs, ElMessage, ElMessageBox } from "element-plus";
import { Download, EditPen } from "@element-plus/icons-vue";
import { SQLNamespace } from "@codemirror/lang-sql";
import { useDevApi } from "../dev.api";
import { useDevStore } from "../dev.store";
import { TDevQueryRow } from "../dev.types";
import { devErrorMessage } from "../dev.utils";
import DevSqlEditor from "./DevSqlEditor.vue";

const { RUN_QUERY, RUN_QUERY_EXCEL, RUN_QUERY_UPDATE, GET_QUERY_SCHEMA } =
  useDevApi();
const { t } = useI18n();
const store = useDevStore();

// Backend cheklovlari — foydalanuvchi 400 olishdan oldin bilib qo'yishi uchun
const UPDATE_RULES = [
  "single",
  "where",
  "returning",
  "limit",
  "tables",
  "timeout",
] as const;

const query = ref("");
const rows = ref<TDevQueryRow[] | null>(null);
const loading = ref(false);
const updating = ref(false);
const exporting = ref(false);
const errorText = ref("");
const elapsed = ref(0);
const schema = shallowRef<SQLNamespace>();
// Excel ekrandagi natijaga mos bo'lishi uchun oxirgi bajarilgan so'rov olinadi, editordagi emas
const executedQuery = ref("");

const columns = computed(() =>
  rows.value?.length ? Object.keys(rows.value[0]) : [],
);

const formatCell = (value: unknown) => {
  if (value == null) return "NULL";
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
};

const run = async () => {
  if (!query.value.trim() || loading.value) return;
  loading.value = true;
  errorText.value = "";
  const startedAt = performance.now();
  try {
    const { data } = await RUN_QUERY(query.value);
    rows.value = data;
    executedQuery.value = query.value;
    elapsed.value = Math.round(performance.now() - startedAt);
  } catch (error) {
    rows.value = null;
    errorText.value = await devErrorMessage(error);
  } finally {
    loading.value = false;
  }
};

const exportExcel = async () => {
  if (!executedQuery.value || exporting.value) return;
  exporting.value = true;
  errorText.value = "";
  try {
    const { data } = await RUN_QUERY_EXCEL(executedQuery.value);
    const url = URL.createObjectURL(data);
    const link = document.createElement("a");
    link.href = url;
    link.download = `query-${dayjs().format("YYYYMMDD-HHmmss")}.xlsx`;
    link.click();
    URL.revokeObjectURL(url);
  } catch (error) {
    errorText.value = await devErrorMessage(error);
  } finally {
    exporting.value = false;
  }
};

// Natija jadvali tegilmaydi: UPDATE qator qaytarmaydi, oldingi SELECT bilan solishtirish qulay
const runUpdate = async () => {
  const sql = query.value.trim();
  if (!sql || updating.value) return;
  try {
    await ElMessageBox.confirm(
      h("div", [
        h("p", { class: "m-0 mb-2" }, t("devPanel.query.updateConfirm")),
        h(
          "pre",
          {
            class:
              "m-0 max-h-60 overflow-auto whitespace-pre-wrap break-all rounded bg-gray-100 p-2 font-mono text-xs dark:bg-gray-800",
          },
          sql,
        ),
      ]),
      t("devPanel.query.runUpdate"),
      {
        type: "warning",
        confirmButtonText: t("devPanel.query.runUpdate"),
        cancelButtonText: t("actions.cancel"),
        confirmButtonClass: "el-button--danger",
      },
    );
  } catch {
    return;
  }
  updating.value = true;
  errorText.value = "";
  try {
    const { data } = await RUN_QUERY_UPDATE(sql);
    ElMessage.success(
      t("devPanel.query.updatedRows", { count: data.updatedRows }),
    );
  } catch (error) {
    errorText.value =
      axios.isAxiosError(error) && error.response?.status === 403
        ? t("devPanel.query.noWriteAccess")
        : await devErrorMessage(error);
  } finally {
    updating.value = false;
  }
};

// Sxema bo'lmasa ham editor ishlayveradi — faqat SQL kalit so'zlari taklif qilinadi
const loadSchema = async () => {
  try {
    const { data } = await GET_QUERY_SCHEMA();
    const namespace: Record<string, Record<string, string[]>> = {};
    for (const { schema: name, table, columns } of data) {
      (namespace[name] ??= {})[table] = columns;
    }
    schema.value = namespace;
  } catch {
    schema.value = undefined;
  }
};

onMounted(loadSchema);
</script>
