import axios from 'axios'
import { ref } from 'vue'
import {
  login,
  verifyTotp,
  saveAdminSession,
  type LoginVerification,
} from '../api/admin'
import type { LoginResult } from '../stores/auth'

export function useLoginForm(onSuccess: (result: LoginResult) => void | Promise<void>) {
  const username = ref('')
  const password = ref('')
  const error = ref('')
  const submitting = ref(false)
  const remember = ref(false)
  const verifyOpen = ref(false)
  const totpOpen = ref(false)
  const totpChallengeId = ref('')
  const totpSubmitting = ref(false)
  const totpError = ref('')

  function submit() {
    error.value = ''
    verifyOpen.value = true
  }

  async function handleVerified(verification: LoginVerification) {
    verifyOpen.value = false
    submitting.value = true
    try {
      const result = await login(username.value.trim(), password.value, verification, remember.value)
      if ('totpRequired' in result) {
        totpChallengeId.value = result.challengeId
        totpError.value = ''
        totpOpen.value = true
        return
      }
      saveAdminSession(result)
      await onSuccess(result)
    } catch (cause) {
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
    } finally {
      submitting.value = false
    }
  }

  async function submitTotp(code: string) {
    totpSubmitting.value = true
    totpError.value = ''
    try {
      const result = await verifyTotp(totpChallengeId.value, code)
      totpOpen.value = false
      saveAdminSession(result)
      await onSuccess(result)
    } catch (cause) {
      if (axios.isAxiosError(cause) && cause.response?.status === 401) {
        totpError.value = '验证码不正确、尝试次数过多或挑战已失效。'
      } else if (axios.isAxiosError(cause) && cause.response?.status === 429) {
        totpError.value = (cause.response.data as { message?: string } | undefined)?.message || '尝试过于频繁。'
      } else {
        totpError.value = '验证码验证失败，请稍后重试。'
      }
    } finally {
      totpSubmitting.value = false
    }
  }

  function cancelTotp() {
    totpOpen.value = false
    totpChallengeId.value = ''
    totpError.value = ''
    error.value = ''
  }

  function handleVerifyCancel() {
    verifyOpen.value = false
  }

  return {
    username, password, error, submitting, remember,
    verifyOpen, handleVerified, handleVerifyCancel, submit,
    totpOpen, totpSubmitting, totpError, submitTotp, cancelTotp,
  }
}
