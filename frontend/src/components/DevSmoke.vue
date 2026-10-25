<template>
  <div>
    <div class="mb-4 flex flex-wrap items-center gap-2">
      <p class="m-0 text-sm text-gray-500">{{ $t("devPanel.smoke.hint") }}</p>
      <div class="ml-auto flex gap-2">
        <el-button v-if="store.canManageAccounts" @click="edit(null)">+ {{ $t("devPanel.smoke.add") }}</el-button>
        <el-button v-if="store.canWrite" type="primary" :loading="running" :disabled="!checks.length"
          @click="runAll">{{ $t("devPanel.smoke.runAll") }}</el-button>
      </div>
    </div>
    <el-alert v-if="errorText" :title="errorText" type="error" show-icon :closable="false" class="mb-4" />

    <el-empty v-if="!loading && !checks.length" :description="$t('devPanel.smoke.none')" />
    <el-table v-else v-loading="loading" :data="checks" size="small">
      <el-table-column :label="$t('devPanel.smoke.check')" min-width="240">
        <template #default="{ row }">
          <el-tag :type="!row.lastRun ? 'info' : row.lastRun.ok ? 'success' : 'danger'" effect="dark"
            size="small" class="mr-2">{{
              !row.lastRun ? "—" : row.lastRun.ok ? "OK" : $t("devPanel.smoke.failed")
            }}</el-tag>
          <b>{{ row.check.name }}</b>
          <el-tag v-if="!row.check.enabled" size="small" type="info" class="ml-2">{{ $t("devPanel.smoke.off") }}</el-tag>
          <div class="text-xs text-gray-500"><code>{{ row.check.method }} {{ row.check.path }}</code>
            → {{ row.check.expectedStatus }}, ≤ {{ formatMs(row.check.maxMs) }}</div>
          <div v-if="row.lastRun && !row.lastRun.ok" class="text-xs text-red-500">{{ row.lastRun.error }}</div>
        </template>
      </el-table-column>
      <el-table-column :label="$t('devPanel.smoke.history')" min-width="220">
        <template #default="{ row }">
          <span class="smoke-dots">
            <i v-for="(run, index) in [...row.history].reverse()" :key="index"
              :class="run.ok ? 'is-ok' : 'is-bad'"
              :title="`${formatTime(run.time)} · ${run.status} · ${formatMs(run.ms)}${run.error ? ' · ' + run.error : ''}`" />
          </span>
        </template>
      </el-table-column>
      <el-table-column :label="$t('devPanel.smoke.passed')" width="110" align="right">
        <template #default="{ row }">{{ formatPercent(row.success, 0) }}</template>
      </el-table-column>
      <el-table-column :label="$t('devPanel.jobs.lastRun')" width="150">
        <template #default="{ row }">{{ row.lastRun ? fromNow(row.lastRun.time) : "—" }}
          <div v-if="row.lastRun" class="text-xs text-gray-500">{{ formatMs(row.lastRun.ms) }}</div>
        </template>
      </el-table-column>
      <el-table-column width="190" align="right">
        <template #default="{ row }">
          <el-button v-if="store.canWrite" size="small" text @click="runOne(row.check.id)">▶</el-button>
          <template v-if="store.canManageAccounts">
            <el-button size="small" text @click="edit(row.check)">{{ $t("devPanel.smoke.edit") }}</el-button>
            <el-button size="small" text type="danger" @click="remove(row.check)">✕</el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialog" :title="form.id ? $t('devPanel.smoke.edit') : $t('devPanel.smoke.add')"
      width="min(620px, 96vw)" append-to-body>
      <el-form label-position="top">
        <el-form-item :label="$t('devPanel.smoke.name')">
          <el-input v-model="form.name" maxlength="100" placeholder="Patient search works" />
        </el-form-item>
        <div class="flex gap-2">
          <el-form-item :label="$t('devPanel.smoke.method')" class="w-28">
            <el-select v-model="form.method">
              <el-option value="GET" label="GET" />
              <el-option value="HEAD" label="HEAD" />
            </el-select>
          </el-form-item>
          <el-form-item :label="$t('devPanel.smoke.path')" class="flex-1">
            <el-input v-model="form.path" placeholder="/v1/patients?page=0" />
          </el-form-item>
        </div>
        <el-form-item :label="$t('devPanel.smoke.headers')">
          <el-input v-model="form.headers" type="textarea" :rows="3"
            placeholder="Authorization: Bearer ${SMOKE_TOKEN}" />
          <p class="m-0 mt-1 text-xs text-gray-500">{{ $t("devPanel.smoke.headersHint") }}</p>
        </el-form-item>
        <div class="flex flex-wrap gap-3">
          <el-form-item :label="$t('devPanel.smoke.expected')">
            <el-input-number v-model="form.expectedStatus" :min="100" :max="599" />
          </el-form-item>
          <el-form-item :label="$t('devPanel.smoke.maxMs')">
            <el-input-number v-model="form.maxMs" :min="100" :max="60000" :step="500" />
          </el-form-item>
          <el-form-item :label="$t('devPanel.smoke.enabled')">
            <el-switch v-model="form.enabled" />
          </el-form-item>
        </div>
        <el-form-item :label="$t('devPanel.smoke.contains')">
          <el-input v-model="form.bodyContains" placeholder='"content"' />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">{{ $t("actions.cancel") }}</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ $t("actions.save") }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage, ElMessageBox } from "element-plus";
import { useDevApi } from "../dev.api";
import { useDevStore } from "../dev.store";
import { IDevSmokeCheck, IDevSmokeView } from "../dev.types";
import {
  devErrorMessage,
  formatMs,
  formatPercent,
  formatTime,
  fromNow,
  useAutoRefresh,
} from "../dev.utils";

const { t } = useI18n();
const store = useDevStore();
const { GET_SMOKE, CREATE_SMOKE, UPDATE_SMOKE, DELETE_SMOKE, RUN_SMOKE } = useDevApi();

const empty = (): IDevSmokeCheck => ({
  name: "", method: "GET", path: "/", headers: "", expectedStatus: 200, maxMs: 3000,
  bodyContains: "", enabled: true,
});

const checks = ref<IDevSmokeView[]>([]);
const loading = ref(false);
const running = ref(false);
const saving = ref(false);
const dialog = ref(false);
const form = ref<IDevSmokeCheck>(empty());
const errorText = ref("");

const load = async () => {
  loading.value = !checks.value.length;
  try {
    checks.value = (await GET_SMOKE()).data;
    errorText.value = "";
  } catch (error) {
    errorText.value = await devErrorMessage(error);
  } finally {
    loading.value = false;
  }
};

const edit = (check: IDevSmokeCheck | null) => {
  form.value = check ? { ...check } : empty();
  dialog.value = true;
};

const save = async () => {
  saving.value = true;
  try {
    if (form.value.id) await UPDATE_SMOKE(form.value.id, form.value);
    else await CREATE_SMOKE(form.value);
    dialog.value = false;
    await load();
  } catch (error) {
    ElMessage.error(await devErrorMessage(error));
  } finally {
    saving.value = false;
  }
};

const remove = async (check: IDevSmokeCheck) => {
  try {
    await ElMessageBox.confirm(t("devPanel.smoke.confirmDelete", { name: check.name }), {
      type: "warning",
    });
    await DELETE_SMOKE(check.id!);
    await load();
  } catch {
    // bekor qilindi
  }
};

const runAll = async () => {
  running.value = true;
  try {
    const runs = (await RUN_SMOKE()).data;
    const failed = runs.filter((run) => !run.ok).length;
    if (failed) ElMessage.error(t("devPanel.smoke.someFailed", { count: failed }));
    else ElMessage.success(t("devPanel.smoke.allPassed"));
    await load();
  } catch (error) {
    ElMessage.error(await devErrorMessage(error));
  } finally {
    running.value = false;
  }
};

const runOne = async (id?: number) => {
  try {
    await RUN_SMOKE(id);
    await load();
  } catch (error) {
    ElMessage.error(await devErrorMessage(error));
  }
};

const refresh = useAutoRefresh(load, 60);
onMounted(() => {
  load();
  refresh.start();
});
onBeforeUnmount(refresh.stop);
</script>

<style scoped>
.smoke-dots {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 3px;
}

.smoke-dots i {
  width: 9px;
  height: 14px;
  border-radius: 2px;
  cursor: help;
}

.smoke-dots i.is-ok {
  background: #3ec972;
}

.smoke-dots i.is-bad {
  background: #d14343;
}
</style>
