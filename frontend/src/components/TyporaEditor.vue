<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { EditorContent, useEditor } from '@tiptap/vue-3'
import StarterKit from '@tiptap/starter-kit'
import { Markdown } from '@tiptap/markdown'
import { Placeholder } from '@tiptap/extensions'
import { TaskItem, TaskList } from '@tiptap/extension-list'
import { TableKit } from '@tiptap/extension-table'

const props = defineProps<{ modelValue: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const sourceMode = ref(false)
const source = ref(props.modelValue)
const syncing = ref(false)

const editor = useEditor({
  content: props.modelValue,
  contentType: 'markdown',
  extensions: [
    StarterKit,
    Markdown,
    Placeholder.configure({ placeholder: '开始写作，输入 Markdown 也会自然排版…' }),
    TaskList,
    TaskItem.configure({ nested: true }),
    TableKit,
  ],
  editorProps: { attributes: { class: 'typora-prose', spellcheck: 'true' } },
  onUpdate: ({ editor: current }) => {
    if (!syncing.value) emit('update:modelValue', current.getMarkdown())
  },
})

watch(() => props.modelValue, (value) => {
  if (sourceMode.value) { if (value !== source.value) source.value = value; return }
  if (!editor.value || value === editor.value.getMarkdown()) return
  syncing.value = true
  editor.value.commands.setContent(value, { contentType: 'markdown' })
  syncing.value = false
})

const can = computed(() => editor.value)

function toggleSource() {
  if (!editor.value) return
  if (!sourceMode.value) source.value = editor.value.getMarkdown()
  else editor.value.commands.setContent(source.value, { contentType: 'markdown' })
  sourceMode.value = !sourceMode.value
}

function updateSource(value: string) {
  source.value = value
  emit('update:modelValue', value)
}

function setLink() {
  if (!editor.value) return
  const current = editor.value.getAttributes('link').href as string | undefined
  const href = window.prompt('输入链接地址', current || 'https://')
  if (href === null) return
  if (!href.trim()) editor.value.chain().focus().unsetLink().run()
  else editor.value.chain().focus().extendMarkRange('link').setLink({ href: href.trim() }).run()
}

defineExpose({ toggleSource })
onBeforeUnmount(() => editor.value?.destroy())
</script>

<template>
  <div class="typora-editor">
    <div class="typora-toolbar" role="toolbar" aria-label="Markdown 格式工具栏">
      <div class="tool-group">
        <button :class="{ active: editor?.isActive('heading', { level: 1 }) }" title="一级标题" @click="editor?.chain().focus().toggleHeading({ level: 1 }).run()">H1</button>
        <button :class="{ active: editor?.isActive('heading', { level: 2 }) }" title="二级标题" @click="editor?.chain().focus().toggleHeading({ level: 2 }).run()">H2</button>
        <button :class="{ active: editor?.isActive('heading', { level: 3 }) }" title="三级标题" @click="editor?.chain().focus().toggleHeading({ level: 3 }).run()">H3</button>
      </div>
      <div class="tool-group">
        <button :class="{ active: editor?.isActive('bold') }" title="粗体 Ctrl+B" @click="editor?.chain().focus().toggleBold().run()"><b>B</b></button>
        <button :class="{ active: editor?.isActive('italic') }" title="斜体 Ctrl+I" @click="editor?.chain().focus().toggleItalic().run()"><i>I</i></button>
        <button :class="{ active: editor?.isActive('strike') }" title="删除线" @click="editor?.chain().focus().toggleStrike().run()"><s>S</s></button>
        <button :class="{ active: editor?.isActive('code') }" title="行内代码" @click="editor?.chain().focus().toggleCode().run()">&lt;/&gt;</button>
      </div>
      <div class="tool-group">
        <button title="无序列表" @click="editor?.chain().focus().toggleBulletList().run()">• 列表</button>
        <button title="有序列表" @click="editor?.chain().focus().toggleOrderedList().run()">1. 列表</button>
        <button title="任务列表" @click="editor?.chain().focus().toggleTaskList().run()">☑ 任务</button>
        <button title="引用" @click="editor?.chain().focus().toggleBlockquote().run()">❝</button>
        <button title="代码块" @click="editor?.chain().focus().toggleCodeBlock().run()">{ }</button>
      </div>
      <div class="tool-group">
        <button title="链接" @click="setLink">↗ 链接</button>
        <button title="插入表格" @click="editor?.chain().focus().insertTable({ rows: 3, cols: 3, withHeaderRow: true }).run()">▦ 表格</button>
        <button title="分隔线" @click="editor?.chain().focus().setHorizontalRule().run()">—</button>
      </div>
      <div class="tool-group tool-history">
        <button :disabled="!can?.can().undo()" title="撤销" @click="editor?.chain().focus().undo().run()">↶</button>
        <button :disabled="!can?.can().redo()" title="重做" @click="editor?.chain().focus().redo().run()">↷</button>
      </div>
      <button class="source-toggle" :class="{ active: sourceMode }" title="Markdown 源码模式 Ctrl+Shift+M" @click="toggleSource">{{ sourceMode ? '所见即所得' : '&lt;/&gt; 源码' }}</button>
    </div>
    <textarea v-if="sourceMode" class="markdown-source" :value="source" spellcheck="false" @input="updateSource(($event.target as HTMLTextAreaElement).value)" />
    <EditorContent v-else :editor="editor" />
  </div>
</template>
