<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { EditorContent, useEditor } from '@tiptap/vue-3'
import StarterKit from '@tiptap/starter-kit'
import { Markdown } from '@tiptap/markdown'
import { TaskItem, TaskList } from '@tiptap/extension-list'
import { TableKit } from '@tiptap/extension-table'
import { fetchPublishedNotes } from '../api/content'
import type { AdminNote } from '../api/admin'

const notes = ref<AdminNote[]>([])
const selectedId = ref<number | null>(null)
const query = ref('')
const loading = ref(true)
const selected = computed(() => notes.value.find(note => note.id === selectedId.value) ?? notes.value[0])
const filtered = computed(() => {
  const needle = query.value.trim().toLowerCase()
  return notes.value.filter(note => !needle || [note.title, note.folder, ...note.tags].join(' ').toLowerCase().includes(needle))
})

const editor = useEditor({
  editable: false,
  content: '',
  contentType: 'markdown',
  extensions: [
    StarterKit,
    Markdown, TaskList, TaskItem.configure({ nested: true }), TableKit,
  ],
  editorProps: { attributes: { class: 'typora-prose public-note-prose' } },
})

watch(selected, note => {
  if (note && editor.value) editor.value.commands.setContent(note.markdownContent, { contentType: 'markdown' })
}, { immediate: true })

onMounted(async () => {
  try { notes.value = await fetchPublishedNotes(); selectedId.value = notes.value[0]?.id ?? null }
  catch { notes.value = [] }
  finally { loading.value = false }
})
onBeforeUnmount(() => editor.value?.destroy())
</script>

<template>
  <section class="notes-page section-wrap">
    <header class="notes-page-head"><div><p class="eyebrow"><span /> LEARNING NOTES / 学习笔记</p><h1>把学到的东西，<br><em>变成可以回来的路。</em></h1></div><p>这里不是答案仓库，而是一张持续生长的认知地图。公开笔记会在保存后自动出现在此处。</p></header>
    <div class="public-notes-layout">
      <aside>
        <label class="search-field"><span>检索笔记</span><input v-model="query" type="search" placeholder="标题、目录或标签…"></label>
        <p>{{ filtered.length.toString().padStart(2, '0') }} PUBLIC NOTES</p>
        <div class="public-note-list">
          <button v-for="note in filtered" :key="note.id" :class="{ active: selected?.id === note.id }" @click="selectedId = note.id"><small>{{ note.folder }} · {{ new Date(note.updatedAt).toLocaleDateString('zh-CN') }}</small><strong>{{ note.title }}</strong><span>{{ note.wordCount }} 字 · {{ note.tags.slice(0, 2).join(' / ') || '学习记录' }}</span></button>
        </div>
      </aside>
      <article v-if="selected" class="public-note-paper"><header><p>{{ selected.folder }} / {{ selected.status === 'PUBLISHED' ? '公开笔记' : '' }}</p><h2>{{ selected.title }}</h2><div><span v-for="tag in selected.tags" :key="tag"># {{ tag }}</span></div></header><EditorContent :editor="editor" /></article>
      <div v-else class="public-notes-empty"><span>✦</span><h2>{{ loading ? '正在翻阅笔记…' : '公开笔记正在整理中' }}</h2><p>管理员将笔记状态设为“公开”后，会在这里出现。</p></div>
    </div>
  </section>
</template>
