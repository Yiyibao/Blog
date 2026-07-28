import { describe, it, expect } from 'vitest'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore, type LoginResult } from '../stores/auth'
import { Capabilities, type Capability } from '../utils/capabilities'

function freshStore() {
  setActivePinia(createPinia())
  return useAuthStore()
}

function result(overrides: Partial<LoginResult> = {}): LoginResult {
  return {
    token: 't-1',
    tokenType: 'Bearer',
    username: 'gxynf',
    expiresAt: '2099-12-31T23:59:59Z',
    role: 'ADMIN',
    displayName: '站长',
    ...overrides,
  }
}

/** 与 src/router/index.ts 守卫一致的副本（FD-8）。 */
function guardedRouter(): Router {
  const r = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/login', name: 'login', component: { template: '<div />' } },
      { path: '/admin/login', name: 'admin-login', component: { template: '<div />' } },
      { path: '/admin', name: 'admin', component: { template: '<div />' }, meta: { requiresAuth: true, capability: Capabilities.CONTENT_MANAGE } },
      { path: '/recipes', name: 'recipes', component: { template: '<div />' } },
    ],
  })
  r.beforeEach((to, _from, next) => {
    if (!to.meta.requiresAuth) { next(); return }
    const auth = useAuthStore()
    if (!auth.isAuthenticated) { next({ name: 'login', query: { next: to.fullPath } }); return }
    const required = to.meta.capability as Capability | undefined
    if (required && !auth.can(required)) { next({ path: '/recipes' }); return }
    next()
  })
  return r
}

describe('FD-8 authStore 角色感知', () => {
  it('保存会话时记录 role 与 displayName，ADMIN 判定成立', () => {
    sessionStorage.clear()
    const auth = freshStore()
    auth.saveSession(result())
    expect(auth.role).toBe('ADMIN')
    expect(auth.displayName).toBe('站长')
    expect(auth.isAdmin).toBe(true)
    expect(auth.isPartner).toBe(false)
    expect(auth.canKitchen).toBe(true)
    expect(sessionStorage.getItem('yubai-admin-role')).toBe('ADMIN')
  })

  it('PARTNER 会话可进 kitchen 但不是 ADMIN', () => {
    sessionStorage.clear()
    const auth = freshStore()
    auth.saveSession(result({ role: 'PARTNER', username: 'gf', displayName: '小伙伴' }))
    expect(auth.isAdmin).toBe(false)
    expect(auth.isPartner).toBe(true)
    expect(auth.canKitchen).toBe(true)
  })

  it('服务端 capabilities 是能力判定事实源', () => {
    sessionStorage.clear()
    const auth = freshStore()
    auth.saveSession(result({ capabilities: [Capabilities.ACCOUNT_ACCESS] }))
    expect(auth.isAdmin).toBe(true)
    expect(auth.can(Capabilities.ACCOUNT_ACCESS)).toBe(true)
    expect(auth.can(Capabilities.CONTENT_MANAGE)).toBe(false)
  })

  it('过期 token 即使保留角色也不具有能力', () => {
    sessionStorage.clear()
    const auth = freshStore()
    auth.saveSession(result({
      expiresAt: '2000-01-01T00:00:00Z',
      capabilities: [Capabilities.CONTENT_MANAGE],
    }))
    expect(auth.can(Capabilities.CONTENT_MANAGE)).toBe(false)
  })

  it('无角色的登录结果 fail-closed：已登录但无任何角色能力', () => {
    sessionStorage.clear()
    const auth = freshStore()
    auth.saveSession(result({ role: undefined, displayName: undefined }))
    expect(auth.isAuthenticated).toBe(true)
    expect(auth.isAdmin).toBe(false)
    expect(auth.canKitchen).toBe(false)
  })

  it('FD-6 之前的旧会话（有 token 无 role）在 store 启动时被清理', () => {
    sessionStorage.clear()
    localStorage.clear()
    sessionStorage.setItem('yubai-admin-token', 'stale-token')
    sessionStorage.setItem('yubai-admin-expiry', '2099-12-31T23:59:59Z')
    const auth = freshStore()
    expect(auth.isAuthenticated).toBe(false)
    expect(sessionStorage.getItem('yubai-admin-token')).toBeNull()
  })

  it('启动时清理遗留 localStorage 密钥', () => {
    sessionStorage.clear()
    localStorage.setItem('yubai-admin-token', 'legacy-token')
    localStorage.setItem('yubai-admin-role', 'ADMIN')
    const auth = freshStore()
    expect(auth.isAuthenticated).toBe(false)
    expect(localStorage.getItem('yubai-admin-token')).toBeNull()
  })

  it('clearSession 连角色与展示名一并清除', () => {
    sessionStorage.clear()
    const auth = freshStore()
    auth.saveSession(result())
    auth.clearSession()
    expect(auth.role).toBeNull()
    expect(sessionStorage.getItem('yubai-admin-role')).toBeNull()
    expect(sessionStorage.getItem('yubai-admin-display')).toBeNull()
  })

  it('守卫：匿名访问 /admin 被送去登录页', async () => {
    sessionStorage.clear()
    freshStore()
    const router = guardedRouter()
    await router.push('/admin')
    await router.isReady()
    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.next).toBe('/admin')
  })

  it('守卫：PARTNER 访问 /admin 被重定向到 /recipes 而非登录页', async () => {
    sessionStorage.clear()
    const auth = freshStore()
    auth.saveSession(result({ role: 'PARTNER', username: 'gf' }))
    const router = guardedRouter()
    await router.push('/admin')
    await router.isReady()
    expect(router.currentRoute.value.name).toBe('recipes')
  })

  it('守卫：ADMIN 正常进入 /admin', async () => {
    sessionStorage.clear()
    const auth = freshStore()
    auth.saveSession(result())
    const router = guardedRouter()
    await router.push('/admin')
    await router.isReady()
    expect(router.currentRoute.value.name).toBe('admin')
  })
})
