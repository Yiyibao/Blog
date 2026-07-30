import { describe, it, expect } from 'vitest'
import { sanitizeHtml } from '../utils/sanitizeHtml'

describe('NF-2 文章正文前端消毒', () => {
  it('剥离 script 标签', () => {
    const out = sanitizeHtml('<p>正常段落</p><script>alert(1)<\/script>')
    expect(out).toContain('正常段落')
    expect(out).not.toContain('script')
  })

  it('剥离事件处理器属性', () => {
    const out = sanitizeHtml('<img src="x" onerror="alert(1)"><p onclick="steal()">文字</p>')
    expect(out).not.toContain('onerror')
    expect(out).not.toContain('onclick')
    expect(out).toContain('文字')
  })

  it('剥离 javascript: 协议链接', () => {
    const out = sanitizeHtml('<a href="javascript:alert(1)">点我</a>')
    expect(out).not.toContain('javascript:')
    expect(out).toContain('点我')
  })

  it('保留 TOC 所需的标题 id 与常规排版标签', () => {
    const input = '<h2 id="section-1">章节</h2><pre><code class="language-ts">const a = 1</code></pre><ul><li>项</li></ul>'
    const out = sanitizeHtml(input)
    expect(out).toContain('id="section-1"')
    expect(out).toContain('language-ts')
    expect(out).toContain('<li>项</li>')
  })

  it('保留图片与安全链接', () => {
    const out = sanitizeHtml('<img src="/api/v1/note-assets/abc.png" alt="图"><a href="https://example.com">外链</a>')
    expect(out).toContain('src="/api/v1/note-assets/abc.png"')
    expect(out).toContain('loading="lazy"')
    expect(out).toContain('decoding="async"')
    expect(out).toContain('https://example.com')
  })

  it('空输入返回空字符串', () => {
    expect(sanitizeHtml('')).toBe('')
  })
})
