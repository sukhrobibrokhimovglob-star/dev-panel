<template>
  <div>
    <div class="mb-4 flex flex-wrap items-center gap-2">
      <p class="m-0 text-sm text-gray-500">{{ $t("devPanel.slo.hint") }}
        <DevHint k="slo" /></p>
      <el-button v-if="store.canManageAccounts" class="ml-auto" @click="openCreate">+
        {{ $t("devPanel.slo.add") }}</el-button>
    </div>
    <el-alert v-if="errorText" :title="errorText" type="error" show-icon :closable="false" class="mb-4" />
    <el-empty v-if="!loading && !goals.length" :description="$t('devPanel.slo.none')" />

    <div v-else v-loading="loading" class="slo-grid">
      <article v-for="status in goals" :key="status.goal.id" class="slo-card"
        :class="status.met ? (status.budgetDaysLeft >= 0 && status.budgetDaysLeft <= 30 ? 'is-warn' : 'is-ok') : 'is-bad'">
        <header class="flex items-start gap-2">
          <div class="min-w-0 flex-1">
            <code class="slo-card__endpoint">{{ status.goal.endpoint }}</code>
            <div class="text-xs text-gray-500">{{ $t("devPanel.slo.goal", {
              share: formatPercent(status.goal.objective, 1), ms: status.goal.targetMs }) }}</div>
          </div>
          <DevVerdict :level="status.met ? (status.budgetDaysLeft >= 0 && status.budgetDaysLeft <= 30 ? 'warn' : 'ok') : 'bad'"
            :text="status.met ? $t('devPanel.slo.met') : $t('devPanel.slo.missed')" />
        </header>
        <div class="slo-card__numbers">
          <div><span>{{ $t("devPanel.slo.week") }}</span>
            <b>{{ formatPercent(status.weekShare, 2) }}</b>
            <small>{{ formatNumber(status.weekCalls) }} {{ $t("devPanel.slo.calls") }}</small></div>
          <div><span>{{ $t("devPanel.slo.month") }}</span>
            <b>{{ formatPercent(status.monthShare, 2) }}</b>
            <small>{{ formatNumber(status.monthCalls) }} {{ $t("devPanel.slo.calls") }}</small></div>
          <div><span>{{ $t("devPanel.slo.burn") }} <DevHint k="sloBurn" /></span>
            <b :class="{ 'text-red-500': status.burnRate > 1 }">{{ status.burnRate.toFixed(2) }}×</b></div>
        </div>
        <div class="mt-2 text-xs text-gray-500">{{ $t("devPanel.slo.budget") }}
          <b>{{ formatPercent(Math.max(0, status.budgetLeft), 0) }}</b>
          <template v-if="status.budgetDaysLeft >= 0"> ·
            <span class="text-red-500">{{ $t("devPanel.slo.runsOut", { days: Math.round(status.budgetDaysLeft) }) }}</span>
          </template>
        </div>
        <el-progress :percentage="Math.round(Math.max(0, Math.min(1, status.budgetLeft)) * 100)"
          :status="status.budgetLeft < 0.2 ? 'exception' : status.budgetLeft < 0.5 ? 'warning' : 'success'"
          :show-text="false" class="mt-1" />
        <footer v-if="store.canManageAccounts" class="mt-2 text-right">
          <el-button size="small" text type="danger" @click="remove(status.goal.id)">{{ $t("devPanel.slo.delete") }}</el-button>
        </footer>
      </article>
    </div>

    <el-dialog v-model="dialog" :title="$t('devPanel.slo.add')" width="min(560px, 96vw)" append-to-body>
      <el-form label-position="top">
        <el-form-item :label="$t('devPanel.slo.endpoint')">
          <el-select v-model="form.endpoint" filterable class="w-full">
            <el-option v-for="item in catalog" :key="item.endpoint" :value="item.endpoint" :label="item.endpoint" />
          </el-select>
        </el-form-item>
        <div class="flex gap-3">
          <el-form-item :label="$t('devPanel.slo.share')">
            <el-input-number v-model="form.percent" :min="50" :max="99.99" :step="0.5" :precision="2" />
          </el-form-item>
          <el-form-item :label="$t('devPanel.slo.within')">
            <el-input-number v-model="form.targetMs" :min="1" :max="600000" :step="100" />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">{{ $t("actions.cancel") }}</el-button>
        <el-button type="primary" :disabled="!form.endpoint" @click="create">{{ $t("actions.save") }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import { useDevApi } from "../dev.api";
import { useDevStore } from "../dev.store";
import { IDevCatalogEntry, IDevSloStatus } from "../dev.types";
import { devErrorMessage, formatNumber, formatPercent } from "../dev.utils";
import DevHint from "./DevHint.vue";
import DevVerdict from "./DevVerdict.vue";

const store = useDevStore();
const { GET_SLO, CREATE_SLO, DELETE_SLO, GET_CATALOG } = useDevApi();
const goals = ref<IDevSloStatus[]>([]);
const catalog = ref<IDevCatalogEntry[]>([]);
const loading = ref(false);
const dialog = ref(false);
const errorText = ref("");
const form = ref({ endpoint: "", percent: 95, targetMs: 500 });

const load = async () => {
  loading.value = true;
  try {
    goals.value = (await GET_SLO()).data;
    errorText.value = "";
  } catch (error) {
    errorText.value = await devErrorMessage(error);
  } finally {
    loading.value = false;
  }
};

const openCreate = async () => {
  form.value = { endpoint: store.takeFocus("slo")?.endpoint ?? "", percent: 95, targetMs: 500 };
  if (!catalog.value.length) {
    try {
      catalog.value = (await GET_CATALOG()).data;
    } catch {
      catalog.value = [];
    }
  }
  dialog.value = true;
};

const create = async () => {
  try {
    await CREATE_SLO({ endpoint: form.value.endpoint, targetMs: form.value.targetMs,
      objective: form.value.percent / 100 });
    dialog.value = false;
    await load();
  } catch (error) {
    ElMessage.error(await devErrorMessage(error));
  }
};

const remove = async (id: number) => {
  try {
    await DELETE_SLO(id);
    await load();
  } catch (error) {
    ElMessage.error(await devErrorMessage(error));
  }
};

onMounted(load);
</script>

<style scoped>
.slo-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 12px;
}

.slo-card {
  padding: 14px 16px;
  border-radius: 12px;
  border: 1px solid var(--el-border-color);
  border-top-width: 3px;
}

.slo-card.is-ok {
  border-top-color: #3ec972;
}

.slo-card.is-warn {
  border-top-color: #e6a23c;
}

.slo-card.is-bad {
  border-top-color: #d14343;
}

.slo-card__endpoint {
  font-size: 13px;
  font-weight: 600;
  word-break: break-all;
}

.slo-card__numbers {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  margin-top: 12px;
}

.slo-card__numbers div {
  display: flex;
  flex-direction: column;
}

.slo-card__numbers span,
.slo-card__numbers small {
  font-size: 11px;
  color: var(--el-text-color-secondary);
}

.slo-card__numbers b {
  font-size: 18px;
  font-variant-numeric: tabular-nums;
}
</style>
