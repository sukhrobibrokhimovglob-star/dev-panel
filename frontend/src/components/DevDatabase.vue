<template>
  <div>
    <el-alert
      v-if="errorText"
      :title="errorText"
      type="error"
      show-icon
      :closable="false"
      class="mb-4"
    />

    <el-tabs v-model="tab" @tab-change="load">
      <!-- Umumiy -->
      <el-tab-pane :label="$t('devPanel.database.overview')" name="overview">
        <div v-if="overview" class="dev-stats-grid">
          <div>
            <span>{{ $t("devPanel.database.size") }}</span
            ><b>{{ formatBytes(overview.sizeBytes) }}</b>
          </div>
          <div :class="{ 'is-bad': connectionShare >= 0.75 }">
            <span>{{ $t("devPanel.database.connections") }}</span
            ><b>{{ overview.connections }} / {{ overview.maxConnections }}</b>
          </div>
          <div>
            <span>{{ $t("devPanel.database.ours") }}</span
            ><b>{{ overview.ownConnections }}</b>
          </div>
          <div>
            <span>{{ $t("devPanel.database.active") }}</span
            ><b>{{ overview.active }}</b>
          </div>
          <div :class="{ 'is-bad': overview.idleInTransaction > 0 }">
            <span>{{ $t("devPanel.database.idleInTx") }}</span
            ><b>{{ overview.idleInTransaction }}</b>
          </div>
          <div :class="{ 'is-bad': overview.blocked > 0 }">
            <span>{{ $t("devPanel.database.blocked") }}</span
            ><b>{{ overview.blocked }}</b>
          </div>
          <div :class="{ 'is-bad': overview.cacheHitRatio < 0.95 }">
            <span>{{ $t("devPanel.database.cacheHit") }}</span
            ><b>{{ (overview.cacheHitRatio * 100).toFixed(2) }}%</b>
          </div>
          <div :class="{ 'is-bad': overview.deadlocks > 0 }">
            <span>Deadlocks</span><b>{{ overview.deadlocks }}</b>
          </div>
          <div>
            <span>{{ $t("devPanel.database.tempFiles") }}</span
            ><b>{{ formatBytes(overview.tempBytes) }}</b>
          </div>
          <div>
            <span>Commit / rollback</span
            ><b>{{ overview.commits }} / {{ overview.rollbacks }}</b>
          </div>
        </div>
        <p v-if="overview" class="m-0 mt-3 text-xs text-gray-500">
          {{ overview.version }}
        </p>
      </el-tab-pane>

      <!-- Hozir ishlayotganlar -->
      <el-tab-pane :label="$t('devPanel.database.activity')" name="activity">
        <div class="mb-3 flex items-center gap-3">
          <el-switch
            v-model="hideIdle"
            :active-text="$t('devPanel.database.hideIdle')"
          />
          <span class="ml-auto text-xs text-gray-500">{{
            $t("devPanel.metrics.autoRefresh", { seconds: 5 })
          }}</span>
        </div>
        <el-table
          :data="visibleActivity"
          size="small"
          border
          :row-class-name="activityRowClass"
        >
          <el-table-column prop="pid" label="PID" width="80" />
          <el-table-column :label="$t('devPanel.database.state')" width="150">
            <template #default="{ row }">
              <el-tag :type="stateTag(row)" size="small">{{ row.state }}</el-tag>
              <div v-if="row.blockedBy.length" class="text-xs text-red-500">
                ⛔ {{ $t("devPanel.database.blockedBy") }}
                {{ row.blockedBy.join(", ") }}
              </div>
              <div v-else-if="row.waitEvent" class="text-xs text-gray-500">
                {{ row.waitType }}: {{ row.waitEvent }}
              </div>
            </template>
          </el-table-column>
          <el-table-column :label="$t('devPanel.api.duration')" width="100">
            <template #default="{ row }">
              <b :class="{ 'text-red-500': row.ms > 60000 && row.state !== 'idle' }">{{
                formatMs(row.ms)
              }}</b>
              <div v-if="row.transactionMs > 0" class="text-xs text-gray-500">
                tx {{ formatMs(row.transactionMs) }}
              </div>
            </template>
          </el-table-column>
          <el-table-column :label="$t('devPanel.database.client')" width="150">
            <template #default="{ row }">
              <div class="text-xs">{{ row.application || "—" }}</div>
              <div class="text-xs text-gray-500">
                {{ row.user }}@{{ row.client || "local" }}
              </div>
            </template>
          </el-table-column>
          <el-table-column label="SQL" min-width="320">
            <template #default="{ row }"
              ><code class="block max-h-24 overflow-auto whitespace-pre-wrap break-all text-xs">{{
                row.query
              }}</code></template
            >
          </el-table-column>
          <el-table-column v-if="canStop" width="110" align="center" fixed="right">
            <template #default="{ row }">
              <div class="flex flex-col items-center gap-1">
                <el-button
                  v-if="row.state === 'active'"
                  size="small"
                  type="warning"
                  plain
                  @click="stop(row, 'cancel')"
                  >Cancel</el-button
                >
                <el-button
                  v-if="row.state !== 'idle'"
                  size="small"
                  type="danger"
                  plain
                  class="!ml-0"
                  @click="stop(row, 'terminate')"
                  >Kill</el-button
                >
              </div>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- Sekin so'rovlar -->
      <el-tab-pane :label="$t('devPanel.database.statements')" name="statements">
        <el-table :data="statements" size="small" border>
          <el-table-column label="SQL" min-width="360">
            <template #default="{ row }"
              ><code class="block max-h-28 overflow-auto whitespace-pre-wrap break-all text-xs">{{
                row.query
              }}</code></template
            >
          </el-table-column>
          <el-table-column prop="calls" :label="$t('devPanel.api.calls')" width="90" align="right" sortable />
          <el-table-column prop="totalMs" label="total" width="90" align="right" sortable>
            <template #default="{ row }"><b>{{ formatMs(row.totalMs) }}</b></template>
          </el-table-column>
          <el-table-column prop="meanMs" label="avg" width="80" align="right" sortable>
            <template #default="{ row }">{{ formatMs(row.meanMs) }}</template>
          </el-table-column>
          <el-table-column prop="maxMs" label="max" width="80" align="right" sortable>
            <template #default="{ row }">{{ formatMs(row.maxMs) }}</template>
          </el-table-column>
          <el-table-column prop="rows" :label="$t('devPanel.database.rows')" width="90" align="right" />
          <el-table-column :label="$t('devPanel.database.cacheHit')" width="90" align="right">
            <template #default="{ row }">{{ (row.cacheHitRatio * 100).toFixed(0) }}%</template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- Jadvallar -->
      <el-tab-pane :label="$t('devPanel.database.tables')" name="tables">
        <el-table :data="tables" size="small" border>
          <el-table-column :label="$t('devPanel.database.table')" min-width="220">
            <template #default="{ row }">
              <code class="text-xs">{{ row.schema }}.{{ row.table }}</code>
              <div class="mt-1 flex flex-wrap gap-1">
                <el-tag v-if="row.missingIndex" type="danger" size="small">{{
                  $t("devPanel.database.missingIndex")
                }}</el-tag>
                <el-tag v-if="row.bloated" type="warning" size="small">{{
                  $t("devPanel.database.bloated")
                }}</el-tag>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="totalBytes" :label="$t('devPanel.database.size')" width="100" align="right" sortable>
            <template #default="{ row }">{{ formatBytes(row.totalBytes) }}</template>
          </el-table-column>
          <el-table-column prop="liveRows" :label="$t('devPanel.database.rows')" width="110" align="right" sortable />
          <el-table-column prop="deadRows" :label="$t('devPanel.database.deadRows')" width="110" align="right" sortable />
          <el-table-column prop="seqScans" label="Seq scans" width="100" align="right" sortable />
          <el-table-column prop="indexScans" label="Index scans" width="110" align="right" sortable />
          <el-table-column :label="$t('devPanel.database.vacuumed')" width="140">
            <template #default="{ row }">{{ fromNow(row.lastVacuum) }}</template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane :label="$t('devPanel.database.unused')" name="unused">
        <p class="m-0 mb-3 text-sm text-gray-500">
          {{ $t("devPanel.database.unusedHint") }}
        </p>
        <el-table :data="unused" size="small" border>
          <el-table-column :label="$t('devPanel.database.index')" min-width="260">
            <template #default="{ row }"
              ><code class="text-xs">{{ row.index }}</code>
              <div class="text-xs text-gray-500">
                {{ row.schema }}.{{ row.table }}
              </div></template
            >
          </el-table-column>
          <el-table-column :label="$t('devPanel.database.size')" width="120" align="right">
            <template #default="{ row }">{{ formatBytes(row.bytes) }}</template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- Qaytarilmagan ulanishlar (Hikari leak detection) -->
      <el-tab-pane
        :label="`${$t('devPanel.database.leaks')} (${leaks.length})`"
        name="leaks"
      >
        <p class="m-0 mb-3 text-sm text-gray-500">
          {{ $t("devPanel.database.leaksHint") }}
        </p>
        <el-empty v-if="!leaks.length" :image-size="70" />
        <div
          v-for="(leak, index) in leaks"
          :key="index"
          class="mb-3 rounded-lg border border-gray-200 p-3 dark:border-gray-700"
        >
          <div class="mb-2 text-sm">
            <b>{{ formatTime(leak.time) }}</b> · {{ leak.thread }}
          </div>
          <pre class="m-0 max-h-72 overflow-auto text-xs">{{ leak.stack }}</pre>
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { useI18n } from "vue-i18n";
import { useDevApi } from "../dev.api";
import { useDevStore } from "../dev.store";
import {
  IDevDbActivity,
  IDevDbOverview,
  IDevDbStatement,
  IDevDbTable,
  IDevLeak,
  IDevUnusedIndex,
} from "../dev.types";
import {
  devErrorMessage,
  formatBytes,
  formatMs,
  formatTime,
  fromNow,
  useAutoRefresh,
} from "../dev.utils";

const { t } = useI18n();
const store = useDevStore();
const api = useDevApi();

const tab = ref("overview");
const overview = ref<IDevDbOverview | null>(null);
const activity = ref<IDevDbActivity[]>([]);
const statements = ref<IDevDbStatement[]>([]);
const tables = ref<IDevDbTable[]>([]);
const unused = ref<IDevUnusedIndex[]>([]);
const leaks = ref<IDevLeak[]>([]);
const hideIdle = ref(true);
const errorText = ref("");

const canStop = computed(
  () => store.canWrite && store.account?.role !== "USER",
);
const connectionShare = computed(() =>
  overview.value
    ? overview.value.connections / Math.max(1, overview.value.maxConnections)
    : 0,
);
const visibleActivity = computed(() =>
  hideIdle.value
    ? activity.value.filter((a) => a.state !== "idle")
    : activity.value,
);

const stateTag = (row: IDevDbActivity) =>
  row.blockedBy.length
    ? "danger"
    : row.state?.startsWith("idle in transaction")
      ? "warning"
      : row.state === "active"
        ? "primary"
        : "info";

const activityRowClass = ({ row }: { row: IDevDbActivity }) =>
  row.blockedBy.length ? "row-blocked" : "";

const load = async () => {
  try {
    switch (tab.value) {
      case "overview":
        overview.value = (await api.GET_DB_OVERVIEW()).data;
        leaks.value = (await api.GET_DB_LEAKS()).data;
        break;
      case "activity":
        activity.value = (await api.GET_DB_ACTIVITY()).data;
        break;
      case "statements":
        statements.value = (await api.GET_DB_STATEMENTS()).data;
        break;
      case "tables":
        tables.value = (await api.GET_DB_TABLES()).data;
        break;
      case "unused":
        unused.value = (await api.GET_DB_UNUSED()).data;
        break;
      case "leaks":
        leaks.value = (await api.GET_DB_LEAKS()).data;
        break;
    }
    errorText.value = "";
  } catch (error) {
    errorText.value = await devErrorMessage(error);
  }
};

const stop = async (row: IDevDbActivity, mode: "cancel" | "terminate") => {
  try {
    await ElMessageBox.confirm(
      t(
        mode === "cancel"
          ? "devPanel.database.cancelConfirm"
          : "devPanel.database.killConfirm",
        { pid: row.pid },
      ),
      mode === "cancel" ? "Cancel" : "Kill",
      { type: "warning" },
    );
  } catch {
    return;
  }
  try {
    await api.STOP_DB_QUERY(row.pid, mode);
    ElMessage.success(`PID ${row.pid}: ${mode}`);
    load();
  } catch (error) {
    ElMessage.error(await devErrorMessage(error));
  }
};

// Faqat "Hozir ishlayotganlar" bo'limi tez-tez yangilanadi
const refresh = useAutoRefresh(() => {
  if (tab.value === "activity") load();
}, 5);
onMounted(() => {
  load();
  refresh.start();
});
onBeforeUnmount(refresh.stop);
</script>

<style scoped>
.dev-stats-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(170px, 1fr));
  gap: 10px;
}

.dev-stats-grid > div {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 12px;
  border-radius: 10px;
  background: var(--el-fill-color-light);
}

.dev-stats-grid span {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.dev-stats-grid b {
  font-size: 18px;
  font-variant-numeric: tabular-nums;
}

.dev-stats-grid > .is-bad b {
  color: var(--el-color-danger);
}

:deep(.row-blocked) {
  --el-table-tr-bg-color: rgba(209, 67, 67, 0.08);
}
</style>
