import { describe, it, expect, afterEach } from 'vitest';
import { Editor } from '@tiptap/core';
import StarterKit from '@tiptap/starter-kit';
import { Markdown } from '@tiptap/markdown';
import CodeBlockLowlight from '@tiptap/extension-code-block-lowlight';
import { CODE_LANGUAGES, lowlight } from '../utils/codeHighlight';

/** L-14：语言标记的保存-重开往返（markdown 围栏 ↔ codeBlock.language 属性）与语言注册面。 */

let editor: Editor | null = null;

function createEditor(content: string) {
  editor = new Editor({
    extensions: [
      StarterKit.configure({ codeBlock: false }),
      CodeBlockLowlight.configure({ lowlight }),
      Markdown,
    ],
    content,
    contentType: 'markdown',
  });
  return editor;
}

afterEach(() => {
  editor?.destroy();
  editor = null;
});

describe('L-14 代码块语言标记往返', () => {
  it('markdown 围栏语言解析进 codeBlock.language 并原样导出', () => {
    const e = createEditor('```typescript\nconst answer: number = 42\n```');
    const block = e.getJSON().content?.[0];
    expect(block?.type).toBe('codeBlock');
    expect(block?.attrs?.language).toBe('typescript');
    expect(e.getMarkdown()).toContain('```typescript');
  });

  it('改写 language 属性后导出围栏标记同步变化（编辑器语言选择器的落库路径）', () => {
    const e = createEditor('```typescript\nconst a = 1\n```');
    e.commands.selectAll();
    e.commands.updateAttributes('codeBlock', { language: 'java' });
    expect(e.getMarkdown()).toContain('```java');
  });

  it('无语言围栏保持纯文本（language 为空）', () => {
    const e = createEditor('```\nplain text\n```');
    const block = e.getJSON().content?.[0];
    expect(block?.type).toBe('codeBlock');
    expect(block?.attrs?.language ?? null).toBeNull();
  });

  it('选择器语言集全部已注册（含别名映射）', () => {
    for (const lang of CODE_LANGUAGES) {
      if (!lang.value) continue;
      expect(lowlight.registered(lang.value), `${lang.value} 应已注册`).toBe(true);
    }
    expect(lowlight.registered('ts')).toBe(true);
    expect(lowlight.registered('sh')).toBe(true);
    expect(lowlight.registered('vue')).toBe(true);
  });
});
