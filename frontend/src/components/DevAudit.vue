<template>
  <div>
    <el-form
      :inline="true"
      class="dev-filter mb-5 border-b border-gray-200 dark:border-[#1f2a3d]"
      @submit.prevent="search"
    >
      <el-form-item>
        <el-input
          v-model="filter.traceId"
          placeholder="Trace ID"
          clearable
          class="!w-64"
        />
        <el-badge
          v-if="isMobile"
          :value="activeFilters"
          :hidden="!activeFilters"
        >
          <el-button
            :type="filtersOpen ? 'primary' : 'default'"
            :icon="Filter"
            :aria-label="$t('filter')"
            @click="filtersOpen = !filtersOpen"
          />
        </el-badge>
      </el-form-item>
      <el-form-item v-show="showFilters">
        <el-select
          v-model="filter.httpMethod"
          :placeholder="$t('devPanel.audit.method')"
          clearable
          class="!w-32"
        >
          <el-option
            v-for="method in METHODS"
            :key="method"
            :label="method"
            :value="method"
          />
        </el-select>
      </el-form-item>
      <el-form-item v-show="showFilters">
        <el-input
          v-model="filter.path"
          :placeholder="$t('devPanel.audit.path')"
          clearable
          class="!w-56"
        />
      </el-form-item>
      <el-form-item v-show="showFilters">
        <el-input-number
          v-model="filter.statusCode"
          :placeholder="$t('devPanel.audit.status')"
          :min="100"
          :max="599"
          :controls="false"
          class="!w-28"
        />
      </el-form-item>
      <el-form-item v-if="!splitRange">
        <el-date-picker
          v-model="range"
          type="datetimerange"
          value-format="YYYY-MM-DDTHH:mm:ss"
          :start-placeholder="$t('devPanel.audit.from')"
          :end-placeholder="$t('devPanel.audit.to')"
        />
      </el-form-item>
      <template v-else>
        <el-form-item v-show="showFilters">
          <el-date-picker
            v-model="rangeFrom"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            :placeholder="$t('devPanel.audit.from')"
          />
        </el-form-item>
        <el-form-item v-show="showFilters">
          <el-date-picker
            v-model="rangeTo"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            :placeholder="$t('devPanel.audit.to')"
          />
        </el-form-item>
      </template>
      <el-form-item v-show="showFilters">
        <el-button type="primary" native-type="submit" :loading="loading">{{
          $t("search")
        }}</el-button>
        <el-button @click="clear">{{ $t("actions.resetFilters") }}</el-button>
      </el-form-item>
    </el-form>

    <el-alert
      v-if="errorText"
      :title="errorText"
      type="error"
      show-icon
      :closable="false"
      class="mb-4"
    />

    <!-- Tor ekranda 8 ustunli jadval sig'maydi — har bir yozuv kartochka ko'rinishida chiqadi -->
    <div v-if="isMobile" v-loading="loading" class="min-h-[120px]">
      <el-empty v-if="!list.length && !loading" :image-size="80" />
      <div
        v-for="(row, index) in list"
        :key="row.id"
        :style="{ '--i': Math.min(index, 10) }"
        class="dev-audit-card mb-3 rounded-lg border border-gray-200 p-3 text-sm dark:border-[#1f2a3d] dark:text-white"
      >
        <div class="mb-2 flex items-center justify-between gap-2">
          <div class="flex items-center gap-2">
            <el-tag
              :type="row.statusCode >= 500 ? 'danger' : 'warning'"
              effect="dark"
              disable-transitions
            >
              {{ row.statusCode }}
            </el-tag>
            <span class="font-semibold">{{ row.httpMethod }}</span>
          </div>
          <el-button link type="primary" @click="openLogs(row)">{{
            $t("devPanel.audit.logs")
          }}</el-button>
        </div>
        <div class="mb-2 break-all font-mono text-xs">{{ row.path }}</div>
        <div
          class="flex flex-wrap gap-x-4 gap-y-1 text-xs text-gray-500 dark:text-gray-400"
        >
          <span>{{ formatDateTime(row.occurredAt) }}</span>
          <span>{{ row.durationMs }} ms</span>
          <span v-if="row.clientIp">{{ row.clientIp }}</span>
        </div>
        <div
          class="mt-2 cursor-pointer break-all font-mono text-xs font-semibold text-violet-600 hover:underline dark:text-violet-300"
          :title="$t('devPanel.audit.filterByTrace')"
          @click="byTrace(row.traceId)"
        >
          {{ row.traceId }}
        </div>
      </div>
    </div>

    <el-table v-else :data="list" v-loading="loading" border stripe>
      <!-- Xato javobi nima degani: masalan, 400 da validatsiya xabari -->
      <el-table-column type="expand" width="40">
        <template #default="{ row }">
          <div class="px-4 py-2">
            <b class="text-sm">{{ $t("devPanel.audit.response") }}</b>
            <pre v-if="row.responseBody" class="audit-body">{{ prettyJson(row.responseBody) }}</pre>
            <p v-else class="m-0 text-xs text-gray-500">{{ $t("devPanel.audit.noResponse") }}</p>
          </div>
        </template>
      </el-table-column>
      <el-table-column :label="$t('devPanel.audit.time')" width="170">
        <template #default="{ row }">{{
          formatDateTime(row.occurredAt)
        }}</template>
      </el-table-column>
      <el-table-column
        :label="$t('devPanel.audit.method')"
        prop="httpMethod"
        width="90"
      />
      <el-table-column
        :label="$t('devPanel.audit.path')"
        prop="path"
        min-width="240"
        show-overflow-tooltip
      />
      <el-table-column :label="$t('devPanel.audit.status')" width="90">
        <template #default="{ row }">
          <el-tag
            :type="row.statusCode >= 500 ? 'danger' : 'warning'"
            effect="dark"
            disable-transitions
          >
            {{ row.statusCode }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="$t('devPanel.audit.duration')" width="110">
        <template #default="{ row }">{{ row.durationMs }} ms</template>
      </el-table-column>
      <el-table-column label="IP" prop="clientIp" width="140" />
      <el-table-column label="Trace ID" min-width="200">
        <template #default="{ row }">
          <span
            class="cursor-pointer font-mono text-xs font-semibold text-violet-600 hover:underline dark:text-violet-300"
            :title="$t('devPanel.audit.filterByTrace')"
            @click="byTrace(row.traceId)"
          >
            {{ row.traceId }}
          </span>
        </template>
      </el-table-column>
      <el-table-column width="90" align="center" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openLogs(row)">{{
            $t("devPanel.audit.logs")
          }}</el-button>
        </template>
      </el-table-column>
    </el-table>

    <DevTraceLogs
      v-if="logsOf"
      v-model="logsVisible"
      :trace-id="logsOf.traceId"
      :occurred-at="logsOf.occurredAt"
    />

    <div class="mt-4 flex justify-center sm:justify-end">
      <el-pagination
        v-model:current-page="currentPage"
        v-model:page-size="filter.size"
        :total="total"
        :page-sizes="[20, 50, 100, 200]"
        :layout="
          isMobile ? 'prev, pager, next' : 'total, sizes, prev, pager, next'
        "
        :pager-count="isMobile ? 5 : 7"
        :small="isMobile"
        @current-change="load"
        @size-change="search"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useMediaQuery } from "@vueuse/core";
import { Filter } from "@element-plus/icons-vue";
import { useDevApi } from "../dev.api";
import { IDevAuditEntry, IDevAuditFilter } from "../dev.types";
import {
  devErrorMessage,
  formatDateTime,
  prettyJson,
  TDevRange,
  useRangeEnds,
} from "../dev.utils";
import DevTraceLogs from "./DevTraceLogs.vue";

const METHODS = ["GET", "POST", "PUT", "PATCH", "DELETE"];

const { GET_AUDIT } = useDevApi();
const isMobile = useMediaQuery("(max-width: 639px)");
const splitRange = useMediaQuery("(max-width: 767px)");

const emptyFilter = (): IDevAuditFilter => ({
  traceId: "",
  httpMethod: undefined,
  path: "",
  statusCode: undefined,
  page: 0,
  size: 20,
});

const filter = ref<IDevAuditFilter>(emptyFilter());
const range = ref<TDevRange>(null);
const { rangeFrom, rangeTo } = useRangeEnds(range);
const list = ref<IDevAuditEntry[]>([]);
const total = ref(0);
const loading = ref(false);
const errorText = ref("");

// Telefonda filtrlar ekranni to'ldirib, natijalarni pastga surib yuboradi —
// shuning uchun faqat Trace ID ko'rinadi, qolganlari tugma orqali ochiladi
const filtersOpen = ref(false);
const showFilters = computed(() => !isMobile.value || filtersOpen.value);
const activeFilters = computed(
  () =>
    [
      filter.value.httpMethod,
      filter.value.path?.trim(),
      filter.value.statusCode,
      range.value,
    ].filter((value) => value != null && value !== "").length,
);

// el-pagination 1 dan, backend esa 0 dan sanaydi
const currentPage = computed({
  get: () => filter.value.page + 1,
  set: (value: number) => (filter.value.page = value - 1),
});

const load = async () => {
  loading.value = true;
  errorText.value = "";
  try {
    const params: IDevAuditFilter = {
      ...filter.value,
      traceId: filter.value.traceId?.trim() || undefined,
      path: filter.value.path?.trim() || undefined,
      statusCode: filter.value.statusCode ?? undefined,
      from: range.value?.[0],
      to: range.value?.[1],
    };
    const { data } = await GET_AUDIT(params);
    list.value = data.list;
    total.value = data.total;
  } catch (error) {
    errorText.value = await devErrorMessage(error);
  } finally {
    loading.value = false;
  }
};

const search = () => {
  filter.value.page = 0;
  filtersOpen.value = false;
  load();
};

const clear = () => {
  filter.value = emptyFilter();
  range.value = null;
  load();
};

const byTrace = (traceId: string) => {
  filter.value = { ...emptyFilter(), traceId, size: filter.value.size };
  range.value = null;
  load();
};

const logsOf = ref<IDevAuditEntry | null>(null);
const logsVisible = ref(false);

const openLogs = (row: IDevAuditEntry) => {
  logsOf.value = row;
  logsVisible.value = true;
};

onMounted(load);
</script>

<style scoped>
.dev-filter {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 16px 20px;
  padding-bottom: 20px;
}
.dev-filter :deep(.el-form-item) {
  margin: 0;
}
.dev-filter :deep(.el-form-item__content) {
  gap: 12px;
}
.dev-filter :deep(.el-form-item__content > .el-button + .el-button) {
  margin-left: 0;
}
/* Status maydoni boshqa inputlar kabi chapdan yozilsin */
.dev-filter :deep(.el-input-number .el-input__inner) {
  text-align: left;
}

/* Tor ekranda filtr maydonlari bittadan, to'liq kenglikda turadi */
@media (max-width: 639px) {
  .dev-filter :deep(.el-form-item) {
    width: 100%;
    margin-right: 0;
  }

  .dev-filter :deep(.el-input),
  .dev-filter :deep(.el-select),
  .dev-filter :deep(.el-input-number),
  .dev-filter :deep(.el-date-editor) {
    width: 100% !important;
  }

  .dev-filter :deep(.el-form-item__content) {
    flex-wrap: nowrap;
  }

  .dev-filter :deep(.el-form-item__content > .el-button) {
    flex: 1;
  }
}

.dev-audit-card {
  animation: dev-audit-in 0.4s cubic-bezier(0.22, 1, 0.36, 1) both;
  animation-delay: calc(var(--i, 0) * 0.04s);
}

@keyframes dev-audit-in {
  from {
    opacity: 0;
    transform: translateY(10px);
  }
}

@media (prefers-reduced-motion: reduce) {
  .dev-audit-card {
    animation: none;
  }
}
.audit-body {
  max-height: 260px;
  margin: 6px 0 0;
  padding: 8px 10px;
  overflow: auto;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-word;
  border-radius: 8px;
  background: var(--el-fill-color-light);
}
</style>
