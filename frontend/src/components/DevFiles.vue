<template>
  <div>
    <div class="mb-4 flex flex-wrap items-center gap-3">
      <el-input
        v-model="search"
        :placeholder="$t('search')"
        clearable
        class="w-full sm:!w-72"
      />
      <el-date-picker
        v-if="!splitRange"
        v-model="range"
        type="daterange"
        value-format="YYYY-MM-DD"
        format="DD.MM.YYYY"
        :start-placeholder="$t('devPanel.files.from')"
        :end-placeholder="$t('devPanel.files.to')"
        :shortcuts="shortcuts"
        unlink-panels
        class="!w-72"
      />
      <div v-else class="dev-files__range">
        <el-date-picker
          v-model="rangeFrom"
          type="date"
          value-format="YYYY-MM-DD"
          format="DD.MM.YYYY"
          :placeholder="$t('devPanel.files.from')"
        />
        <el-date-picker
          v-model="rangeTo"
          type="date"
          value-format="YYYY-MM-DD"
          format="DD.MM.YYYY"
          :placeholder="$t('devPanel.files.to')"
        />
      </div>
      <el-button v-if="search || range" @click="clearFilters">{{
        $t("actions.resetFilters")
      }}</el-button>
      <el-button class="ml-auto" :loading="loading" @click="load">{{
        $t("actions.refresh")
      }}</el-button>
    </div>

    <el-breadcrumb separator="/" class="mb-3 font-mono text-sm">
      <el-breadcrumb-item v-for="crumb in breadcrumbs" :key="crumb.dir">
        <a
          v-if="crumb.dir !== currentDir"
          class="cursor-pointer"
          @click="openDir(crumb.dir)"
          >{{ crumb.label }}</a
        >
        <span v-else>{{ crumb.label }}</span>
      </el-breadcrumb-item>
    </el-breadcrumb>

    <el-alert
      v-if="errorText"
      :title="errorText"
      type="error"
      show-icon
      :closable="false"
      class="mb-4"
    />

    <el-table
      :data="rows"
      v-loading="loading"
      border
      stripe
      :default-sort="{ prop: 'lastModified', order: 'descending' }"
      :empty-text="range ? $t('devPanel.files.empty') : undefined"
      @row-dblclick="
        (row: IDevFileRow) => (row.isDir ? openDir(row.path) : open(row.path))
      "
    >
      <el-table-column
        :label="$t('devPanel.files.name')"
        prop="name"
        min-width="280"
        sortable
        show-overflow-tooltip
      >
        <template #default="{ row }">
          <a
            v-if="row.isDir"
            class="inline-flex cursor-pointer items-center gap-2 font-mono text-xs"
            style="color: var(--el-color-primary)"
            @click="openDir(row.path)"
          >
            <el-icon><Folder /></el-icon>{{ row.name }}/
          </a>
          <span v-else class="inline-flex items-center gap-2 font-mono text-xs">
            <el-icon><Document /></el-icon>{{ row.name }}
          </span>
        </template>
      </el-table-column>
      <el-table-column
        :label="$t('devPanel.files.size')"
        prop="sizeBytes"
        width="120"
        sortable
      >
        <template #default="{ row }">{{ formatBytes(row.sizeBytes) }}</template>
      </el-table-column>
      <el-table-column
        :label="$t('devPanel.files.modified')"
        prop="lastModified"
        width="180"
        sortable
      >
        <template #default="{ row }">{{
          formatDateTime(row.lastModified)
        }}</template>
      </el-table-column>
      <el-table-column width="210" align="center">
        <template #default="{ row }">
          <el-button v-if="row.isDir" size="small" @click="openDir(row.path)">
            {{ $t("devPanel.files.open") }}
          </el-button>
          <template v-else>
            <el-button size="small" @click="open(row.path)">
              {{ $t("devPanel.files.open") }}
            </el-button>
            <el-button
              size="small"
              type="primary"
              :loading="busyPath === row.path + ':save'"
              @click="save(row.path)"
            >
              {{ $t("actions.download") }}
            </el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>

    <DevFilePreview
      v-model="previewOpen"
      :folder="folder"
      :path="previewPath"
      @download="save"
    />
  </div>
</template>

<style scoped>
.dev-files__range {
  display: flex;
  gap: 8px;
  width: 100%;
}

.dev-files__range :deep(.el-date-editor) {
  flex: 1;
  min-width: 0;
  width: auto;
}
</style>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useMediaQuery } from "@vueuse/core";
import { ElMessage } from "element-plus";
import moment from "moment";
import { translate } from "../i18n";
import { Document, Folder } from "@element-plus/icons-vue";
import { useDevApi } from "../dev.api";
import { IDevFileEntry, TDevFileFolder } from "../dev.types";
import {
  devErrorMessage,
  formatBytes,
  formatDateTime,
  TDevRange,
  useRangeEnds,
} from "../dev.utils";
import DevFilePreview from "./DevFilePreview.vue";

const props = defineProps<{ folder: TDevFileFolder }>();

const { GET_FILES, DOWNLOAD_FILE } = useDevApi();

const files = ref<IDevFileEntry[]>([]);
const search = ref("");
const loading = ref(false);
const errorText = ref("");
const busyPath = ref("");
const previewOpen = ref(false);
const previewPath = ref("");
// Joriy papka: "" — ildiz (logs/ yoki docs/), aks holda "archive" kabi, oxirida "/" siz
const currentDir = ref("");

interface IDevFileRow extends IDevFileEntry {
  name: string;
  isDir: boolean;
}

// Sana oralig'i — o'zgartirilgan kun bo'yicha (brauzer vaqt zonasida), ikkala chegara ham kiradi
const range = ref<TDevRange>(null);
const { rangeFrom, rangeTo } = useRangeEnds(range);
const splitRange = useMediaQuery("(max-width: 767px)");

const daysAgo = (days: number) =>
  moment().subtract(days, "days").format("YYYY-MM-DD");

const shortcuts = computed(() => [
  {
    text: translate("devPanel.files.today"),
    value: () => [daysAgo(0), daysAgo(0)],
  },
  {
    text: translate("devPanel.files.yesterday"),
    value: () => [daysAgo(1), daysAgo(1)],
  },
  {
    text: translate("devPanel.files.last7"),
    value: () => [daysAgo(6), daysAgo(0)],
  },
  {
    text: translate("devPanel.files.last30"),
    value: () => [daysAgo(29), daysAgo(0)],
  },
]);

// Papka qatori (hajmi, sanasi) filtrdan o'tgan fayllardan yig'iladi — mos fayli yo'q papka ko'rinmaydi
const inRange = (file: IDevFileEntry) => {
  if (!range.value) return true;
  const day = moment(file.lastModified).format("YYYY-MM-DD");
  const [from, to] = range.value;
  return (!from || day >= from) && (!to || day <= to);
};

const clearFilters = () => {
  search.value = "";
  range.value = null;
};

const prefix = computed(() => (currentDir.value ? `${currentDir.value}/` : ""));

const breadcrumbs = computed(() => {
  const crumbs: { label: string; dir: string }[] = [
    { label: props.folder, dir: "" },
  ];
  let dir = "";
  for (const part of currentDir.value.split("/").filter(Boolean)) {
    dir = dir ? `${dir}/${part}` : part;
    crumbs.push({ label: part, dir });
  }
  return crumbs;
});

// Backend daraxtni tekis ro'yxat qilib qaytaradi — joriy papkaning bevosita fayllari va
// ichki papkalarini shu ro'yxatdan yig'amiz (papka hajmi — ichidagilar yig'indisi, sanasi — eng yangisi)
const rows = computed<IDevFileRow[]>(() => {
  const inDir = files.value.filter(
    (file) => file.path.startsWith(prefix.value) && inRange(file),
  );
  const term = search.value.trim().toLowerCase();
  if (term) {
    // Qidiruvda ichki papkalar ham ko'riladi, nom joriy papkaga nisbatan ko'rsatiladi
    return inDir
      .filter((file) => file.path.toLowerCase().includes(term))
      .map((file) => ({
        ...file,
        name: file.path.slice(prefix.value.length),
        isDir: false,
      }));
  }
  const dirs = new Map<string, IDevFileRow>();
  const result: IDevFileRow[] = [];
  for (const file of inDir) {
    const rest = file.path.slice(prefix.value.length);
    const slash = rest.indexOf("/");
    if (slash < 0) {
      result.push({ ...file, name: rest, isDir: false });
      continue;
    }
    const name = rest.slice(0, slash);
    const dir = dirs.get(name);
    if (dir) {
      dir.sizeBytes += file.sizeBytes;
      if (file.lastModified > dir.lastModified)
        dir.lastModified = file.lastModified;
    } else {
      dirs.set(name, {
        path: prefix.value + name,
        name,
        isDir: true,
        sizeBytes: file.sizeBytes,
        lastModified: file.lastModified,
      });
    }
  }
  return [...dirs.values(), ...result];
});

const openDir = (dir: string) => {
  currentDir.value = dir;
  search.value = "";
};

const load = async () => {
  loading.value = true;
  errorText.value = "";
  try {
    const { data } = await GET_FILES(props.folder);
    files.value = data;
    // Yangilangandan keyin papka yo'qolgan bo'lsa (masalan, arxiv tozalangan) — ildizga qaytamiz
    if (
      currentDir.value &&
      !data.some((file) => file.path.startsWith(prefix.value))
    ) {
      currentDir.value = "";
    }
  } catch (error) {
    errorText.value = await devErrorMessage(error);
  } finally {
    loading.value = false;
  }
};

// Basic auth sarlavhasi kerak bo'lgani uchun oddiy <a href> ishlamaydi — faylni blob qilib olamiz
const fetchBlob = async (path: string) => {
  busyPath.value = `${path}:save`;
  try {
    const { data } = await DOWNLOAD_FILE(props.folder, path);
    return data;
  } catch (error) {
    ElMessage.error(await devErrorMessage(error));
    return null;
  } finally {
    busyPath.value = "";
  }
};

// Yangi tabga emas, shu sahifaning o'zida modal oynada ochamiz
const open = (path: string) => {
  previewPath.value = path;
  previewOpen.value = true;
};

const save = async (path: string) => {
  const blob = await fetchBlob(path);
  if (!blob) return;
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = path.split("/").pop() || path;
  link.click();
  URL.revokeObjectURL(url);
};

onMounted(load);
</script>
