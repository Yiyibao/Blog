<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { EditorContent, useEditor } from '@tiptap/vue-3'
import type { Editor } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import { Markdown } from '@tiptap/markdown'
import { Placeholder } from '@tiptap/extensions'
import { TaskItem, TaskList } from '@tiptap/extension-list'
import { TableKit } from '@tiptap/extension-table'
import Image from '@tiptap/extension-image'
import FileHandler from '@tiptap/extension-file-handler'
import { Mathematics } from '@tiptap/extension-mathematics'
import 'katex/dist/katex.min.css'

const props = defineProps<{ modelValue: string; uploadImage: (file: File) => Promise<string> }>()
const emit = defineEmits<{ 'update:modelValue': [value: string]; 'upload-error': [message: string] }>()
const sourceMode = ref(false)
const source = ref(props.modelValue)
const syncing = ref(false)
const uploading = ref(false)
const imageInput = ref<HTMLInputElement>()
const slashOpen = ref(false)
const slashPos = ref(0)

async function insertFiles(current: Editor, files: File[], pos?: number) {
  const images = files.filter(file => ['image/png', 'image/jpeg', 'image/webp', 'image/gif'].includes(file.type))
  if (!images.length) return
  uploading.value = true
  try {
    for (const file of images) {
      const url = await props.uploadImage(file)
      const chain = current.chain().focus()
      if (pos !== undefined) chain.setTextSelection(pos)
      chain.setImage({ src: url, alt: file.name, title: file.name }).run()
    }
  } catch {
    emit('upload-error', '图片上传失败，请使用 8MB 内的 PNG、JPEG、WebP 或 GIF。')
  } finally { uploading.value = false }
}

function editMath(kind: 'inline' | 'block', latex = '') {
  const value = window.prompt(kind === 'inline' ? '输入行内 LaTeX 公式' : '输入块级 LaTeX 公式', latex || 'E = mc^2')
  if (!value?.trim() || !editor.value) return
  if (kind === 'inline') editor.value.chain().focus().insertInlineMath({ latex: value.trim() }).run()
  else editor.value.chain().focus().insertBlockMath({ latex: value.trim() }).run()
}

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
    Image.configure({ resize: { enabled: true, directions: ['bottom-right'], minWidth: 120, minHeight: 80, alwaysPreserveAspectRatio: true } }),
    Mathematics.configure({
      inlineOptions: { onClick: node => editMath('inline', String(node.attrs.latex || '')) },
      blockOptions: { onClick: node => editMath('block', String(node.attrs.latex || '')) },
      katexOptions: { throwOnError: false },
    }),
    FileHandler.configure({
      allowedMimeTypes: ['image/png', 'image/jpeg', 'image/webp', 'image/gif'],
      consumePasteEvent: true,
      onPaste: (current, files) => { void insertFiles(current, files) },
      onDrop: (current, files, pos) => { void insertFiles(current, files, pos) },
    }),
  ],
  editorProps: {
    attributes: { class: 'typora-prose', spellcheck: 'true' },
    handleKeyDown: (view, event) => {
      if (event.key === '/' && view.state.selection.empty) {
        const parent = view.state.selection.$from.parent
        if (parent.isTextblock && parent.textContent.length === 0) {
          slashPos.value = view.state.selection.from
          window.setTimeout(() => { slashOpen.value = true }, 0)
        }
      }
      if (event.key === 'Escape') slashOpen.value = false
      return false
    },
  },
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

function runSlash(command: 'text' | 'h1' | 'h2' | 'task' | 'quote' | 'code' | 'table' | 'image' | 'math') {
  if (!editor.value) return
  const to = editor.value.state.selection.from
  const chain = editor.value.chain().focus().deleteRange({ from: slashPos.value, to })
  if (command === 'text') chain.setParagraph().run()
  if (command === 'h1') chain.setHeading({ level: 1 }).run()
  if (command === 'h2') chain.setHeading({ level: 2 }).run()
  if (command === 'task') chain.toggleTaskList().run()
  if (command === 'quote') chain.toggleBlockquote().run()
  if (command === 'code') chain.toggleCodeBlock().run()
  if (command === 'table') chain.insertTable({ rows: 3, cols: 3, withHeaderRow: true }).run()
  if (command === 'image') { chain.run(); imageInput.value?.click() }
  if (command === 'math') { chain.run(); editMath('block') }
  slashOpen.value = false
}

function chooseImages(event: Event) {
  const input = event.target as HTMLInputElement
  if (input.files && editor.value) void insertFiles(editor.value, [...input.files])
  input.value = ''
}

const charCount = computed(() => {
  const text = source.value || ''
  return text.replace(/\s+/g, '').length
})

const wordCount = computed(() => {
  const text = source.value || ''
  const cn = (text.match(/[\u4e00-\u9fa5]/g) || []).length
  const en = (text.replace(/[\u4e00-\u9fa5]/g, ' ').match(/[a-zA-Z0-9]+/g) || []).length
  return cn + en
})

const readMinutes = computed(() => {
  return Math.max(1, Math.ceil(wordCount.value / 300))
})

defineExpose({ toggleSource })
function onGlobalShortcut(event: KeyboardEvent) {
  if ((event.ctrlKey || event.metaKey) && event.shiftKey && event.key.toLowerCase() === 'm') {
    event.preventDefault(); toggleSource()
  }
}
onMounted(() => window.addEventListener('keydown', onGlobalShortcut))
onBeforeUnmount(() => { window.removeEventListener('keydown', onGlobalShortcut); editor.value?.destroy() })
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
        <button title="上传图片，也可直接粘贴或拖入" @click="imageInput?.click()">▧ 图片</button>
        <button title="插入数学公式" @click="editMath('block')">∑ 公式</button>
        <button title="分隔线" @click="editor?.chain().focus().setHorizontalRule().run()">—</button>
      </div>
      <div class="tool-group tool-history">
        <button :disabled="!can?.can().undo()" title="撤销" @click="editor?.chain().focus().undo().run()">↶</button>
        <button :disabled="!can?.can().redo()" title="重做" @click="editor?.chain().focus().redo().run()">↷</button>
      </div>
      <button class="source-toggle" :class="{ active: sourceMode }" title="Markdown 源码模式 Ctrl+Shift+M" @click="toggleSource">{{ sourceMode ? '所见即所得' : '&lt;/&gt; 源码' }}</button>
      <input ref="imageInput" hidden type="file" multiple accept="image/png,image/jpeg,image/webp,image/gif" @change="chooseImages">
    </div>
    <div v-if="uploading" class="editor-uploading"><i /> 正在上传并插入图片…</div>
    <div v-if="slashOpen && !sourceMode" class="slash-menu">
      <header><span>快速插入</span><kbd>ESC 关闭</kbd></header>
      <button @click="runSlash('text')"><b>¶</b><span>正文<small>普通文本段落</small></span></button>
      <button @click="runSlash('h1')"><b>H1</b><span>一级标题<small>章节主标题</small></span></button>
      <button @click="runSlash('h2')"><b>H2</b><span>二级标题<small>章节小标题</small></span></button>
      <button @click="runSlash('task')"><b>☑</b><span>任务列表<small>可勾选的待办事项</small></span></button>
      <button @click="runSlash('quote')"><b>❝</b><span>引用<small>突出一段引文</small></span></button>
      <button @click="runSlash('code')"><b>{ }</b><span>代码块<small>多行等宽代码</small></span></button>
      <button @click="runSlash('table')"><b>▦</b><span>表格<small>3 × 3 数据表</small></span></button>
      <button @click="runSlash('image')"><b>▧</b><span>图片<small>上传本地图片</small></span></button>
      <button @click="runSlash('math')"><b>∑</b><span>数学公式<small>KaTeX / LaTeX</small></span></button>
    </div>
    <textarea v-if="sourceMode" class="markdown-source" :value="source" spellcheck="false" @input="updateSource(($event.target as HTMLTextAreaElement).value)" />
    <EditorContent v-else :editor="editor" />
    <div class="editor-status-bar">
      <div class="editor-status-info">
        <span>字数：{{ wordCount }} 字</span>
        <span>字符：{{ charCount }}</span>
        <span>预计阅读：{{ readMinutes }} 分钟</span>
      </div>
      <div class="editor-status-sync">
        <span>● 草稿已实时同步</span>
      </div>
    </div>
  </div>
</template>
