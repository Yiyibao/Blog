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

// L-14：代码块高亮走同一只读管线——围栏语言生效且恶意内容仍是纯文本
const CODE_NOTE = {
  ...MALICIOUS_NOTE,
  id: 2,
  title: '代码高亮笔记',
  markdownContent: [
    '```javascript',
    'const total = 1',
    '// <script>window.__xss_executed = true<\/script>',
    '```',
  ].join('\n'),
}

let noteItems: Array<typeof MALICIOUS_NOTE> = [MALICIOUS_NOTE]

vi.mock('../api/content', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/content')>()
  return {
    ...actual,
    fetchPublishedNotes: vi.fn(async () => ({
      items: noteItems,
      page: 0,
      size: 20,
      totalElements: noteItems.length,
      totalPages: 1,
    })),
  }
})

beforeEach(() => {
  setActivePinia(createPinia())
  noteItems = [MALICIOUS_NOTE]
  // @ts-expect-error 测试哨兵
  delete window.__xss_executed
})

async function mountNotes() {
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
  return wrapper
}

describe('P0-5 公开笔记渲染不执行 HTML', () => {
  it('markdown 中的 script/onerror/javascript: 不进入渲染结果', async () => {
    const wrapper = await mountNotes()

    const html = wrapper.html()
    expect(html).toContain('正文内容保持可见')

    expect(wrapper.element.querySelector('script')).toBeNull()
    expect(html).not.toContain('onerror=')
    expect(wrapper.element.querySelector('a[href^="javascript:"]')).toBeNull()
    // @ts-expect-error 测试哨兵
    expect(window.__xss_executed).toBeUndefined()

    wrapper.unmount()
  })

  it('L-14：围栏语言触发语法高亮，代码内的恶意标签仍是纯文本', async () => {
    noteItems = [CODE_NOTE]
    const wrapper = await mountNotes()

    const codeEl = wrapper.element.querySelector('pre code')
    expect(codeEl).toBeTruthy()
    expect(codeEl!.textContent).toContain('const total = 1')
    // lowlight 装饰产生 token span
    expect(wrapper.element.querySelector('pre code .hljs-keyword')).toBeTruthy()
    // 代码内容里的 <script> 只是文本，不产生可执行节点
    expect(wrapper.element.querySelector('script')).toBeNull()
    expect(codeEl!.textContent).toContain('<script>')
    // @ts-expect-error 测试哨兵
    expect(window.__xss_executed).toBeUndefined()

    wrapper.unmount()
  })
})
