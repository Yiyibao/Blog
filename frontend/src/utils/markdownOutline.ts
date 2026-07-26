/**
 * 3B：TOC 从 Markdown 源提取（不再对渲染后 DOM/HTML 字符串做脆弱正则）。
 * id 按文档内出现顺序确定（h-0、h-1…），中文标题无需 slug 化；
 * ControlledMarkdown 渲染后按同一顺序把 id 写到 h2 元素上，两侧天然对齐。
 */
export interface OutlineItem {
  id: string
  title: string
}

export function extractMarkdownOutline(markdown: string): OutlineItem[] {
  const items: OutlineItem[] = []
  // 跳过围栏代码块内的 "## " 行
  let inFence = false
  for (const line of (markdown || '').split(/\r?\n/)) {
    if (/^\s*(```|~~~)/.test(line)) {
      inFence = !inFence
      continue
    }
    if (inFence) continue
    const match = /^##\s+(.+?)\s*$/.exec(line)
    if (match) {
      items.push({ id: `h-${items.length}`, title: stripInlineMarks(match[1]) })
    }
  }
  return items
}

/** 去掉行内 Markdown 记号，目录展示纯文本。 */
function stripInlineMarks(text: string): string {
  return text
    .replace(/`([^`]*)`/g, '$1')
    .replace(/\*\*([^*]*)\*\*/g, '$1')
    .replace(/\*([^*]*)\*/g, '$1')
    .replace(/~~([^~]*)~~/g, '$1')
    .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
    .trim()
}
