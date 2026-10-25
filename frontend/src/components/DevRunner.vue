<template>
  <div>
    <p class="mb-3 mt-0 text-sm text-gray-500">{{ $t("devPanel.runner.hint") }}</p>
    <el-alert v-if="!store.canWrite" type="info" :closable="false" show-icon class="mb-3"
      :title="$t('devPanel.runner.readOnly')" />

    <div class="runner-grid">
      <section class="runner-card">
        <div class="mb-3 flex flex-wrap gap-2">
          <el-select v-model="endpoint" filterable clearable class="min-w-0 flex-1"
            :placeholder="$t('devPanel.runner.pickEndpoint')" @change="fill">
            <el-option v-for="item in catalog" :key="item.endpoint" :value="item.endpoint"
              :label="item.endpoint">
              <span>{{ item.endpoint }}</span>
              <span class="ml-2 text-xs text-gray-400">{{ item.handler }}</span>
            </el-option>
          </el-select>
        </div>
        <div class="mb-3 flex gap-2">
          <el-select v-model="method" class="!w-28">
            <el-option v-for="name in METHODS" :key="name" :value="name" :label="name" />
          </el-select>
          <el-input v-model="path" placeholder="/v1/users?page=0" class="flex-1"
            @keydown.enter="send" />
        </div>

        <template v-if="details?.pathVariables.length || details?.query.length">
          <div class="runner-label">{{ $t("devPanel.runner.parameters") }}</div>
          <div v-for="param in [...(details?.pathVariables ?? []), ...(details?.query ?? [])]"
            :key="param.name" class="mb-2 flex items-center gap-2">
            <span class="w-40 truncate text-sm" :title="param.type">
              {{ param.name }}<b v-if="param.required" class="text-red-500">*</b>
            </span>
            <el-input v-model="values[param.name]" size="small" :placeholder="param.type"
              class="flex-1" @input="buildPath" />
          </div>
        </template>

        <div class="runner-label">
          {{ $t("devPanel.runner.headers") }}
          <el-button size="small" text @click="headers.push({ name: '', value: '' })">+</el-button>
        </div>
        <div v-for="(header, index) in headers" :key="index" class="mb-2 flex gap-2">
          <el-input v-model="header.name" size="small" placeholder="Authorization" class="w-40" />
          <el-input v-model="header.value" size="small" class="flex-1"
            :type="isSecret(header.name) ? 'password' : 'text'" show-password />
          <el-button size="small" text @click="headers.splice(index, 1)">✕</el-button>
        </div>
        <p class="m-0 text-xs text-gray-400">{{ $t("devPanel.runner.tokenNote") }}</p>

        <template v-if="hasBody">
          <div class="runner-label">{{ $t("devPanel.runner.body") }}
            <span v-if="details?.bodyType" class="text-xs font-normal text-gray-400">
              {{ shortType(details.bodyType) }}</span></div>
          <el-input v-model="body" type="textarea" :rows="10" class="runner-mono" />
        </template>

        <div class="mt-4 flex flex-wrap gap-2">
          <el-button type="primary" :loading="sending" :disabled="!store.canWrite || !path"
            @click="send">{{ $t("devPanel.runner.send") }}</el-button>
          <el-button :disabled="!path" @click="copyCurl">{{ $t("devPanel.runner.copyCurl") }}</el-button>
          <el-button :disabled="!path" @click="copyTest">{{ $t("devPanel.runner.copyTest") }}</el-button>
        </div>
      </section>

      <section class="runner-card">
        <el-empty v-if="!response" :description="$t('devPanel.runner.noResponse')" />
        <template v-else>
          <div class="mb-3 flex flex-wrap items-center gap-2">
            <el-tag :type="response.status === 0 || response.status >= 500 ? 'danger'
              : response.status >= 400 ? 'warning' : 'success'" effect="dark">
              {{ response.status || $t("devPanel.runner.failed") }}</el-tag>
            <span class="text-sm">{{ formatMs(response.ms) }}</span>
            <span class="text-sm text-gray-500">{{ formatBytes(response.bytes) }}</span>
            <el-button v-if="response.traceId" size="small" type="primary" plain class="ml-auto"
              @click="openWaterfall(response.traceId)">{{ $t("devPanel.runner.waterfall") }}</el-button>
          </div>
          <el-alert v-if="response.error" :title="response.error" type="error" :closable="false"
            class="mb-3" />
          <el-alert v-if="response.truncated" :title="$t('devPanel.runner.truncated')" type="info"
            :closable="false" class="mb-3" />
          <details class="mb-3">
            <summary class="cursor-pointer text-xs text-gray-500">{{ $t("devPanel.runner.headers") }}</summary>
            <pre class="runner-pre">{{ Object.entries(response.headers).map(([k, v]) => `${k}: ${v}`).join("\n") }}</pre>
          </details>
          <pre class="runner-pre runner-pre--body">{{ prettyJson(response.body) }}</pre>
        </template>
      </section>
    </div>

    <DevWaterfall v-model="waterfallVisible" :trace-id="waterfallId" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage, ElMessageBox } from "element-plus";
import { useDevApi } from "../dev.api";
import { useDevStore } from "../dev.store";
import {
  IDevCatalogEntry,
  IDevEndpointDetails,
  IDevRunnerReq,
  IDevRunnerResponse,
} from "../dev.types";
import {
  applicationBaseUrl,
  copyToClipboard,
  devErrorMessage,
  formatBytes,
  formatMs,
  prettyJson,
  toCurl,
} from "../dev.utils";
import DevWaterfall from "./DevWaterfall.vue";

const METHODS = ["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"];
const HISTORY_KEY = "dev_panel_runner";
const SECRET = /authorization|token|cookie|secret|password|api-?key/i;

const { t } = useI18n();
const store = useDevStore();
const { GET_CATALOG, GET_ENDPOINT_DETAILS, RUNNER_SEND } = useDevApi();

const catalog = ref<IDevCatalogEntry[]>([]);
const endpoint = ref<string | null>(null);
const details = ref<IDevEndpointDetails | null>(null);
const method = ref("GET");
const path = ref("");
const body = ref("");
const headers = ref<{ name: string; value: string }[]>([
  { name: "Authorization", value: "" },
]);
const values = reactive<Record<string, string>>({});
const response = ref<IDevRunnerResponse | null>(null);
const sending = ref(false);
const waterfallId = ref<string | null>(null);
const waterfallVisible = ref(false);

const openWaterfall = (id: string) => {
  waterfallId.value = id;
  waterfallVisible.value = true;
};

const hasBody = computed(() => !["GET", "HEAD", "OPTIONS"].includes(method.value));
const isSecret = (name: string) => SECRET.test(name);
const shortType = (type: string) => type.replace(/[\w.]+\./g, "");

const headerMap = () =>
  Object.fromEntries(
    headers.value.filter((h) => h.name.trim() && h.value.trim())
      .map((h) => [h.name.trim(), h.value]),
  );

// Tanlangan endpoint shabloni + kiritilgan qiymatlar → aniq yo'l
const buildPath = () => {
  if (!details.value) return;
  let built = details.value.pattern;
  for (const param of details.value.pathVariables) {
    const value = values[param.name];
    built = built.replace(new RegExp(`\\{${param.name}(:[^}]*)?\\}`),
      value ? encodeURIComponent(value) : `{${param.name}}`);
  }
  const query = details.value.query
    .filter((param) => values[param.name])
    .map((param) => `${encodeURIComponent(param.name)}=${encodeURIComponent(values[param.name])}`)
    .join("&");
  path.value = query ? `${built}?${query}` : built;
};

const fill = async (selected: string | null) => {
  details.value = null;
  Object.keys(values).forEach((key) => delete values[key]);
  if (!selected) return;
  try {
    const found = (await GET_ENDPOINT_DETAILS(selected)).data;
    details.value = found;
    method.value = found.method;
    for (const param of found.query) {
      if (param.example) values[param.name] = param.example;
    }
    for (const header of found.headers) {
      if (!headers.value.some((h) => h.name.toLowerCase() === header.name.toLowerCase())) {
        headers.value.push({ name: header.name, value: "" });
      }
    }
    body.value = found.bodyExample == null ? "" : JSON.stringify(found.bodyExample, null, 2);
    if (body.value && !headers.value.some((h) => h.name.toLowerCase() === "content-type")) {
      headers.value.push({ name: "Content-Type", value: "application/json" });
    }
    buildPath();
  } catch (error) {
    ElMessage.error(await devErrorMessage(error));
  }
};

const send = async () => {
  if (!path.value || !store.canWrite) return;
  const request: IDevRunnerReq = {
    method: method.value,
    path: path.value,
    headers: headerMap(),
    body: hasBody.value ? body.value : null,
  };
  if (hasBody.value || method.value === "DELETE") {
    try {
      await ElMessageBox.confirm(t("devPanel.runner.confirmWrite", { method: method.value }),
        t("devPanel.runner.realData"), { type: "warning" });
      request.confirmWrite = true;
    } catch {
      return;
    }
  }
  sending.value = true;
  try {
    response.value = (await RUNNER_SEND(request)).data;
    remember();
  } catch (error) {
    ElMessage.error(await devErrorMessage(error));
  } finally {
    sending.value = false;
  }
};

const copyCurl = () =>
  copyToClipboard(toCurl(applicationBaseUrl(), method.value, path.value,
    Object.fromEntries(Object.entries(headerMap()).map(([name, value]) =>
      [name, isSecret(name) ? "<TOKEN>" : value])),
    hasBody.value ? body.value : null));

// Shu so'rovdan MockMvc testi: kutilgan status — oxirgi javobniki
const copyTest = () => {
  const status = response.value?.status || 200;
  const name = (endpoint.value ?? `${method.value} ${path.value}`)
    .replace(/[^A-Za-z0-9]+/g, " ").trim().split(" ")
    .map((word, i) => (i === 0 ? word.toLowerCase() : word.charAt(0).toUpperCase() + word.slice(1)))
    .join("");
  const lines = [
    "@Test",
    `void ${name || "endpoint"}Answers${status}() throws Exception {`,
    `  mockMvc.perform(${method.value.toLowerCase()}("${path.value}")`,
    ...Object.entries(headerMap()).filter(([n]) => !isSecret(n))
      .map(([n, v]) => `      .header("${n}", "${v.replace(/"/g, '\\"')}")`),
    ...(hasBody.value && body.value
      ? [`      .content("""\n${body.value}\n      """)`]
      : []),
    `      )`,
    `      .andExpect(status().is(${status}));`,
    "}",
  ];
  copyToClipboard(lines.join("\n"));
};

// Oxirgi so'rov shu brauzerda eslab qolinadi (Authorization va boshqa sirlarsiz)
const remember = () => {
  try {
    const safe = headers.value.filter((h) => !isSecret(h.name));
    localStorage.setItem(HISTORY_KEY, JSON.stringify({ endpoint: endpoint.value,
      method: method.value, path: path.value, body: body.value, headers: safe }));
  } catch {
    // saqlanmasa ham ishlayveradi
  }
};

const restore = () => {
  try {
    const saved = JSON.parse(localStorage.getItem(HISTORY_KEY) ?? "null");
    if (!saved) return;
    endpoint.value = saved.endpoint;
    method.value = saved.method;
    path.value = saved.path;
    body.value = saved.body ?? "";
    headers.value = [{ name: "Authorization", value: "" },
      ...(saved.headers ?? []).filter((h: { name: string }) => h.name !== "Authorization")];
  } catch {
    // eslab qolinmagan
  }
};

onMounted(async () => {
  try {
    catalog.value = (await GET_CATALOG()).data;
  } catch {
    catalog.value = [];
  }
  const asked = store.takeFocus("runner");
  if (asked?.runner) {
    method.value = asked.runner.method;
    path.value = asked.runner.path;
    body.value = asked.runner.body ?? "";
    headers.value = [{ name: "Authorization", value: "" },
      ...Object.entries(asked.runner.headers).map(([name, value]) => ({ name, value }))];
    endpoint.value = asked.endpoint ?? null;
  } else if (asked?.endpoint) {
    endpoint.value = asked.endpoint;
    await fill(asked.endpoint);
  } else {
    restore();
  }
});
</script>

<style scoped>
.runner-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 16px;
}

@media (max-width: 1100px) {
  .runner-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

.runner-card {
  min-width: 0;
  padding: 16px;
  border-radius: 12px;
  border: 1px solid var(--el-border-color);
  background: var(--el-bg-color);
}

.runner-label {
  margin: 12px 0 6px;
  font-size: 13px;
  font-weight: 600;
}

.runner-mono :deep(textarea),
.runner-pre {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
}

.runner-pre {
  margin: 6px 0 0;
  padding: 10px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
  border-radius: 8px;
  background: var(--el-fill-color-light);
}

.runner-pre--body {
  max-height: 60vh;
}
</style>
