<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import axios from 'axios'
import AdminSidebar from '../components/AdminSidebar.vue'
import {
  clearAdminSession, hasValidAdminSession, fetchAdminPosts,
  fetchAdminSeriesList, fetchAdminSeries, createSeries, updateSeries, setSeriesEntries, deleteSeries,
  type AdminSeries, type AdminSeriesEntry, type SeriesPayload,
} from '../api/admin'

/**
 * 4B：合集管理——建合集 → 挂文章 → 拖拽排序（HTML5 DnD，整表提交）。
 * 乐观锁 version 随行：409 时提示并自动刷新最新数据。
 */
const router = useRouter()
const seriesList = ref<AdminSeries[]>([])
const current = ref<AdminSeries | null>(null)
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const notice = ref('')

const form = reactive<SeriesPayload>({ name: '', slug: '', description: '', coverImage: null, status: 'DRAFT' })
const creating = ref(false)

/** 成员工作副本（拖拽/增删只改本地，「保存排序」时整表提交）。 */
const workingEntries = ref<AdminSeriesEntry[]>([])
const entriesDirty = ref(false)
const dragIndex = ref<number | null>(null)

const postOptions = ref<{ id: number; title: string; status: string }[]>([])
const selectedPostId = ref<number | ''>('')

const availablePosts = computed(() =>
  postOptions.value.filter((post) => !workingEntries.value.some((entry) => entry.postId === post.id)))

function handleAuthError(cause: unknown) {
  if (axios.isAxiosError(cause) && cause.response?.status === 401) {
    clearAdminSession()
    void router.replace('/admin/login')
    return true
  }
  return false
}

/** 409：合集被并发更新——提示并刷新到最新版本。 */
async function handleConflict(cause: unknown) {
  if (axios.isAxiosError(cause) && cause.response?.status === 409) {
    error.value = '合集已在其他位置更新，已刷新最新数据，请基于最新内容重试。'
    if (current.value) await select(current.value.id)
    return true
  }
  return false
}

async function load() {
  if (!hasValidAdminSession()) {
    void router.replace('/admin/login')
    return
  }
  loading.value = true
  error.value = ''
  try {
    seriesList.value = await fetchAdminSeriesList()
    if (current.value) {
      const still = seriesList.value.find((s) => s.id === current.value?.id)
      if (still) applyCurrent(still)
      else current.value = null
    }
  } catch (cause) {
    if (!handleAuthError(cause)) error.value = '读取合集失败，请确认后端服务正在运行。'
  } finally {
    loading.value = false
  }
}

function applyCurrent(series: AdminSeries) {
  current.value = series
  Object.assign(form, {
    name: series.name, slug: series.slug, description: series.description,
    coverImage: series.coverImage, status: series.status,
  })
  workingEntries.value = series.entries.map((entry) => ({ ...entry }))
  entriesDirty.value = false
}

async function select(id: number) {
  try {
    applyCurrent(await fetchAdminSeries(id))
    creating.value = false
    notice.value = ''
  } catch (cause) {
    if (!handleAuthError(cause)) error.value = '读取合集详情失败。'
  }
}

function startCreate() {
  creating.value = true
  current.value = null
  workingEntries.value = []
  entriesDirty.value = false
  notice.value = ''
  Object.assign(form, { name: '', slug: '', description: '', coverImage: null, status: 'DRAFT' })
}

async function saveSeries() {
  saving.value = true
  error.value = ''
  notice.value = ''
  const payload: SeriesPayload = { ...form, coverImage: form.coverImage || null }
  try {
    if (creating.value) {
      const created = await createSeries(payload)
      creating.value = false
      applyCurrent(created)
      notice.value = '合集已创建，可开始挂文章。'
    } else if (current.value) {
      applyCurrent(await updateSeries(current.value.id, current.value.version, payload))
      notice.value = '合集信息已保存。'
    }
    await load()
  } catch (cause) {
    if (!handleAuthError(cause) && !(await handleConflict(cause))) {
      error.value = axios.isAxiosError(cause) && cause.response?.status === 400
        ? '保存失败：请检查字段（slug 仅允许小写字母/数字/连字符）。'
        : '保存合集失败，请稍后重试。'
    }
  } finally {
    saving.value = false
  }
}

async function removeSeries(series: AdminSeries) {
  if (!window.confirm(`确认删除合集“${series.name}”？成员文章本身不受影响。`)) return
  try {
    await deleteSeries(series.id)
    if (current.value?.id === series.id) current.value = null
    await load()
  } catch (cause) {
    if (!handleAuthError(cause)) error.value = '删除合集失败，请稍后重试。'
  }
}

// ── 成员编排（本地工作副本 + 整表提交）─────────────────────────────────────

function addEntry() {
  const post = postOptions.value.find((p) => p.id === selectedPostId.value)
  if (!post) return
  workingEntries.value.push({
    postId: post.id, slug: '', title: post.title, date: '', chapterTitle: null,
    position: workingEntries.value.length + 1,
  })
  selectedPostId.value = ''
  entriesDirty.value = true
}

function removeEntry(index: number) {
  workingEntries.value.splice(index, 1)
  entriesDirty.value = true
}

function onDragStart(index: number, event: DragEvent) {
  dragIndex.value = index
  event.dataTransfer?.setData('text/plain', String(index))
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move'
}

function onDrop(index: number) {
  if (dragIndex.value === null || dragIndex.value === index) {
    dragIndex.value = null
    return
  }
  const [moved] = workingEntries.value.splice(dragIndex.value, 1)
  workingEntries.value.splice(index, 0, moved)
  dragIndex.value = null
  entriesDirty.value = true
}

function moveEntry(index: number, delta: number) {
  const target = index + delta
  if (target < 0 || target >= workingEntries.value.length) return
  const [moved] = workingEntries.value.splice(index, 1)
  workingEntries.value.splice(target, 0, moved)
  entriesDirty.value = true
}

async function saveEntries() {
  if (!current.value) return
  saving.value = true
  error.value = ''
  notice.value = ''
  try {
    applyCurrent(await setSeriesEntries(current.value.id, current.value.version,
      workingEntries.value.map((entry) => ({ postId: entry.postId, chapterTitle: entry.chapterTitle || null }))))
    notice.value = '成员与排序已保存。'
    await load()
  } catch (cause) {
    if (!handleAuthError(cause) && !(await handleConflict(cause))) {
      error.value = '保存成员失败，请稍后重试。'
    }
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  await load()
  try {
    const page = await fetchAdminPosts(0, 50)
    postOptions.value = page.items.map((post) => ({
      id: post.id, title: post.title, status: post.status ?? '',
    }))
  } catch {
    /* 文章选项加载失败不阻塞页面，可稍后重进 */
  }
})
</script>

<template>
  <section class="admin-console">
    <AdminSidebar />
    <main class="admin-main">
      <header class="admin-topbar">
        <div>
          <span class="admin-breadcrumb">后台管理 / 合集</span>
          <h1>合集管理</h1>
        </div>
        <button class="primary-btn" type="button" @click="startCreate">＋ 新建合集</button>
      </header>

      <p v-if="error" class="admin-error" role="alert">{{ error }}</p>
      <p v-if="notice" class="series-notice" role="status">{{ notice }}</p>
      <p v-if="loading" role="status">正在加载…</p>

      <div v-else class="series-layout">
        <aside class="series-list">
          <p v-if="!seriesList.length" class="muted">还没有合集，点「新建合集」开始。</p>
          <button
            v-for="series in seriesList"
            :key="series.id"
            type="button"
            class="series-item"
            :class="{ active: current?.id === series.id }"
            @click="select(series.id)"
          >
            <strong>{{ series.name }}</strong>
            <small>
              <i :class="series.status === 'PUBLISHED' ? 'pub' : 'draft'">{{ series.status === 'PUBLISHED' ? '已发布' : '草稿' }}</i>
              · {{ series.entryCount }} 篇 · v{{ series.version }}
            </small>
          </button>
        </aside>

        <div v-if="creating || current" class="series-editor">
          <form class="series-form" @submit.prevent="saveSeries">
            <strong>{{ creating ? '新建合集' : `编辑：${current?.name}` }}</strong>
            <div class="form-grid">
              <label>名称<input v-model="form.name" required maxlength="200"></label>
              <label>Slug<input v-model="form.slug" required maxlength="200" pattern="[a-z0-9]+(-[a-z0-9]+)*" placeholder="vue-deep-dive"></label>
              <label>状态
                <select v-model="form.status">
                  <option value="DRAFT">草稿</option>
                  <option value="PUBLISHED">发布</option>
                </select>
              </label>
              <label class="wide">简介<textarea v-model="form.description" maxlength="5000" rows="2" /></label>
              <label class="wide">封面地址（可空）<input :value="form.coverImage ?? ''" placeholder="https://… 或 /images/…" @input="form.coverImage = ($event.target as HTMLInputElement).value || null"></label>
            </div>
            <footer>
              <button v-if="!creating && current" type="button" class="danger" @click="removeSeries(current)">删除合集</button>
              <button class="primary" type="submit" :disabled="saving">{{ saving ? '保存中…' : '保存合集' }}</button>
            </footer>
          </form>

          <section v-if="current" class="entries-block">
            <header class="block-head">
              <h2>成员与排序（{{ workingEntries.length }}）</h2>
              <small>拖拽或用 ↑↓ 调整顺序；公开页只展示已发布文章并重新连续编号</small>
            </header>

            <ul class="entry-rows">
              <li
                v-for="(entry, index) in workingEntries"
                :key="entry.postId"
                draggable="true"
                :class="{ dragging: dragIndex === index }"
                @dragstart="onDragStart(index, $event)"
                @dragover.prevent
                @drop.prevent="onDrop(index)"
                @dragend="dragIndex = null"
              >
                <span class="drag-handle" title="拖拽排序">⠿</span>
                <span class="entry-order">{{ index + 1 }}</span>
                <span class="entry-title">{{ entry.title }}</span>
                <input
                  class="chapter-input"
                  :value="entry.chapterTitle ?? ''"
                  maxlength="200"
                  placeholder="章节标题（可空）"
                  @input="entry.chapterTitle = ($event.target as HTMLInputElement).value || null; entriesDirty = true"
                >
                <span class="entry-actions">
                  <button type="button" title="上移" @click="moveEntry(index, -1)">↑</button>
                  <button type="button" title="下移" @click="moveEntry(index, 1)">↓</button>
                  <button type="button" class="danger" title="移出合集" @click="removeEntry(index)">✕</button>
                </span>
              </li>
            </ul>
            <p v-if="!workingEntries.length" class="muted">还没有成员，从下方挑一篇文章加入。</p>

            <div class="entry-add">
              <select v-model="selectedPostId">
                <option value="" disabled>选择文章加入合集…</option>
                <option v-for="post in availablePosts" :key="post.id" :value="post.id">
                  {{ post.title }}{{ post.status === 'PUBLISHED' ? '' : '（草稿）' }}
                </option>
              </select>
              <button type="button" :disabled="selectedPostId === ''" @click="addEntry">添加</button>
              <button class="primary" type="button" :disabled="saving || !entriesDirty" @click="saveEntries">
                {{ saving ? '保存中…' : entriesDirty ? '保存成员与排序' : '排序已保存' }}
              </button>
            </div>
          </section>
        </div>
        <p v-else class="muted pick-hint">从左侧选择一个合集，或新建一个。</p>
      </div>
    </main>
  </section>
</template>

<style scoped>
.primary-btn {
  padding: 8px 16px;
  border-radius: 10px;
  border: none;
  background: var(--accent);
  color: #fff;
  font-size: 13px;
  cursor: pointer;
}
.series-notice { color: #2f7d4f; font-size: 13px; }
.muted { color: var(--muted); font-size: 13px; }
.series-layout {
  display: grid;
  grid-template-columns: 260px 1fr;
  gap: 20px;
  align-items: start;
}
.series-list { display: flex; flex-direction: column; gap: 8px; }
.series-item {
  text-align: left;
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--surface);
  color: var(--ink);
  cursor: pointer;
}
.series-item.active { border-color: var(--accent); }
.series-item strong { display: block; font-size: 14px; margin-bottom: 4px; }
.series-item small { color: var(--muted); font-size: 12px; }
.series-item i { font-style: normal; }
.series-item i.pub { color: #2f7d4f; }
.series-item i.draft { color: #c47b2c; }
.series-editor { display: flex; flex-direction: column; gap: 20px; }
.series-form, .entries-block {
  padding: 18px;
  border: 1px solid var(--line);
  border-radius: 14px;
  background: var(--surface);
}
.series-form strong { display: block; margin-bottom: 12px; font-size: 14px; color: var(--ink); }
.form-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 10px;
}
.form-grid label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  color: var(--muted);
}
.form-grid label.wide { grid-column: 1 / -1; }
.form-grid input, .form-grid textarea, .form-grid select {
  padding: 8px 10px;
  border-radius: 8px;
  border: 1px solid var(--line-strong);
  background: var(--surface-solid);
  color: var(--ink);
  font-size: 13px;
}
.series-form footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 12px;
}
.series-form footer button, .entry-add button {
  padding: 8px 16px;
  border-radius: 10px;
  border: 1px solid var(--line-strong);
  background: var(--surface-solid);
  color: var(--ink);
  font-size: 13px;
  cursor: pointer;
}
.series-form footer button.primary, .entry-add button.primary {
  border: none;
  background: var(--accent);
  color: #fff;
}
.series-form footer button.danger { color: #b4452c; border-color: color-mix(in srgb, #b4452c 40%, var(--line)); }
.block-head { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.block-head h2 { margin: 0; font-size: 15px; color: var(--ink); }
.block-head small { color: var(--muted); font-size: 12px; }
.entry-rows { list-style: none; margin: 0 0 12px; padding: 0; display: flex; flex-direction: column; gap: 6px; }
.entry-rows li {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--surface-solid);
  cursor: grab;
}
.entry-rows li.dragging { opacity: 0.5; border-style: dashed; }
.drag-handle { color: var(--muted); cursor: grab; }
.entry-order {
  font-family: ui-monospace, monospace;
  color: var(--accent);
  min-width: 20px;
  font-size: 13px;
}
.entry-title { flex: 1; font-size: 13px; color: var(--ink); }
.chapter-input {
  width: 180px;
  padding: 6px 8px;
  border-radius: 8px;
  border: 1px solid var(--line);
  background: var(--surface);
  color: var(--ink);
  font-size: 12px;
}
.entry-actions { display: flex; gap: 4px; }
.entry-actions button {
  padding: 4px 8px;
  border-radius: 6px;
  border: 1px solid var(--line-strong);
  background: var(--surface-solid);
  color: var(--ink);
  font-size: 12px;
  cursor: pointer;
}
.entry-actions button.danger { color: #b4452c; }
.entry-add { display: flex; gap: 8px; align-items: center; }
.entry-add select {
  flex: 1;
  padding: 8px 10px;
  border-radius: 8px;
  border: 1px solid var(--line-strong);
  background: var(--surface-solid);
  color: var(--ink);
  font-size: 13px;
}
.pick-hint { padding: 40px 0; text-align: center; }
@media (max-width: 900px) {
  .series-layout { grid-template-columns: 1fr; }
}
</style>
