<template>
  <Transition name="dev-page" mode="out-in">
    <DevDashboard v-if="showDashboard" @logout="logout" />

    <div class="devlogin" v-else>
      <div class="card" :class="{ 'card--single': authenticated }">
        <!-- Kirish tomoni -->
        <section class="entry">
          <header class="entry__brand">
            <svg
              width="34"
              height="34"
              viewBox="0 0 34 34"
              fill="none"
              aria-hidden="true"
              xmlns="http://www.w3.org/2000/svg"
            >
              <rect width="34" height="34" rx="8" fill="currentColor" />
              <path
                d="M9 12l5 5-5 5M17 23h8"
                stroke="#fff"
                stroke-width="2.4"
                stroke-linecap="round"
                stroke-linejoin="round"
              />
            </svg>
            <span class="entry__wordmark">DEV<em>PANEL</em></span>

            <button
              v-if="authenticated"
              class="entry__logout"
              type="button"
              @click="logout"
            >
              {{ $t("logout") }}
            </button>
          </header>

          <Transition name="swap" mode="out-in">
            <!-- 1. Login va parol -->
            <div class="entry__main" v-if="!stage" key="login">
              <h2 class="entry__welcome">{{ $t("devPanel.title") }}</h2>
              <p class="entry__hint">{{ $t("devPanel.hint") }}</p>

              <Transition name="alert">
                <div class="entry__errors" v-if="errorText" role="alert">
                  <p>{{ errorText }}</p>
                </div>
              </Transition>

              <form class="entry__form" @submit.prevent="submit">
                <label class="field">
                  <span class="field__label">{{ $t("devPanel.login") }}</span>
                  <input
                    v-model="login"
                    type="text"
                    autocomplete="username"
                    class="field__input"
                    required
                  />
                </label>
                <label class="field">
                  <span class="field__label">{{
                    $t("devPanel.password")
                  }}</span>
                  <span class="field__control">
                    <input
                      v-model="password"
                      :type="showPassword ? 'text' : 'password'"
                      autocomplete="current-password"
                      class="field__input"
                      required
                    />
                    <button
                      type="button"
                      class="field__toggle"
                      :aria-label="
                        $t(
                          showPassword
                            ? 'devPanel.hidePassword'
                            : 'devPanel.showPassword',
                        )
                      "
                      @click="showPassword = !showPassword"
                    >
                      <el-icon :size="18"
                        ><Hide v-if="showPassword" /><View v-else
                      /></el-icon>
                    </button>
                  </span>
                </label>
                <button class="entry__submit" type="submit" :disabled="loading">
                  <span
                    v-if="loading"
                    class="entry__spinner"
                    aria-hidden="true"
                  />
                  {{ $t("devPanel.submit") }}
                </button>
              </form>
            </div>

            <!-- 2. Vaqtinchalik parolni almashtirish (birinchi kirish) -->
            <div
              class="entry__main"
              v-else-if="stage === 'PASSWORD_CHANGE'"
              key="change"
            >
              <h2 class="entry__welcome">
                {{ $t("devPanel.changePassword") }}
              </h2>
              <p class="entry__hint">{{ $t("devPanel.mustChangePassword") }}</p>

              <Transition name="alert">
                <div class="entry__errors" v-if="errorText" role="alert">
                  <p>{{ errorText }}</p>
                </div>
              </Transition>

              <form class="entry__form" @submit.prevent="changePassword">
                <label class="field">
                  <span class="field__label">{{
                    $t("devPanel.newPassword")
                  }}</span>
                  <span class="field__control">
                    <input
                      v-model="newPassword"
                      :type="showPassword ? 'text' : 'password'"
                      autocomplete="new-password"
                      class="field__input"
                      minlength="12"
                      maxlength="128"
                      required
                    />
                    <button
                      type="button"
                      class="field__toggle"
                      :aria-label="
                        $t(
                          showPassword
                            ? 'devPanel.hidePassword'
                            : 'devPanel.showPassword',
                        )
                      "
                      @click="showPassword = !showPassword"
                    >
                      <el-icon :size="18"
                        ><Hide v-if="showPassword" /><View v-else
                      /></el-icon>
                    </button>
                  </span>
                </label>
                <button class="entry__submit" type="submit" :disabled="loading">
                  <span
                    v-if="loading"
                    class="entry__spinner"
                    aria-hidden="true"
                  />
                  {{ $t("devPanel.changePassword") }}
                </button>
              </form>
            </div>

            <!-- 3. Google Authenticator'ni ulash (birinchi kirish yoki reset'dan keyin) yoki 4. kod -->
            <div class="entry__main" v-else :key="stage">
              <h2 class="entry__welcome">
                {{
                  $t(
                    stage === "TOTP_SETUP"
                      ? "devPanel.twoFactor.setupTitle"
                      : "devPanel.twoFactor.codeTitle",
                  )
                }}
              </h2>
              <p class="entry__hint">
                {{
                  $t(
                    stage === "TOTP_SETUP"
                      ? "devPanel.twoFactor.setupHint"
                      : "devPanel.twoFactor.codeHint",
                  )
                }}
              </p>

              <div v-if="stage === 'TOTP_SETUP' && setup" class="totp-setup">
                <img
                  v-if="qrImage"
                  :src="qrImage"
                  :alt="$t('devPanel.twoFactor.qrAlt')"
                  class="totp-setup__qr"
                  width="200"
                  height="200"
                />
                <div class="totp-setup__manual">
                  <span class="field__label">{{
                    $t("devPanel.twoFactor.manualKey")
                  }}</span>
                  <code>{{ groupedSecret }}</code>
                </div>
              </div>

              <Transition name="alert">
                <div class="entry__errors" v-if="errorText" role="alert">
                  <p>{{ errorText }}</p>
                </div>
              </Transition>

              <form class="entry__form" @submit.prevent="verifyCode">
                <label class="field">
                  <span class="field__label">{{
                    $t("devPanel.twoFactor.code")
                  }}</span>
                  <input
                    ref="codeInput"
                    v-model="code"
                    type="text"
                    inputmode="numeric"
                    autocomplete="one-time-code"
                    pattern="[0-9 ]{6,7}"
                    maxlength="7"
                    placeholder="123 456"
                    class="field__input totp-code"
                    required
                  />
                </label>
                <button class="entry__submit" type="submit" :disabled="loading">
                  <span
                    v-if="loading"
                    class="entry__spinner"
                    aria-hidden="true"
                  />
                  {{
                    $t(
                      stage === "TOTP_SETUP"
                        ? "devPanel.twoFactor.confirm"
                        : "devPanel.submit",
                    )
                  }}
                </button>
              </form>
            </div>
          </Transition>
        </section>

        <!-- Brend paneli -->
        <section class="panel" v-if="!authenticated">
          <span class="panel__glow" aria-hidden="true" />
          <p class="panel__eyebrow">DEV PANEL</p>
          <h1 class="panel__title">{{ $t("devPanel.panelTitle") }}</h1>
          <p class="panel__lead">{{ $t("devPanel.panelLead") }}</p>
        </section>
      </div>
    </div>
  </Transition>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from "vue";
import { storeToRefs } from "pinia";
import { ElMessage } from "element-plus";
import { Hide, View } from "@element-plus/icons-vue";
import { useI18n } from "vue-i18n";
import QRCode from "qrcode";
import DevDashboard from "./DevDashboard.vue";
import { IDevLoginState, TDevLoginStage } from "../dev.types";
import { useDevApi } from "../dev.api";
import { useDevStore } from "../dev.store";
import { devErrorMessage } from "../dev.utils";

const { t } = useI18n();
const {
  LOGIN,
  GET_LOGIN_STATE,
  LOGIN_NEW_PASSWORD,
  LOGIN_CODE,
  LOGOUT,
  GET_ME,
} = useDevApi();
const store = useDevStore();
const { account } = storeToRefs(store);

const login = ref("");
const password = ref("");
const newPassword = ref("");
const code = ref("");
const showPassword = ref(false);
const errorText = ref("");
const loading = ref(false);
// null — hali parol kiritilmagan; boshqasi — backend'dagi tugallanmagan login bosqichi
const stage = ref<Exclude<TDevLoginStage, "ACTIVE"> | null>(null);
const setup = ref<IDevLoginState["setup"]>(null);
const qrImage = ref("");
const codeInput = ref<HTMLInputElement>();

const authenticated = computed(() => !!stage.value);
const showDashboard = computed(() => !!account.value);

// Qo'lda kiritish oson bo'lsin: ABCD EFGH IJKL ...
const groupedSecret = computed(
  () => setup.value?.secret.match(/.{1,4}/g)?.join(" ") ?? "",
);

// QR brauzerning o'zida chiziladi — kalit hech qanday tashqi servisga ketmaydi
watch(setup, async (value) => {
  qrImage.value = value
    ? await QRCode.toDataURL(value.uri, { width: 200, margin: 1 })
    : "";
});

const applyState = async (state: IDevLoginState) => {
  errorText.value = "";
  if (state.stage === "ACTIVE") {
    account.value = (await GET_ME()).data;
    stage.value = null;
    return;
  }
  stage.value = state.stage;
  setup.value = state.setup;
  code.value = "";
  if (state.stage !== "PASSWORD_CHANGE") {
    await nextTick();
    codeInput.value?.focus();
  }
};

const run = async (action: () => Promise<void>) => {
  loading.value = true;
  errorText.value = "";
  try {
    await action();
  } catch (error) {
    errorText.value = await devErrorMessage(error);
  } finally {
    loading.value = false;
  }
};

const submit = () =>
  run(async () => {
    const { data } = await LOGIN(login.value.trim(), password.value);
    password.value = "";
    await applyState(data);
  });

const changePassword = () =>
  run(async () => {
    const { data } = await LOGIN_NEW_PASSWORD(newPassword.value);
    newPassword.value = "";
    ElMessage.success(t("devPanel.passwordChanged"));
    await applyState(data);
  });

const verifyCode = () =>
  run(async () => {
    const { data } = await LOGIN_CODE(code.value.replace(/\s/g, ""));
    if (stage.value === "TOTP_SETUP")
      ElMessage.success(t("devPanel.twoFactor.enabled"));
    stage.value = null;
    setup.value = null;
    account.value = data;
  });

const logout = async () => {
  try {
    await LOGOUT();
  } catch {
    // sessiya allaqachon tugagan bo'lsa ham login sahifasiga qaytamiz
  }
  store.reset();
  stage.value = null;
  setup.value = null;
  login.value = "";
  password.value = "";
  newPassword.value = "";
  code.value = "";
  showPassword.value = false;
  errorText.value = "";
};

// Sahifa yangilanganda: cookie hali amal qilsa — panel, tugallanmagan login bo'lsa — o'sha bosqich
onMounted(async () => {
  try {
    account.value = (await GET_ME()).data;
  } catch {
    try {
      await applyState((await GET_LOGIN_STATE()).data);
    } catch {
      // sessiya yo'q — login formasi
    }
  }
});
</script>

<style scoped>
.totp-setup {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 16px;
  margin: 4px 0 16px;
}

.totp-setup__qr {
  padding: 8px;
  border: 1px solid #dde4ee;
  border-radius: 12px;
  background: #fff;
}

.totp-setup__manual {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 6px;
  min-width: 180px;
}

.totp-setup__manual code {
  font-size: 13px;
  line-height: 1.6;
  word-break: break-word;
  user-select: all;
}

.totp-code {
  font-size: 22px;
  letter-spacing: 0.3em;
  text-align: center;
  font-variant-numeric: tabular-nums;
}

.devlogin {
  --ink: #071e33;
  --ink-soft: #0e2f4c;
  --brand: #106297;
  --glacier: #58b4e3;
  --frost: #eef3f9;
  --danger: #d14343;

  /* html/body scroll bo'lmaydi (style.css) — tor ekranda karta sig'masa shu blok scroll bo'ladi;
     markazlash margin: auto bilan, aks holda baland kartaning tepasi kesilib qoladi */
  display: flex;
  height: 100vh;
  height: 100dvh;
  overflow-y: auto;
  padding: 40px 24px;
  background:
    radial-gradient(
      80% 60% at 50% 0%,
      rgba(88, 180, 227, 0.16) 0%,
      rgba(88, 180, 227, 0) 70%
    ),
    var(--frost);
  color: var(--ink);
}

.card {
  display: grid;
  grid-template-columns: minmax(0, 0.92fr) minmax(0, 1fr);
  width: min(1000px, 100%);
  min-height: min(560px, 78vh);
  margin: auto;
  border-radius: 24px;
  overflow: hidden;
  background: #fff;
  box-shadow:
    0 1px 2px rgba(7, 30, 51, 0.06),
    0 30px 60px -30px rgba(7, 30, 51, 0.28);
}

.card--single {
  grid-template-columns: minmax(0, 1fr);
  width: min(560px, 100%);
  min-height: 0;
}

/* ---------- Kirish tomoni ---------- */

.entry {
  display: flex;
  flex-direction: column;
  padding: clamp(28px, 3.4vw, 48px);
  background: #fff;
}

.entry__brand {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--brand);
}

.entry__wordmark {
  font-size: 18px;
  font-weight: 700;
  letter-spacing: 0.16em;
}

.entry__wordmark em {
  margin-left: 6px;
  padding: 2px 7px;
  border-radius: 999px;
  background: var(--brand);
  color: #fff;
  font-size: 10px;
  font-weight: 700;
  font-style: normal;
  letter-spacing: 0.08em;
  vertical-align: middle;
}

.entry__logout {
  margin-left: auto;
  padding: 7px 14px;
  border: 1px solid rgba(7, 30, 51, 0.12);
  border-radius: 999px;
  background: transparent;
  color: rgba(7, 30, 51, 0.6);
  font-family: inherit;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
  transition:
    background 0.18s ease,
    color 0.18s ease;
}

.entry__logout:hover {
  background: var(--frost);
  color: var(--ink);
}

.entry__main {
  display: flex;
  flex-direction: column;
  margin: auto 0;
  padding: 40px 0;
}

.entry__welcome {
  margin: 0;
  font-size: clamp(24px, 2.2vw, 30px);
  font-weight: 700;
  letter-spacing: -0.02em;
  color: var(--ink);
}

.entry__hint {
  margin: 10px 0 0;
  max-width: 26em;
  font-size: 14px;
  line-height: 1.6;
  color: rgba(7, 30, 51, 0.6);
}

.entry__errors {
  margin-top: 20px;
  padding: 12px 14px;
  border-left: 2px solid var(--danger);
  background: rgba(209, 67, 67, 0.07);
  color: var(--danger);
  font-size: 13px;
  line-height: 1.5;
}

.entry__errors p {
  margin: 0;
}

.entry__form {
  display: flex;
  flex-direction: column;
  gap: 16px;
  margin-top: 28px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.field__label {
  font-size: 12.5px;
  font-weight: 600;
  color: rgba(7, 30, 51, 0.6);
}

.field__input {
  height: 48px;
  padding: 0 14px;
  border: 1px solid rgba(7, 30, 51, 0.14);
  border-radius: 12px;
  background: var(--frost);
  color: var(--ink);
  font-family: inherit;
  font-size: 14px;
  outline: none;
  transition:
    border-color 0.18s ease,
    background 0.18s ease;
}

.field__input:focus {
  border-color: var(--glacier);
  background: #fff;
}

.field__control {
  position: relative;
  display: flex;
}

.field__control .field__input {
  flex: 1;
  min-width: 0;
  padding-right: 48px;
}

.field__toggle {
  position: absolute;
  top: 50%;
  right: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: rgba(7, 30, 51, 0.5);
  cursor: pointer;
  transform: translateY(-50%);
  transition:
    background 0.18s ease,
    color 0.18s ease;
}

.field__toggle:hover {
  background: rgba(7, 30, 51, 0.06);
  color: var(--ink);
}

.remember {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  align-self: flex-start;
  font-size: 13.5px;
  color: rgba(7, 30, 51, 0.72);
  cursor: pointer;
  user-select: none;
}

.remember__box {
  width: 18px;
  height: 18px;
  margin: 0;
  accent-color: var(--brand);
  cursor: pointer;
}

.entry__submit {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  margin-top: 8px;
  width: 100%;
  height: 52px;
  border: none;
  border-radius: 12px;
  background: var(--brand);
  color: #fff;
  font-family: inherit;
  font-size: 15px;
  font-weight: 700;
  letter-spacing: 0.01em;
  cursor: pointer;
  box-shadow: 0 10px 24px -12px rgba(16, 98, 151, 0.75);
  transition:
    transform 0.18s ease,
    background 0.18s ease,
    box-shadow 0.18s ease;
}

.entry__submit:hover {
  background: #0d5182;
  transform: translateY(-1px);
  box-shadow: 0 16px 30px -14px rgba(16, 98, 151, 0.85);
}

.entry__submit:active {
  transform: translateY(0);
}

.entry__submit:disabled {
  opacity: 0.6;
  cursor: wait;
  transform: none;
}

/* ---------- Brend paneli ---------- */

.panel {
  position: relative;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: clamp(32px, 3.4vw, 52px);
  overflow: hidden;
  background:
    radial-gradient(
      110% 80% at 88% 6%,
      rgba(88, 180, 227, 0.3) 0%,
      rgba(88, 180, 227, 0) 58%
    ),
    linear-gradient(168deg, var(--ink-soft) 0%, var(--ink) 64%);
  color: #fff;
}

.panel::after {
  content: "";
  position: absolute;
  inset: -20% -40% auto -10%;
  height: 150%;
  background: repeating-linear-gradient(
    104deg,
    rgba(255, 255, 255, 0.05) 0px,
    rgba(255, 255, 255, 0.05) 1px,
    transparent 1px,
    transparent 78px
  );
  pointer-events: none;
}

.panel > * {
  position: relative;
  z-index: 1;
}

.panel__eyebrow {
  margin: 0 0 18px;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.2em;
  text-transform: uppercase;
  color: var(--glacier);
}

.panel__title {
  margin: 0;
  font-size: clamp(24px, 2.4vw, 34px);
  font-weight: 700;
  line-height: 1.15;
  letter-spacing: -0.02em;
  text-wrap: balance;
}

.panel__lead {
  position: relative;
  margin: 22px 0 0;
  padding-top: 22px;
  max-width: 30em;
  font-size: 14px;
  line-height: 1.66;
  color: rgba(255, 255, 255, 0.72);
}

.panel__lead::before {
  content: "";
  position: absolute;
  top: 0;
  left: 0;
  width: 56px;
  height: 2px;
  background: var(--glacier);
}

/* ---------- Animatsiyalar ---------- */

.card {
  animation: card-in 0.7s cubic-bezier(0.22, 1, 0.36, 1) both;
}

/* Kontent karta ochilgach birin-ketin chiqadi — ko'z tabiiy tartibda o'qiydi */
.entry__brand,
.entry__main > *,
.panel > :not(.panel__glow) {
  animation: rise-in 0.6s cubic-bezier(0.22, 1, 0.36, 1) both;
}

.entry__brand {
  animation-delay: 0.15s;
}
.entry__main > :nth-child(1) {
  animation-delay: 0.22s;
}
.entry__main > :nth-child(2) {
  animation-delay: 0.28s;
}
.entry__main > :nth-child(3) {
  animation-delay: 0.34s;
}
.entry__main > :nth-child(4) {
  animation-delay: 0.4s;
}

.entry__form > * {
  animation: rise-in 0.55s cubic-bezier(0.22, 1, 0.36, 1) both;
}
.entry__form > :nth-child(1) {
  animation-delay: 0.38s;
}
.entry__form > :nth-child(2) {
  animation-delay: 0.44s;
}
.entry__form > :nth-child(3) {
  animation-delay: 0.5s;
}
.entry__form > :nth-child(4) {
  animation-delay: 0.56s;
}

.panel > .panel__eyebrow {
  animation-name: slide-in;
  animation-delay: 0.3s;
}
.panel > .panel__title {
  animation-name: slide-in;
  animation-delay: 0.4s;
}
.panel > .panel__lead {
  animation-name: slide-in;
  animation-delay: 0.5s;
}

.panel__lead::before {
  transform-origin: left;
  animation: line-grow 0.8s cubic-bezier(0.22, 1, 0.36, 1) 0.8s both;
}

.panel::after {
  animation: stripes-drift 24s linear infinite;
}

.panel__glow {
  position: absolute;
  top: -30%;
  right: -30%;
  width: 90%;
  aspect-ratio: 1;
  border-radius: 50%;
  background: radial-gradient(
    circle,
    rgba(88, 180, 227, 0.35) 0%,
    rgba(88, 180, 227, 0) 65%
  );
  filter: blur(10px);
  pointer-events: none;
  z-index: 0;
  animation: glow-float 12s ease-in-out infinite alternate;
}

.entry__brand svg {
  animation: logo-pulse 4s ease-in-out 1s infinite;
}

.field__input:focus {
  box-shadow: 0 0 0 4px rgba(88, 180, 227, 0.18);
}

.field__input {
  transition:
    border-color 0.18s ease,
    background 0.18s ease,
    box-shadow 0.25s ease;
}

.entry__submit {
  position: relative;
  overflow: hidden;
}

/* Tugma ustida yorug'lik chizig'i o'tadi */
.entry__submit::after {
  content: "";
  position: absolute;
  inset: 0 auto 0 -60%;
  width: 40%;
  background: linear-gradient(
    100deg,
    rgba(255, 255, 255, 0) 0%,
    rgba(255, 255, 255, 0.28) 50%,
    rgba(255, 255, 255, 0) 100%
  );
  transform: skewX(-18deg);
  pointer-events: none;
}

.entry__submit:hover:not(:disabled)::after {
  animation: shine 0.8s ease;
}

.entry__spinner {
  width: 16px;
  height: 16px;
  border: 2px solid rgba(255, 255, 255, 0.35);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}

/* Login va parol almashtirish formalari orasida o'tish */
.swap-enter-active,
.swap-leave-active {
  transition:
    opacity 0.28s ease,
    transform 0.28s cubic-bezier(0.22, 1, 0.36, 1);
}
.swap-enter-from {
  opacity: 0;
  transform: translateX(24px);
}
.swap-leave-to {
  opacity: 0;
  transform: translateX(-24px);
}

.alert-enter-active {
  animation: shake 0.45s cubic-bezier(0.36, 0.07, 0.19, 0.97) both;
  transition: opacity 0.2s ease;
}
.alert-leave-active {
  transition: opacity 0.2s ease;
}
.alert-enter-from,
.alert-leave-to {
  opacity: 0;
}

.dev-page-enter-active,
.dev-page-leave-active {
  transition:
    opacity 0.3s ease,
    transform 0.3s ease;
}
.dev-page-enter-from,
.dev-page-leave-to {
  opacity: 0;
  transform: scale(0.985);
}

@keyframes card-in {
  from {
    opacity: 0;
    transform: translateY(24px) scale(0.98);
  }
}

@keyframes rise-in {
  from {
    opacity: 0;
    transform: translateY(14px);
  }
}

@keyframes slide-in {
  from {
    opacity: 0;
    transform: translateX(24px);
  }
}

@keyframes line-grow {
  from {
    transform: scaleX(0);
  }
}

@keyframes stripes-drift {
  to {
    transform: translateX(80.39px);
  }
}

@keyframes glow-float {
  0% {
    transform: translate(0, 0) scale(1);
  }
  50% {
    transform: translate(-18%, 22%) scale(1.15);
  }
  100% {
    transform: translate(-6%, 48%) scale(0.95);
  }
}

@keyframes logo-pulse {
  0%,
  100% {
    transform: scale(1);
  }
  50% {
    transform: scale(1.08);
  }
}

@keyframes shine {
  to {
    left: 130%;
  }
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

@keyframes shake {
  10%,
  90% {
    transform: translateX(-1px);
  }
  20%,
  80% {
    transform: translateX(3px);
  }
  30%,
  50%,
  70% {
    transform: translateX(-5px);
  }
  40%,
  60% {
    transform: translateX(5px);
  }
}

/* Harakatni kamaytirishni so'ragan foydalanuvchilar uchun animatsiyalar o'chadi */
@media (prefers-reduced-motion: reduce) {
  .devlogin *,
  .devlogin *::before,
  .devlogin *::after {
    animation: none !important;
    transition-duration: 0.01ms !important;
  }
}

@media (max-width: 899px) {
  .card {
    grid-template-columns: 1fr;
    min-height: 0;
    border-radius: 20px;
  }

  .entry__main {
    padding: 32px 0;
  }

  .panel {
    padding: 32px 24px 36px;
  }
}

@media (max-width: 479px) {
  .devlogin {
    padding: 16px 12px;
  }

  .entry {
    padding: 24px 20px;
  }

  .entry__main {
    padding: 24px 0 8px;
  }

  .panel {
    padding: 28px 20px 32px;
  }
}
</style>
