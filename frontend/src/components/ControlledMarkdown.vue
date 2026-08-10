<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { EditorContent, useEditor } from '@tiptap/vue-3';
import StarterKit from '@tiptap/starter-kit';
import { Markdown } from '@tiptap/markdown';
import { TaskItem, TaskList } from '@tiptap/extension-list';
import { TableKit } from '@tiptap/extension-table';
import Image from '@tiptap/extension-image';
import { Mathematics } from '@tiptap/extension-mathematics';
import CodeBlockLowlight from '@tiptap/extension-code-block-lowlight';
import { lowlight } from '../utils/codeHighlight';
import 'katex/dist/katex.min.css';
import '../styles/code-highlight.css';

/**
 * 3A-4：文章 MARKDOWN 格式的受控渲染——与公开笔记同一条 Tiptap 只读管线
 * （schema 白名单即安全边界，原始 HTML 不进渲染树；DOMPurify 留给 HTML 存量路径兜底）。
 * 3B：渲染完成后按出现顺序给 h2 写 id（h-0、h-1…），与 extractMarkdownOutline 的目录对齐。
 */
const props = defineProps<{ markdown: string }>();
const emit = defineEmits<{ rendered: [] }>();

const host = ref<HTMLElement | null>(null);

const editor = useEditor({
  editable: false,
  content: props.markdown,
  contentType: 'markdown',
  extensions: [
    StarterKit.configure({ codeBlock: false }),
    CodeBlockLowlight.configure({ lowlight }),
    Markdown,
    TaskList,
    TaskItem.configure({ nested: true }),
    TableKit,
    Image,
    Mathematics.configure({ katexOptions: { throwOnError: false } }),
  ],
  editorProps: { attributes: { class: 'typora-prose article-markdown-prose' } },
});

// useEditor 在组件挂载后才实例化，EditorContent 再下一拍才产出 DOM——
// 以 editor ref 就绪为信号（外加 mounted 兜底）等两拍后回填 id
watch(editor, (instance) => {
  if (instance) void applyHeadingIds();
});
onMounted(() => void applyHeadingIds());

watch(
  () => props.markdown,
  (value) => {
    if (!editor.value) return;
    editor.value.commands.setContent(value ?? '', { contentType: 'markdown' });
    void applyHeadingIds();
  },
);

async function applyHeadingIds() {
  await nextTick();
  await nextTick();
  const headings = host.value?.querySelectorAll('h2') ?? [];
  headings.forEach((heading, index) => heading.setAttribute('id', `h-${index}`));
  const images = host.value?.querySelectorAll('img') ?? [];
  images.forEach((image) => {
    image.setAttribute('loading', 'lazy');
    image.setAttribute('decoding', 'async');
  });
  emit('rendered');
}

onBeforeUnmount(() => editor.value?.destroy());
</script>

<template>
  <div ref="host" class="controlled-markdown">
    <EditorContent :editor="editor" />
  </div>
</template>
