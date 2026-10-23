<template>
  <div class="live">
    <div class="mb-3 flex flex-wrap items-center gap-2">
      <el-select v-model="level" style="width: 120px" @change="reconnect">
        <el-option v-for="l in LEVELS" :key="l" :label="l" :value="l" />
      </el-select>
      <el-input
        v-model="query"
        clearable
        :placeholder="$t('devPanel.liveLogs.filter')"
        style="width: 280px"
        @keyup.enter="reconnect"
        @clear="reconnect"
      />
      <el-button @click="reconnect">{{ $t("devPanel.search") }}</el-button>
      <el-button :type="paused ? 'warning' : 'default'" @click="paused = !paused">{{
        paused ? $t("devPanel.liveLogs.resume") : $t("devPanel.liveLogs.pause")
      }}</el-button>
      <el-button @click="lines = []">{{ $t("devPanel.liveLogs.clear") }}</el-button>
      <span class="ml-auto flex items-center gap-2 text-xs text-gray-500">
        <span class="live__dot" :class="{ 'is-on': connected }" />
        {{ connected ? $t("devPanel.liveLogs.live") : $t("devPanel.liveLogs.offline") }}
        · {{ lines.length }}
      </span>
    </div>
    <div ref="box" class="live__box" @scroll="onScroll">
      <div
        v-for="line in lines"
        :key="line.seq"
        class="live__line"
        :class="`is-${line.level.toLowerCase()}`"
      >
        <span class="live__time">{{ time(line.time) }}</span>
        <span class="live__level">{{ line.level }}</span>
        <span v-if="line.traceId" class="live__trace" @click="query = line.traceId; reconnect()">{{
          line.traceId.slice(0, 8)
        }}</span>
        <span class="live__logger">{{ shortLogger(line.logger) }}</span>
        <span class="live__msg">{{ line.message }}</span>
        <pre v-if="line.error" class="live__error">{{ line.error }}</pre>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from "vue";
import moment from "moment";
import { liveLogsUrl } from "../dev.api";
import { IDevLogLine } from "../dev.types";

const LEVELS = ["DEBUG", "INFO", "WARN", "ERROR"];
const KEEP = 3000;

const level = ref("INFO");
const query = ref("");
const lines = ref<IDevLogLine[]>([]);
const paused = ref(false);
const connected = ref(false);
const box = ref<HTMLElement>();
let source: EventSource | null = null;
let stickToBottom = true;

const time = (millis: number) => moment(millis).format("HH:mm:ss.SSS");
const shortLogger = (logger: string) =>
  logger.split(".").map((part, i, all) => (i < all.length - 1 ? part[0] : part)).join(".");

const onScroll = () => {
  const el = box.value;
  if (el) stickToBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 40;
};

// EventSource cookie'ni o'zi yuboradi (bir xil origin), shuning uchun login ishlaydi
const connect = () => {
  source = new EventSource(liveLogsUrl(level.value, query.value));
  source.onopen = () => (connected.value = true);
  source.onerror = () => (connected.value = false);
  source.addEventListener("lines", async (event: MessageEvent) => {
    connected.value = true;
    if (paused.value) return;
    const batch: IDevLogLine[] = JSON.parse(event.data);
    if (!batch.length) return;
    const merged = lines.value.concat(batch);
    lines.value = merged.length > KEEP ? merged.slice(merged.length - KEEP) : merged;
    if (stickToBottom) {
      await nextTick();
      box.value?.scrollTo({ top: box.value.scrollHeight });
    }
  });
};

const reconnect = () => {
  source?.close();
  lines.value = [];
  stickToBottom = true;
  connect();
};

onMounted(connect);
onBeforeUnmount(() => source?.close());
</script>

<style scoped>
.live__box {
  height: calc(100vh - 230px);
  min-height: 300px;
  overflow: auto;
  padding: 8px 10px;
  border-radius: 10px;
  background: #0d1117;
  color: #c9d1d9;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  line-height: 1.55;
}

.live__line {
  white-space: pre-wrap;
  word-break: break-word;
}

.live__time {
  color: #6e7681;
  margin-right: 8px;
}

.live__level {
  display: inline-block;
  width: 44px;
  font-weight: 700;
}

.is-error .live__level,
.is-error .live__msg {
  color: #ff7b72;
}

.is-warn .live__level {
  color: #e3b341;
}

.is-info .live__level {
  color: #58a6ff;
}

.is-debug .live__level {
  color: #8b949e;
}

.live__trace {
  margin-right: 8px;
  color: #d2a8ff;
  cursor: pointer;
}

.live__logger {
  margin-right: 8px;
  color: #7ee787;
}

.live__error {
  margin: 2px 0 6px 52px;
  color: #ffa198;
  font-size: 11px;
}

.live__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #909399;
}

.live__dot.is-on {
  background: #3ec972;
  animation: blink 1.6s infinite;
}

@keyframes blink {
  50% {
    opacity: 0.3;
  }
}
</style>
