/**
 * 5A：搜索命中高亮——把文本按查询词切成命中/非命中分段，
 * 由模板用 <mark> 渲染分段（纯文本插值，天然无 XSS 面；不走 v-html）。
 */
export interface HighlightSegment {
  text: string
  hit: boolean
}

export function splitHighlight(text: string, query: string): HighlightSegment[] {
  const source = text ?? ''
  const needle = (query ?? '').trim().toLowerCase()
  if (!source) return []
  if (!needle) return [{ text: source, hit: false }]

  const lower = source.toLowerCase()
  const segments: HighlightSegment[] = []
  let cursor = 0
  while (cursor < source.length) {
    const index = lower.indexOf(needle, cursor)
    if (index < 0) {
      segments.push({ text: source.slice(cursor), hit: false })
      break
    }
    if (index > cursor) segments.push({ text: source.slice(cursor, index), hit: false })
    segments.push({ text: source.slice(index, index + needle.length), hit: true })
    cursor = index + needle.length
  }
  return segments
}
