/**
 * 4C：纯文本行级 diff（LCS）——版本历史抽屉里对比「选中版本 vs 当前编辑内容」。
 * 输出顺序保持两侧原始行序：先删除行（旧有新无），后新增行（新有旧无）。
 */
export interface DiffLine {
  type: 'same' | 'add' | 'del';
  text: string;
}

const MAX_LINES = 2000;

export function diffLines(oldText: string, newText: string): DiffLine[] {
  const oldLines = oldText.split('\n').slice(0, MAX_LINES);
  const newLines = newText.split('\n').slice(0, MAX_LINES);
  const m = oldLines.length;
  const n = newLines.length;

  // LCS 长度表（行数受 MAX_LINES 限制，m*n 最大 4M 个 Int32，可接受）
  const width = n + 1;
  const table = new Int32Array((m + 1) * width);
  for (let i = m - 1; i >= 0; i--) {
    for (let j = n - 1; j >= 0; j--) {
      table[i * width + j] =
        oldLines[i] === newLines[j]
          ? table[(i + 1) * width + j + 1] + 1
          : Math.max(table[(i + 1) * width + j], table[i * width + j + 1]);
    }
  }

  const result: DiffLine[] = [];
  let i = 0;
  let j = 0;
  while (i < m && j < n) {
    if (oldLines[i] === newLines[j]) {
      result.push({ type: 'same', text: oldLines[i] });
      i++;
      j++;
    } else if (table[(i + 1) * width + j] >= table[i * width + j + 1]) {
      result.push({ type: 'del', text: oldLines[i] });
      i++;
    } else {
      result.push({ type: 'add', text: newLines[j] });
      j++;
    }
  }
  while (i < m) result.push({ type: 'del', text: oldLines[i++] });
  while (j < n) result.push({ type: 'add', text: newLines[j++] });
  return result;
}
