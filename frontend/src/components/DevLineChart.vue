<template>
  <div class="dev-chart">
    <div class="dev-chart__head">
      <h4 class="dev-chart__title">{{ title }}</h4>
      <div class="dev-chart__legend">
        <span v-for="item in series" :key="item.name">
          <i :style="{ background: item.color }" />{{ item.name }}
          <b v-if="latest(item) != null">{{ format(latest(item)) }}</b>
        </span>
      </div>
    </div>

    <div
      v-if="times.length > 1"
      ref="plot"
      class="dev-chart__plot"
      @mousemove="onMove"
      @mouseleave="hover = null"
    >
      <span class="dev-chart__axis dev-chart__axis--top">{{
        format(top)
      }}</span>
      <span class="dev-chart__axis dev-chart__axis--bottom">{{
        format(0)
      }}</span>
      <svg :viewBox="`0 0 ${W} ${H}`" preserveAspectRatio="none">
        <line
          v-for="y in [0.25, 0.5, 0.75]"
          :key="y"
          x1="0"
          :x2="W"
          :y1="H * y"
          :y2="H * y"
          class="dev-chart__grid"
        />
        <path
          v-for="item in series"
          :key="item.name"
          :d="path(item.values)"
          :stroke="item.color"
          fill="none"
          stroke-width="1.6"
          vector-effect="non-scaling-stroke"
          stroke-linejoin="round"
        />
        <line
          v-if="hover != null"
          :x1="x(hover)"
          :x2="x(hover)"
          y1="0"
          :y2="H"
          class="dev-chart__cursor"
        />
      </svg>
      <div
        v-if="hover != null"
        class="dev-chart__tip"
        :class="{ 'is-left': hover > times.length / 2 }"
        :style="{ left: `${(hover / (times.length - 1)) * 100}%` }"
      >
        <div class="dev-chart__tip-time">{{ formatTime(times[hover]) }}</div>
        <div v-for="item in series" :key="item.name">
          <i :style="{ background: item.color }" />{{ item.name }}:
          <b>{{ format(item.values[hover]) }}</b>
        </div>
      </div>
    </div>
    <p v-else class="dev-chart__empty">{{ $t("devPanel.metrics.noData") }}</p>

    <div v-if="times.length > 1" class="dev-chart__times">
      <span>{{ formatTime(times[0]) }}</span>
      <span>{{ formatTime(times[times.length - 1]) }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import moment from "moment";

export interface IDevChartSeries {
  name: string;
  color: string;
  values: number[];
}

const props = withDefaults(
  defineProps<{
    title: string;
    times: string[];
    series: IDevChartSeries[];
    // Berilmasa eng katta qiymatdan olinadi (masalan, % uchun 100)
    max?: number;
    format?: (value: number) => string;
  }>(),
  { max: undefined, format: (value: number) => String(Math.round(value)) },
);

const W = 600;
const H = 140;

const plot = ref<HTMLElement>();
const hover = ref<number | null>(null);

const top = computed(() => {
  if (props.max != null) return props.max;
  const highest = Math.max(
    0,
    ...props.series.flatMap((item) => item.values.filter((v) => v != null)),
  );
  return highest > 0 ? highest * 1.1 : 1;
});

const x = (index: number) => (index / (props.times.length - 1)) * W;
const y = (value: number) => H - (Math.min(value, top.value) / top.value) * H;

const path = (values: number[]) =>
  values
    .map((value, index) =>
      value == null
        ? ""
        : `${index === 0 || values[index - 1] == null ? "M" : "L"}${x(index).toFixed(1)},${y(value).toFixed(1)}`,
    )
    .join("");

const latest = (item: IDevChartSeries) =>
  item.values.length ? item.values[item.values.length - 1] : null;

const formatTime = (value: string) => moment(value).format("HH:mm");

const onMove = (event: MouseEvent) => {
  const box = plot.value?.getBoundingClientRect();
  if (!box || props.times.length < 2) return;
  const ratio = Math.min(Math.max((event.clientX - box.left) / box.width, 0), 1);
  hover.value = Math.round(ratio * (props.times.length - 1));
};
</script>

<style scoped>
.dev-chart__head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: space-between;
  gap: 4px 12px;
  margin-bottom: 8px;
}

.dev-chart__title {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
}

.dev-chart__legend {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.dev-chart__legend i,
.dev-chart__tip i {
  display: inline-block;
  width: 8px;
  height: 8px;
  margin-right: 4px;
  border-radius: 2px;
}

.dev-chart__legend b {
  margin-left: 4px;
  color: var(--el-text-color-primary);
  font-variant-numeric: tabular-nums;
}

.dev-chart__plot {
  position: relative;
  height: 140px;
  border-left: 1px solid var(--el-border-color);
  border-bottom: 1px solid var(--el-border-color);
  cursor: crosshair;
}

.dev-chart__plot svg {
  display: block;
  width: 100%;
  height: 100%;
}

.dev-chart__grid {
  stroke: var(--el-border-color-lighter);
  stroke-dasharray: 3 4;
  vector-effect: non-scaling-stroke;
}

.dev-chart__cursor {
  stroke: var(--el-text-color-placeholder);
  vector-effect: non-scaling-stroke;
}

.dev-chart__axis {
  position: absolute;
  left: 4px;
  font-size: 11px;
  color: var(--el-text-color-placeholder);
  pointer-events: none;
}

.dev-chart__axis--top {
  top: 0;
}

.dev-chart__axis--bottom {
  bottom: 2px;
}

.dev-chart__tip {
  position: absolute;
  top: 8px;
  z-index: 1;
  padding: 6px 8px;
  margin-left: 10px;
  font-size: 12px;
  white-space: nowrap;
  pointer-events: none;
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  background: var(--el-bg-color-overlay);
  box-shadow: var(--el-box-shadow-light);
}

.dev-chart__tip.is-left {
  transform: translateX(calc(-100% - 20px));
}

.dev-chart__tip-time {
  margin-bottom: 2px;
  color: var(--el-text-color-secondary);
}

.dev-chart__times {
  display: flex;
  justify-content: space-between;
  margin-top: 4px;
  font-size: 11px;
  color: var(--el-text-color-placeholder);
}

.dev-chart__empty {
  margin: 0;
  padding: 40px 0;
  text-align: center;
  font-size: 13px;
  color: var(--el-text-color-placeholder);
}
</style>
