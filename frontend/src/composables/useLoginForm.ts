import axios from 'axios'
import { ref } from 'vue'
import {
  fetchLoginChallenge,
  login,
  saveAdminSession,
  type LoginChallenge,
} from '../api/admin'
import type { LoginResult } from '../stores/auth'
import { solvePow } from '../utils/pow'

/**
 * FD-9：登录表单逻辑，从 AdminLogin.vue 原样提取——challenge 一次性使用、
 * PoW 求解、失败后预取升级 challenge、错误分级文案，全部保持既有行为；
 * 新增 remember（保持登录 24h，默认不勾）。/login 与 /admin/login 双入口共用。
 */
export function useLoginForm(onSuccess: (result: LoginResult) => void | Promise<void>) {
  const username = ref('')
  const password = ref('')
  const error = ref('')
  const submitting = ref(false)
  const remember = ref(false)
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
      }, remember.value)
      saveAdminSession(result, { remember: remember.value })
      await onSuccess(result)
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

  return {
    username, password, error, submitting, verifying, remember,
    captchaRequired, captchaImage, captchaAnswer,
    refreshCaptcha, submit,
  }
}
