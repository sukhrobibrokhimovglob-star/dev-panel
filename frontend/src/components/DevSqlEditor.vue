<template>
  <div
    ref="host"
    class="dev-sql-editor overflow-hidden rounded border border-gray-300 dark:border-gray-600"
  />
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useDark } from "@vueuse/core";
import { basicSetup } from "codemirror";
import { Compartment, EditorState, Prec } from "@codemirror/state";
import { EditorView, keymap, placeholder } from "@codemirror/view";
import { PostgreSQL, sql, SQLNamespace } from "@codemirror/lang-sql";
import { oneDark } from "@codemirror/theme-one-dark";

const props = defineProps<{
  modelValue: string;
  schema?: SQLNamespace;
  placeholder?: string;
}>();

const emit = defineEmits<{
  (e: "update:modelValue", value: string): void;
  (e: "run"): void;
}>();

const host = ref<HTMLElement>();
const isDark = useDark();
const language = new Compartment();
const theme = new Compartment();
let view: EditorView | null = null;

// Sxema keyinroq (asinxron) keladi, shuning uchun til kengaytmasi alohida qayta sozlanadi
const sqlLanguage = () =>
  sql({
    dialect: PostgreSQL,
    schema: props.schema,
    defaultSchema: "public",
    upperCaseKeywords: true,
  });

const editorTheme = EditorView.theme({
  "&": { fontSize: "13px" },
  "&.cm-focused": { outline: "none" },
  ".cm-scroller": {
    minHeight: "150px",
    maxHeight: "420px",
    fontFamily: "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace",
  },
});

onMounted(() => {
  view = new EditorView({
    parent: host.value,
    state: EditorState.create({
      doc: props.modelValue,
      extensions: [
        // Ctrl+Enter autocomplete'ning Enter'idan oldin ushlanishi kerak
        Prec.highest(
          keymap.of([
            {
              key: "Mod-Enter",
              run: () => {
                emit("run");
                return true;
              },
            },
          ]),
        ),
        basicSetup,
        language.of(sqlLanguage()),
        theme.of(isDark.value ? oneDark : []),
        editorTheme,
        placeholder(props.placeholder ?? ""),
        EditorView.updateListener.of((update) => {
          if (update.docChanged) {
            emit("update:modelValue", update.state.doc.toString());
          }
        }),
      ],
    }),
  });
});

watch(
  () => props.schema,
  () => view?.dispatch({ effects: language.reconfigure(sqlLanguage()) }),
);

watch(isDark, (dark) =>
  view?.dispatch({ effects: theme.reconfigure(dark ? oneDark : []) }),
);

// Tashqaridan o'zgartirilganda (masalan tozalash) editor matnini moslaymiz
watch(
  () => props.modelValue,
  (value) => {
    if (!view || value === view.state.doc.toString()) return;
    view.dispatch({
      changes: { from: 0, to: view.state.doc.length, insert: value },
    });
  },
);

onBeforeUnmount(() => {
  view?.destroy();
  view = null;
});
</script>

<style scoped>
.dev-sql-editor {
  transition:
    border-color 0.2s ease,
    box-shadow 0.25s ease;
}

.dev-sql-editor:focus-within {
  border-color: var(--el-color-primary);
  box-shadow: 0 0 0 3px var(--el-color-primary-light-8);
}
</style>
