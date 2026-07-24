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
}

export const useAuthStore = defineStore('auth', () => {
  const token = ref(readSessionValue('yubai-admin-token'))
  const username = ref(readSessionValue('yubai-admin-name'))
  const expiresAt = ref(readSessionValue('yubai-admin-expiry'))

  const isAuthenticated = computed(() => {
    if (!token.value) return false
    if (expiresAt.value && Date.parse(expiresAt.value) <= Date.now()) {
      clearSession()
      return false
    }
    return true
  })

  function saveSession(result: LoginResult) {
    token.value = result.token
    username.value = result.username
    expiresAt.value = result.expiresAt
    writeSessionValue('yubai-admin-token', result.token)
    writeSessionValue('yubai-admin-name', result.username)
    writeSessionValue('yubai-admin-expiry', result.expiresAt)
  }

  function clearSession() {
    token.value = null
    username.value = null
    expiresAt.value = null
    removeSessionValue('yubai-admin-token')
    removeSessionValue('yubai-admin-name')
    removeSessionValue('yubai-admin-expiry')
  }

  return { token, username, expiresAt, isAuthenticated, saveSession, clearSession }
})
