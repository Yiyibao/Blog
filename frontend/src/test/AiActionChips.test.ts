import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import AiActionChips from '../components/AiActionChips.vue'
import type { AiStreamCallbacks } from '../api/admin'

enableAutoUnmount(afterEach)

const mockStream = vi.fn()

vi.mock('../api/admin', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/admin')>()
  return {
    ...actual,
    streamAiChat: (...args: unknown[]) => mockStream(...args),
  }
})

beforeEach(() => {
  setActivePinia(createPinia())
  mockStream.mockReset()
})

function mountChips(context = '# 文章\n\n这是当前编辑内容。') {
  return mount(AiActionChips, { props: { getContext: () => context } })
}

describe('4A-5 场景化 AI 动作 chips', () => {
  it('点击总结：携带指令+当前内容调用流式接口，结果面板可一键填入（emit apply）', async () => {
    mockStream.mockImplementation(async (_messages: unknown, callbacks: AiStreamCallbacks) => {
      callbacks.onDelta('一段生成的摘要')
    })
    const wrapper = mountChips()

    await wrapper.findAll('.ai-chip')[0].trigger('click')
    await flushPromises()

    const [messages] = mockStream.mock.calls[0] as [Array<{ role: string; content: string }>]
    expect(messages[0].content).toContain('摘要')
    expect(messages[0].content).toContain('这是当前编辑内容')

    expect(wrapper.find('.result-text').text()).toContain('一段生成的摘要')
    await wrapper.find('.apply-btn').trigger('click')

    expect(wrapper.emitted('apply')).toEqual([['summary', '一段生成的摘要']])
    // 填入后面板关闭——只填入不保存，保存是宿主的显式动作
    expect(wrapper.find('.chips-result').exists()).toBe(false)
  })

  it('上下文超长时按 7000 字截断（适配服务端单条 8000 限额）', async () => {
    mockStream.mockImplementation(async (_m: unknown, callbacks: AiStreamCallbacks) => {
      callbacks.onDelta('ok')
    })
    const wrapper = mountChips('长'.repeat(9000))
    await wrapper.findAll('.ai-chip')[0].trigger('click')
    await flushPromises()

    const [messages] = mockStream.mock.calls[0] as [Array<{ content: string }>]
    expect(messages[0].content.length).toBeLessThanOrEqual(8000)
  })

  it('空上下文直接提示，不发请求', async () => {
    const wrapper = mountChips('   ')
    await wrapper.findAll('.ai-chip')[0].trigger('click')
    await flushPromises()

    expect(mockStream).not.toHaveBeenCalled()
    expect(wrapper.find('.chips-error').text()).toContain('没有可用的编辑内容')
  })

  it('请求失败展示错误且不留结果面板', async () => {
    mockStream.mockRejectedValue(new Error('boom'))
    const wrapper = mountChips()
    await wrapper.findAll('.ai-chip')[0].trigger('click')
    await flushPromises()

    expect(wrapper.find('.chips-error').text()).toContain('AI 请求失败')
    expect(wrapper.find('.chips-result').exists()).toBe(false)
  })
})
