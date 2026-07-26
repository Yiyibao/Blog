import axios from 'axios'
import { ref } from 'vue'
import {
  login,
  saveAdminSession,
  type LoginVerification,
} from '../api/admin'
import type { LoginResult } from '../stores/auth'

/**
 * FD-9：登录表单逻辑，/login 与 /admin/login 双入口共用。
 * L-15：人机验证从提交内联流程改为显性弹窗（HumanVerifyModal）——
 * 提交先开弹窗，弹窗内完成 challenge/PoW/图形码/冷却闭环后回传凭据再真正登录；
 * 关闭弹窗即中止本次登录。challenge 协议与后端契约不变。
 */
export function useLoginForm(onSuccess: (result: LoginResult) => void | Promise<void>) {
  const username = ref('')
  const password = ref('')
  const error = ref('')
  const submitting = ref(false)
  const remember = ref(false)
  /** L-15：验证弹窗开关——模板据此挂载 HumanVerifyModal */
  const verifyOpen = ref(false)

  function submit() {
    error.value = ''
    verifyOpen.value = true
  }

  /** 弹窗验证通过后回传凭据，执行真正的登录请求。 */
  async function handleVerified(verification: LoginVerification) {
    verifyOpen.value = false
    submitting.value = true
    try {
      const result = await login(username.value.trim(), password.value, verification, remember.value)
      saveAdminSession(result, { remember: remember.value })
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

  /** 关闭弹窗 = 中止登录（不视为错误）。 */
  function handleVerifyCancel() {
    verifyOpen.value = false
  }

  return {
    username, password, error, submitting, remember,
    verifyOpen, handleVerified, handleVerifyCancel, submit,
  }
}
