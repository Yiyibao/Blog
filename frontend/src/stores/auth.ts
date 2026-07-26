import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

const memorySession = new Map<string, string>()

function readSessionValue(key: string) {
  try {
    return window.sessionStorage?.getItem(key) ?? memorySession.get(key) ?? null
  } catch {
    return memorySession.get(key) ?? null
  }
}

function writeSessionValue(key: string, value: string) {
  memorySession.set(key, value)
  try {
    window.sessionStorage?.setItem(key, value)
  } catch {
    // Some privacy modes disable sessionStorage; keep in memory.
  }
}

function removeSessionValue(key: string) {
  memorySession.delete(key)
  try {
    window.sessionStorage?.removeItem(key)
  } catch {
    // Already cleared from memory.
  }
}

export interface LoginResult {
  token: string
  tokenType: string
  username: string
  expiresAt: string
  // FD-8：可选以兼容旧调用方/夹具；真实后端自 FD-6 起必返
  role?: string
  displayName?: string
}

export const useAuthStore = defineStore('auth', () => {
  const token = ref(readSessionValue('yubai-admin-token'))
  const username = ref(readSessionValue('yubai-admin-name'))
  const expiresAt = ref(readSessionValue('yubai-admin-expiry'))
  const role = ref(readSessionValue('yubai-admin-role'))
  const displayName = ref(readSessionValue('yubai-admin-display'))

  const isAuthenticated = computed(() => {
    if (!token.value) return false
    if (expiresAt.value && Date.parse(expiresAt.value) <= Date.now()) {
      clearSession()
      return false
    }
    return true
  })

  // FD-8：fail-closed——role 缺失一律不算 ADMIN；越权判断绝不给未知角色放行
  const isAdmin = computed(() => isAuthenticated.value && role.value === 'ADMIN')
  const isPartner = computed(() => isAuthenticated.value && role.value === 'PARTNER')
  const canKitchen = computed(() => isAdmin.value || isPartner.value)

  function saveSession(result: LoginResult) {
    token.value = result.token
    username.value = result.username
    expiresAt.value = result.expiresAt
    role.value = result.role ?? null
    displayName.value = result.displayName ?? null
    writeSessionValue('yubai-admin-token', result.token)
    writeSessionValue('yubai-admin-name', result.username)
    writeSessionValue('yubai-admin-expiry', result.expiresAt)
    if (result.role) writeSessionValue('yubai-admin-role', result.role)
    else removeSessionValue('yubai-admin-role')
    if (result.displayName) writeSessionValue('yubai-admin-display', result.displayName)
    else removeSessionValue('yubai-admin-display')
  }

  function clearSession() {
    token.value = null
    username.value = null
    expiresAt.value = null
    role.value = null
    displayName.value = null
    removeSessionValue('yubai-admin-token')
    removeSessionValue('yubai-admin-name')
    removeSessionValue('yubai-admin-expiry')
    removeSessionValue('yubai-admin-role')
    removeSessionValue('yubai-admin-display')
  }

  // FD-8：FD-6 之前签发的会话没有 role——启动即清，让持有者重登一次拿到带角色的新会话
  if (token.value && !role.value) {
    clearSession()
  }

  return { token, username, expiresAt, role, displayName, isAuthenticated, isAdmin, isPartner, canKitchen, saveSession, clearSession }
})
