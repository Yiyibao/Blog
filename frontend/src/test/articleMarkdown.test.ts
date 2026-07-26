import { describe, it, expect, afterEach } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import ControlledMarkdown from '../components/ControlledMarkdown.vue'
import { extractMarkdownOutline } from '../utils/markdownOutline'

enableAutoUnmount(afterEach)

/** 3A-4/3B：文章 Markdown 受控渲染——publicNotesXss 模式扩展到文章 + TOC 源文提取。 */

describe('3B extractMarkdownOutline', () => {
  it('提取 h2、跳过代码围栏、剥离行内记号', () => {
    const markdown = [
      '# 主标题不入目录',
      '## 第一节',
      '```bash',
      '## 这行在代码块里，不是标题',
      '```',
      '## **加粗的**第二节 `含代码`',
      '### 三级标题不入目录',
      '## [链接标题](https://example.com)',
    ].join('\n')

    expect(extractMarkdownOutline(markdown)).toEqual([
      { id: 'h-0', title: '第一节' },
      { id: 'h-1', title: '加粗的第二节 含代码' },
      { id: 'h-2', title: '链接标题' },
    ])
  })

  it('空文与无标题文返回空目录', () => {
    expect(extractMarkdownOutline('')).toEqual([])
    expect(extractMarkdownOutline('正文而已')).toEqual([])
  })
})

describe('3A-4 ControlledMarkdown 受控渲染', () => {
  async function mountMarkdown(markdown: string) {
    const wrapper = mount(ControlledMarkdown, {
      props: { markdown },
      attachTo: document.body,
    })
    await flushPromises()
    return wrapper
  }

  it('恶意 HTML 不进渲染树（script/onerror/javascript: 全部失效）', async () => {
    const wrapper = await mountMarkdown([
      '## 标题',
      '',
      '<script>window.__article_xss = true<\/script>',
      '',
      '<img src="x" onerror="window.__article_xss = true">',
      '',
      '[点我](javascript:alert(1))',
      '',
      '正文保持可见。',
    ].join('\n'))

    expect(wrapper.text()).toContain('正文保持可见')
    expect(wrapper.element.querySelector('script')).toBeNull()
    expect(wrapper.html()).not.toContain('onerror=')
    expect(wrapper.element.querySelector('a[href^="javascript:"]')).toBeNull()
    // @ts-expect-error 测试哨兵
    expect(window.__article_xss).toBeUndefined()
  })

  it('围栏语言触发 lowlight 高亮，h2 按序落 id 与目录对齐', async () => {
    const wrapper = await mountMarkdown([
      '## 第一节',
      '',
      '```javascript',
      'const n = 1',
      '```',
      '',
      '## 第二节',
    ].join('\n'))

    expect(wrapper.element.querySelector('pre code .hljs-keyword')).toBeTruthy()
    const headings = [...wrapper.element.querySelectorAll('h2')]
    expect(headings.map((h) => h.id)).toEqual(['h-0', 'h-1'])
  })
})
