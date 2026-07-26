import { describe, it, expect, beforeEach, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import AdminLogin from '../components/AdminLogin.vue'
import { clearAdminSession, getAdminSessionName, hasValidAdminSession, saveAdminSession } from '../api/admin'
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

// L-7：登录组件提交前会先解 PoW，测试环境直接给定 nonce
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

const LOGIN_RESULT = {
  token: 'fresh-token',
  tokenType: 'Bearer',
  username: 'gxynf',
  expiresAt: '2099-12-31T23:59:59Z',
  // FD-8：真实后端自 FD-6 起返回角色；无角色的会话会被启动清理（见 authRole.test.ts）
  role: 'ADMIN',
  displayName: '站长',
}

/** 复刻 src/router/index.ts 的守卫逻辑（守卫读 useAuthStore；FD-8 起 requiresAuth+requiresRole）。 */
function createGuardedRouter(): Router {
  const r = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div>Home</div>' } },
      { path: '/admin/login', name: 'admin-login', component: { template: '<div>Login</div>' } },
      { path: '/admin', name: 'admin', component: { template: '<div>Dashboard</div>' }, meta: { requiresAuth: true, requiresRole: 'ADMIN' } },
      { path: '/recipes', name: 'recipes', component: { template: '<div>Recipes</div>' } },
    ],
  })
  r.beforeEach((to, _from, next) => {
    if (!to.meta.requiresAuth) {
      next()
      return
    }
    const auth = useAuthStore()
    if (!auth.isAuthenticated) {
      next({ name: 'admin-login' })
      return
    }
    if (to.meta.requiresRole && to.meta.requiresRole !== auth.role) {
      next({ path: '/recipes' })
      return
    }
    next()
  })
  return r
}

function startUnauthenticated() {
  sessionStorage.clear()
  setActivePinia(createPinia())
}

beforeEach(() => {
  mockLogin.mockReset()
  mockFetchChallenge.mockReset()
  mockFetchChallenge.mockResolvedValue(POW_CHALLENGE)
})

describe('NF-1 管理端登录态单一事实源', () => {
  it('saveAdminSession 直接写入 authStore，守卫立即可见', () => {
    startUnauthenticated()
    const auth = useAuthStore()
    expect(auth.isAuthenticated).toBe(false)

    saveAdminSession(LOGIN_RESULT)

    expect(auth.isAuthenticated).toBe(true)
    expect(auth.token).toBe('fresh-token')
    expect(getAdminSessionName()).toBe('gxynf')
    // 持久化仍写 sessionStorage，刷新后可恢复
    expect(sessionStorage.getItem('yubai-admin-token')).toBe('fresh-token')
  })

  it('clearAdminSession 同步清空 store 与 sessionStorage', () => {
    startUnauthenticated()
    saveAdminSession(LOGIN_RESULT)

    clearAdminSession()

    expect(useAuthStore().isAuthenticated).toBe(false)
    expect(hasValidAdminSession()).toBe(false)
    expect(sessionStorage.getItem('yubai-admin-token')).toBeNull()
  })

  it('过期会话视为未登录', () => {
    startUnauthenticated()
    saveAdminSession({ ...LOGIN_RESULT, expiresAt: '2000-01-01T00:00:00Z' })

    expect(hasValidAdminSession()).toBe(false)
    expect(useAuthStore().isAuthenticated).toBe(false)
  })

  it('登录成功后跳转 /admin 不再被守卫弹回（重定向死循环回归测试）', async () => {
    startUnauthenticated()
    const router = createGuardedRouter()

    // 未登录访问 /admin：被守卫送去登录页
    await router.push('/admin')
    await router.isReady()
    expect(router.currentRoute.value.name).toBe('admin-login')

    mockLogin.mockResolvedValue(LOGIN_RESULT)
    const wrapper = mount(AdminLogin, { global: { plugins: [router] } })
    await wrapper.find('input[autocomplete="username"]').setValue('gxynf')
    await wrapper.find('input[type="password"]').setValue('secret')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    // 守卫与登录流程读写同一个 store：跳转成功且停留在 /admin
    expect(mockLogin).toHaveBeenCalledWith('gxynf', 'secret', { challengeId: 'ch-1', nonce: '42', captchaAnswer: undefined })
    expect(router.currentRoute.value.name).toBe('admin')
  })

  it('已登录访问登录页时自动回到 /admin', async () => {
    startUnauthenticated()
    saveAdminSession(LOGIN_RESULT)
    const router = createGuardedRouter()
    await router.push('/admin/login')
    await router.isReady()

    mount(AdminLogin, { global: { plugins: [router] } })
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('admin')
  })
})
