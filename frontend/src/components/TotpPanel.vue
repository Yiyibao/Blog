<script setup lang="ts">
import axios from 'axios';
import { onMounted, ref } from 'vue';
import { disableTotp, enableTotp, fetchTotpStatus, setupTotp } from '../api/admin';

const enabled = ref(false);
const loading = ref(true);
const error = ref('');
const success = ref('');

// Setup state
const setuping = ref(false);
const setupPassword = ref('');
const setupSecret = ref('');
const setupUri = ref('');
const setupCode = ref('');
const setupSubmitting = ref(false);

// Enable state
const enableSubmitting = ref(false);

// Disable state
const disablePassword = ref('');
const disableCode = ref('');
const disableSubmitting = ref(false);

onMounted(loadStatus);

async function loadStatus() {
  loading.value = true;
  try {
    const status = await fetchTotpStatus();
    enabled.value = status.enabled;
  } catch {
    error.value = '无法读取安全设置状态。';
  } finally {
    loading.value = false;
  }
}

async function startSetup() {
  if (!setupPassword.value) return;
  setupSubmitting.value = true;
  error.value = '';
  success.value = '';
  try {
    const result = await setupTotp(setupPassword.value);
    setupSecret.value = result.secret;
    setupUri.value = result.otpauthUri;
  } catch (cause) {
    error.value =
      axios.isAxiosError(cause) && cause.response?.status === 401 ? '密码不正确。' : '设置失败，请稍后再试。';
  } finally {
    setupSubmitting.value = false;
  }
}

async function confirmEnable() {
  if (setupCode.value.length !== 6) return;
  enableSubmitting.value = true;
  error.value = '';
  success.value = '';
  try {
    await enableTotp(setupCode.value);
    enabled.value = true;
    setuping.value = false;
    setupSecret.value = '';
    setupUri.value = '';
    setupCode.value = '';
    setupPassword.value = '';
    success.value = '两步验证已启用。';
  } catch (cause) {
    if (axios.isAxiosError(cause) && cause.response?.status === 401) {
      error.value = '验证码不正确。';
    } else {
      error.value = '启用失败，请稍后再试。';
    }
  } finally {
    enableSubmitting.value = false;
  }
}

async function confirmDisable() {
  if (disableCode.value.length !== 6 || !disablePassword.value) return;
  disableSubmitting.value = true;
  error.value = '';
  success.value = '';
  try {
    await disableTotp(disablePassword.value, disableCode.value);
    enabled.value = false;
    disablePassword.value = '';
    disableCode.value = '';
    success.value = '两步验证已关闭，其他设备的续期会话已撤销。';
  } catch (cause) {
    if (axios.isAxiosError(cause) && cause.response?.status === 401) {
      error.value = '密码或验证码不正确。';
    } else {
      error.value = '关闭失败，请稍后再试。';
    }
  } finally {
    disableSubmitting.value = false;
  }
}
</script>

<template>
  <section class="totp-panel">
    <h3>两步验证（TOTP）</h3>
    <p class="totp-panel-desc">通过身份验证器应用生成的一次性验证码增强账号安全。</p>

    <p v-if="error" class="totp-error" role="alert">{{ error }}</p>
    <p v-if="success" class="totp-success" role="status">{{ success }}</p>

    <div v-if="loading" class="totp-loading">加载中…</div>

    <!-- Disabled state: show setup button when not in setup flow -->
    <div v-else-if="!enabled && !setuping" class="totp-status-row">
      <span class="totp-status-badge off">已关闭</span>
      <button
        class="button primary"
        @click="
          setuping = true;
          setupPassword = '';
          setupSecret = '';
          setupUri = '';
          setupCode = '';
          error = '';
          success = '';
        "
      >
        设置两步验证
      </button>
    </div>

    <!-- Setup flow -->
    <div v-else-if="!enabled && setuping && !setupSecret" class="totp-setup">
      <label
        >输入当前密码以开始设置
        <input
          v-model="setupPassword"
          type="password"
          autocomplete="current-password"
          required
          placeholder="当前密码"
        />
      </label>
      <button class="button primary" :disabled="!setupPassword || setupSubmitting" @click="startSetup">
        {{ setupSubmitting ? '验证中…' : '下一步' }}
      </button>
    </div>

    <div v-else-if="!enabled && setupSecret" class="totp-setup-secret">
      <p>请在身份验证器应用中手动输入密钥；在移动设备上也可直接打开配置链接。</p>
      <a v-if="setupUri" class="totp-open-link" :href="setupUri">在身份验证器中打开</a>
      <div class="totp-secret-display">
        <span>密钥：</span>
        <code>{{ setupSecret }}</code>
      </div>
      <label
        >输入应用中的 6 位验证码以完成启用
        <input
          v-model="setupCode"
          type="text"
          inputmode="numeric"
          maxlength="6"
          placeholder="000000"
          autocomplete="off"
        />
      </label>
      <div class="totp-setup-actions">
        <button
          class="button secondary"
          @click="
            setuping = false;
            setupSecret = '';
            setupUri = '';
            setupCode = '';
            setupPassword = '';
          "
        >
          取消
        </button>
        <button
          class="button primary"
          :disabled="setupCode.length !== 6 || enableSubmitting"
          @click="confirmEnable"
        >
          {{ enableSubmitting ? '启用中…' : '启用' }}
        </button>
      </div>
    </div>

    <!-- Enabled state -->
    <div v-else-if="enabled" class="totp-enabled">
      <div class="totp-status-row">
        <span class="totp-status-badge on">已启用</span>
      </div>
      <details class="totp-disable-section">
        <summary>关闭两步验证</summary>
        <div class="totp-disable-form">
          <label
            >当前密码
            <input
              v-model="disablePassword"
              type="password"
              autocomplete="current-password"
              required
              placeholder="当前密码"
            />
          </label>
          <label
            >身份验证器中的验证码
            <input
              v-model="disableCode"
              type="text"
              inputmode="numeric"
              maxlength="6"
              placeholder="000000"
              autocomplete="off"
            />
          </label>
          <button
            class="button danger"
            :disabled="disableCode.length !== 6 || !disablePassword || disableSubmitting"
            @click="confirmDisable"
          >
            {{ disableSubmitting ? '关闭中…' : '关闭两步验证' }}
          </button>
        </div>
      </details>
    </div>
  </section>
</template>

<style scoped>
.totp-panel {
  display: flex;
  flex-direction: column;
  gap: 14px;
  max-width: 420px;
}
.totp-panel h3 {
  margin: 0;
  font-size: 1rem;
  color: var(--ink);
}
.totp-panel-desc {
  margin: 0;
  font-size: 0.82rem;
  color: var(--muted);
}
.totp-error {
  margin: 0;
  padding: 10px 13px;
  border-left: 2px solid #b84f48;
  background: color-mix(in srgb, #b84f48 8%, transparent);
  color: #b84f48;
  font-size: 0.82rem;
}
.totp-success {
  margin: 0;
  padding: 10px 13px;
  border-left: 2px solid #2e9e5b;
  background: color-mix(in srgb, #2e9e5b 8%, transparent);
  color: #2e9e5b;
  font-size: 0.82rem;
}
.totp-loading {
  color: var(--faint);
  font-size: 0.82rem;
}
.totp-status-row {
  display: flex;
  align-items: center;
  gap: 12px;
}
.totp-status-badge {
  padding: 3px 10px;
  border-radius: 999px;
  font-size: 0.78rem;
  font-weight: 600;
}
.totp-status-badge.on {
  background: color-mix(in srgb, #2e9e5b 14%, transparent);
  color: #2e9e5b;
}
.totp-status-badge.off {
  background: color-mix(in srgb, var(--muted) 14%, transparent);
  color: var(--muted);
}
.totp-setup,
.totp-setup-secret,
.totp-disable-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.totp-setup label,
.totp-setup-secret label,
.totp-disable-form label {
  display: flex;
  flex-direction: column;
  gap: 6px;
  color: var(--muted);
  font-size: 0.82rem;
}
.totp-setup input,
.totp-setup-secret input,
.totp-disable-form input {
  padding: 10px 12px;
  border: 1px solid var(--line-strong);
  border-radius: 10px;
  background: transparent;
  color: var(--ink);
  font-size: 0.92rem;
  outline: none;
}
.totp-setup input:focus,
.totp-setup-secret input:focus,
.totp-disable-form input:focus {
  border-color: var(--accent);
}
.totp-setup-actions {
  display: flex;
  gap: 10px;
}
.totp-open-link {
  width: fit-content;
  color: var(--accent);
  font-size: 0.82rem;
}
.totp-secret-display {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 0.82rem;
  color: var(--muted);
}
.totp-secret-display code {
  padding: 6px 10px;
  border-radius: 6px;
  background: var(--surface);
  font-size: 0.82rem;
  word-break: break-all;
}
.totp-enabled {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.totp-disable-section summary {
  cursor: pointer;
  color: #b84f48;
  font-size: 0.82rem;
}
.totp-disable-section[open] summary {
  margin-bottom: 12px;
}
.button.danger {
  border: 1px solid #b84f48;
  background: transparent;
  color: #b84f48;
  padding: 10px 18px;
  border-radius: 10px;
  font-size: 14px;
  cursor: pointer;
}
.button.danger:disabled {
  opacity: 0.4;
  cursor: default;
}
.button.danger:hover:not(:disabled) {
  background: color-mix(in srgb, #b84f48 8%, transparent);
}
</style>
