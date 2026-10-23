<template>
  <div>
    <section class="mb-4 rounded-xl border border-gray-200 p-4 dark:border-gray-700">
      <h3 class="m-0 mb-2 text-base font-semibold dark:text-white">Google Authenticator</h3>
      <p class="m-0 text-sm text-gray-600 dark:text-gray-300">
        {{ $t("devPanel.security.enrolled") }}:
        <b>{{ formatTime(store.account?.totpEnabledAt) }}</b>
        <template v-if="store.account?.totpEnabledIp">
          · IP <b>{{ store.account.totpEnabledIp }}</b></template
        >
      </p>
      <p class="m-0 mt-1 text-xs text-gray-500">{{ $t("devPanel.security.enrolledHint") }}</p>
    </section>

    <div class="mb-3 flex flex-wrap items-center gap-3">
      <h3 class="m-0 text-base font-semibold dark:text-white">
        {{ $t("devPanel.security.sessions") }}
      </h3>
      <el-button
        class="ml-auto"
        type="danger"
        plain
        :disabled="sessions.length < 2"
        @click="endOthers"
        >{{ $t("devPanel.security.endOthers") }}</el-button
      >
    </div>
    <el-table v-loading="loading" :data="sessions" size="small" border>
      <el-table-column label="IP" width="150">
        <template #default="{ row }">
          {{ row.clientIp || "—" }}
          <el-tag v-if="row.current" size="small" type="success" class="ml-1">{{
            $t("devPanel.security.thisOne")
          }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="$t('devPanel.security.browser')" min-width="260">
        <template #default="{ row }"
          ><span class="text-xs">{{ row.userAgent || "—" }}</span></template
        >
      </el-table-column>
      <el-table-column :label="$t('devPanel.security.loggedIn')" width="140">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column :label="$t('devPanel.security.lastActive')" width="140">
        <template #default="{ row }">{{ fromNow(row.lastSeenAt) }}</template>
      </el-table-column>
      <el-table-column width="100" align="center">
        <template #default="{ row }">
          <el-button v-if="!row.current" link type="danger" @click="end(row.id)">{{
            $t("devPanel.security.end")
          }}</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import { useDevApi } from "../dev.api";
import { useDevStore } from "../dev.store";
import { IDevSession } from "../dev.types";
import { devErrorMessage, formatTime, fromNow } from "../dev.utils";

const store = useDevStore();
const { GET_SESSIONS, END_SESSION, END_OTHER_SESSIONS } = useDevApi();
const sessions = ref<IDevSession[]>([]);
const loading = ref(false);

const load = async () => {
  loading.value = true;
  try {
    sessions.value = (await GET_SESSIONS()).data;
  } catch (error) {
    ElMessage.error(await devErrorMessage(error));
  } finally {
    loading.value = false;
  }
};

const end = async (id: string) => {
  try {
    await END_SESSION(id);
    load();
  } catch (error) {
    ElMessage.error(await devErrorMessage(error));
  }
};

const endOthers = async () => {
  try {
    await END_OTHER_SESSIONS();
    load();
  } catch (error) {
    ElMessage.error(await devErrorMessage(error));
  }
};

onMounted(load);
</script>
