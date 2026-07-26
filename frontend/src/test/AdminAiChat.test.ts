import { describe, it, expect, beforeEach, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import axios from 'axios'
import AdminAiChat from '../components/AdminAiChat.vue'
import AdminAiPage from '../pages/AdminAiPage.vue'
import router from '../router/index'
import * as adminApi from '../api/admin'
import { useAuthStore } from '../stores/auth'

const mockSendAiChat = vi.fn()
const mockClearAdminSession = vi.fn()

vi.mock('../api/admin', async (importOriginal) => {
  const actual = await importOriginal<typeof adminApi>()
  return {
    ...actual,
    sendAiChat: (...args: unknown[]) => mockSendAiChat(...args),
    clearAdminSession: (...args: unknown[]) => mockClearAdminSession(...args),
  }
})

function createTestRouter() {
  const r = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div>Home</div>' } },
      { path: '/admin/login', name: 'admin-login', component: { template: '<div>Login Page</div>' } },
      { path: '/admin', name: 'admin', component: { template: '<div>Dashboard</div>' } },
      { path: '/admin/notes', name: 'admin-notes', component: { template: '<div>Notes</div>' } },
      { path: '/admin/ai', name: 'admin-ai', component: AdminAiPage, meta: { requiresAdmin: true } },
    ],
  })
  r.beforeEach((to, _from, next) => {
    if (to.meta.requiresAdmin) {
      const auth = useAuthStore()
      if (!auth.isAuthenticated) {
        next({ name: 'admin-login' })
        return
      }
    }
    next()
  })
  return r
}

async function mountComponent(testRouter = createTestRouter()) {
  await testRouter.push('/admin/ai')
  await testRouter.isReady()
  return mount(AdminAiChat, {
    global: {
      plugins: [testRouter],
    },
  })
}

beforeEach(() => {
  setActivePinia(createPinia())
  mockSendAiChat.mockReset()
  mockClearAdminSession.mockReset()
  window.sessionStorage.clear()
  window.sessionStorage.setItem('yubai-admin-token', 'valid-token')
  window.sessionStorage.setItem('yubai-admin-expiry', '2099-12-31T23:59:59Z')
})

describe('AdminAiChat Component', () => {
  it('restores stored messages from sessionStorage on mount', async () => {
    const stored = [
      { role: 'user', content: 'Previous question' },
      { role: 'assistant', content: 'Previous answer' },
    ]
    window.sessionStorage.setItem('yubai-admin-ai-messages', JSON.stringify(stored))

    const wrapper = await mountComponent()
    const text = wrapper.text()
    expect(text).toContain('Previous question')
    expect(text).toContain('Previous answer')
  })

  it('safely ignores corrupted or invalid sessionStorage data', async () => {
    window.sessionStorage.setItem('yubai-admin-ai-messages', 'invalid-json{{{')
    const wrapper = await mountComponent()
    expect(wrapper.text()).toContain('管理员 AI 助手')
    expect(window.sessionStorage.getItem('yubai-admin-ai-messages')).toBeNull()
  })

  it('sends user message, displays response, and updates sessionStorage', async () => {
    mockSendAiChat.mockResolvedValue({
      content: 'Hello! I am DeepSeek AI.',
      model: 'deepseek-v4-flash',
      usage: null,
    })

    const wrapper = await mountComponent()
    const input = wrapper.find('textarea')
    await input.setValue('Hello AI')
    await wrapper.find('button.send-btn').trigger('click')

    expect(mockSendAiChat).toHaveBeenCalledWith([
      { role: 'user', content: 'Hello AI' },
    ])

    await flushPromises()

    expect(wrapper.text()).toContain('Hello AI')
    expect(wrapper.text()).toContain('Hello! I am DeepSeek AI.')

    const saved = window.sessionStorage.getItem('yubai-admin-ai-messages')
    expect(saved).not.toBeNull()
    const parsed = JSON.parse(saved!)
    expect(parsed).toHaveLength(2)
    expect(parsed[0]).toEqual({ role: 'user', content: 'Hello AI' })
    expect(parsed[1]).toEqual({ role: 'assistant', content: 'Hello! I am DeepSeek AI.' })
  })

  it('displays loading state and disables inputs during request', async () => {
    let resolveApi!: (val: unknown) => void
    mockSendAiChat.mockReturnValue(new Promise((resolve) => { resolveApi = resolve }))

    const wrapper = await mountComponent()
    const input = wrapper.find('textarea')
    await input.setValue('Thinking prompt')
    await wrapper.find('button.send-btn').trigger('click')

    expect(wrapper.text()).toContain('思考中…')
    expect(wrapper.find('textarea').attributes('disabled')).toBeDefined()
    expect(wrapper.find('button.send-btn').attributes('disabled')).toBeDefined()

    resolveApi({ content: 'Done thinking', model: 'test' })
    await flushPromises()

    expect(wrapper.text()).not.toContain('思考中…')
    expect(wrapper.text()).toContain('Done thinking')
  })

  it('displays error state when API request fails', async () => {
    mockSendAiChat.mockRejectedValue(new Error('Network failure'))

    const wrapper = await mountComponent()
    const input = wrapper.find('textarea')
    await input.setValue('Failed query')
    await wrapper.find('button.send-btn').trigger('click')

    await flushPromises()

    expect(wrapper.find('.chat-error-bar').text()).toContain('AI 响应失败，请检查网络或稍后重试。')
  })

  it('clears session and redirects to /admin/login on 401 error', async () => {
    const error401 = new axios.AxiosError('Unauthorized', '401', undefined, undefined, {
      status: 401,
      data: {},
      headers: {},
      config: { headers: {} as any },
      statusText: 'Unauthorized',
    })
    mockSendAiChat.mockRejectedValue(error401)

    const testRouter = createTestRouter()
    const wrapper = await mountComponent(testRouter)

    const input = wrapper.find('textarea')
    await input.setValue('Unauthorized test')
    await wrapper.find('button.send-btn').trigger('click')

    await flushPromises()

    expect(mockClearAdminSession).toHaveBeenCalled()
    expect(testRouter.currentRoute.value.path).toBe('/admin/login')
  })

  it('clears conversation when confirmed', async () => {
    window.sessionStorage.setItem('yubai-admin-ai-messages', JSON.stringify([
      { role: 'user', content: 'Message to clear' },
    ]))
    vi.spyOn(window, 'confirm').mockReturnValue(true)

    const wrapper = await mountComponent()
    expect(wrapper.text()).toContain('Message to clear')

    const clearBtn = wrapper.find('.clear-btn')
    expect(clearBtn.exists()).toBe(true)
    await clearBtn.trigger('click')

    expect(wrapper.text()).not.toContain('Message to clear')
    expect(window.sessionStorage.getItem('yubai-admin-ai-messages')).toBeNull()
  })

  it('renders untrusted content as plain text safely without HTML injection', async () => {
    const htmlPayload = '<script>alert("xss")</script><div id="xss-target">Test HTML</div>'
    mockSendAiChat.mockResolvedValue({
      content: htmlPayload,
      model: 'deepseek-v4-flash',
      usage: null,
    })

    const wrapper = await mountComponent()
    const input = wrapper.find('textarea')
    await input.setValue(htmlPayload)
    await wrapper.find('button.send-btn').trigger('click')

    await flushPromises()

    expect(wrapper.find('#xss-target').exists()).toBe(false)
    expect(wrapper.find('script').exists()).toBe(false)
    expect(wrapper.text()).toContain(htmlPayload)
  })

  it('triggers send on Ctrl + Enter keydown', async () => {
    mockSendAiChat.mockResolvedValue({
      content: 'Hotkey response',
      model: 'deepseek-v4-flash',
    })

    const wrapper = await mountComponent()
    const input = wrapper.find('textarea')
    await input.setValue('Keyboard message')

    await input.trigger('keydown', { key: 'Enter', ctrlKey: true })
    await flushPromises()

    expect(mockSendAiChat).toHaveBeenCalledWith([
      { role: 'user', content: 'Keyboard message' },
    ])
    expect(wrapper.text()).toContain('Hotkey response')
  })
})

describe('Route Authentication & Guard', () => {
  it('protects /admin/ai with meta.requiresAdmin', () => {
    const aiRoute = router.getRoutes().find(r => r.path === '/admin/ai')
    expect(aiRoute).toBeDefined()
    expect(aiRoute?.meta?.requiresAdmin).toBe(true)
  })

  it('redirects unauthenticated user navigating to /admin/ai to /admin/login', async () => {
    const testRouter = createTestRouter()
    const authStore = useAuthStore()
    authStore.clearSession()

    await testRouter.push('/admin/ai')
    await testRouter.isReady()

    expect(testRouter.currentRoute.value.path).toBe('/admin/login')
  })

  it('allows authenticated user to navigate to /admin/ai', async () => {
    const testRouter = createTestRouter()
    const authStore = useAuthStore()
    authStore.saveSession({
      token: 'valid-token',
      tokenType: 'Bearer',
      username: 'admin',
      expiresAt: '2099-12-31T23:59:59Z',
    })

    await testRouter.push('/admin/ai')
    await testRouter.isReady()

    expect(testRouter.currentRoute.value.path).toBe('/admin/ai')
  })
})
