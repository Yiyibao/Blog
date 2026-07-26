import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import AdminAiSidebar from '../components/AdminAiSidebar.vue'

enableAutoUnmount(afterEach)

const mockFetchProviders = vi.fn()

vi.mock('../api/admin', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/admin')>()
  return {
    ...actual,
    fetchAiProviders: (...args: unknown[]) => mockFetchProviders(...args),
  }
})

function provider(id: number, name: string, overrides: Record<string, unknown> = {}) {
  return {
    id, name, baseUrl: 'https://api.example.com', models: ['m-a', 'm-b'], defaultModel: 'm-a',
    enabled: true, isDefault: id === 1, hasKey: true, keyTail: '1234',
    dailyRequestLimit: 200, dailyTokenLimit: 200000,
    createdAt: '2026-07-01T00:00:00Z', updatedAt: '2026-07-01T00:00:00Z',
    ...overrides,
  }
}

async function mountDock(path: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/admin', component: { template: '<div />' } },
      { path: '/admin/notes', component: { template: '<div />' } },
      { path: '/admin/ai', component: { template: '<div />' } },
    ],
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(AdminAiSidebar, {
    global: {
      plugins: [router],
      stubs: {
        AdminAiChat: {
          name: 'AdminAiChat',
          template: '<div class="chat-stub" />',
          // 布尔缩写属性需要显式类型声明才会被强转为 true（数组式 props 会收到空串）
          props: { compact: Boolean, providerId: Number, model: String },
        },
      },
    },
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  localStorage.clear()
  setActivePinia(createPinia())
  mockFetchProviders.mockReset()
  mockFetchProviders.mockResolvedValue([provider(1, 'deepseek'), provider(2, 'glm', { isDefault: false })])
})

describe('4A-4 AI 助手停靠侧边栏', () => {
  it('/admin 路由显示触发钮，/admin/ai 全屏页隐藏（防双实例同写会话）', async () => {
    const onAdmin = await mountDock('/admin/notes')
    expect(onAdmin.find('.ai-dock-trigger').exists()).toBe(true)

    const onFullPage = await mountDock('/admin/ai')
    expect(onFullPage.find('.ai-dock').exists()).toBe(false)
  })

  it('展开后渲染 compact 对话核心并注入默认供应商与模型', async () => {
    const wrapper = await mountDock('/admin')
    await wrapper.find('.ai-dock-trigger').trigger('click')
    await flushPromises()

    const chat = wrapper.findComponent({ name: 'AdminAiChat' })
    expect(chat.exists()).toBe(true)
    expect(chat.props('compact')).toBe(true)
    expect(chat.props('providerId')).toBe(1)
    expect(chat.props('model')).toBe('m-a')
    // 展开态记忆
    expect(localStorage.getItem('yubai-ai-sidebar-open')).toBe('1')
  })

  it('切换供应商后模型跟随其默认值', async () => {
    const wrapper = await mountDock('/admin')
    await wrapper.find('.ai-dock-trigger').trigger('click')
    await flushPromises()

    const providerSelect = wrapper.find('select[aria-label="选择供应商"]')
    await providerSelect.setValue('2')
    await flushPromises()

    const chat = wrapper.findComponent({ name: 'AdminAiChat' })
    expect(chat.props('providerId')).toBe(2)
    expect(chat.props('model')).toBe('m-a')
  })

  it('Ctrl+Shift+A 快捷键开合', async () => {
    const wrapper = await mountDock('/admin')
    expect(wrapper.find('.ai-dock-panel').exists()).toBe(false)

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'a', ctrlKey: true, shiftKey: true }))
    await flushPromises()
    expect(wrapper.find('.ai-dock-panel').exists()).toBe(true)

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'a', ctrlKey: true, shiftKey: true }))
    await flushPromises()
    expect(wrapper.find('.ai-dock-panel').exists()).toBe(false)
  })
})
