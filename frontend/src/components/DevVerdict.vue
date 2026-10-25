<template>
  <span class="dev-verdict" :class="`is-${level}`">
    <span class="dev-verdict__dot" />
    {{ text || $t(`devPanel.verdict.${level}`) }}
  </span>
</template>

<script setup lang="ts">
// Kartochka boshidagi xulosa: yashil — joyida, sariq — kuzatish kerak, qizil — muammo
withDefaults(
  defineProps<{ level: "ok" | "warn" | "bad" | "unknown"; text?: string }>(),
  { text: "" },
);
</script>

<style scoped>
.dev-verdict {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 2px 9px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
  border: 1px solid currentColor;
}

.dev-verdict__dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: currentColor;
}

.is-ok {
  color: #2fa863;
}

.is-warn {
  color: #d58a1f;
}

.is-bad {
  color: #d14343;
}

.is-unknown {
  color: var(--el-text-color-secondary);
}
</style>
