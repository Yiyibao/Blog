import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { Capabilities, getCapabilities, type Capability } from '../utils/capabilities'

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

// FD-9："保持登录"用 localStorage 跨会话持久化；隐私模式全部 try/catch 降级
function readPersistentValue(key: string) {
  try {
    return window.localStorage?.getItem(key) ?? null
  } catch {
    return null
  }
}

function writePersistentValue(key: string, value: string) {
  try {
    window.localStorage?.setItem(key, value)
  } catch {
    // 存不了就退化为仅本会话
  }
}

function removePersistentValue(key: string) {
  try {
    window.localStorage?.removeItem(key)
  } catch {
    // 忽略
  }
}

const SESSION_KEYS = ['yubai-admin-token', 'yubai-admin-name', 'yubai-admin-expiry', 'yubai-admin-role', 'yubai-admin-display', 'yubai-admin-capabilities'] as const
const KNOWN_CAPABILITIES = new Set<Capability>(Object.values(Capabilities))

function normalizeCapabilities(values: unknown): Capability[] {
  if (!Array.isArray(values)) return []
  return [...new Set(values.filter((value): value is Capability =>
    typeof value === 'string' && KNOWN_CAPABILITIES.has(value as Capability)))]
}

function restoreCapabilities(serialized: string | null, role: string | null): Capability[] {
  if (!serialized) return [...getCapabilities(role)]
  try {
    return normalizeCapabilities(JSON.parse(serialized))
  } catch {
    return [...getCapabilities(role)]
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
  capabilities?: string[]
}

export const useAuthStore = defineStore('auth', () => {
  // 会话优先，localStorage（保持登录）兜底
  const readValue = (key: string) => readSessionValue(key) ?? readPersistentValue(key)
  const token = ref(readValue('yubai-admin-token'))
  const username = ref(readValue('yubai-admin-name'))
  const expiresAt = ref(readValue('yubai-admin-expiry'))
  const role = ref(readValue('yubai-admin-role'))
  const displayName = ref(readValue('yubai-admin-display'))
  const storedCapabilities = readValue('yubai-admin-capabilities')
  const capabilities = ref<Capability[]>(restoreCapabilities(storedCapabilities, role.value))

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
  const canKitchen = computed(() => can(Capabilities.KITCHEN_ACCESS))
  function can(capability: Capability): boolean {
    return isAuthenticated.value && capabilities.value.includes(capability)
  }

  function saveSession(result: LoginResult, options: { remember?: boolean } = {}) {
    token.value = result.token
    username.value = result.username
    expiresAt.value = result.expiresAt
    role.value = result.role ?? null
    displayName.value = result.displayName ?? null
    capabilities.value = result.capabilities
      ? normalizeCapabilities(result.capabilities)
      : [...getCapabilities(result.role)]
    const values: Record<(typeof SESSION_KEYS)[number], string | null> = {
      'yubai-admin-token': result.token,
      'yubai-admin-name': result.username,
      'yubai-admin-expiry': result.expiresAt,
      'yubai-admin-role': result.role ?? null,
      'yubai-admin-display': result.displayName ?? null,
      'yubai-admin-capabilities': capabilities.value.length ? JSON.stringify(capabilities.value) : null,
    }
    for (const key of SESSION_KEYS) {
      const value = values[key]
      if (value) writeSessionValue(key, value)
      else removeSessionValue(key)
      // FD-9：勾选保持登录才落 localStorage；未勾选的登录顺带清掉历史持久化副本
      if (options.remember && value) writePersistentValue(key, value)
      else removePersistentValue(key)
    }
  }

  function clearSession() {
    token.value = null
    username.value = null
    expiresAt.value = null
    role.value = null
    displayName.value = null
    capabilities.value = []
    for (const key of SESSION_KEYS) {
      removeSessionValue(key)
      removePersistentValue(key)
    }
  }

  // FD-8：FD-6 之前签发的会话没有 role——启动即清，让持有者重登一次拿到带角色的新会话
  if (token.value && !role.value) {
    clearSession()
  }

  return { token, username, expiresAt, role, displayName, capabilities, isAuthenticated, isAdmin, isPartner, canKitchen, can, saveSession, clearSession }
})
