import { describe, it, expect } from 'vitest';
import { splitHighlight } from '../utils/searchHighlight';

describe('splitHighlight', () => {
  it('marks case-insensitive hits and keeps original casing', () => {
    expect(splitHighlight('Vue 深入 vUe 浅出', 'vue')).toEqual([
      { text: 'Vue', hit: true },
      { text: ' 深入 ', hit: false },
      { text: 'vUe', hit: true },
      { text: ' 浅出', hit: false },
    ]);
  });

  it('returns whole text unhit when query empty or no match', () => {
    expect(splitHighlight('中文标题', '')).toEqual([{ text: '中文标题', hit: false }]);
    expect(splitHighlight('中文标题', '独角鲸')).toEqual([{ text: '中文标题', hit: false }]);
  });

  it('handles adjacent and full-string hits', () => {
    expect(splitHighlight('鲸鲸', '鲸')).toEqual([
      { text: '鲸', hit: true },
      { text: '鲸', hit: true },
    ]);
    expect(splitHighlight('整串命中', '整串命中')).toEqual([{ text: '整串命中', hit: true }]);
  });

  it('treats html in source as plain text segments', () => {
    const segments = splitHighlight('<img src=x onerror=alert(1)> 标题', '标题');
    expect(segments.map((s) => s.text).join('')).toBe('<img src=x onerror=alert(1)> 标题');
    expect(segments.at(-1)).toEqual({ text: '标题', hit: true });
  });
});
