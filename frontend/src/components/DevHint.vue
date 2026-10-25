<template>
  <el-tooltip placement="top" :show-after="150" popper-class="dev-hint-popper">
    <template #content>
      <div class="dev-hint">{{ $t(`devPanel.hints.${k}`) }}</div>
    </template>
    <span class="dev-hint__icon" :aria-label="$t(`devPanel.hints.${k}`)" tabindex="0">ⓘ</span>
  </el-tooltip>
</template>

<script setup lang="ts">
// Har bir raqam yonidagi ⓘ: nima ekani, normal qiymati va nima qilish kerakligi
defineProps<{ k: string }>();
</script>

<style scoped>
.dev-hint__icon {
  display: inline-block;
  margin-left: 4px;
  font-size: 12px;
  line-height: 1;
  color: var(--el-text-color-placeholder);
  cursor: help;
  vertical-align: middle;
}

.dev-hint__icon:hover,
.dev-hint__icon:focus {
  color: var(--el-color-primary);
  outline: none;
}

.dev-hint {
  max-width: 320px;
  white-space: pre-line;
  line-height: 1.45;
}
</style>
