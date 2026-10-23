<template>
  <el-dialog
    v-model="visible"
    :title="path"
    :width="isMobile ? '100%' : '90%'"
    :fullscreen="isMobile"
    top="4vh"
    append-to-body
    destroy-on-close
    class="dev-preview"
    @closed="reset"
  >
    <div v-loading="loading" class="min-h-[200px]">
      <el-alert
        v-if="errorText"
        :title="errorText"
        type="error"
        show-icon
        :closable="false"
      />

      <template v-else-if="kind === 'text'">
        <div class="mb-3 flex flex-wrap items-center gap-3">
          <el-radio-group v-if="isMarkdown" v-model="mdView" size="small">
            <el-radio-button value="rendered">{{
              $t("devPanel.files.preview.rendered")
            }}</el-radio-button>
            <el-radio-button value="raw">{{
              $t("devPanel.files.preview.raw")
            }}</el-radio-button>
          </el-radio-group>
          <template v-if="!showRendered">
            <el-input
              v-model="lineFilter"
              :placeholder="$t('devPanel.files.preview.filter')"
              clearable
              class="w-full sm:!w-72"
            />
            <el-checkbox v-model="wrap">{{
              $t("devPanel.files.preview.wrap")
            }}</el-checkbox>
          </template>
          <span
            v-if="!showRendered"
            class="text-xs text-gray-500 dark:text-gray-400"
          >
            {{
              $t("devPanel.files.preview.lines", {
                shown: visibleLines.length,
                total: lines.length,
              })
            }}
          </span>
          <div class="ml-auto flex gap-2">
            <el-button size="small" @click="scrollTo('top')">{{
              $t("devPanel.files.preview.toTop")
            }}</el-button>
            <el-button size="small" @click="scrollTo('end')">{{
              $t("devPanel.files.preview.toEnd")
            }}</el-button>
          </div>
        </div>

        <el-alert
          v-if="truncatedFrom"
          :title="
            $t('devPanel.files.preview.truncated', {
              size: formatBytes(truncatedFrom),
              shown: formatBytes(MAX_TEXT_BYTES),
            })
          "
          type="warning"
          show-icon
          :closable="false"
          class="mb-3"
        />

        <div
          v-if="showRendered"
          ref="textBox"
          class="dev-preview__md"
          v-html="renderedHtml"
        />
        <pre
          v-else
          ref="textBox"
          class="dev-preview__text"
          :class="{ 'is-wrapped': wrap }"
          >{{ visibleText || $t("devPanel.files.preview.empty") }}</pre>
      </template>

      <img
        v-else-if="kind === 'image'"
        :src="objectUrl"
        :alt="path"
        class="mx-auto max-h-[78vh] max-w-full"
      />

      <iframe
        v-else-if="kind === 'pdf'"
        :src="objectUrl"
        :title="path"
        class="h-[78vh] w-full border-0"
      />

      <el-empty
        v-else-if="kind === 'binary'"
        :description="$t('devPanel.files.preview.unsupported')"
      />
    </div>

    <template #footer>
      <el-button @click="visible = false">{{ $t("actions.close") }}</el-button>
      <el-button
        type="primary"
        :disabled="!blob"
        @click="emit('download', path)"
        >{{ $t("actions.download") }}</el-button
      >
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from "vue";
import { useMediaQuery } from "@vueuse/core";
import DOMPurify from "dompurify";
import { marked } from "marked";
import { useDevApi } from "../dev.api";
import { TDevFileFolder } from "../dev.types";
import { devErrorMessage, formatBytes } from "../dev.utils";

type TPreviewKind = "text" | "image" | "pdf" | "binary" | "";

const props = defineProps<{ folder: TDevFileFolder; path: string }>();
const emit = defineEmits<{ (e: "download", path: string): void }>();
const visible = defineModel<boolean>({ required: true });

// Katta loglarni to'liq DOM'ga chiqarish brauzerni qotiradi — faqat oxirgi qismini ko'rsatamiz,
// chunki logda eng kerakli yozuvlar oxirida bo'ladi
const MAX_TEXT_BYTES = 2 * 1024 * 1024;
const IMAGE_EXT = ["png", "jpg", "jpeg", "gif", "svg", "webp", "bmp"];
const TEXT_EXT = [
  "log",
  "txt",
  "md",
  "markdown",
  "json",
  "xml",
  "yml",
  "yaml",
  "csv",
  "sql",
  "properties",
  "conf",
  "ini",
  "html",
  "sh",
  "out",
  "err",
];
const BINARY_EXT = ["gz", "zip", "tar", "7z", "rar", "jar", "xlsx", "docx"];

const { DOWNLOAD_FILE } = useDevApi();
const isMobile = useMediaQuery("(max-width: 639px)");

const loading = ref(false);
const errorText = ref("");
const kind = ref<TPreviewKind>("");
const blob = ref<Blob | null>(null);
const objectUrl = ref("");
const text = ref("");
const truncatedFrom = ref(0);
const lineFilter = ref("");
const wrap = ref(false);
const textBox = ref<HTMLElement>();
const mdView = ref<"rendered" | "raw">("rendered");

const isMarkdown = computed(
  () =>
    kind.value === "text" && ["md", "markdown"].includes(extension(props.path)),
);
const showRendered = computed(
  () => isMarkdown.value && mdView.value === "rendered",
);

// Fayl serverdan keladi — ichidagi HTML/skriptlar ishga tushmasligi uchun tozalaymiz
const renderedHtml = computed(() =>
  showRendered.value
    ? DOMPurify.sanitize(marked.parse(text.value, { async: false }) as string)
    : "",
);

const lines = computed(() => (text.value ? text.value.split(/\r?\n/) : []));

const visibleLines = computed(() => {
  const term = lineFilter.value.trim().toLowerCase();
  if (!term) return lines.value;
  return lines.value.filter((line) => line.toLowerCase().includes(term));
});

const visibleText = computed(() => visibleLines.value.join("\n"));

const extension = (path: string) =>
  path.split("/").pop()?.split(".").pop()?.toLowerCase() ?? "";

// Kengaytmasi noma'lum fayllar (masalan, app.log.1) uchun: boshida NUL bayt bo'lsa — binar
const looksBinary = async (data: Blob) => {
  const head = new Uint8Array(await data.slice(0, 8192).arrayBuffer());
  return head.includes(0);
};

const detectKind = async (path: string, data: Blob): Promise<TPreviewKind> => {
  const ext = extension(path);
  if (IMAGE_EXT.includes(ext)) return "image";
  if (ext === "pdf") return "pdf";
  if (TEXT_EXT.includes(ext)) return "text";
  if (BINARY_EXT.includes(ext)) return "binary";
  return (await looksBinary(data)) ? "binary" : "text";
};

const readText = async (data: Blob) => {
  if (data.size <= MAX_TEXT_BYTES) {
    truncatedFrom.value = 0;
    return data.text();
  }
  truncatedFrom.value = data.size;
  const tail = await data.slice(data.size - MAX_TEXT_BYTES).text();
  // Kesilgan joydagi chala qatorni tashlab yuboramiz
  return tail.slice(tail.indexOf("\n") + 1);
};

const scrollTo = (where: "top" | "end") => {
  const box = textBox.value;
  if (box) box.scrollTop = where === "top" ? 0 : box.scrollHeight;
};

const reset = () => {
  if (objectUrl.value) URL.revokeObjectURL(objectUrl.value);
  objectUrl.value = "";
  blob.value = null;
  text.value = "";
  kind.value = "";
  errorText.value = "";
  truncatedFrom.value = 0;
  lineFilter.value = "";
  mdView.value = "rendered";
};

const load = async () => {
  reset();
  loading.value = true;
  try {
    const { data } = await DOWNLOAD_FILE(props.folder, props.path);
    blob.value = data;
    kind.value = await detectKind(props.path, data);
    if (kind.value === "text") {
      text.value = await readText(data);
    } else if (kind.value === "pdf") {
      // Server octet-stream qaytarsa iframe PDF'ni ko'rsatmay yuklab yuboradi
      objectUrl.value = URL.createObjectURL(
        new Blob([data], { type: "application/pdf" }),
      );
    } else if (kind.value === "image") {
      const type =
        extension(props.path) === "svg" ? "image/svg+xml" : data.type;
      objectUrl.value = URL.createObjectURL(new Blob([data], { type }));
    }
  } catch (error) {
    errorText.value = await devErrorMessage(error);
  } finally {
    loading.value = false;
  }
  // Logning eng yangi yozuvlari oxirida — darhol o'sha yerga tushiramiz
  if (kind.value === "text" && props.folder === "logs") {
    await nextTick();
    scrollTo("end");
  }
};

watch(
  () => [visible.value, props.path] as const,
  ([open, path]) => {
    if (open && path) load();
  },
  { immediate: true },
);
</script>

<style scoped>
.dev-preview__text {
  max-height: 68vh;
  overflow: auto;
  margin: 0;
  padding: 12px 14px;
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-primary);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre;
}

/* Mobilda dialog to'liq ekran — kontent balandligini ekran bo'yicha olamiz */
@media (max-width: 639px) {
  .dev-preview__text,
  .dev-preview__md {
    max-height: calc(100dvh - 240px);
  }

  .dev-preview__md {
    padding: 12px 14px;
  }
}

.dev-preview__text.is-wrapped {
  white-space: pre-wrap;
  word-break: break-all;
}

/* Tailwind preflight sarlavha/ro'yxat uslublarini nolga tushiradi — v-html ichini qaytadan bezaymiz */
.dev-preview__md {
  max-height: 68vh;
  overflow: auto;
  padding: 16px 24px;
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  color: var(--el-text-color-primary);
  font-size: 14px;
  line-height: 1.65;
  overflow-wrap: anywhere;
}

.dev-preview__md :deep(h1),
.dev-preview__md :deep(h2),
.dev-preview__md :deep(h3),
.dev-preview__md :deep(h4) {
  margin: 1.2em 0 0.5em;
  font-weight: 600;
  line-height: 1.3;
}

.dev-preview__md :deep(h1) {
  font-size: 1.7em;
  padding-bottom: 0.3em;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.dev-preview__md :deep(h2) {
  font-size: 1.35em;
  padding-bottom: 0.25em;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.dev-preview__md :deep(h3) {
  font-size: 1.15em;
}

.dev-preview__md :deep(> :first-child) {
  margin-top: 0;
}

.dev-preview__md :deep(p),
.dev-preview__md :deep(ul),
.dev-preview__md :deep(ol),
.dev-preview__md :deep(pre),
.dev-preview__md :deep(table),
.dev-preview__md :deep(blockquote) {
  margin: 0 0 0.9em;
}

.dev-preview__md :deep(ul) {
  list-style: disc;
  padding-left: 1.6em;
}

.dev-preview__md :deep(ol) {
  list-style: decimal;
  padding-left: 1.6em;
}

.dev-preview__md :deep(li + li) {
  margin-top: 0.25em;
}

.dev-preview__md :deep(a) {
  color: var(--el-color-primary);
  text-decoration: underline;
}

.dev-preview__md :deep(code) {
  padding: 0.15em 0.4em;
  border-radius: 4px;
  background: var(--el-fill-color);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 0.88em;
}

.dev-preview__md :deep(pre) {
  padding: 12px 14px;
  overflow: auto;
  border-radius: 6px;
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color-lighter);
}

.dev-preview__md :deep(pre code) {
  padding: 0;
  background: none;
  font-size: 12px;
  line-height: 1.5;
}

.dev-preview__md :deep(table) {
  display: block;
  max-width: 100%;
  overflow: auto;
  border-collapse: collapse;
}

.dev-preview__md :deep(th),
.dev-preview__md :deep(td) {
  padding: 6px 12px;
  border: 1px solid var(--el-border-color);
  text-align: left;
}

.dev-preview__md :deep(th) {
  background: var(--el-fill-color-light);
  font-weight: 600;
}

.dev-preview__md :deep(blockquote) {
  padding: 0 1em;
  border-left: 4px solid var(--el-border-color);
  color: var(--el-text-color-secondary);
}

.dev-preview__md :deep(hr) {
  margin: 1.2em 0;
  border: 0;
  border-top: 1px solid var(--el-border-color);
}

.dev-preview__md :deep(img) {
  max-width: 100%;
}

/* Fayl yuklangach kontent sekin paydo bo'ladi */
.dev-preview__text,
.dev-preview__md,
img,
iframe {
  animation: dev-preview-in 0.35s cubic-bezier(0.22, 1, 0.36, 1) both;
}

@keyframes dev-preview-in {
  from {
    opacity: 0;
    transform: translateY(8px);
  }
}

@media (prefers-reduced-motion: reduce) {
  .dev-preview__text,
  .dev-preview__md,
  img,
  iframe {
    animation: none;
  }
}
</style>

<style>
/* Element Plus o'zgaruvchilari loyihada dark rejimga o'tmaydi (faqat .el-dialog foni qo'lda qoraytirilgan),
   shuning uchun matn qora, tugma va kod bloklari oq bo'lib qolardi — dialog ichida palitrani qayta belgilaymiz */
:is(.dark, [data-theme="dark"]) .dev-preview {
  --el-bg-color: #26262b;
  --el-bg-color-overlay: #2c2c32;
  --el-fill-color-blank: #26262b;
  --el-fill-color: #3a3a42;
  --el-fill-color-light: #1e1e22;
  --el-fill-color-lighter: #2a2a30;
  --el-text-color-primary: #ececf1;
  --el-text-color-regular: #d4d4d8;
  --el-text-color-secondary: #a1a1aa;
  --el-text-color-placeholder: #71717a;
  --el-border-color: #45454d;
  --el-border-color-light: #3f3f46;
  --el-border-color-lighter: #38383f;
  --el-color-primary-light-3: #6d95e0;
  --el-color-primary-light-5: #4a70b8;
  --el-color-primary-light-7: #2f4a7a;
  --el-color-primary-light-8: #263b60;
  --el-color-primary-light-9: #1f2c44;
  --el-mask-color: rgba(22, 22, 24, 0.7);
}

:is(.dark, [data-theme="dark"]) .dev-preview__md {
  background: #1b1b1f;
  border-color: #38383f;
}

:is(.dark, [data-theme="dark"]) .dev-preview__text {
  background: #1b1b1f;
  border-color: #38383f;
  color: #d4d4d8;
}

:is(.dark, [data-theme="dark"]) .dev-preview__md h1,
:is(.dark, [data-theme="dark"]) .dev-preview__md h2,
:is(.dark, [data-theme="dark"]) .dev-preview__md h3,
:is(.dark, [data-theme="dark"]) .dev-preview__md h4 {
  color: #fafafa;
}

:is(.dark, [data-theme="dark"]) .dev-preview__md a {
  color: #7aa7ff;
}

:is(.dark, [data-theme="dark"]) .dev-preview__md code {
  background: #2f2f36;
  color: #f0b37e;
}

:is(.dark, [data-theme="dark"]) .dev-preview__md pre {
  background: #141417;
  border-color: #2e2e34;
}

:is(.dark, [data-theme="dark"]) .dev-preview__md pre code {
  background: none;
  color: #d4d4d8;
}

:is(.dark, [data-theme="dark"]) .dev-preview__md th {
  background: #26262b;
  color: #fafafa;
}

:is(.dark, [data-theme="dark"]) .dev-preview__md tr:nth-child(2n) td {
  background: #202024;
}

:is(.dark, [data-theme="dark"]) .dev-preview__md blockquote {
  border-left-color: #4a70b8;
  color: #a1a1aa;
}
</style>
