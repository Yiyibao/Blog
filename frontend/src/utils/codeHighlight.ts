import { createLowlight } from 'lowlight';
import bash from 'highlight.js/lib/languages/bash';
import css from 'highlight.js/lib/languages/css';
import java from 'highlight.js/lib/languages/java';
import javascript from 'highlight.js/lib/languages/javascript';
import json from 'highlight.js/lib/languages/json';
import markdown from 'highlight.js/lib/languages/markdown';
import python from 'highlight.js/lib/languages/python';
import sql from 'highlight.js/lib/languages/sql';
import typescript from 'highlight.js/lib/languages/typescript';
import xml from 'highlight.js/lib/languages/xml';
import yaml from 'highlight.js/lib/languages/yaml';

/**
 * L-14：代码块语法高亮内核——按需注册常用语言集控制体积（highlight.js 全量 ~1MB，这里只挑 11 门）。
 * 仅被 TyporaEditor 与 PublicNotes 引用，二者都在懒加载路由 chunk 内，公开首屏不背这份体积（P1-7）。
 */
export const lowlight = createLowlight();

lowlight.register({ bash, css, java, javascript, json, markdown, python, sql, typescript, xml, yaml });
lowlight.registerAlias({
  bash: ['sh', 'shell', 'zsh'],
  javascript: ['js', 'jsx'],
  typescript: ['ts', 'tsx'],
  python: ['py'],
  xml: ['html', 'vue'],
  markdown: ['md'],
  yaml: ['yml'],
});

/** 编辑器语言选择器选项——value 即落进 markdown 围栏的语言标记。 */
export const CODE_LANGUAGES: ReadonlyArray<{ value: string; label: string }> = [
  { value: '', label: '纯文本' },
  { value: 'javascript', label: 'JavaScript' },
  { value: 'typescript', label: 'TypeScript' },
  { value: 'java', label: 'Java' },
  { value: 'python', label: 'Python' },
  { value: 'sql', label: 'SQL' },
  { value: 'bash', label: 'Bash / Shell' },
  { value: 'json', label: 'JSON' },
  { value: 'html', label: 'HTML / XML' },
  { value: 'css', label: 'CSS' },
  { value: 'yaml', label: 'YAML' },
  { value: 'markdown', label: 'Markdown' },
];
