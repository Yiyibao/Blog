<script setup lang="ts">
import axios from 'axios'
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { changePassword, logout as apiLogout } from '../api/admin'
import { useUiStore } from '../stores/uiStore'

const router = useRouter()
const uiStore = useUiStore()

const currentPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const error = ref('')
const submitting = ref(false)

async function submit() {
  error.value = ''
  if (newPassword.value.length < 12) {
    error.value = '新密码至少 12 位——推荐一句只有你们懂的短语，好记又难猜。'
    return
  }
  if (newPassword.value !== confirmPassword.value) {
    error.value = '两次输入的新密码不一致。'
    return
  }
  submitting.value = true
  try {
    await changePassword(currentPassword.value, newPassword.value)
    // FD-25：服务端已推进 sessions_valid_from，本地会话立即作废
    uiStore.showToast('密码已更新，请用新密码重新登录')
    apiLogout()
    await router.replace('/login')
  } catch (cause) {
    const message = axios.isAxiosError(cause)
      ? (cause.response?.data as { message?: string } | undefined)?.message
      : undefined
    error.value = message || '修改失败，请稍后再试。'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <form class="password-form" @submit.prevent="submit">
    <label>当前密码<input v-model="currentPassword" type="password" autocomplete="current-password" required></label>
    <label>新密码<input v-model="newPassword" type="password" autocomplete="new-password" required placeholder="至少 12 位，推荐短语"></label>
    <label>确认新密码<input v-model="confirmPassword" type="password" autocomplete="new-password" required></label>
    <p v-if="error" class="password-error" role="alert">{{ error }}</p>
    <button class="button primary tap-44" type="submit" :disabled="submitting">
      {{ submitting ? '正在修改…' : '更新密码' }}
    </button>
    <small>修改成功后，所有设备上的登录都会失效，需要用新密码重新登录。</small>
  </form>
</template>

<style scoped>
.password-form { display: flex; flex-direction: column; gap: 16px; max-width: 380px; }
.password-form label { display: flex; flex-direction: column; gap: 7px; color: var(--muted); font-size: .82rem; }
.password-form input { padding: 11px 13px; border: 1px solid var(--line-strong); border-radius: 10px; background: transparent; color: var(--ink); font-size: .92rem; outline: none; transition: border-color .2s; }
.password-form input:focus { border-color: var(--accent); }
.password-error { margin: 0; padding: 10px 13px; border-left: 2px solid #b84f48; background: color-mix(in srgb, #b84f48 8%, transparent); color: #b84f48; font-size: .82rem; }
.password-form small { color: var(--faint); line-height: 1.6; }
.password-form input:focus-visible, .password-form button:focus-visible { outline: 2px solid #0071e3; outline-offset: 2px; }
</style>
