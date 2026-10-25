<template>
  <div v-loading="loading">
    <div class="mb-4 flex flex-wrap items-center gap-2">
      <p class="m-0 text-sm text-gray-500">{{ $t("devPanel.tests.hint") }}</p>
      <div class="ml-auto flex gap-2">
        <el-button v-if="store.canManageAccounts" :loading="uploading" @click="pick">⬆
          {{ $t("devPanel.tests.upload") }}</el-button>
        <el-button @click="load">{{ $t("actions.refresh") }}</el-button>
      </div>
      <input ref="fileInput" type="file" accept=".json,.csv" class="hidden" @change="upload" />
    </div>
    <el-alert v-if="errorText" :title="errorText" type="error" show-icon :closable="false" class="mb-4" />

    <el-alert v-if="data && !data.summary" type="info" :closable="false" show-icon class="mb-4"
      :title="$t('devPanel.tests.noReport')">
      <div class="text-xs leading-relaxed">
        <div>{{ $t("devPanel.tests.how1") }}</div>
        <pre class="tests-pre">mvn test -Ddevpanel.tests.report=src/main/resources/devpanel-tests.json</pre>
        <div>{{ $t("devPanel.tests.how2") }}</div>
      </div>
    </el-alert>

    <template v-if="data">
      <div class="tests-tiles mb-4">
        <div class="tests-tile">
          <span>{{ $t("devPanel.tests.endpointsTested") }}</span>
          <b>{{ formatPercent(data.endpointsCovered, 0) }}</b>
          <small>{{ data.testedEndpoints }} / {{ data.endpoints }}</small>
        </div>
        <div class="tests-tile">
          <span>{{ $t("devPanel.tests.trafficTested") }} <DevHint k="trafficTested" /></span>
          <b>{{ formatPercent(data.trafficCovered, 0) }}</b>
        </div>
        <div v-if="data.summary" class="tests-tile">
          <span>{{ $t("devPanel.tests.tests") }}</span>
          <b>{{ data.summary.tests }}</b>
          <small>
            <span class="text-green-600">✓ {{ data.summary.passed }}</span>
            <span v-if="data.summary.failed" class="ml-2 text-red-500">✗ {{ data.summary.failed }}</span>
            <span v-if="data.summary.skipped" class="ml-2">⤼ {{ data.summary.skipped }}</span>
          </small>
        </div>
        <div v-if="data.coverage" class="tests-tile">
          <span>{{ $t("devPanel.tests.lines") }}</span>
          <b>{{ formatPercent(data.coverage.lines, 0) }}</b>
          <small>{{ $t("devPanel.tests.branches") }} {{ formatPercent(data.coverage.branches, 0) }}</small>
        </div>
      </div>

      <div v-if="data.summary" class="mb-4 text-xs text-gray-500">
        {{ $t("devPanel.tests.reportFrom", { when: fromNow(data.summary.generatedAt), source: data.summary.source }) }}
        <template v-if="data.summary.gitCommit"> · commit <code>{{ data.summary.gitCommit.slice(0, 8) }}</code></template>
        <el-tag v-if="data.summary.sameCommit === false" type="warning" size="small" class="ml-2">{{
          $t("devPanel.tests.outdated", { deployed: (data.summary.deployedCommit ?? "").slice(0, 8) }) }}</el-tag>
      </div>

      <el-tabs v-model="tab">
        <el-tab-pane :label="$t('devPanel.tests.endpoints')" name="endpoints">
          <div class="mb-3 flex flex-wrap gap-2">
            <el-input v-model="filter" clearable size="small" class="!w-64"
              :placeholder="$t('devPanel.tests.filter')" />
            <el-radio-group v-model="show" size="small">
              <el-radio-button value="all">{{ $t("devPanel.risks.all") }}</el-radio-button>
              <el-radio-button value="untested">{{ $t("devPanel.tests.untested") }}</el-radio-button>
              <el-radio-button value="failing">{{ $t("devPanel.tests.failing") }}</el-radio-button>
            </el-radio-group>
          </div>
          <el-table :data="rows" size="small" max-height="620">
            <el-table-column :label="$t('devPanel.tests.endpoint')" min-width="300">
              <template #default="{ row }">
                <code class="text-xs">{{ row.endpoint }}</code>
                <div class="text-xs text-gray-400">{{ row.handler }}</div>
              </template>
            </el-table-column>
            <el-table-column :label="$t('devPanel.tests.tests')" min-width="230">
              <template #default="{ row }">
                <el-tag v-if="!row.testCount" type="danger" size="small" effect="plain">{{ $t("devPanel.tests.none") }}</el-tag>
                <template v-else>
                  <b>{{ row.testCount }}</b>
                  <span class="ml-1 text-xs text-gray-500">({{ row.statuses.join(", ") }})</span>
                  <div v-for="test in row.failingTests" :key="test" class="text-xs text-red-500">✗ {{ test }}</div>
                  <el-tooltip v-if="row.tests.length" placement="top">
                    <template #content><div v-for="test in row.tests" :key="test">{{ test }}</div></template>
                    <span class="ml-1 cursor-help text-xs text-gray-400">ⓘ</span>
                  </el-tooltip>
                </template>
              </template>
            </el-table-column>
            <el-table-column :label="$t('devPanel.tests.perDay')" width="110" align="right">
              <template #default="{ row }">{{ row.callsPerDay }}</template>
            </el-table-column>
            <el-table-column :label="$t('devPanel.tests.prodErrors')" width="140" align="right">
              <template #default="{ row }">
                <span v-if="row.serverErrors" class="text-red-500">5xx {{ row.serverErrors }}</span>
                <span v-if="row.clientErrors" class="ml-1 text-orange-500">4xx {{ row.clientErrors }}</span>
                <div v-if="row.untestedInProduction.length" class="text-xs text-orange-500">
                  {{ $t("devPanel.tests.notChecked", { classes: row.untestedInProduction.join(", ") }) }}</div>
              </template>
            </el-table-column>
            <el-table-column :label="$t('devPanel.tests.risk')" width="90" align="right" prop="risk" sortable />
            <el-table-column width="80" align="right">
              <template #default="{ row }">
                <el-button size="small" text type="primary" @click="tryIt(row.endpoint)">▶</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane v-if="data.failingTests.length"
          :label="`${$t('devPanel.tests.failing')} (${data.failingTests.length})`" name="failing">
          <div v-for="test in data.failingTests" :key="test.id" class="tests-failure">
            <b>{{ test.id }}</b>
            <pre class="tests-pre">{{ test.failure }}</pre>
          </div>
        </el-tab-pane>

        <el-tab-pane :label="$t('devPanel.tests.coverage')" name="coverage">
          <el-alert v-if="!data.coverage" type="info" :closable="false" show-icon
            :title="$t('devPanel.tests.noCoverage')" />
          <template v-else>
            <el-table :data="data.coverage.packages" size="small" max-height="520">
              <el-table-column prop="name" :label="$t('devPanel.tests.package')" min-width="320" />
              <el-table-column :label="$t('devPanel.tests.lines')" min-width="220">
                <template #default="{ row }">
                  <el-progress :percentage="Math.round(Math.max(0, row.lines) * 100)"
                    :status="row.lines < 0.3 ? 'exception' : row.lines < 0.6 ? 'warning' : 'success'" />
                </template>
              </el-table-column>
              <el-table-column :label="$t('devPanel.tests.branches')" width="110" align="right">
                <template #default="{ row }">{{ formatPercent(row.branches, 0) }}</template>
              </el-table-column>
              <el-table-column prop="classes" :label="$t('devPanel.tests.classes')" width="90" align="right" />
            </el-table>
            <template v-if="data.coverage.uncovered.length">
              <h4 class="mb-2 mt-4 text-sm">{{ $t("devPanel.tests.uncovered") }}</h4>
              <el-table :data="data.coverage.uncovered" size="small" max-height="360">
                <el-table-column prop="name" :label="$t('devPanel.tests.class')" min-width="360" />
                <el-table-column prop="lines" :label="$t('devPanel.tests.linesMissed')" width="130" align="right" />
              </el-table>
            </template>
          </template>
        </el-tab-pane>
      </el-tabs>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage } from "element-plus";
import { useDevApi } from "../dev.api";
import { useDevStore } from "../dev.store";
import { IDevTests } from "../dev.types";
import { devErrorMessage, formatPercent, fromNow } from "../dev.utils";
import DevHint from "./DevHint.vue";

const { t } = useI18n();
const store = useDevStore();
const { GET_TESTS, UPLOAD_TESTS } = useDevApi();
const data = ref<IDevTests | null>(null);
const loading = ref(false);
const uploading = ref(false);
const errorText = ref("");
const tab = ref("endpoints");
const filter = ref("");
const show = ref<"all" | "untested" | "failing">("all");
const fileInput = ref<HTMLInputElement>();

const rows = computed(() =>
  (data.value?.list ?? []).filter(
    (row) =>
      (!filter.value || row.endpoint.toLowerCase().includes(filter.value.toLowerCase())) &&
      (show.value === "all" ||
        (show.value === "untested" && !row.testCount) ||
        (show.value === "failing" && row.failingTests.length)),
  ),
);

const load = async () => {
  loading.value = true;
  try {
    data.value = (await GET_TESTS()).data;
    errorText.value = "";
  } catch (error) {
    errorText.value = await devErrorMessage(error);
  } finally {
    loading.value = false;
  }
};

const pick = () => fileInput.value?.click();

const upload = async (event: Event) => {
  const file = (event.target as HTMLInputElement).files?.[0];
  if (!file) return;
  uploading.value = true;
  try {
    const kind = (await UPLOAD_TESTS(await file.text())).data.kind;
    ElMessage.success(t(`devPanel.tests.uploaded.${kind}`));
    await load();
  } catch (error) {
    ElMessage.error(await devErrorMessage(error));
  } finally {
    uploading.value = false;
    if (fileInput.value) fileInput.value.value = "";
  }
};

// Testsiz endpoint'ni API runner'da sinab ko'rish
const tryIt = (endpoint: string) => {
  store.focus = { tab: "runner", endpoint };
};

onMounted(load);
</script>

<style scoped>
.tests-tiles {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(170px, 1fr));
  gap: 10px;
}

.tests-tile {
  display: flex;
  flex-direction: column;
  padding: 12px 14px;
  border-radius: 12px;
  border: 1px solid var(--el-border-color);
  background: var(--el-fill-color-lighter);
}

.tests-tile span,
.tests-tile small {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.tests-tile b {
  font-size: 22px;
  font-variant-numeric: tabular-nums;
}

.tests-pre {
  margin: 6px 0;
  padding: 8px 10px;
  overflow-x: auto;
  font-size: 12px;
  white-space: pre-wrap;
  border-radius: 8px;
  background: var(--el-fill-color-light);
}

.tests-failure {
  margin-bottom: 10px;
  font-size: 13px;
}
</style>
