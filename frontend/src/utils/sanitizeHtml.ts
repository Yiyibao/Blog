import DOMPurify from 'dompurify';

/**
 * NF-2：文章正文渲染前的前端消毒（两步走第一步）。
 * 后端写入时已有 jsoup 白名单，这里是第二道防线——
 * 依赖链任何一环出问题（如管理端原始 HTML 文本域误存危险片段），
 * 也不会在公开页面形成存储型 XSS。
 * 第一期 Markdown 迁移后统一为「Markdown → 受控渲染」管线。
 */
export function sanitizeHtml(html: string): string {
  if (!html) return '';
  const sanitized = DOMPurify.sanitize(html, {
    USE_PROFILES: { html: true },
    // 保留 TOC 锚点与代码高亮所需属性；DOMPurify 默认保留 id/class，
    // 这里显式禁止危险的 URI 协议之外不再收紧，避免破坏既有正文。
    FORBID_TAGS: ['style', 'form', 'input', 'button'],
    FORBID_ATTR: ['formaction'],
  });
  const template = document.createElement('template');
  template.innerHTML = sanitized;
  template.content.querySelectorAll('img').forEach((image) => {
    image.setAttribute('loading', 'lazy');
    image.setAttribute('decoding', 'async');
  });
  return template.innerHTML;
}
