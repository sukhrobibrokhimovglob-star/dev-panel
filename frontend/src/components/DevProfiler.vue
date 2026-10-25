<template>
  <div>
    <p class="mb-3 mt-0 text-sm text-gray-500">{{ $t("devPanel.profiler.hint") }}</p>
    <el-alert v-if="errorText" :title="errorText" type="error" show-icon :closable="false"
      class="mb-4" />

    <div class="mb-4 flex flex-wrap items-center gap-2">
      <template v-if="store.canManageAccounts">
        <el-select v-model="seconds" class="!w-28">
          <el-option v-for="value in [10, 20, 30, 60]" :key="value" :value="value"
            :label="`${value} s`" />
        </el-select>
        <el-select v-model="scope" class="!w-56">
          <el-option value="requests" :label="$t('devPanel.profiler.requests')" />
          <el-option value="all" :label="$t('devPanel.profiler.all')" />
        </el-select>
        <el-button type="primary" :loading="starting || running" @click="start">{{
          running ? $t("devPanel.profiler.recording") : $t("devPanel.profiler.start")
        }}</el-button>
      </template>
      <span v-else class="text-sm text-gray-500">{{ $t("devPanel.profiler.adminsOnly") }}</span>
      <el-select v-model="selectedId" class="ml-auto !w-72"
        :placeholder="$t('devPanel.profiler.pick')" @change="open">
        <el-option v-for="item in profiles" :key="item.id" :value="item.id"
          :label="`${formatTime(item.startedAt)} · ${item.seconds}s · ${item.scope} · ${item.status}`" />
      </el-select>
    </div>

    <el-empty v-if="!profile" :description="$t('devPanel.profiler.none')" />
    <template v-else>
      <div class="mb-2 flex flex-wrap items-center gap-3 text-sm">
        <span>{{ $t("devPanel.profiler.samples", { count: profile.threadSamples }) }}</span>
        <span class="text-gray-500">{{ $t("devPanel.profiler.threads", { count: profile.threads }) }}</span>
        <span class="legend"><i class="is-app" /> {{ $t("devPanel.profiler.yourCode") }}</span>
        <span class="legend"><i class="is-lib" /> {{ $t("devPanel.profiler.libraries") }}</span>
        <el-button v-if="zoomPath.length" size="small" @click="zoomPath = []">↺ {{
          $t("devPanel.profiler.resetZoom") }}</el-button>
        <span v-if="profile.status === 'RUNNING'" class="text-orange-500">⏺ {{
          $t("devPanel.profiler.recording") }}</span>
      </div>

      <div class="flame" :style="{ height: `${rows * ROW + 4}px` }">
        <div
          v-for="cell in cells"
          :key="cell.id"
          class="flame__cell"
          :class="cell.node.application ? 'is-app' : 'is-lib'"
          :style="{
            left: `${cell.x * 100}%`,
            width: `calc(${cell.width * 100}% - 1px)`,
            top: `${cell.depth * ROW}px`,
            filter: `brightness(${cell.shade})`,
          }"
          :title="`${cell.node.name}\n${cell.node.total} (${percent(cell.node.total)}), self ${cell.node.self}`"
          @click="zoom(cell.path)"
        >
          <span v-if="cell.width > 0.04">{{ shortName(cell.node.name) }}</span>
        </div>
      </div>

      <h4 class="mb-2 mt-5 text-sm">{{ $t("devPanel.profiler.hottest") }}
        <DevHint k="profilerSelf" /></h4>
      <el-table :data="profile.hottest" size="small" max-height="420">
        <el-table-column :label="$t('devPanel.profiler.method')" min-width="380">
          <template #default="{ row }">
            <el-tag v-if="row.application" size="small" type="primary" class="mr-2">{{
              $t("devPanel.profiler.yourCode") }}</el-tag>
            <code class="text-xs">{{ row.method }}</code>
          </template>
        </el-table-column>
        <el-table-column :label="$t('devPanel.profiler.self')" width="110" align="right">
          <template #default="{ row }">{{ percent(row.self) }}</template>
        </el-table-column>
        <el-table-column :label="$t('devPanel.profiler.total')" width="110" align="right">
          <template #default="{ row }">{{ percent(row.total) }}</template>
        </el-table-column>
      </el-table>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { useDevApi } from "../dev.api";
import { useDevStore } from "../dev.store";
import { IDevFlameNode, IDevProfile, IDevProfileSummary } from "../dev.types";
import { devErrorMessage, formatTime } from "../dev.utils";
import DevHint from "./DevHint.vue";

const ROW = 18;
const MAX_DEPTH = 80;

const store = useDevStore();
const { START_PROFILE, GET_PROFILES, GET_PROFILE } = useDevApi();
const profiles = ref<IDevProfileSummary[]>([]);
const profile = ref<IDevProfile | null>(null);
const selectedId = ref<string | null>(null);
const seconds = ref(20);
const scope = ref("requests");
const starting = ref(false);
const errorText = ref("");
const zoomPath = ref<string[]>([]);
let poll: ReturnType<typeof setInterval> | null = null;

const running = computed(() => profiles.value.some((item) => item.status === "RUNNING"));

// Kattalashtirilgan tugun: yo'l bo'yicha daraxtdan topiladi
const focusRoot = computed<IDevFlameNode | null>(() => {
  let node = profile.value?.root ?? null;
  for (const name of zoomPath.value) {
    node = node?.children.find((child) => child.name === name) ?? node;
  }
  return node;
});

interface ICell {
  id: string;
  node: IDevFlameNode;
  x: number;
  width: number;
  depth: number;
  path: string[];
  shade: number;
}

const cells = computed<ICell[]>(() => {
  const root = focusRoot.value;
  if (!root || !root.total) return [];
  const result: ICell[] = [];
  const walk = (node: IDevFlameNode, x: number, depth: number, path: string[]) => {
    const width = node.total / root.total;
    if (width < 0.002 || depth > MAX_DEPTH) return;
    result.push({ id: `${path.join(">")}`, node, x, width, depth, path,
      shade: 0.85 + ((hash(node.name) % 30) / 100) });
    let offset = x;
    for (const child of node.children) {
      walk(child, offset, depth + 1, [...path, child.name]);
      offset += child.total / root.total;
    }
  };
  walk(root, 0, 0, [...zoomPath.value]);
  return result;
});

const rows = computed(() => Math.max(1, ...cells.value.map((cell) => cell.depth + 1)));

const hash = (text: string) => {
  let value = 0;
  for (let i = 0; i < text.length; i++) value = (value * 31 + text.charCodeAt(i)) >>> 0;
  return value;
};

const shortName = (name: string) => {
  const parts = name.split(".");
  return parts.length > 2 ? parts.slice(-2).join(".") : name;
};

const percent = (count: number) =>
  profile.value?.root.total
    ? `${((count / profile.value.root.total) * 100).toFixed(1)}%`
    : "—";

// Tugunni bosish uni kattalashtiradi; eng yuqoridagini bosish bir pog'ona orqaga qaytaradi
const zoom = (path: string[]) => {
  zoomPath.value = path.length === zoomPath.value.length ? path.slice(0, -1) : path;
};

const loadList = async () => {
  try {
    profiles.value = (await GET_PROFILES()).data;
    errorText.value = "";
  } catch (error) {
    errorText.value = await devErrorMessage(error);
  }
};

const open = async (id: string) => {
  try {
    profile.value = (await GET_PROFILE(id)).data;
    zoomPath.value = [];
  } catch (error) {
    errorText.value = await devErrorMessage(error);
  }
};

const start = async () => {
  starting.value = true;
  try {
    const started = (await START_PROFILE(seconds.value, scope.value)).data;
    selectedId.value = started.id;
    profile.value = started;
    await loadList();
    watchRunning(started.id);
  } catch (error) {
    errorText.value = await devErrorMessage(error);
  } finally {
    starting.value = false;
  }
};

// Yozilayotgan profilni har 2 soniyada yangilab turamiz
const watchRunning = (id: string) => {
  stopPoll();
  poll = setInterval(async () => {
    await open(id);
    if (profile.value?.status === "DONE") {
      stopPoll();
      loadList();
    }
  }, 2000);
};

const stopPoll = () => {
  if (poll) clearInterval(poll);
  poll = null;
};

onMounted(async () => {
  await loadList();
  if (profiles.value.length) {
    selectedId.value = profiles.value[0].id;
    await open(profiles.value[0].id);
    if (profiles.value[0].status === "RUNNING") watchRunning(profiles.value[0].id);
  }
});
onBeforeUnmount(stopPoll);
</script>

<style scoped>
.flame {
  position: relative;
  width: 100%;
  overflow: hidden;
  border-radius: 8px;
  border: 1px solid var(--el-border-color-lighter);
  background: var(--el-fill-color-lighter);
}

.flame__cell {
  position: absolute;
  height: 17px;
  padding: 0 4px;
  overflow: hidden;
  font-size: 11px;
  line-height: 17px;
  white-space: nowrap;
  text-overflow: ellipsis;
  color: #1f2937;
  border-radius: 2px;
  cursor: pointer;
}

.flame__cell.is-app {
  background: #a78bfa;
}

.flame__cell.is-lib {
  background: #fbbf77;
}

.flame__cell:hover {
  outline: 1px solid #111827;
  z-index: 1;
}

.legend i {
  display: inline-block;
  width: 10px;
  height: 10px;
  margin-right: 4px;
  border-radius: 2px;
  vertical-align: -1px;
}

.legend i.is-app {
  background: #a78bfa;
}

.legend i.is-lib {
  background: #fbbf77;
}
</style>
