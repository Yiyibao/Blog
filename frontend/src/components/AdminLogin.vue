<script setup lang="ts">
import axios from 'axios'
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  fetchLoginChallenge,
  hasValidAdminSession,
  login,
  saveAdminSession,
  type LoginChallenge,
} from '../api/admin'
import { solvePow } from '../utils/pow'

const router = useRouter()
const username = ref('')
const password = ref('')
const error = ref('')
const submitting = ref(false)
// L-7：人机验证状态——challenge 一次性使用，pendingChallenge 只在未消费前复用
const pendingChallenge = ref<LoginChallenge | null>(null)
const captchaRequired = ref(false)
const captchaImage = ref('')
const captchaAnswer = ref('')
const verifying = ref(false)

async function ensureChallenge(): Promise<LoginChallenge> {
  if (pendingChallenge.value) return pendingChallenge.value
  const challenge = await fetchLoginChallenge(username.value.trim() || undefined)
  pendingChallenge.value = challenge
  if (challenge.type === 'IMAGE') {
    captchaRequired.value = true
    captchaImage.value = challenge.captchaImage ?? ''
  }
  return challenge
}

async function refreshCaptcha() {
  pendingChallenge.value = null
  captchaAnswer.value = ''
  try {
    await ensureChallenge()
  } catch {
    error.value = '获取验证码失败，请稍后重试。'
  }
}

async function submit() {
  error.value = ''
  submitting.value = true
  try {
    const challenge = await ensureChallenge()
    if (challenge.type === 'IMAGE' && !captchaAnswer.value.trim()) {
      error.value = '请输入下方图形验证码后再继续。'
      return
    }

    verifying.value = true
    const nonce = await solvePow(challenge.salt, challenge.difficulty)
    verifying.value = false

    pendingChallenge.value = null // 即将被服务端消费，成败都不可复用
    const result = await login(username.value.trim(), password.value, {
      challengeId: challenge.challengeId,
      nonce,
      captchaAnswer: challenge.type === 'IMAGE' ? captchaAnswer.value.trim() : undefined,
    })
    saveAdminSession(result)
    await router.replace('/admin')
  } catch (cause) {
    pendingChallenge.value = null
    captchaAnswer.value = ''
    if (axios.isAxiosError(cause) && cause.response?.status === 401) {
      error.value = '用户名或密码不正确。'
    } else if (axios.isAxiosError(cause) && cause.response?.status === 400) {
      error.value = '人机验证未通过，请重试。'
    } else if (axios.isAxiosError(cause) && cause.response?.status === 429) {
      const message = (cause.response.data as { message?: string } | undefined)?.message
      error.value = message || '尝试过于频繁，请稍后再试。'
    } else if (axios.isAxiosError(cause) && cause.response) {
      error.value = `登录服务暂时不可用（${cause.response.status}），请稍后重试。`
    } else {
      error.value = '无法连接本地后端，请确认 8080 服务正在运行。'
    }
    // 失败后预取新 challenge：若已升级为图形码，立即渲染输入区
    void ensureChallenge().catch(() => {})
  } finally {
    verifying.value = false
    submitting.value = false
  }
}

onMounted(() => {
  if (hasValidAdminSession()) void router.replace('/admin')
})
</script>

<template>
  <section class="admin-login section-wrap">
    <div class="admin-login-copy">
      <p class="eyebrow"><span /> PRIVATE STUDIO</p>
      <h1>回到你的<br><em>内容工作台。</em></h1>
      <p>管理文章、菜品与学习笔记，让每一次发布都保持清晰、可靠和可追溯。</p>
      <RouterLink class="back-link" to="/">← 返回博客</RouterLink>
    </div>
    <form class="admin-login-card" @submit.prevent="submit">
      <span class="admin-lock">AUTH / 01</span>
      <h2>管理员登录</h2>
      <p>使用本机配置的管理账号继续。</p>
      <label>用户名<input v-model="username" autocomplete="username" required placeholder="admin"></label>
      <label>密码<input v-model="password" type="password" autocomplete="current-password" required placeholder="••••••••••••"></label>
      <div v-if="captchaRequired" class="admin-captcha">
        <label for="captcha-answer">图形验证码</label>
        <button
          type="button"
          class="admin-captcha-image"
          title="看不清？点击换一张"
          :disabled="submitting"
          @click="refreshCaptcha"
        >
          <img v-if="captchaImage" :src="captchaImage" alt="图形验证码，点击可更换">
        </button>
        <div class="admin-captcha-row">
          <input
            id="captcha-answer"
            v-model="captchaAnswer"
            autocomplete="off"
            inputmode="text"
            placeholder="输入图中字符（不区分大小写）"
          >
          <button type="button" class="admin-captcha-refresh" :disabled="submitting" @click="refreshCaptcha">换一张</button>
        </div>
      </div>
      <p v-if="error" class="admin-error" role="alert" aria-live="assertive">{{ error }}</p>
      <button class="button primary" type="submit" :disabled="submitting">
        {{ verifying ? '安全校验中…' : submitting ? '正在验证…' : '进入工作台 ↗' }}
      </button>
      <small>登录令牌仅保存在当前浏览器会话中。</small>
    </form>
  </section>
</template>
