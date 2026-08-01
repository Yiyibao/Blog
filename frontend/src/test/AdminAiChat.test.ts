import { describe, it, expect, beforeEach, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import AdminAiChat from '../components/AdminAiChat.vue'
import AdminAiPage from '../pages/AdminAiPage.vue'
import router from '../router/index'
import * as adminApi from '../api/admin'
import { useAuthStore } from '../stores/auth'

const mockStreamAiChat = vi.fn()
const mockLogout = vi.fn()
const mockFetchAiProviders = vi.fn()

vi.mock('../api/admin', async (importOriginal) => {
  const actual = await importOriginal<typeof adminApi>()
  return {
    ...actual,
    fetchAiProviders: (...args: unknown[]) => mockFetchAiProviders(...args),
    streamAiChat: (...args: unknown[]) => mockStreamAiChat(...args),
    logout: (...args: unknown[]) => mockLogout(...args),
  }
})

// 4A-2：模拟流式成功——按增量回调后正常结束
function streamResolve(content: string) {
  mockStreamAiChat.mockImplementation(
    async (_messages: unknown, callbacks: adminApi.AiStreamCallbacks) => {
      callbacks.onDelta(content)
      callbacks.onDone?.({ model: 'deepseek-v4-flash', usage: null })
    },
  )
}

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
  mockFetchAiProviders.mockReset()
  mockFetchAiProviders.mockResolvedValue([{
    id: 1,
    name: 'OpenCode Sidecar',
    baseUrl: 'http://127.0.0.1:4096',
    providerType: 'OPENCODE_SERVER',
    models: [
      'deepseek-v4-flash', 'deepseek-v4-pro', 'glm-5.1', 'glm-5.2',
      'gpt-5.6-luna', 'grok-4.5', 'hy3', 'kimi-k2.6', 'kimi-k2.7-code',
      'kimi-k3', 'mimo-v2.5', 'mimo-v2.5-pro', 'minimax-m2.7', 'minimax-m3',
      'qwen3.6-plus', 'qwen3.7-max', 'qwen3.7-plus',
    ],
    defaultModel: 'mimo-v2.5',
    enabled: true,
    isDefault: true,
    hasKey: true,
    keyTail: null,
    dailyRequestLimit: 200,
    dailyTokenLimit: 200000,
    createdAt: '2026-07-01T00:00:00Z',
    updatedAt: '2026-07-01T00:00:00Z',
  }])
  mockStreamAiChat.mockReset()
  mockLogout.mockReset()
  window.sessionStorage.clear()
  window.sessionStorage.setItem('yubai-admin-token', 'valid-token')
  window.sessionStorage.setItem('yubai-admin-expiry', '2099-12-31T23:59:59Z')
  window.sessionStorage.setItem('yubai-admin-role', 'ADMIN')
})

describe('AdminAiChat Component', () => {
  it('shows every configured model below the chat input', async () => {
    const wrapper = await mountComponent()
    await flushPromises()

    const select = wrapper.find('[data-testid="chat-model-select"]')
    expect(select.exists()).toBe(true)
    expect(select.findAll('option').map((option) => option.text())).toHaveLength(17)
    expect(select.findAll('option').map((option) => option.text())).toContain('qwen3.7-plus')
    expect(select.findAll('option').map((option) => option.text())).toContain('mimo-v2.5-pro')
  })

  it('sends the selected model with the next message', async () => {
    streamResolve('Model response')

    const wrapper = await mountComponent()
    await flushPromises()
    await wrapper.find('[data-testid="chat-model-select"]').setValue('qwen3.7-plus')
    await wrapper.find('textarea').setValue('Use this model')
    await wrapper.find('button.send-btn').trigger('click')

    expect(mockStreamAiChat).toHaveBeenCalledWith(
      [{ role: 'user', content: 'Use this model' }],
      expect.objectContaining({ onDelta: expect.any(Function) }),
      expect.objectContaining({ model: 'qwen3.7-plus', providerId: 1, signal: expect.any(AbortSignal) }),
    )
  })

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

  it('sends user message, streams response, and updates sessionStorage', async () => {
    streamResolve('Hello! I am DeepSeek AI.')

    const wrapper = await mountComponent()
    const input = wrapper.find('textarea')
    await input.setValue('Hello AI')
    await wrapper.find('button.send-btn').trigger('click')

    expect(mockStreamAiChat).toHaveBeenCalledWith(
      [{ role: 'user', content: 'Hello AI' }],
      expect.objectContaining({ onDelta: expect.any(Function) }),
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    )

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

  it('displays loading state, disables inputs, then renders streamed deltas', async () => {
    let resolveApi!: () => void
    let capturedCallbacks!: adminApi.AiStreamCallbacks
    mockStreamAiChat.mockImplementation(
      (_messages: unknown, callbacks: adminApi.AiStreamCallbacks) => {
        capturedCallbacks = callbacks
        return new Promise<void>((resolve) => { resolveApi = () => resolve() })
      },
    )

    const wrapper = await mountComponent()
    const input = wrapper.find('textarea')
    await input.setValue('Thinking prompt')
    await wrapper.find('button.send-btn').trigger('click')

    expect(wrapper.text()).toContain('思考中…')
    expect(wrapper.text()).toContain('停止生成')
    expect(wrapper.find('textarea').attributes('disabled')).toBeDefined()
    expect(wrapper.find('button.send-btn').attributes('disabled')).toBeDefined()

    capturedCallbacks.onDelta('Done ')
    capturedCallbacks.onDelta('thinking')
    await flushPromises()
    expect(wrapper.text()).not.toContain('思考中…')
    expect(wrapper.text()).toContain('Done thinking')

    resolveApi()
    await flushPromises()

    expect(wrapper.text()).not.toContain('停止生成')
    expect(wrapper.text()).toContain('Done thinking')
  })

  it('stops streaming on demand and keeps the partial content', async () => {
    let capturedCallbacks!: adminApi.AiStreamCallbacks
    mockStreamAiChat.mockImplementation(
      (_messages: unknown, callbacks: adminApi.AiStreamCallbacks, options: adminApi.AiStreamOptions) =>
        new Promise<void>((_resolve, reject) => {
          capturedCallbacks = callbacks
          options.signal?.addEventListener('abort', () => {
            const abortError = new Error('aborted')
            abortError.name = 'AbortError'
            reject(abortError)
          })
        }),
    )

    const wrapper = await mountComponent()
    await wrapper.find('textarea').setValue('Long generation')
    await wrapper.find('button.send-btn').trigger('click')

    capturedCallbacks.onDelta('Partial answer')
    await flushPromises()
    expect(wrapper.text()).toContain('Partial answer')

    await wrapper.find('button.stop-btn').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Partial answer')
    expect(wrapper.find('.chat-error-bar').exists()).toBe(false)
    // 中止后回到空闲态：输入框解除禁用、停止按钮消失；send 按钮因输入已清空仍然禁用，
    // 重新输入后应恢复可用（可再次发送）。
    expect(wrapper.find('textarea').attributes('disabled')).toBeUndefined()
    expect(wrapper.find('button.stop-btn').exists()).toBe(false)
    await wrapper.find('textarea').setValue('Follow-up question')
    expect(wrapper.find('button.send-btn').attributes('disabled')).toBeUndefined()

    const saved = JSON.parse(window.sessionStorage.getItem('yubai-admin-ai-messages')!)
    expect(saved[saved.length - 1]).toEqual({ role: 'assistant', content: 'Partial answer' })
  })

  it('displays error state when the stream fails', async () => {
    mockStreamAiChat.mockRejectedValue(new Error('Network failure'))

    const wrapper = await mountComponent()
    const input = wrapper.find('textarea')
    await input.setValue('Failed query')
    await wrapper.find('button.send-btn').trigger('click')

    await flushPromises()

    expect(wrapper.find('.chat-error-bar').text()).toContain('AI 响应失败，请检查网络或稍后重试。')
  })

  it('clears session and redirects to /admin/login on 401 error', async () => {
    mockStreamAiChat.mockRejectedValue(new adminApi.AiStreamHttpError(401, '未登录或登录已过期'))

    const testRouter = createTestRouter()
    const wrapper = await mountComponent(testRouter)

    const input = wrapper.find('textarea')
    await input.setValue('Unauthorized test')
    await wrapper.find('button.send-btn').trigger('click')

    await flushPromises()

    expect(mockLogout).toHaveBeenCalled()
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
    streamResolve(htmlPayload)

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
    streamResolve('Hotkey response')

    const wrapper = await mountComponent()
    const input = wrapper.find('textarea')
    await input.setValue('Keyboard message')

    await input.trigger('keydown', { key: 'Enter', ctrlKey: true })
    await flushPromises()

    expect(mockStreamAiChat).toHaveBeenCalledWith(
      [{ role: 'user', content: 'Keyboard message' }],
      expect.objectContaining({ onDelta: expect.any(Function) }),
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    )
    expect(wrapper.text()).toContain('Hotkey response')
  })
})

describe('FD-29 宠物动画事件（供 AdminPetAssistant 驱动状态，不改请求逻辑）', () => {
  it('成功流依次发出 stream-start / stream-first-delta / stream-complete', async () => {
    streamResolve('OK response')

    const wrapper = await mountComponent()
    await wrapper.find('textarea').setValue('hello pet')
    await wrapper.find('button.send-btn').trigger('click')
    await flushPromises()

    const events = wrapper.emitted()
    expect(events['stream-start']).toHaveLength(1)
    expect(events['stream-first-delta']).toHaveLength(1)
    expect(events['stream-complete']).toHaveLength(1)
    expect(events['stream-error']).toBeUndefined()
    expect(events['stream-abort']).toBeUndefined()
  })

  it('失败发出 stream-error', async () => {
    mockStreamAiChat.mockRejectedValue(new Error('Network failure'))

    const wrapper = await mountComponent()
    await wrapper.find('textarea').setValue('boom')
    await wrapper.find('button.send-btn').trigger('click')
    await flushPromises()

    expect(wrapper.emitted('stream-error')).toHaveLength(1)
    expect(wrapper.emitted('stream-complete')).toBeUndefined()
  })

  it('展示后端返回的安全错误消息（不吞错、不透传供应商原始响应）', async () => {
    mockStreamAiChat.mockRejectedValue(new adminApi.AiStreamHttpError(502, 'OpenCode Server returned an error'))

    const wrapper = await mountComponent()
    await wrapper.find('textarea').setValue('will fail')
    await wrapper.find('button.send-btn').trigger('click')
    await flushPromises()

    expect(wrapper.find('.chat-error-bar').text()).toContain('OpenCode Server returned an error')
  })

  it('内部标记 empty response 回退通用文案', async () => {
    mockStreamAiChat.mockRejectedValue(new adminApi.AiStreamHttpError(502, 'empty response'))

    const wrapper = await mountComponent()
    await wrapper.find('textarea').setValue('empty reply')
    await wrapper.find('button.send-btn').trigger('click')
    await flushPromises()

    expect(wrapper.find('.chat-error-bar').text()).toContain('AI 响应失败，请检查网络或稍后重试。')
  })

  it('停止生成发出 stream-abort，不发出 complete/error', async () => {
    let capturedCallbacks!: adminApi.AiStreamCallbacks
    mockStreamAiChat.mockImplementation(
      (_messages: unknown, callbacks: adminApi.AiStreamCallbacks, options: adminApi.AiStreamOptions) =>
        new Promise<void>((_resolve, reject) => {
          capturedCallbacks = callbacks
          options.signal?.addEventListener('abort', () => {
            const abortError = new Error('aborted')
            abortError.name = 'AbortError'
            reject(abortError)
          })
        }),
    )

    const wrapper = await mountComponent()
    await wrapper.find('textarea').setValue('long job')
    await wrapper.find('button.send-btn').trigger('click')
    capturedCallbacks.onDelta('partial')
    await flushPromises()

    await wrapper.find('button.stop-btn').trigger('click')
    await flushPromises()

    expect(wrapper.emitted('stream-abort')).toHaveLength(1)
    expect(wrapper.emitted('stream-complete')).toBeUndefined()
    expect(wrapper.emitted('stream-error')).toBeUndefined()
  })

  it('输入框带稳定 testid 供 /admin/ai 宠物点击聚焦', async () => {
    const wrapper = await mountComponent()
    expect(wrapper.find('[data-testid="ai-chat-input"]').exists()).toBe(true)
  })

  it('卸载时中止进行中的流式请求（导航到 /admin/ai 等面板销毁场景依赖）', async () => {
    const captured = { signal: null as AbortSignal | null }
    mockStreamAiChat.mockImplementation(
      (_messages: unknown, _callbacks: adminApi.AiStreamCallbacks, options: adminApi.AiStreamOptions) =>
        new Promise<void>((_resolve, reject) => {
          captured.signal = options.signal ?? null
          options.signal?.addEventListener('abort', () => reject(new Error('aborted')))
        }),
    )

    const wrapper = await mountComponent()
    await wrapper.find('textarea').setValue('long running job')
    await wrapper.find('button.send-btn').trigger('click')
    expect(captured.signal?.aborted).toBe(false)

    wrapper.unmount()
    expect(captured.signal?.aborted).toBe(true)
  })
})

describe('Route Authentication & Guard', () => {
  it('protects /admin/ai with ADMIN role requirement (FD-8)', () => {
    const aiRoute = router.getRoutes().find(r => r.path === '/admin/ai')
    expect(aiRoute).toBeDefined()
    expect(aiRoute?.meta?.requiresAuth).toBe(true)
    expect(aiRoute?.meta?.capability).toBe('ai:usage')
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
