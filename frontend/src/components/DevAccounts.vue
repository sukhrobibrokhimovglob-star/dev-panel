<template>
  <div>
    <div class="mb-4 flex flex-wrap items-center gap-3">
      <el-button v-if="store.canWrite" type="primary" @click="openCreate">
        {{ $t("devPanel.accounts.create") }}
      </el-button>
      <el-button class="ml-auto" :loading="loading" @click="load">{{
        $t("actions.refresh")
      }}</el-button>
    </div>

    <el-alert
      v-if="errorText"
      :title="errorText"
      type="error"
      show-icon
      :closable="false"
      class="mb-4"
    />

    <el-table :data="accounts" v-loading="loading" border stripe>
      <el-table-column label="ID" prop="id" width="70" />
      <el-table-column
        :label="$t('devPanel.login')"
        prop="username"
        min-width="160"
      />
      <el-table-column :label="$t('devPanel.role')" width="140">
        <template #default="{ row }">
          <el-tag :type="roleTag(row.role)" effect="dark" disable-transitions>{{
            row.role
          }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column
        :label="$t('devPanel.access')"
        prop="access"
        width="130"
      />
      <el-table-column :label="$t('devPanel.accounts.status')" min-width="230">
        <template #default="{ row }">
          <div class="flex flex-wrap items-center gap-1">
            <el-tag
              :type="row.enabled ? 'success' : 'info'"
              effect="dark"
              disable-transitions
            >
              {{
                $t(
                  row.enabled
                    ? "devPanel.accounts.enabled"
                    : "devPanel.accounts.disabled",
                )
              }}
            </el-tag>
            <el-tag
              v-if="row.mustChangePassword"
              type="warning"
              effect="dark"
              disable-transitions
            >
              {{ $t("devPanel.accounts.temporary") }}
            </el-tag>
            <el-tag
              :type="row.totpEnabled ? 'success' : 'info'"
              effect="plain"
              disable-transitions
            >
              {{
                $t(
                  row.totpEnabled
                    ? "devPanel.twoFactor.on"
                    : "devPanel.twoFactor.notSetUp",
                )
              }}
            </el-tag>
          </div>
        </template>
      </el-table-column>
      <el-table-column
        :label="$t('devPanel.accounts.createdBy')"
        prop="createdBy"
        width="130"
      />
      <el-table-column :label="$t('devPanel.accounts.createdAt')" width="170">
        <template #default="{ row }">{{
          formatDateTime(row.createdAt)
        }}</template>
      </el-table-column>
      <el-table-column v-if="manageable" min-width="360" align="center">
        <template #default="{ row }">
          <div
            v-if="row.role !== 'SUPER_ADMIN'"
            class="flex flex-nowrap items-center justify-center gap-2"
          >
            <el-button size="small" class="!ml-0" @click="openEdit(row)">{{
              $t("actions.edit")
            }}</el-button>
            <el-button
              size="small"
              type="warning"
              class="!ml-0"
              @click="resetPassword(row)"
            >
              {{ $t("devPanel.accounts.resetPassword") }}
            </el-button>
            <el-button
              v-if="row.totpEnabled"
              size="small"
              type="danger"
              plain
              class="!ml-0"
              @click="resetTwoFactor(row)"
            >
              {{ $t("devPanel.twoFactor.reset") }}
            </el-button>
          </div>
        </template>
      </el-table-column>
    </el-table>

    <!-- Yaratish / tahrirlash -->
    <el-dialog
      v-model="formVisible"
      :title="
        $t(editingId ? 'devPanel.accounts.edit' : 'devPanel.accounts.create')
      "
      width="min(440px, 94vw)"
      append-to-body
    >
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        @submit.prevent="submit"
      >
        <el-form-item
          v-if="!editingId"
          :label="$t('devPanel.login')"
          prop="username"
        >
          <el-input v-model="form.username" autocomplete="off" />
        </el-form-item>
        <el-form-item :label="$t('devPanel.role')" prop="role">
          <el-select v-model="form.role" class="w-full">
            <el-option
              v-for="role in creatableRoles"
              :key="role"
              :label="role"
              :value="role"
            />
          </el-select>
        </el-form-item>
        <!-- ADMIN har doim READ_WRITE — backend boshqasini rad etadi -->
        <el-form-item
          v-if="form.role === 'USER'"
          :label="$t('devPanel.access')"
          prop="access"
        >
          <el-radio-group v-model="form.access">
            <el-radio value="READ_ONLY">READ_ONLY</el-radio>
            <el-radio value="READ_WRITE">READ_WRITE</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="editingId" :label="$t('devPanel.accounts.status')">
          <el-switch
            v-model="form.enabled"
            :active-text="$t('devPanel.accounts.enabled')"
            :inactive-text="$t('devPanel.accounts.disabled')"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">{{
          $t("actions.cancel")
        }}</el-button>
        <el-button type="primary" :loading="saving" @click="submit">{{
          $t("actions.save")
        }}</el-button>
      </template>
    </el-dialog>

    <!-- Vaqtinchalik parol faqat bir marta ko'rsatiladi, backend uni saqlamaydi -->
    <el-dialog
      v-model="passwordVisible"
      :title="$t('devPanel.accounts.temporaryPassword')"
      width="min(440px, 94vw)"
      append-to-body
    >
      <p class="mt-0 text-sm text-gray-500 dark:text-gray-400">
        {{ $t("devPanel.accounts.temporaryHint") }}
      </p>
      <div class="mb-1 text-sm dark:text-gray-300">
        {{ $t("devPanel.login") }}: <b>{{ issued?.account.username }}</b>
      </div>
      <el-input
        :model-value="issued?.temporaryPassword"
        readonly
        class="font-mono"
      >
        <template #append>
          <el-button @click="copyPassword">{{
            $t("devPanel.accounts.copy")
          }}</el-button>
        </template>
      </el-input>
      <template #footer>
        <el-button type="primary" @click="passwordVisible = false"
          >OK</el-button
        >
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { ElMessage, ElMessageBox, FormInstance, FormRules } from "element-plus";
import { useI18n } from "vue-i18n";
import { useDevApi } from "../dev.api";
import { useDevStore } from "../dev.store";
import {
  IDevAccount,
  IDevAccountCredentials,
  TDevAccess,
  TDevRole,
} from "../dev.types";
import { devErrorMessage, formatDateTime } from "../dev.utils";

const { t } = useI18n();
const store = useDevStore();
const {
  GET_ACCOUNTS,
  CREATE_ACCOUNT,
  UPDATE_ACCOUNT,
  RESET_PASSWORD,
  RESET_TWO_FACTOR,
} =
  useDevApi();

const accounts = ref<IDevAccount[]>([]);
const loading = ref(false);
const errorText = ref("");

const formRef = ref<FormInstance>();
const formVisible = ref(false);
const saving = ref(false);
const editingId = ref<number | null>(null);
const form = ref<{
  username: string;
  role: TDevRole;
  access: TDevAccess;
  enabled: boolean;
}>({
  username: "",
  role: "USER",
  access: "READ_ONLY",
  enabled: true,
});

const passwordVisible = ref(false);
const issued = ref<IDevAccountCredentials | null>(null);

// Tahrirlash va parolni tiklash faqat SUPER_ADMIN'ga ruxsat etilgan
const manageable = computed(() => store.isSuperAdmin && store.canWrite);

const creatableRoles = computed<TDevRole[]>(() =>
  store.isSuperAdmin ? ["ADMIN", "USER"] : ["USER"],
);

const rules = computed<FormRules>(() => ({
  username: [
    {
      required: true,
      message: t("devPanel.accounts.usernameRequired"),
      trigger: "blur",
    },
    {
      pattern: /^[A-Za-z0-9][A-Za-z0-9._-]{2,63}$/,
      message: t("devPanel.accounts.usernamePattern"),
      trigger: "blur",
    },
  ],
  role: [{ required: true, trigger: "change" }],
}));

const roleTag = (role: TDevRole) =>
  role === "SUPER_ADMIN" ? "danger" : role === "ADMIN" ? "warning" : "info";

const load = async () => {
  loading.value = true;
  errorText.value = "";
  try {
    const { data } = await GET_ACCOUNTS();
    accounts.value = data;
  } catch (error) {
    errorText.value = await devErrorMessage(error);
  } finally {
    loading.value = false;
  }
};

const openCreate = () => {
  editingId.value = null;
  form.value = {
    username: "",
    role: "USER",
    access: "READ_ONLY",
    enabled: true,
  };
  formVisible.value = true;
  formRef.value?.clearValidate();
};

const openEdit = (account: IDevAccount) => {
  editingId.value = account.id;
  form.value = {
    username: account.username,
    role: account.role,
    access: account.access,
    enabled: account.enabled,
  };
  formVisible.value = true;
  formRef.value?.clearValidate();
};

const showIssued = (credentials: IDevAccountCredentials) => {
  issued.value = credentials;
  passwordVisible.value = true;
};

const submit = async () => {
  if (!(await formRef.value?.validate().catch(() => false))) return;
  saving.value = true;
  const access = form.value.role === "ADMIN" ? "READ_WRITE" : form.value.access;
  try {
    if (editingId.value) {
      await UPDATE_ACCOUNT(editingId.value, {
        role: form.value.role,
        access,
        enabled: form.value.enabled,
      });
      ElMessage.success(t("devPanel.accounts.saved"));
    } else {
      const { data } = await CREATE_ACCOUNT({
        username: form.value.username.trim(),
        role: form.value.role,
        access,
      });
      showIssued(data);
    }
    formVisible.value = false;
    load();
  } catch (error) {
    ElMessage.error(await devErrorMessage(error));
  } finally {
    saving.value = false;
  }
};

const resetPassword = async (account: IDevAccount) => {
  try {
    await ElMessageBox.confirm(
      t("devPanel.accounts.resetConfirm", { username: account.username }),
      t("devPanel.accounts.resetPassword"),
      {
        type: "warning",
        confirmButtonText: t("devPanel.accounts.resetPassword"),
        cancelButtonText: t("actions.cancel"),
      },
    );
  } catch {
    return;
  }
  try {
    const { data } = await RESET_PASSWORD(account.id);
    showIssued(data);
    load();
  } catch (error) {
    ElMessage.error(await devErrorMessage(error));
  }
};

// Telefonini yo'qotgan foydalanuvchi: tizimdan chiqariladi, keyingi kirishda QR'ni qayta skanerlaydi
const resetTwoFactor = async (account: IDevAccount) => {
  try {
    await ElMessageBox.confirm(
      t("devPanel.twoFactor.resetConfirm", { username: account.username }),
      t("devPanel.twoFactor.reset"),
      {
        type: "warning",
        confirmButtonText: t("devPanel.twoFactor.reset"),
        cancelButtonText: t("actions.cancel"),
      },
    );
  } catch {
    return;
  }
  try {
    await RESET_TWO_FACTOR(account.id);
    ElMessage.success(t("devPanel.twoFactor.resetDone"));
    load();
  } catch (error) {
    ElMessage.error(await devErrorMessage(error));
  }
};

const copyPassword = async () => {
  try {
    await navigator.clipboard.writeText(issued.value?.temporaryPassword ?? "");
    ElMessage.success(t("devPanel.accounts.copied"));
  } catch {
    ElMessage.error(t("devPanel.serverError"));
  }
};

onMounted(load);
</script>
