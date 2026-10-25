<template>
  <div>
    <p class="mb-3 mt-0 text-sm text-gray-500">{{ $t("devPanel.data.hint") }}</p>
    <el-alert v-if="errorText" :title="errorText" type="error" show-icon :closable="false" class="mb-4" />

    <div class="mb-3 flex flex-wrap items-center gap-2">
      <el-select v-model="selected" filterable class="!w-80" :placeholder="$t('devPanel.data.pickTable')"
        @change="choose">
        <el-option v-for="table in tables" :key="`${table.schema}.${table.table}`"
          :value="`${table.schema}.${table.table}`" :label="`${table.schema}.${table.table}`" />
      </el-select>
      <el-button v-if="current" size="small" @click="addFilter">+ {{ $t("devPanel.data.filter") }}</el-button>
      <span v-if="page" class="ml-auto text-sm text-gray-500">{{ $t("devPanel.data.total", { count: page.total }) }}</span>
    </div>

    <div v-for="(item, index) in filters" :key="index" class="mb-2 flex flex-wrap items-center gap-2">
      <el-select v-model="item.column" size="small" class="!w-52" filterable>
        <el-option v-for="column in current?.columns ?? []" :key="column" :value="column" :label="column" />
      </el-select>
      <el-select v-model="item.op" size="small" class="!w-40">
        <el-option v-for="op in OPS" :key="op" :value="op" :label="$t(`devPanel.data.ops.${op}`)" />
      </el-select>
      <el-input v-if="item.op !== 'null' && item.op !== 'notnull'" v-model="item.value" size="small"
        class="!w-56" @keydown.enter="search" />
      <el-button size="small" text @click="filters.splice(index, 1)">✕</el-button>
    </div>
    <el-button v-if="filters.length" size="small" type="primary" class="mb-3" @click="search">{{
      $t("devPanel.data.apply") }}</el-button>

    <el-table v-if="page" v-loading="loading" :data="page.rows" size="small" border max-height="620"
      @sort-change="sortBy">
      <el-table-column v-for="column in page.columns" :key="column" :prop="column" :label="column"
        sortable="custom" min-width="140" show-overflow-tooltip>
        <template #default="{ row }">
          <span v-if="row[column] == null" class="text-gray-400">null</span>
          <span v-else>{{ display(row[column]) }}</span>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination v-if="page && page.total > size" class="mt-3" layout="prev, pager, next, sizes"
      :total="page.total" :page-size="size" :current-page="pageNumber + 1" :page-sizes="[25, 50, 100, 200]"
      @current-change="(n: number) => { pageNumber = n - 1; load(); }"
      @size-change="(n: number) => { size = n; pageNumber = 0; load(); }" />
    <el-empty v-if="!current" :description="$t('devPanel.data.none')" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useDevApi } from "../dev.api";
import { useDevStore } from "../dev.store";
import { IDevDataPage, IDevSchemaTable } from "../dev.types";
import { devErrorMessage } from "../dev.utils";

const OPS = ["contains", "eq", "ne", "gt", "gte", "lt", "lte", "null", "notnull"];

const store = useDevStore();
const { GET_DATA_TABLES, GET_DATA_ROWS } = useDevApi();
const tables = ref<IDevSchemaTable[]>([]);
const selected = ref<string | null>(null);
const page = ref<IDevDataPage | null>(null);
const filters = ref<{ column: string; op: string; value: string }[]>([]);
const sort = ref<string | undefined>();
const desc = ref(false);
const pageNumber = ref(0);
const size = ref(50);
const loading = ref(false);
const errorText = ref("");

const current = computed(() =>
  tables.value.find((table) => `${table.schema}.${table.table}` === selected.value) ?? null,
);

const display = (value: unknown) =>
  typeof value === "object" ? JSON.stringify(value) : String(value);

const choose = () => {
  filters.value = [];
  sort.value = undefined;
  pageNumber.value = 0;
  load();
};

const addFilter = () =>
  filters.value.push({ column: current.value?.columns[0] ?? "", op: "contains", value: "" });

const search = () => {
  pageNumber.value = 0;
  load();
};

const sortBy = ({ prop, order }: { prop: string; order: string | null }) => {
  sort.value = order ? prop : undefined;
  desc.value = order === "descending";
  load();
};

const load = async () => {
  if (!current.value) return;
  loading.value = true;
  try {
    page.value = (await GET_DATA_ROWS({
      schema: current.value.schema,
      table: current.value.table,
      page: pageNumber.value,
      size: size.value,
      sort: sort.value,
      desc: desc.value,
      filter: filters.value.filter((f) => f.column)
        .map((f) => `${f.column}:${f.op}:${f.value ?? ""}`),
    })).data;
    errorText.value = "";
  } catch (error) {
    errorText.value = await devErrorMessage(error);
  } finally {
    loading.value = false;
  }
};

onMounted(async () => {
  try {
    tables.value = (await GET_DATA_TABLES()).data;
  } catch (error) {
    errorText.value = await devErrorMessage(error);
  }
  const asked = store.takeFocus("data");
  if (asked?.table) {
    const found = tables.value.find((t) => t.table === asked.table
      || `${t.schema}.${t.table}` === asked.table);
    if (found) {
      selected.value = `${found.schema}.${found.table}`;
      choose();
    }
  }
});
</script>
