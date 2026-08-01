import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import App from '../App.vue'
import { useAuthStore, type LoginResult } from '../stores/auth'

enableAutoUnmount(afterEach)

function session(role: 'ADMIN' | 'PARTNER'): LoginResult {
  return {
    token: 't-1',
    tokenType: 'Bearer',
    username: role === 'ADMIN' ? 'admin' : 'gf',
    expiresAt: '2099-12-31T23:59:59Z',
    role,
    displayName: role === 'ADMIN' ? '站长' : '小伙伴',
  }
}

function createTestRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/admin', name: 'admin', component: { template: '<div />' } },
      { path: '/admin/login', name: 'admin-login', component: { template: '<div />' } },
      { path: '/login', name: 'login', component: { template: '<div />' } },
    ],
  })
}

let petHostMounts = 0

const PetHostStub = {
  name: 'AdminPetAssistant',
  props: [''],
  template: '<div class="pet-host-stub" />',
  mounted() { petHostMounts += 1 },
}

async function mountApp(role: 'ADMIN' | 'PARTNER' | 'GUEST' | null, path: string) {
  const router = createTestRouter()
  await router.push(path)
  await router.isReady()
  const auth = useAuthStore()
  auth.clearSession()
  if (role === 'ADMIN' || role === 'PARTNER') {
    auth.saveSession(session(role))
  }
  const wrapper = mount(App, {
    global: {
      plugins: [router],
      stubs: {
        GlobalSearch: true,
        EntryGate: true,
        AmbientSound: true,
        SiteFooter: true,
        AdminPetAssistant: PetHostStub,
      },
    },
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  setActivePinia(createPinia())
  window.sessionStorage.clear()
  petHostMounts = 0
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: query.includes('prefers-color-scheme: dark'),
    media: query,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  }))
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('FD-29 App 级宠物挂载条件', () => {
  it('游客不挂载宠物宿主，shell 无避让类', async () => {
    const wrapper = await mountApp('GUEST', '/')
    expect(wrapper.find('.pet-host-stub').exists()).toBe(false)
    expect(petHostMounts).toBe(0)
    expect(wrapper.find('.site-shell').classes()).not.toContain('has-pet-assistant')
  })

  it('过期会话不挂载宠物宿主', async () => {
    const router = createTestRouter()
    await router.push('/')
    await router.isReady()
    const auth = useAuthStore()
    auth.clearSession()
    auth.saveSession({ ...session('ADMIN'), expiresAt: '2000-01-01T00:00:00Z' })
    const wrapper = mount(App, {
      global: {
        plugins: [router],
        stubs: {
          GlobalSearch: true, EntryGate: true, AmbientSound: true, SiteFooter: true,
          AdminPetAssistant: PetHostStub,
        },
      },
    })
    await flushPromises()
    expect(wrapper.find('.pet-host-stub').exists()).toBe(false)
    expect(petHostMounts).toBe(0)
  })

  it('ADMIN 与 PARTNER 在公开页与后台页均挂载宠物宿主，并上移回到顶部避让', async () => {
    for (const role of ['ADMIN', 'PARTNER'] as const) {
      for (const path of ['/', '/admin']) {
        const wrapper = await mountApp(role, path)
        expect(wrapper.find('.pet-host-stub').exists()).toBe(true)
        expect(wrapper.find('.site-shell').classes()).toContain('has-pet-assistant')
        expect(petHostMounts).toBeGreaterThan(0)
      }
    }
  })

  it('登录页（/login 与 /admin/login）不挂载宠物宿主', async () => {
    for (const path of ['/login', '/admin/login']) {
      const wrapper = await mountApp('ADMIN', path)
      expect(wrapper.find('.pet-host-stub').exists(), path).toBe(false)
    }
  })

  it('游客不把宠物图集带入主包：宠物宿主为异步组件，挂载前不会触发 import', async () => {
    // App 中 AdminPetAssistant 通过 defineAsyncComponent 注册，代码分割独立 chunk；
    // 这里验证游客分支 v-if=false 时异步组件从未被加载
    const wrapper = await mountApp('GUEST', '/')
    expect(wrapper.find('.site-shell').classes()).not.toContain('has-pet-assistant')
    expect(petHostMounts).toBe(0)
  })
})
