import { describe, it, expect, beforeEach, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import LoginPage from '../pages/LoginPage.vue'
import { useAuthStore } from '../stores/auth'

const mockLogin = vi.fn()
const mockFetchChallenge = vi.fn()

vi.mock('../api/admin', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/admin')>()
  return {
    ...actual,
    login: (...args: unknown[]) => mockLogin(...args),
    fetchLoginChallenge: (...args: unknown[]) => mockFetchChallenge(...args),
  }
})

vi.mock('../utils/pow', () => ({
  solvePow: vi.fn().mockResolvedValue('42'),
}))

const POW_CHALLENGE = {
  challengeId: 'ch-1',
  type: 'POW' as const,
  salt: 'abcd',
  difficulty: 1,
  captchaImage: null,
}

function loginResult(role: string, overrides: Record<string, unknown> = {}) {
  return {
    token: 'fresh-token',
    tokenType: 'Bearer',
    username: role === 'PARTNER' ? 'gf' : 'gxynf',
    expiresAt: '2099-12-31T23:59:59Z',
    role,
    displayName: role === 'PARTNER' ? '小伙伴' : '站长',
    ...overrides,
  }
}

function createTestRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/login', name: 'login', component: LoginPage },
      { path: '/admin', name: 'admin', component: { template: '<div>Admin</div>' } },
      { path: '/recipes', name: 'recipes', component: { template: '<div>Recipes</div>' } },
    ],
  })
}

async function mountPage(url = '/login') {
  const router = createTestRouter()
  await router.push(url)
  await router.isReady()
  const wrapper = mount(LoginPage, { global: { plugins: [router] } })
  return { wrapper, router }
}

async function fillAndSubmit(wrapper: ReturnType<typeof mount>, options: { remember?: boolean } = {}) {
  await wrapper.find('input[autocomplete="username"]').setValue('gf')
  await wrapper.find('input[type="password"]').setValue('红烧肉要少放糖多放辣2026')
  if (options.remember) await wrapper.find('input[type="checkbox"]').setValue(true)
  await wrapper.find('form').trigger('submit.prevent')
  await flushPromises()
}

beforeEach(() => {
  sessionStorage.clear()
  localStorage.clear()
  setActivePinia(createPinia())
  mockLogin.mockReset()
  mockFetchChallenge.mockReset()
  mockFetchChallenge.mockResolvedValue(POW_CHALLENGE)
})

describe('FD-9 通用登录页', () => {
  it('PARTNER 登录后落地 /recipes', async () => {
    mockLogin.mockResolvedValue(loginResult('PARTNER'))
    const { wrapper, router } = await mountPage()
    await fillAndSubmit(wrapper)
    expect(router.currentRoute.value.path).toBe('/recipes')
    expect(useAuthStore().isPartner).toBe(true)
  })

  it('ADMIN 登录后落地 /admin', async () => {
    mockLogin.mockResolvedValue(loginResult('ADMIN'))
    const { wrapper, router } = await mountPage()
    await fillAndSubmit(wrapper)
    expect(router.currentRoute.value.path).toBe('/admin')
  })

  it('带 ?next= 时优先回到来路（FD-14 意图接续的载体）', async () => {
    mockLogin.mockResolvedValue(loginResult('PARTNER'))
    const { wrapper, router } = await mountPage('/login?next=%2Frecipes%3Fview%3Dmenu%26intent%3DaddDish')
    await fillAndSubmit(wrapper)
    expect(router.currentRoute.value.fullPath).toBe('/recipes?view=menu&intent=addDish')
  })

  it('拒绝站外 next，回退角色默认页（防开放重定向）', async () => {
    mockLogin.mockResolvedValue(loginResult('PARTNER'))
    const { wrapper, router } = await mountPage('/login?next=https%3A%2F%2Fevil.example')
    await fillAndSubmit(wrapper)
    expect(router.currentRoute.value.path).toBe('/recipes')
  })

  it('勾选保持登录：remember 传给 login 且会话持久化到 localStorage', async () => {
    mockLogin.mockResolvedValue(loginResult('PARTNER'))
    const { wrapper } = await mountPage()
    await fillAndSubmit(wrapper, { remember: true })
    expect(mockLogin).toHaveBeenCalledWith('gf', '红烧肉要少放糖多放辣2026',
      { challengeId: 'ch-1', nonce: '42', captchaAnswer: undefined }, true)
    expect(localStorage.getItem('yubai-admin-token')).toBe('fresh-token')
    expect(localStorage.getItem('yubai-admin-role')).toBe('PARTNER')
  })

  it('不勾选保持登录：不落 localStorage 且清掉历史持久化副本', async () => {
    localStorage.setItem('yubai-admin-token', 'old-persistent')
    mockLogin.mockResolvedValue(loginResult('PARTNER'))
    const { wrapper } = await mountPage()
    await fillAndSubmit(wrapper)
    expect(mockLogin).toHaveBeenCalledWith('gf', '红烧肉要少放糖多放辣2026',
      { challengeId: 'ch-1', nonce: '42', captchaAnswer: undefined }, false)
    expect(localStorage.getItem('yubai-admin-token')).toBeNull()
  })

  it('已登录访问 /login 直接被送走', async () => {
    useAuthStore().saveSession(loginResult('PARTNER') as never)
    const { router } = await mountPage()
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/recipes')
  })
})

describe('FD-9 authStore 保持登录持久化', () => {
  it('sessionStorage 为空时从 localStorage 兜底恢复会话', () => {
    localStorage.setItem('yubai-admin-token', 'persisted-token')
    localStorage.setItem('yubai-admin-name', 'gf')
    localStorage.setItem('yubai-admin-expiry', '2099-12-31T23:59:59Z')
    localStorage.setItem('yubai-admin-role', 'PARTNER')
    setActivePinia(createPinia())
    const auth = useAuthStore()
    expect(auth.isAuthenticated).toBe(true)
    expect(auth.isPartner).toBe(true)
  })

  it('clearSession 同时清掉 localStorage 持久化副本', () => {
    const auth = useAuthStore()
    auth.saveSession(loginResult('PARTNER') as never, { remember: true })
    expect(localStorage.getItem('yubai-admin-token')).toBe('fresh-token')
    auth.clearSession()
    expect(localStorage.getItem('yubai-admin-token')).toBeNull()
    expect(sessionStorage.getItem('yubai-admin-token')).toBeNull()
  })
})
