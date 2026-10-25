<template>
  <div>
    <!-- Xulosa: nechta xavf, qaysi biri yaqinlashib kelyapti -->
    <div class="risk-summary mb-4">
      <div class="risk-summary__item is-critical">
        <b>{{ data?.counts.CRITICAL ?? 0 }}</b>
        <span>{{ $t("devPanel.risks.critical") }}</span>
      </div>
      <div class="risk-summary__item is-warning">
        <b>{{ data?.counts.WARNING ?? 0 }}</b>
        <span>{{ $t("devPanel.risks.warnings") }}</span>
      </div>
      <div class="risk-summary__item is-info">
        <b>{{ data?.counts.INFO ?? 0 }}</b>
        <span>{{ $t("devPanel.risks.notes") }}</span>
      </div>
      <div class="risk-summary__item">
        <b>{{ data?.counts.FORECAST ?? 0 }}</b>
        <span>🔮 {{ $t("devPanel.risks.forecasts") }}</span>
      </div>
      <div class="risk-summary__item">
        <b>{{ data?.counts.WEAKNESS ?? 0 }}</b>
        <span>🧱 {{ $t("devPanel.risks.weaknesses") }}</span>
      </div>
      <div class="ml-auto flex flex-wrap items-center gap-2">
        <span v-if="data?.lastRun" class="text-xs text-gray-500">
          {{ $t("devPanel.risks.lastRun", { when: fromNow(data.lastRun) }) }}
        </span>
        <el-button :loading="reportLoading" @click="openReport">📄
          {{ $t("devPanel.risks.weeklyReport") }}</el-button>
        <el-button v-if="store.canWrite" type="primary" :loading="running" @click="runNow">{{
          $t("devPanel.risks.runNow")
        }}</el-button>
      </div>
    </div>

    <p class="mb-3 mt-0 text-sm text-gray-500">{{ $t("devPanel.risks.hint") }}</p>

    <el-alert
      v-if="errorText"
      :title="errorText"
      type="error"
      show-icon
      :closable="false"
      class="mb-4"
    />
    <el-alert
      v-for="(reason, detector) in data?.failures ?? {}"
      :key="detector"
      :title="$t('devPanel.risks.detectorFailed', { detector })"
      :description="reason"
      type="warning"
      show-icon
      :closable="false"
      class="mb-2"
    />

    <div class="mb-4 flex flex-wrap items-center gap-2">
      <el-radio-group v-model="category" size="small">
        <el-radio-button value="ALL">{{ $t("devPanel.risks.all") }}</el-radio-button>
        <el-radio-button value="FORECAST">🔮 {{ $t("devPanel.risks.forecasts") }}</el-radio-button>
        <el-radio-button value="WEAKNESS">🧱 {{ $t("devPanel.risks.weaknesses") }}</el-radio-button>
      </el-radio-group>
      <el-select v-model="area" size="small" clearable class="!w-44"
        :placeholder="$t('devPanel.risks.area')">
        <el-option v-for="name in areas" :key="name" :value="name"
          :label="$t(`devPanel.risks.areas.${name}`, name)" />
      </el-select>
      <el-checkbox v-model="showSilenced" size="small">{{
        $t("devPanel.risks.showSilenced")
      }}</el-checkbox>
    </div>

    <div v-loading="loading">
      <el-empty v-if="!loading && !visible.length" :description="$t('devPanel.risks.none')" />
      <article
        v-for="item in visible"
        :key="item.risk.key"
        class="risk-card"
        :class="[`is-${item.risk.severity.toLowerCase()}`, { 'is-silenced': item.silenced }]"
      >
        <header class="risk-card__head">
          <el-tag :type="SEVERITY_TAG[item.risk.severity]" effect="dark" size="small">{{
            $t(`devPanel.risks.severity.${item.risk.severity}`)
          }}</el-tag>
          <el-tag size="small" effect="plain">{{
            item.risk.category === "FORECAST" ? "🔮" : "🧱"
          }} {{ $t(`devPanel.risks.areas.${item.risk.area}`, item.risk.area) }}</el-tag>
          <el-tag v-if="item.risk.daysLeft != null" size="small" type="danger" effect="plain">
            ⏳ {{ daysText(item.risk.daysLeft) }}
          </el-tag>
          <el-tag v-for="flag in item.flags" :key="flag" size="small"
            :type="flag === 'WORSE' ? 'danger' : 'success'">{{
              $t(`devPanel.risks.flags.${flag}`)
            }}</el-tag>
          <h3 class="risk-card__title">{{ item.risk.title }}</h3>
        </header>

        <p v-if="item.risk.impact" class="risk-card__impact">
          <b>{{ $t("devPanel.risks.whatHappens") }}</b> {{ item.risk.impact }}
        </p>
        <p v-if="item.risk.evidence" class="risk-card__line">
          <b>{{ $t("devPanel.risks.evidence") }}</b> {{ item.risk.evidence }}
        </p>
        <p v-if="item.risk.fix" class="risk-card__line">
          <b>{{ $t("devPanel.risks.fix") }}</b> {{ item.risk.fix }}
        </p>
        <p v-if="item.risk.where" class="risk-card__line">
          <b>{{ $t("devPanel.risks.where") }}</b> <code>{{ item.risk.where }}</code>
        </p>
        <div v-if="item.risk.sql" class="risk-card__sql">
          <pre>{{ item.risk.sql }}</pre>
          <el-button size="small" text @click="copyToClipboard(item.risk.sql)">{{
            $t("devPanel.waterfall.copy")
          }}</el-button>
        </div>

        <footer class="risk-card__foot">
          <span class="text-xs text-gray-500">
            {{ $t("devPanel.risks.firstSeen", { when: fromNow(item.firstSeen) }) }}
            <template v-if="item.changedFrom">
              · {{ $t("devPanel.risks.changedFrom", {
                from: $t(`devPanel.risks.severity.${item.changedFrom}`),
                when: fromNow(item.changedAt),
              }) }}
            </template>
            <template v-if="item.acceptedBy">
              · {{ $t("devPanel.risks.acceptedBy", { who: item.acceptedBy }) }}:
              “{{ item.acceptedReason }}”
            </template>
            <template v-else-if="item.silenced && item.snoozedUntil">
              · {{ $t("devPanel.risks.snoozedUntil", { when: formatTime(item.snoozedUntil) }) }}
            </template>
          </span>
          <div class="ml-auto flex flex-wrap gap-1">
            <el-button v-if="item.risk.tab" size="small" text type="primary"
              @click="emit('open', item.risk.tab)">{{ $t("devPanel.risks.open") }} →</el-button>
            <template v-if="store.canWrite">
              <el-button v-if="item.silenced" size="small" text @click="reopen(item)">{{
                $t("devPanel.risks.reopen")
              }}</el-button>
              <template v-else>
                <el-dropdown trigger="click" @command="(days: number) => snooze(item, days)">
                  <el-button size="small" text>{{ $t("devPanel.risks.snooze") }} ▾</el-button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item v-for="days in [1, 7, 30]" :key="days" :command="days">{{
                        $t("devPanel.risks.forDays", { days })
                      }}</el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
                <el-button size="small" text @click="accept(item)">{{
                  $t("devPanel.risks.accept")
                }}</el-button>
              </template>
            </template>
          </div>
        </footer>
      </article>

      <details v-if="data?.resolved.length" class="mt-6">
        <summary class="cursor-pointer text-sm font-semibold">
          ✅ {{ $t("devPanel.risks.resolved", { count: data.resolved.length }) }}
        </summary>
        <el-table :data="data.resolved" size="small" class="mt-2">
          <el-table-column :label="$t('devPanel.risks.risk')" min-width="320">
            <template #default="{ row }">{{ row.risk.title }}</template>
          </el-table-column>
          <el-table-column :label="$t('devPanel.risks.seen')" width="170">
            <template #default="{ row }">{{ formatTime(row.firstSeen) }}</template>
          </el-table-column>
          <el-table-column :label="$t('devPanel.risks.resolvedAt')" width="170">
            <template #default="{ row }">{{ formatTime(row.resolvedAt) }}</template>
          </el-table-column>
        </el-table>
      </details>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage, ElMessageBox } from "element-plus";
import { useDevApi } from "../dev.api";
import { useDevStore } from "../dev.store";
import { IDevRiskItem, IDevRisks } from "../dev.types";
import {
  SEVERITY_TAG,
  copyToClipboard,
  devErrorMessage,
  formatTime,
  fromNow,
  openBlob,
  useAutoRefresh,
} from "../dev.utils";

const emit = defineEmits<{ (e: "open", tab: string): void }>();

const { t } = useI18n();
const store = useDevStore();
const { GET_RISKS, RUN_RISKS, SNOOZE_RISK, ACCEPT_RISK, REOPEN_RISK, GET_WEEKLY_REPORT } =
  useDevApi();

const data = ref<IDevRisks | null>(null);
const loading = ref(false);
const running = ref(false);
const reportLoading = ref(false);
const errorText = ref("");
const category = ref<"ALL" | "FORECAST" | "WEAKNESS">("ALL");
const area = ref<string | null>(null);
const showSilenced = ref(false);

const areas = computed(() =>
  [...new Set((data.value?.open ?? []).map((item) => item.risk.area))].sort(),
);

const visible = computed(() =>
  (data.value?.open ?? []).filter(
    (item) =>
      (showSilenced.value || !item.silenced) &&
      (category.value === "ALL" || item.risk.category === category.value) &&
      (!area.value || item.risk.area === area.value),
  ),
);

const daysText = (days: number) =>
  days < 1
    ? t("devPanel.risks.lessThanDay")
    : days < 60
      ? t("devPanel.risks.inDays", { days: Math.round(days) })
      : t("devPanel.risks.inMonths", { months: Math.round(days / 30) });

const load = async () => {
  loading.value = !data.value;
  try {
    data.value = (await GET_RISKS()).data;
    errorText.value = "";
  } catch (error) {
    errorText.value = await devErrorMessage(error);
  } finally {
    loading.value = false;
  }
};

const runNow = async () => {
  running.value = true;
  try {
    data.value = (await RUN_RISKS()).data;
    errorText.value = "";
  } catch (error) {
    errorText.value = await devErrorMessage(error);
  } finally {
    running.value = false;
  }
};

const snooze = async (item: IDevRiskItem, days: number) => {
  await act(() => SNOOZE_RISK(item.risk.key, days));
};

const accept = async (item: IDevRiskItem) => {
  try {
    const { value } = await ElMessageBox.prompt(
      t("devPanel.risks.acceptReason"),
      item.risk.title,
      { confirmButtonText: t("devPanel.risks.accept"), inputValue: "" },
    );
    await act(() => ACCEPT_RISK(item.risk.key, value));
  } catch {
    // bekor qilindi
  }
};

const reopen = (item: IDevRiskItem) => act(() => REOPEN_RISK(item.risk.key));

const act = async (call: () => Promise<unknown>) => {
  try {
    await call();
    await load();
  } catch (error) {
    ElMessage.error(await devErrorMessage(error));
  }
};

const openReport = async () => {
  reportLoading.value = true;
  try {
    openBlob((await GET_WEEKLY_REPORT()).data);
  } catch (error) {
    ElMessage.error(await devErrorMessage(error));
  } finally {
    reportLoading.value = false;
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
.risk-summary {
  display: flex;
  flex-wrap: wrap;
  align-items: stretch;
  gap: 10px;
}

.risk-summary__item {
  display: flex;
  flex-direction: column;
  min-width: 110px;
  padding: 10px 14px;
  border-radius: 12px;
  border: 1px solid var(--el-border-color);
  background: var(--el-fill-color-lighter);
}

.risk-summary__item b {
  font-size: 24px;
  line-height: 1.1;
  font-variant-numeric: tabular-nums;
}

.risk-summary__item span {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.risk-summary__item.is-critical b {
  color: #d14343;
}

.risk-summary__item.is-warning b {
  color: #d58a1f;
}

.risk-summary__item.is-info b {
  color: #3b82f6;
}

.risk-card {
  margin-bottom: 12px;
  padding: 14px 16px;
  border-radius: 12px;
  border: 1px solid var(--el-border-color);
  border-left-width: 4px;
  background: var(--el-bg-color);
  transition: box-shadow 0.15s ease;
}

.risk-card:hover {
  box-shadow: var(--el-box-shadow-light);
}

.risk-card.is-critical {
  border-left-color: #d14343;
}

.risk-card.is-warning {
  border-left-color: #e6a23c;
}

.risk-card.is-info {
  border-left-color: #3b82f6;
}

.risk-card.is-silenced {
  opacity: 0.6;
}

.risk-card__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.risk-card__title {
  flex-basis: 100%;
  margin: 6px 0 4px;
  font-size: 15px;
  font-weight: 650;
  line-height: 1.35;
}

.risk-card__impact,
.risk-card__line {
  margin: 4px 0;
  font-size: 13px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}

.risk-card__impact b,
.risk-card__line b {
  color: var(--el-text-color-secondary);
  font-weight: 600;
}

.risk-card__line code {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
}

.risk-card__sql {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin: 8px 0 2px;
}

.risk-card__sql pre {
  flex: 1;
  margin: 0;
  padding: 8px 10px;
  overflow-x: auto;
  font-size: 12px;
  border-radius: 8px;
  background: var(--el-fill-color-light);
}

.risk-card__foot {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
}
</style>
