import { describe, it, expect } from 'vitest';
import { diffLines } from '../utils/textDiff';

describe('diffLines', () => {
  it('marks identical texts as all-same', () => {
    const result = diffLines('a\nb\nc', 'a\nb\nc');
    expect(result).toHaveLength(3);
    expect(result.every((line) => line.type === 'same')).toBe(true);
  });

  it('detects added and removed lines', () => {
    const result = diffLines('第一行\n第二行\n第三行', '第一行\n改写的第二行\n第三行\n新增第四行');
    expect(result).toEqual([
      { type: 'same', text: '第一行' },
      { type: 'del', text: '第二行' },
      { type: 'add', text: '改写的第二行' },
      { type: 'same', text: '第三行' },
      { type: 'add', text: '新增第四行' },
    ]);
  });

  it('handles empty old side as pure additions', () => {
    const result = diffLines('', 'a\nb');
    // 空字符串 split 出一行空串，与新文本无公共行时全删全增
    expect(result.filter((line) => line.type === 'add').map((line) => line.text)).toEqual(['a', 'b']);
  });

  it('keeps original ordering with interleaved changes', () => {
    const result = diffLines('a\nx\nb', 'a\nb\ny');
    expect(result.map((line) => `${line.type}:${line.text}`)).toEqual(['same:a', 'del:x', 'same:b', 'add:y']);
  });
});
