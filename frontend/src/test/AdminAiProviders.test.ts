import { describe, it, expect, beforeEach, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import AdminAiProviders from '../components/AdminAiProviders.vue'
import AdminAiProvidersPage from '../pages/AdminAiProvidersPage.vue'
import router from '../router/index'
import * as adminApi from '../api/admin'
import { useAuthStore } from '../stores/auth'

const mockFetchAiProviders = vi.fn()
const mockCreateAiProvider = vi.fn()
const mockUpdateAiProvider = vi.fn()
const mockDeleteAiProvider = vi.fn()
const mockSetDefaultAiProvider = vi.fn()
const mockTestAiProvider = vi.fn()
const mockLogout = vi.fn()

vi.mock('../api/admin', async (importOriginal) => {
  const actual = await importOriginal<typeof adminApi>()
  return {
    ...actual,
    fetchAiProviders: (...args: unknown[]) => mockFetchAiProviders(...args),
    createAiProvider: (...args: unknown[]) => mockCreateAiProvider(...args),
    updateAiProvider: (...args: unknown[]) => mockUpdateAiProvider(...args),
    deleteAiProvider: (...args: unknown[]) => mockDeleteAiProvider(...args),
    setDefaultAiProvider: (...args: unknown[]) => mockSetDefaultAiProvider(...args),
    testAiProvider: (...args: unknown[]) => mockTestAiProvider(...args),
    logout: (...args: unknown[]) => mockLogout(...args),
  }
})

// 4A-3：keyTail 尾 4 位是响应中唯一的密钥痕迹——界面永不出现完整密钥
const deepseekProvider: adminApi.AiProvider = {
  id: 1,
  name: 'deepseek',
  baseUrl: 'https://api.deepseek.com',
  providerType: 'OPENAI_COMPATIBLE',
  models: ['deepseek-chat', 'deepseek-reasoner'],
  defaultModel: 'deepseek-chat',
  enabled: true,
  isDefault: true,
  hasKey: true,
  keyTail: '8f2e',
  dailyRequestLimit: 200,
  dailyTokenLimit: 200000,
  createdAt: '2026-07-01T00:00:00Z',
  updatedAt: '2026-07-20T00:00:00Z',
}

const disabledProvider: adminApi.AiProvider = {
  ...deepseekProvider,
  id: 2,
  name: 'ollama-local',
  baseUrl: 'https://ollama.internal.example',
  models: [],
  defaultModel: 'qwen3:14b',
  enabled: false,
  isDefault: false,
  hasKey: false,
  keyTail: null,
}

const secondaryProvider: adminApi.AiProvider = {
  ...deepseekProvider,
  id: 3,
  name: 'kimi',
  baseUrl: 'https://api.moonshot.cn',
  models: ['moonshot-v1-8k'],
  defaultModel: 'moonshot-v1-8k',
  isDefault: false,
  keyTail: '77aa',
}

function axiosLikeError(status: number, message?: string) {
  return Object.assign(new Error(message ?? `HTTP ${status}`), {
    isAxiosError: true,
    response: { status, data: message ? { message } : {} },
  })
}

function createTestRouter() {
  const r = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div>Home</div>' } },
      { path: '/admin/login', name: 'admin-login', component: { template: '<div>Login Page</div>' } },
      { path: '/admin', name: 'admin', component: { template: '<div>Dashboard</div>' } },
      { path: '/admin/notes', name: 'admin-notes', component: { template: '<div>Notes</div>' } },
      { path: '/admin/ai', name: 'admin-ai', component: { template: '<div>AI Chat</div>' } },
      { path: '/admin/ai/providers', name: 'admin-ai-providers', component: AdminAiProvidersPage, meta: { requiresAdmin: true } },
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
  await testRouter.push('/admin/ai/providers')
  await testRouter.isReady()
  const wrapper = mount(AdminAiProviders, {
    global: { plugins: [testRouter] },
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  setActivePinia(createPinia())
  mockFetchAiProviders.mockReset().mockResolvedValue([deepseekProvider, disabledProvider, secondaryProvider])
  mockCreateAiProvider.mockReset().mockResolvedValue(deepseekProvider)
  mockUpdateAiProvider.mockReset().mockResolvedValue(deepseekProvider)
  mockDeleteAiProvider.mockReset().mockResolvedValue(undefined)
  mockSetDefaultAiProvider.mockReset().mockResolvedValue(deepseekProvider)
  mockTestAiProvider.mockReset()
  mockLogout.mockReset()
  window.sessionStorage.clear()
  window.sessionStorage.setItem('yubai-admin-token', 'valid-token')
  window.sessionStorage.setItem('yubai-admin-expiry', '2099-12-31T23:59:59Z')
  window.sessionStorage.setItem('yubai-admin-role', 'ADMIN')
})

describe('AdminAiProviders Component', () => {
  it('renders provider rows with masked key tail and never a full key', async () => {
    const wrapper = await mountComponent()
    const text = wrapper.text()
    expect(text).toContain('deepseek')
    expect(text).toContain('····8f2e')
    expect(text).toContain('未设置密钥')
  })

  it('marks the default provider and shows enabled state per row', async () => {
    const wrapper = await mountComponent()
    const rows = wrapper.findAll('.provider-table article')
    expect(rows).toHaveLength(3)
    expect(rows[0].find('.provider-default-chip').exists()).toBe(true)
    expect(rows[1].find('.provider-default-chip').exists()).toBe(false)
    expect(rows[0].find('.provider-toggle').text()).toBe('已启用')
    expect(rows[1].find('.provider-toggle').text()).toBe('已停用')
  })

  it('creates a provider with parsed model list and entered api key', async () => {
    const wrapper = await mountComponent()
    await wrapper.find('.provider-section > header button.primary').trigger('click')

    await wrapper.find('input[placeholder="deepseek"]').setValue('glm')
    await wrapper.find('input[type="url"]').setValue('https://open.bigmodel.cn/api/paas/v4')
    await wrapper.find('input[type="password"]').setValue('glm-secret-key')
    await wrapper.find('.admin-editor textarea').setValue('glm-4.7\nglm-4.7-air, glm-4-flash')
    await wrapper.find('[data-testid="default-model"]').setValue('glm-4.7')
    await wrapper.find('form.admin-editor').trigger('submit')
    await flushPromises()

    expect(mockCreateAiProvider).toHaveBeenCalledWith({
      name: 'glm',
      baseUrl: 'https://open.bigmodel.cn/api/paas/v4',
      providerType: 'OPENAI_COMPATIBLE',
      apiKey: 'glm-secret-key',
      models: ['glm-4.7', 'glm-4.7-air', 'glm-4-flash'],
      defaultModel: 'glm-4.7',
      enabled: true,
      dailyRequestLimit: 200,
      dailyTokenLimit: 200000,
    })
    expect(wrapper.find('.admin-editor').exists()).toBe(false)
    expect(mockFetchAiProviders).toHaveBeenCalledTimes(2)
    // 密钥只写不回显：保存后输入过的明文密钥不得出现在页面任何位置
    expect(wrapper.text()).not.toContain('glm-secret-key')
  })

  it('edits without re-entering the key: empty password field is omitted from payload', async () => {
    const wrapper = await mountComponent()
    await wrapper.findAll('.provider-table article')[0]
      .findAll('.provider-actions button')
      .find((btn) => btn.text() === '编辑')!
      .trigger('click')

    const keyInput = wrapper.find('input[type="password"]')
    expect((keyInput.element as HTMLInputElement).value).toBe('')
    expect(keyInput.attributes('placeholder')).toContain('8f2e')

    await wrapper.find('form.admin-editor').trigger('submit')
    await flushPromises()

    expect(mockUpdateAiProvider).toHaveBeenCalledTimes(1)
    const [id, payload] = mockUpdateAiProvider.mock.calls[0]
    expect(id).toBe(1)
    expect(payload).not.toHaveProperty('apiKey')
    expect(payload.providerType).toBe('OPENAI_COMPATIBLE')
    expect(payload.name).toBe('deepseek')
    expect(payload.models).toEqual(['deepseek-chat', 'deepseek-reasoner'])
  })

  it('toggles enabled from the list without touching the stored key', async () => {
    const wrapper = await mountComponent()
    await wrapper.findAll('.provider-table article')[0].find('.provider-toggle').trigger('click')
    await flushPromises()

    expect(mockUpdateAiProvider).toHaveBeenCalledTimes(1)
    const [id, payload] = mockUpdateAiProvider.mock.calls[0]
    expect(id).toBe(1)
    expect(payload.enabled).toBe(false)
    expect(payload.providerType).toBe('OPENAI_COMPATIBLE')
    expect(payload).not.toHaveProperty('apiKey')
  })

  it('sets a provider as default; disabled providers cannot be selected', async () => {
    const wrapper = await mountComponent()
    const rows = wrapper.findAll('.provider-table article')

    // 默认供应商所在行不出现「设为默认」按钮
    expect(rows[0].findAll('.provider-actions button').map((btn) => btn.text())).not.toContain('设为默认')

    const disabledRowBtn = rows[1].findAll('.provider-actions button').find((btn) => btn.text() === '设为默认')!
    expect(disabledRowBtn.attributes('disabled')).toBeDefined()

    await rows[2].findAll('.provider-actions button').find((btn) => btn.text() === '设为默认')!.trigger('click')
    await flushPromises()
    expect(mockSetDefaultAiProvider).toHaveBeenCalledWith(3)
  })

  it('runs a connectivity test and lists the returned models', async () => {
    mockTestAiProvider.mockResolvedValue({
      ok: true,
      message: '连接成功',
      models: ['deepseek-chat', 'deepseek-reasoner'],
    })
    const wrapper = await mountComponent()
    await wrapper.findAll('.provider-table article')[0]
      .findAll('.provider-actions button')
      .find((btn) => btn.text() === '测试连通')!
      .trigger('click')
    await flushPromises()

    expect(mockTestAiProvider).toHaveBeenCalledWith(1)
    const result = wrapper.find('.provider-test')
    expect(result.classes()).toContain('ok')
    expect(result.text()).toContain('连接成功')
    expect(result.findAll('.provider-model-list li').map((item) => item.text()))
      .toEqual(['deepseek-chat', 'deepseek-reasoner'])
  })

  it('shows a rate limit hint when the test endpoint returns 429', async () => {
    mockTestAiProvider.mockRejectedValue(axiosLikeError(429))
    const wrapper = await mountComponent()
    await wrapper.findAll('.provider-table article')[0]
      .findAll('.provider-actions button')
      .find((btn) => btn.text() === '测试连通')!
      .trigger('click')
    await flushPromises()

    const result = wrapper.find('.provider-test')
    expect(result.classes()).toContain('fail')
    expect(result.text()).toContain('测试过于频繁')
  })

  it('surfaces backend conflict message when saving a duplicate name', async () => {
    mockCreateAiProvider.mockRejectedValue(axiosLikeError(409, '同名供应商已存在'))
    const wrapper = await mountComponent()
    await wrapper.find('.provider-section > header button.primary').trigger('click')
    await wrapper.find('input[placeholder="deepseek"]').setValue('deepseek')
    await wrapper.find('input[type="url"]').setValue('https://api.deepseek.com')
    await wrapper.find('[data-testid="default-model"]').setValue('deepseek-chat')
    await wrapper.find('form.admin-editor').trigger('submit')
    await flushPromises()

    expect(wrapper.find('.admin-editor .admin-error').text()).toContain('同名供应商已存在')
    expect(wrapper.find('.admin-editor').exists()).toBe(true)

    // 取消关闭后，抽屉内的保存错误不得遗留为页面级错误
    await wrapper.find('.admin-editor footer button.secondary').trigger('click')
    expect(wrapper.find('.admin-editor').exists()).toBe(false)
    expect(wrapper.find('.admin-page-error').exists()).toBe(false)
  })

  it('locks drawer close controls while a save is in flight', async () => {
    let resolveSave!: (value: adminApi.AiProvider) => void
    mockCreateAiProvider.mockImplementation(() => new Promise((resolve) => { resolveSave = resolve }))
    const wrapper = await mountComponent()
    await wrapper.find('.provider-section > header button.primary').trigger('click')
    await wrapper.find('input[placeholder="deepseek"]').setValue('glm')
    await wrapper.find('input[type="url"]').setValue('https://open.bigmodel.cn/api/paas/v4')
    await wrapper.find('[data-testid="default-model"]').setValue('glm-4.7')
    await wrapper.find('form.admin-editor').trigger('submit')
    await flushPromises()

    // 在途期间：取消与 × 禁用，点击遮罩不关闭——防止迟到响应作用到用户新开的抽屉
    expect(wrapper.find('.admin-editor footer button.secondary').attributes('disabled')).toBeDefined()
    expect(wrapper.find('.admin-editor > header button').attributes('disabled')).toBeDefined()
    await wrapper.find('.admin-editor-backdrop').trigger('click')
    expect(wrapper.find('.admin-editor').exists()).toBe(true)

    resolveSave(deepseekProvider)
    await flushPromises()
    expect(wrapper.find('.admin-editor').exists()).toBe(false)
  })

  it('invalidates a stale connectivity result after the provider is edited and saved', async () => {
    mockTestAiProvider.mockResolvedValue({ ok: true, message: '连接成功', models: [] })
    const wrapper = await mountComponent()
    const rowButtons = () => wrapper.findAll('.provider-table article')[0].findAll('.provider-actions button')

    await rowButtons().find((btn) => btn.text() === '测试连通')!.trigger('click')
    await flushPromises()
    expect(wrapper.find('.provider-test').exists()).toBe(true)

    await rowButtons().find((btn) => btn.text() === '编辑')!.trigger('click')
    await wrapper.find('form.admin-editor').trigger('submit')
    await flushPromises()
    expect(wrapper.find('.provider-test').exists()).toBe(false)
  })

  it('deletes only after confirmation', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false)
    const wrapper = await mountComponent()
    const deleteBtn = () => wrapper.findAll('.provider-table article')[2]
      .findAll('.provider-actions button')
      .find((btn) => btn.text() === '删除')!

    await deleteBtn().trigger('click')
    expect(mockDeleteAiProvider).not.toHaveBeenCalled()

    confirmSpy.mockReturnValue(true)
    await deleteBtn().trigger('click')
    await flushPromises()
    expect(mockDeleteAiProvider).toHaveBeenCalledWith(3)
  })

  it('clears session and redirects to /admin/login on 401', async () => {
    mockFetchAiProviders.mockRejectedValue(axiosLikeError(401))
    const testRouter = createTestRouter()
    await mountComponent(testRouter)

    expect(mockLogout).toHaveBeenCalled()
    expect(testRouter.currentRoute.value.path).toBe('/admin/login')
  })

  it('clears session and redirects when an inline action hits 401', async () => {
    mockTestAiProvider.mockRejectedValue(axiosLikeError(401))
    const testRouter = createTestRouter()
    const wrapper = await mountComponent(testRouter)

    await wrapper.findAll('.provider-table article')[0]
      .findAll('.provider-actions button')
      .find((btn) => btn.text() === '测试连通')!
      .trigger('click')
    await flushPromises()

    expect(mockLogout).toHaveBeenCalled()
    expect(testRouter.currentRoute.value.path).toBe('/admin/login')
    // 令牌失效不应被当作普通测试失败展示
    expect(wrapper.find('.provider-test').exists()).toBe(false)
  })

  it('highlights the AI provider entry in the sidebar', async () => {
    const wrapper = await mountComponent()
    const activeLinks = wrapper.findAll('.admin-sidebar nav a.active')
    expect(activeLinks).toHaveLength(1)
    expect(activeLinks[0].text()).toContain('AI 供应商')
  })

  it('shows protocol labels on provider rows', async () => {
    const wrapper = await mountComponent()
    const chips = wrapper.findAll('.provider-protocol-chip')
    expect(chips).toHaveLength(3)
    chips.forEach((chip) => expect(chip.text()).toBe('OpenAI'))
  })

  it('shows env-only credential label for OPENCODE_SERVER', async () => {
    const ocsProvider = { ...deepseekProvider, id: 5, name: 'ocs', baseUrl: 'http://127.0.0.1:4096', providerType: 'OPENCODE_SERVER' as const, hasKey: false, keyTail: null }
    mockFetchAiProviders.mockResolvedValue([deepseekProvider, ocsProvider])
    const wrapper = await mountComponent()
    expect(wrapper.text()).toContain('凭据来自环境变量')
    expect(wrapper.text()).not.toContain('未设置密钥')
  })

  it('creates a provider with OPENCODE_SERVER type and omits apiKey from payload', async () => {
    mockCreateAiProvider.mockResolvedValue({ ...deepseekProvider, id: 4, name: 'ocs', providerType: 'OPENCODE_SERVER' })
    const wrapper = await mountComponent()
    await wrapper.find('.provider-section > header button.primary').trigger('click')

    await wrapper.find('input[placeholder="deepseek"]').setValue('ocs')
    await wrapper.find('input[type="url"]').setValue('http://127.0.0.1:4096')
    await wrapper.find('input[type="radio"][value="OPENCODE_SERVER"]').setValue(true)
    await wrapper.find('.admin-editor textarea').setValue('opencode-sidecar')
    await wrapper.find('[data-testid="default-model"]').setValue('opencode-sidecar')
    await wrapper.find('form.admin-editor').trigger('submit')
    await flushPromises()

    expect(mockCreateAiProvider).toHaveBeenCalledWith({
      name: 'ocs',
      baseUrl: 'http://127.0.0.1:4096',
      providerType: 'OPENCODE_SERVER',
      models: ['opencode-sidecar'],
      defaultModel: 'opencode-sidecar',
      enabled: true,
      dailyRequestLimit: 200,
      dailyTokenLimit: 200000,
    })
    expect(mockCreateAiProvider.mock.calls[0][0]).not.toHaveProperty('apiKey')
  })

  it('hides api key input and shows guidance when OPENCODE_SERVER is selected', async () => {
    const wrapper = await mountComponent()
    await wrapper.find('.provider-section > header button.primary').trigger('click')

    // initially OPENAI_COMPATIBLE: api key input visible, no guidance
    expect(wrapper.find('input[type="password"]').exists()).toBe(true)
    expect(wrapper.find('.provider-guidance').exists()).toBe(false)

    // switch to OPENCODE_SERVER
    await wrapper.find('input[type="radio"][value="OPENCODE_SERVER"]').setValue(true)
    await flushPromises()

    expect(wrapper.find('input[type="password"]').exists()).toBe(false)
    expect(wrapper.find('.provider-guidance').exists()).toBe(true)
    expect(wrapper.find('.provider-guidance').text()).toContain('127.0.0.1:4096')
    expect(wrapper.find('.provider-guidance').text()).toContain('APP_AI_OPENCODE_USERNAME')
    expect(wrapper.find('.provider-guidance').text()).toContain('APP_AI_OPENCODE_PASSWORD')
  })

  it('switching provider type clears unsaved apiKey from the form', async () => {
    let resolveSave!: (value: adminApi.AiProvider) => void
    mockCreateAiProvider.mockImplementation(() => new Promise((resolve) => { resolveSave = resolve }))
    const wrapper = await mountComponent()
    await wrapper.find('.provider-section > header button.primary').trigger('click')

    // type apiKey, then switch type
    await wrapper.find('input[type="password"]').setValue('secret-123')
    expect((wrapper.find('input[type="password"]').element as HTMLInputElement).value).toBe('secret-123')

    await wrapper.find('input[type="radio"][value="OPENCODE_SERVER"]').setValue(true)
    await flushPromises()

    // apiKey cleared
    await wrapper.find('input[type="radio"][value="OPENAI_COMPATIBLE"]').setValue(true)
    await flushPromises()
    expect((wrapper.find('input[type="password"]').element as HTMLInputElement).value).toBe('')

    // save must not contain apiKey
    await wrapper.find('input[placeholder="deepseek"]').setValue('test-provider')
    await wrapper.find('input[type="url"]').setValue('https://api.test.com')
    await wrapper.find('[data-testid="default-model"]').setValue('test-model')
    await wrapper.find('form.admin-editor').trigger('submit')
    await flushPromises()

    resolveSave(deepseekProvider)
    await flushPromises()

    // 切换会清除 apiKey，切换回 OpenAI 兼容后未重新输入，故 payload 不含 apiKey
    expect(mockCreateAiProvider.mock.calls[0][0]).not.toHaveProperty('apiKey')
    expect(mockCreateAiProvider.mock.calls[0][0].providerType).toBe('OPENAI_COMPATIBLE')
  })

  it('shows conditional base URL placeholder in drawer', async () => {
    const wrapper = await mountComponent()
    await wrapper.find('.provider-section > header button.primary').trigger('click')

    // default OPENAI_COMPATIBLE
    const urlInput = wrapper.find('input[type="url"]')
    expect(urlInput.attributes('placeholder')).toBe('https://api.deepseek.com')

    // switch to OPENCODE_SERVER
    await wrapper.find('input[type="radio"][value="OPENCODE_SERVER"]').setValue(true)
    await flushPromises()
    expect(urlInput.attributes('placeholder')).toBe('http://127.0.0.1:4096')
  })

  it('shows empty state mentioning both protocol types', async () => {
    mockFetchAiProviders.mockResolvedValue([])
    const wrapper = await mountComponent()
    expect(wrapper.text()).toContain('OpenAI 兼容')
    expect(wrapper.text()).toContain('OpenCode Server')
  })

  it('preserves providerType when editing an OPENCODE_SERVER provider', async () => {
    const ocsProvider = { ...deepseekProvider, id: 5, name: 'ocs', baseUrl: 'http://127.0.0.1:4096', providerType: 'OPENCODE_SERVER' as const }
    mockFetchAiProviders.mockResolvedValue([deepseekProvider, ocsProvider])
    const wrapper = await mountComponent()

    await wrapper.findAll('.provider-table article')[1]
      .findAll('.provider-actions button')
      .find((btn) => btn.text() === '编辑')!
      .trigger('click')

    // ensure OPENCODE_SERVER is selected
    const ocsRadio = wrapper.find('input[type="radio"][value="OPENCODE_SERVER"]')
    expect((ocsRadio.element as HTMLInputElement).checked).toBe(true)

    // save without typing apiKey
    await wrapper.find('form.admin-editor').trigger('submit')
    await flushPromises()

    expect(mockUpdateAiProvider).toHaveBeenCalled()
    const payload = mockUpdateAiProvider.mock.calls[0][1]
    expect(payload.providerType).toBe('OPENCODE_SERVER')
    expect(payload).not.toHaveProperty('apiKey')
  })

  it('preserves providerType when toggling enabled on an OPENCODE_SERVER provider', async () => {
    const ocsProvider = { ...deepseekProvider, id: 5, name: 'ocs', baseUrl: 'http://127.0.0.1:4096', providerType: 'OPENCODE_SERVER' as const }
    mockFetchAiProviders.mockResolvedValue([deepseekProvider, ocsProvider])
    const wrapper = await mountComponent()

    await wrapper.findAll('.provider-table article')[1].find('.provider-toggle').trigger('click')
    await flushPromises()

    expect(mockUpdateAiProvider).toHaveBeenCalledTimes(1)
    const payload = mockUpdateAiProvider.mock.calls[0][1]
    expect(payload.providerType).toBe('OPENCODE_SERVER')
    expect(payload).not.toHaveProperty('apiKey')
  })
})

describe('Route Registration & Guard', () => {
  it('registers /admin/ai/providers with ADMIN role requirement (FD-8)', () => {
    const providerRoute = router.getRoutes().find((r) => r.path === '/admin/ai/providers')
    expect(providerRoute).toBeDefined()
    expect(providerRoute?.meta?.requiresAuth).toBe(true)
    expect(providerRoute?.meta?.capability).toBe('ai:manage')
  })

  it('redirects unauthenticated visitors to /admin/login', async () => {
    const testRouter = createTestRouter()
    useAuthStore().clearSession()

    await testRouter.push('/admin/ai/providers')
    await testRouter.isReady()

    expect(testRouter.currentRoute.value.path).toBe('/admin/login')
  })
})
