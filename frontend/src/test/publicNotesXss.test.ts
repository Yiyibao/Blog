import { describe, it, expect, beforeEach, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import PublicNotes from '../components/PublicNotes.vue'

/**
 * P0-5：固化「笔记 markdown 公开渲染不执行 HTML」。
 * 后端返回的 markdownContent 含 <script>/<img onerror> 时，
 * TipTap 只读渲染必须不产生可执行节点。
 */

const MALICIOUS_NOTE = {
  id: 1,
  title: 'XSS 测试笔记',
  markdownContent: [
    '# 标题',
    '',
    '<script>window.__xss_executed = true<\/script>',
    '',
    '<img src="x" onerror="window.__xss_executed = true">',
    '',
    '[点我](javascript:alert(1))',
    '',
    '正文内容保持可见。',
  ].join('\n'),
  folder: '安全',
  status: 'PUBLISHED',
  tags: ['xss'],
  sourceFileName: null,
  wordCount: 10,
  version: 1,
  createdAt: '2026-07-01T00:00:00Z',
  updatedAt: '2026-07-01T00:00:00Z',
}

vi.mock('../api/content', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/content')>()
  return {
    ...actual,
    fetchPublishedNotes: vi.fn(async () => ({
      items: [MALICIOUS_NOTE],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
    })),
  }
})

beforeEach(() => {
  setActivePinia(createPinia())
  // @ts-expect-error 测试哨兵
  delete window.__xss_executed
})

describe('P0-5 公开笔记渲染不执行 HTML', () => {
  it('markdown 中的 script/onerror/javascript: 不进入渲染结果', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/notes', name: 'notes', component: { template: '<div />' } }],
    })
    await router.push('/notes')
    await router.isReady()

    const wrapper = mount(PublicNotes, { attachTo: document.body, global: { plugins: [router] } })
    await flushPromises()
    await vi.runAllTimersAsync()
    await flushPromises()

    const html = wrapper.html()
    expect(html).toContain('正文内容保持可见')

    expect(wrapper.element.querySelector('script')).toBeNull()
    expect(html).not.toContain('onerror=')
    expect(wrapper.element.querySelector('a[href^="javascript:"]')).toBeNull()
    // @ts-expect-error 测试哨兵
    expect(window.__xss_executed).toBeUndefined()

    wrapper.unmount()
  })
})
