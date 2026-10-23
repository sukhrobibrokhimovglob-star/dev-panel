<template>
  <div v-loading="loading && !alerts">
    <el-alert
      v-if="errorText"
      :title="errorText"
      type="error"
      show-icon
      :closable="false"
      class="mb-4"
    />
    <template v-if="alerts">
      <section class="mb-4 rounded-xl border border-gray-200 p-4 dark:border-gray-700">
        <h3 class="m-0 mb-3 text-base font-semibold dark:text-white">
          {{ $t("devPanel.alerts.channels") }}
        </h3>
        <div class="flex flex-wrap items-center gap-2">
          <el-tag :type="alerts.settings.telegram ? 'success' : 'info'" effect="dark"
            >Telegram: {{ alerts.settings.telegram ? "ON" : "OFF" }}</el-tag
          >
          <el-tag :type="alerts.settings.webhook ? 'success' : 'info'" effect="dark"
            >Webhook: {{ alerts.settings.webhook ? "ON" : "OFF" }}</el-tag
          >
          <el-tag effect="plain"
            >{{ $t("devPanel.alerts.summary") }}:
            {{ alerts.settings.dailySummaryAt || "—" }}</el-tag
          >
          <el-tag effect="plain">{{ alerts.settings.instance }}</el-tag>
          <el-button
            v-if="store.canWrite && (alerts.settings.telegram || alerts.settings.webhook)"
            class="ml-auto"
            type="primary"
            :loading="sending"
            @click="sendTest"
            >{{ $t("devPanel.alerts.test") }}</el-button
          >
        </div>
        <el-alert
          v-if="alerts.settings.lastDeliveryError"
          class="mt-3"
          type="error"
          :closable="false"
          :title="$t('devPanel.alerts.deliveryFailed')"
          :description="alerts.settings.lastDeliveryError"
        />
        <div
          v-if="!alerts.settings.telegram && !alerts.settings.webhook"
          class="mt-3 text-sm text-gray-500"
        >
          <p class="m-0 mb-2">{{ $t("devPanel.alerts.setupHint") }}</p>
          <pre class="dev-code">devpanel.alerts.telegram-bot-token=123456:ABC...
devpanel.alerts.telegram-chat-id=123456789</pre>
        </div>
      </section>

      <h3 class="m-0 mb-3 text-base font-semibold dark:text-white">
        {{ $t("devPanel.alerts.history") }}
      </h3>
      <el-empty v-if="!alerts.history.length" :description="$t('devPanel.alerts.none')" />
      <el-timeline v-else>
        <el-timeline-item
          v-for="(event, index) in alerts.history"
          :key="index"
          :timestamp="formatTime(event.time)"
          :color="event.resolved ? STATUS_COLORS.OK : STATUS_COLORS[event.status]"
        >
          <b>{{ event.resolved ? "✅ " : "" }}{{ event.title }}</b>
          <div v-if="event.detail" class="text-sm text-gray-500">{{ event.detail }}</div>
        </el-timeline-item>
      </el-timeline>
    </template>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import { useI18n } from "vue-i18n";
import { useDevApi } from "../dev.api";
import { useDevStore } from "../dev.store";
import { IDevAlerts } from "../dev.types";
import { STATUS_COLORS, devErrorMessage, formatTime } from "../dev.utils";

const { t } = useI18n();
const store = useDevStore();
const { GET_ALERTS, TEST_ALERT } = useDevApi();
const alerts = ref<IDevAlerts | null>(null);
const loading = ref(false);
const sending = ref(false);
const errorText = ref("");

const load = async () => {
  loading.value = true;
  try {
    alerts.value = (await GET_ALERTS()).data;
    errorText.value = "";
  } catch (error) {
    errorText.value = await devErrorMessage(error);
  } finally {
    loading.value = false;
  }
};

const sendTest = async () => {
  sending.value = true;
  try {
    await TEST_ALERT();
    ElMessage.success(t("devPanel.alerts.sent"));
  } catch (error) {
    ElMessage.error(await devErrorMessage(error));
  } finally {
    sending.value = false;
    load();
  }
};

onMounted(load);
</script>

<style scoped>
.dev-code {
  margin: 0;
  padding: 10px 12px;
  font-size: 12px;
  border-radius: 8px;
  background: var(--el-fill-color-light);
}
</style>
