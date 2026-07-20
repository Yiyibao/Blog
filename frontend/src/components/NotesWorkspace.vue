<script setup lang="ts">
import axios from 'axios'
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import TyporaEditor from './TyporaEditor.vue'
import {
  createNote, deleteNote, exportNote, fetchNotes, importNote, updateNote,
  type AdminNote, type NotePayload, type NoteStatus,
} from '../api/admin'

const router = useRouter()
const notes = ref<AdminNote[]>([])
const selectedId = ref<number | null>(null)
const query = ref('')
const statusFilter = ref<'ALL' | NoteStatus>('ALL')
const loading = ref(true)
const saveState = ref<'saved' | 'dirty' | 'saving' | 'error'>('saved')
const error = ref('')
const focusMode = ref(false)
const fileInput = ref<HTMLInputElement>()
const form = reactive<NotePayload>({ title: '', markdownContent: '', folder: '未分类', status: 'DRAFT', tags: [], version: 0 })
const tagText = ref('')
let saveTimer: number | undefined
let applying = false

const filteredNotes = computed(() => {
  const needle = query.value.trim().toLowerCase()
  return notes.value.filter(note => (statusFilter.value === 'ALL' || note.status === statusFilter.value)
    && (!needle || [note.title, note.folder, ...note.tags].join(' ').toLowerCase().includes(needle)))
})
const selected = computed(() => notes.value.find(note => note.id === selectedId.value))
const outline = computed(() => form.markdownContent.split('\n').flatMap((line, index) => {
  const match = /^(#{1,3})\s+(.+)$/.exec(line)
  return match ? [{ level: match[1].length, title: match[2].replace(/[*_`]/g, ''), index }] : []
}))
const charCount = computed(() => form.markdownContent.replace(/\s/g, '').length)
const readMinutes = computed(() => Math.max(1, Math.ceil(charCount.value / 500)))
const saveLabel = computed(() => ({ saved: '已保存', dirty: '等待保存', saving: '正在保存…', error: '保存失败' })[saveState.value])

function payload(): NotePayload {
  return { ...form, title: form.title.trim() || '未命名笔记', tags: tagText.value.split(/[,，]/).map(tag => tag.trim()).filter(Boolean) }
}

function applyNote(note: AdminNote) {
  applying = true
  selectedId.value = note.id
  Object.assign(form, { title: note.title, markdownContent: note.markdownContent, folder: note.folder, status: note.status, tags: note.tags, version: note.version })
  tagText.value = note.tags.join(', ')
  saveState.value = 'saved'
  void nextTick(() => { applying = false })
}

async function load() {
  if (!sessionStorage.getItem('yubai-admin-token')) return void router.replace('/admin/login')
  try {
    notes.value = await fetchNotes()
    if (notes.value[0]) applyNote(notes.value[0])
  } catch (cause) { handleError(cause, '无法读取学习笔记，请确认后端服务正在运行。') }
  finally { loading.value = false }
}

function handleError(cause: unknown, fallback: string) {
  if (axios.isAxiosError(cause) && cause.response?.status === 401) return void router.replace('/admin/login')
  error.value = axios.isAxiosError(cause) && cause.response?.status === 409 ? '云端版本已变化，请重新选择笔记后继续编辑。' : fallback
  saveState.value = 'error'
}

async function newNote() {
  clearTimeout(saveTimer)
  try {
    const note = await createNote({ title: '未命名笔记', markdownContent: '# 未命名笔记\n\n从这里开始记录。', folder: '未分类', status: 'DRAFT', tags: [], version: 0 })
    notes.value.unshift(note); applyNote(note)
  } catch (cause) { handleError(cause, '新建笔记失败。') }
}

async function saveNow() {
  if (!selectedId.value || saveState.value === 'saving') return
  clearTimeout(saveTimer); saveState.value = 'saving'; error.value = ''
  try {
    const saved = await updateNote(selectedId.value, payload())
    const index = notes.value.findIndex(note => note.id === saved.id)
    if (index >= 0) notes.value[index] = saved
    form.version = saved.version
    saveState.value = 'saved'
  } catch (cause) { handleError(cause, '保存失败，请稍后重试。') }
}

function scheduleSave() {
  if (applying || !selectedId.value) return
  saveState.value = 'dirty'
  clearTimeout(saveTimer)
  saveTimer = window.setTimeout(saveNow, 1000)
}

async function onImport(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  try { const note = await importNote(file); notes.value.unshift(note); applyNote(note) }
  catch (cause) { handleError(cause, '导入失败，仅支持 2MB 内的 Markdown 或文本文件。') }
  finally { input.value = '' }
}

async function removeCurrent() {
  if (!selected.value || !window.confirm(`确认删除“${selected.value.title}”？`)) return
  try {
    await deleteNote(selected.value.id)
    notes.value = notes.value.filter(note => note.id !== selected.value?.id)
    if (notes.value[0]) applyNote(notes.value[0]); else selectedId.value = null
  } catch (cause) { handleError(cause, '删除失败。') }
}

function onShortcut(event: KeyboardEvent) {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') { event.preventDefault(); void saveNow() }
  if ((event.ctrlKey || event.metaKey) && event.shiftKey && event.key.toLowerCase() === 'f') { event.preventDefault(); focusMode.value = !focusMode.value }
}

watch([() => form.title, () => form.markdownContent, () => form.folder, () => form.status, tagText], scheduleSave)
onMounted(() => { void load(); window.addEventListener('keydown', onShortcut) })
onBeforeUnmount(() => { clearTimeout(saveTimer); window.removeEventListener('keydown', onShortcut) })
</script>

<template>
  <section class="notes-studio" :class="{ 'focus-mode': focusMode }">
    <aside class="notes-library">
      <header><button class="notes-back" @click="router.push('/admin')">← 工作台</button><p>LEARNING ARCHIVE</p><h1>学习笔记</h1></header>
      <div class="notes-actions"><button class="new-note" @click="newNote">＋ 新建笔记</button><button @click="fileInput?.click()">⇧ 导入</button><input ref="fileInput" type="file" accept=".md,.markdown,.txt,text/markdown,text/plain" hidden @change="onImport"></div>
      <label class="notes-search">⌕<input v-model="query" type="search" placeholder="搜索标题、标签、目录"></label>
      <div class="notes-filters"><button v-for="item in ['ALL','DRAFT','PUBLISHED','ARCHIVED'] as const" :key="item" :class="{ active: statusFilter === item }" @click="statusFilter = item">{{ { ALL: '全部', DRAFT: '草稿', PUBLISHED: '公开', ARCHIVED: '归档' }[item] }}</button></div>
      <div v-if="loading" class="notes-empty">正在整理书架…</div>
      <div v-else-if="!filteredNotes.length" class="notes-empty">暂无匹配笔记</div>
      <div class="notes-list">
        <button v-for="note in filteredNotes" :key="note.id" :class="{ active: selectedId === note.id }" @click="applyNote(note)">
          <span>{{ note.folder }} · {{ new Date(note.updatedAt).toLocaleDateString('zh-CN') }}</span><strong>{{ note.title }}</strong><small>{{ note.wordCount }} 字 · {{ note.tags.slice(0,2).join(' / ') || '无标签' }}</small>
        </button>
      </div>
    </aside>

    <main class="note-desk">
      <div v-if="!selectedId" class="note-welcome"><span>✦</span><h2>留下一条学习轨迹</h2><p>新建一篇笔记，或从本地导入 Markdown。</p><button class="button primary" @click="newNote">新建第一篇笔记</button></div>
      <template v-else>
        <header class="note-topbar">
          <div class="note-meta-fields"><input v-model="form.folder" aria-label="目录" placeholder="目录"><span>/</span><select v-model="form.status" aria-label="状态"><option value="DRAFT">草稿</option><option value="PUBLISHED">公开</option><option value="ARCHIVED">归档</option></select></div>
          <div class="note-save-state" :class="saveState"><i />{{ saveLabel }}</div>
          <div class="note-top-actions"><button title="专注模式 Ctrl+Shift+F" @click="focusMode = !focusMode">{{ focusMode ? '退出专注' : '专注' }}</button><button @click="selected && exportNote(selected)">导出 .md</button><button class="danger" @click="removeCurrent">删除</button><button class="save-note" @click="saveNow">保存</button></div>
        </header>
        <div class="manuscript">
          <input v-model="form.title" class="note-title" maxlength="200" placeholder="未命名笔记" aria-label="笔记标题">
          <input v-model="tagText" class="note-tags" placeholder="添加标签，用逗号分隔" aria-label="标签">
          <TyporaEditor v-model="form.markdownContent" />
        </div>
        <footer class="note-statusbar"><span>Markdown</span><span>{{ charCount }} 字符</span><span>约 {{ readMinutes }} 分钟阅读</span><span>Ctrl + S 保存</span></footer>
      </template>
      <p v-if="error" class="note-error">{{ error }}</p>
    </main>

    <aside class="note-outline">
      <p>OUTLINE / 大纲</p>
      <div v-if="outline.length"><button v-for="item in outline" :key="`${item.index}-${item.title}`" :style="{ paddingLeft: `${(item.level - 1) * 14}px` }">{{ item.title }}</button></div>
      <small v-else>添加一至三级标题后，文章结构会显示在这里。</small>
      <div class="outline-note"><b>写作提示</b><p>输入 <code>#</code> 创建标题，<code>- [ ]</code> 创建任务，三个反引号创建代码块。</p></div>
    </aside>
  </section>
</template>
