<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchPosts, fetchDishes, fetchPublishedNotes } from '../api/content'
import type { Post, Dish } from '../data'
import type { AdminNote } from '../api/admin'

type ArchiveContentType = 'ARTICLE' | 'NOTE' | 'DISH'

interface ArchiveEntry {
  type: ArchiveContentType
  title: string
  summary: string
  publishedAt: string
  url: string
  category: string
  tags: string[]
}

import KnowledgeGraph from '../components/KnowledgeGraph.vue'

const route = useRoute()
const router = useRouter()

const posts = ref<Post[]>([])
const dishes = ref<Dish[]>([])
const notes = ref<AdminNote[]>([])
const loading = ref(true)
const loadError = ref('')

const typeFilter = computed(() => {
  const raw = Array.isArray(route.query.type) ? route.query.type[0] : route.query.type
  const valid = ['article', 'note', 'dish']
  return typeof raw === 'string' && valid.includes(raw) ? raw : 'all'
})

function setFilter(value: string) {
  router.replace(value === 'all' ? { query: {} } : { query: { type: value } })
}

function normalizeDate(item: Post | Dish | AdminNote, type: ArchiveContentType): string {
  if (type === 'ARTICLE') {
    const p = item as Post
    return p.date ? `${p.date}T00:00:00Z` : ''
  }
  const ts = (item as Dish | AdminNote).createdAt
  return ts || ''
}

function toEntry(item: Post | Dish | AdminNote, type: ArchiveContentType): ArchiveEntry {
  if (type === 'ARTICLE') {
    const p = item as Post
    return { type, title: p.title, summary: p.excerpt, publishedAt: normalizeDate(p, type), url: `/articles/${p.slug}`, category: p.category, tags: p.tags }
  }
  if (type === 'DISH') {
    const d = item as Dish
    return { type, title: d.name, summary: d.summary, publishedAt: normalizeDate(d, type), url: `/recipes?dish=${d.slug}`, category: d.category, tags: [] }
  }
  const n = item as AdminNote
  return { type, title: n.title, summary: '', publishedAt: normalizeDate(n, type), url: `/notes?note=${n.id}`, category: n.folder, tags: n.tags }
}

const allEntries = computed(() => {
  const result: ArchiveEntry[] = []
  if (typeFilter.value === 'all' || typeFilter.value === 'article') {
    result.push(...posts.value.map(p => toEntry(p, 'ARTICLE')))
  }
  if (typeFilter.value === 'all' || typeFilter.value === 'dish') {
    result.push(...dishes.value.map(d => toEntry(d, 'DISH')))
  }
  if (typeFilter.value === 'all' || typeFilter.value === 'note') {
    result.push(...notes.value.map(n => toEntry(n, 'NOTE')))
  }
  return result.sort((a, b) => b.publishedAt.localeCompare(a.publishedAt))
})

interface MonthGroup {
  month: number
  label: string
  entries: ArchiveEntry[]
}

interface YearGroup {
  year: number
  months: MonthGroup[]
}

const groups = computed(() => {
  const yearMap = new Map<number, Map<number, ArchiveEntry[]>>()
  for (const entry of allEntries.value) {
    if (!entry.publishedAt) continue
    const d = new Date(entry.publishedAt)
    if (isNaN(d.getTime())) continue
    const y = d.getUTCFullYear()
    const m = d.getUTCMonth() + 1
    if (!yearMap.has(y)) yearMap.set(y, new Map())
    const monthMap = yearMap.get(y)!
    if (!monthMap.has(m)) monthMap.set(m, [])
    monthMap.get(m)!.push(entry)
  }
  const result: YearGroup[] = []
  const years = [...yearMap.keys()].sort((a, b) => b - a)
  for (const y of years) {
    const monthMap = yearMap.get(y)!
    const months: MonthGroup[] = [...monthMap.keys()]
      .sort((a, b) => b - a)
      .map(m => ({
        month: m,
        label: `${m}月`,
        entries: monthMap.get(m)!,
      }))
    result.push({ year: y, months })
  }
  return result
})

const totalCount = computed(() => allEntries.value.length)

const typeLabel: Record<string, string> = {
  ARTICLE: '文章',
  NOTE: '笔记',
  DISH: '菜品',
}

async function load() {
  loading.value = true
  loadError.value = ''
  let postErr = false
  let dishErr = false
  let noteErr = false
  const [postRes, dishRes, noteRes] = await Promise.all([
    fetchPosts(0, 50).catch(() => { postErr = true; return { items: [] as Post[] } }),
    fetchDishes(0, 50).catch(() => { dishErr = true; return { items: [] as Dish[] } }),
    fetchPublishedNotes(0, 50).catch(() => { noteErr = true; return { items: [] as AdminNote[] } }),
  ])
  posts.value = postRes.items ?? []
  dishes.value = dishRes.items ?? []
  notes.value = noteRes.items ?? []
  if (postErr && dishErr && noteErr) {
    loadError.value = '归档数据暂时无法加载，请稍后重试。'
  }
  loading.value = false
}

watch(() => route.query.type, () => {
  if (!loading.value) load()
})

onMounted(load)
</script>

<template>
  <section class="archive-page section-wrap">
    <header class="archive-head">
      <p class="eyebrow"><span /> ARCHIVE / 内容存档</p>
      <h1>从开始到现在，<br><em>所有记录都在这里。</em></h1>
      <p>按时间倒序浏览所有公开的文章、学习笔记和菜谱。</p>
    </header>

    <KnowledgeGraph />

    <div class="archive-toolbar">
      <strong>{{ totalCount }} 条记录</strong>
      <nav class="archive-filters" role="tablist" aria-label="内容类型筛选">
        <button
          v-for="opt in [['all', '全部'], ['article', '文章'], ['note', '学习笔记'], ['dish', '菜品']]"
          :key="opt[0]"
          role="tab"
          :aria-selected="typeFilter === opt[0]"
          :class="{ active: typeFilter === opt[0] }"
          @click="setFilter(opt[0])"
        >{{ opt[1] }}</button>
      </nav>
    </div>

    <div v-if="loading" class="archive-loading" role="status">
      <span>正在加载归档数据…</span>
    </div>

    <div v-else-if="loadError" class="archive-error" role="alert">
      <h2>加载失败</h2>
      <p>{{ loadError }}</p>
      <button class="button primary" type="button" @click="load">重试</button>
    </div>

    <div v-else-if="totalCount === 0" class="archive-empty">
      <h2>暂无记录</h2>
      <p>没有符合条件的公开内容。</p>
    </div>

    <div v-else class="archive-body">
      <div v-for="yearGroup in groups" :key="yearGroup.year" class="archive-year">
        <h2 class="year-title">{{ yearGroup.year }}</h2>
        <div v-for="monthGroup in yearGroup.months" :key="monthGroup.month" class="archive-month">
          <h3 class="month-title">{{ monthGroup.label }}</h3>
          <div class="archive-list">
            <a
              v-for="(entry, idx) in monthGroup.entries"
              :key="`${entry.type}-${idx}-${entry.url}`"
              :href="entry.url"
              class="archive-entry"
            >
              <span class="entry-type" :class="`type-${entry.type.toLowerCase()}`">{{ typeLabel[entry.type] }}</span>
              <span class="entry-title">{{ entry.title }}</span>
              <span v-if="entry.category" class="entry-category">{{ entry.category }}</span>
            </a>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.archive-page {
  padding-top: clamp(72px, 9vw, 120px);
  padding-bottom: 110px;
}
.archive-head { margin-bottom: 40px; }
.archive-head h1 { margin: 16px 0 0; font: 400 clamp(2.5rem, 5vw, 4.2rem)/1.04 Georgia, "Songti SC", serif; letter-spacing: -.05em; }
.archive-head h1 em { color: var(--accent); font-style: normal; }
.archive-head p { margin-top: 18px; color: var(--muted); font-size: .95rem; line-height: 1.7; max-width: 560px; }
.archive-toolbar { display: flex; justify-content: space-between; align-items: center; gap: 20px; flex-wrap: wrap; padding: 14px 0; border-top: 1px solid var(--line); border-bottom: 1px solid var(--line); margin-bottom: 36px; }
.archive-toolbar strong { font-size: .85rem; font-weight: 520; color: var(--muted); white-space: nowrap; }
.archive-filters { display: flex; gap: 4px; overflow-x: auto; }
.archive-filters button { flex: 0 0 auto; padding: 8px 13px; color: var(--muted); background: transparent; border: 0; border-radius: 8px; font-size: .78rem; cursor: pointer; transition: color .15s, background .15s; }
.archive-filters button:hover { color: var(--ink); }
.archive-filters button.active { color: var(--paper); background: var(--ink); }
.archive-loading, .archive-error, .archive-empty { display: grid; place-items: center; min-height: 300px; padding: 60px; text-align: center; }
.archive-loading span { color: var(--muted); font-size: .9rem; }
.archive-error h2, .archive-empty h2 { font-size: 1.8rem; font-weight: 520; margin: 0 0 12px; }
.archive-error p, .archive-empty p { color: var(--muted); max-width: 420px; line-height: 1.7; }
.archive-year { margin-bottom: 44px; }
.year-title { font: 520 clamp(1.8rem, 3.5vw, 2.8rem)/1 Georgia, "Songti SC", serif; letter-spacing: -.04em; color: var(--ink); margin: 0 0 6px; padding-bottom: 8px; border-bottom: 1px solid var(--line); }
.archive-month { margin: 24px 0 0; }
.month-title { font-size: 1rem; font-weight: 600; color: var(--muted); margin: 0 0 10px; }
.archive-list { display: flex; flex-direction: column; gap: 1px; background: var(--line); border-radius: 10px; overflow: hidden; }
.archive-entry { display: flex; align-items: center; gap: 14px; padding: 14px 18px; background: var(--surface); text-decoration: none; color: var(--ink); transition: background .15s; }
.archive-entry:hover { background: var(--accent-soft); }
.entry-type { flex: 0 0 auto; padding: 3px 9px; border-radius: 6px; font-size: .7rem; font-weight: 600; letter-spacing: .04em; white-space: nowrap; }
.type-article { color: #fff; background: #1649d8; }
.type-note { color: #fff; background: #8c7bff; }
.type-dish { color: #fff; background: #e67e22; }
.entry-title { flex: 1 1 auto; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: .92rem; }
.entry-category { flex: 0 0 auto; color: var(--muted); font-size: .75rem; white-space: nowrap; }
</style>
