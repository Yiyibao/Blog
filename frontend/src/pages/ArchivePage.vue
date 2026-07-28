<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchPosts, fetchDishes, fetchPublishedNotes } from '../api/content'
import { useAuthStore } from '../stores/auth'
import type { PostSummary, Dish } from '../data'
import type { AdminNoteSummary } from '../api/admin'
import KnowledgeGraph from '../components/KnowledgeGraph.vue'

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

const route = useRoute()
const router = useRouter()

const posts = ref<PostSummary[]>([])
const dishes = ref<Dish[]>([])
const notes = ref<AdminNoteSummary[]>([])
const loading = ref(true)
const loadError = ref('')
/** NF-8：三路数据部分失败时的提示条文案（全败走 loadError 整页错误态） */
const partialError = ref('')

const selectedRelation = computed(() => {
  const raw = Array.isArray(route.query.relation) ? route.query.relation[0] : route.query.relation
  return typeof raw === 'string' ? raw : ''
})

/** L-13：视图切换与类型分类已移除，URL 仅承载 relation；旧链接的 view/type 参数顺带清除。 */
function updateQuery(patch: { relation?: string | null }) {
  const query = { ...route.query }
  delete query.view
  delete query.type

  if (patch.relation !== undefined) {
    if (!patch.relation) delete query.relation
    else query.relation = patch.relation
  }

  void router.replace({ query })
}

function normalizeDate(item: PostSummary | Dish | AdminNoteSummary, type: ArchiveContentType): string {
  if (type === 'ARTICLE') {
    const p = item as PostSummary
    return p.date ? `${p.date}T00:00:00Z` : ''
  }
  const ts = (item as Dish | AdminNoteSummary).createdAt
  return ts || ''
}

function toEntry(item: PostSummary | Dish | AdminNoteSummary, type: ArchiveContentType): ArchiveEntry {
  if (type === 'ARTICLE') {
    const p = item as PostSummary
    return { type, title: p.title, summary: p.excerpt, publishedAt: normalizeDate(p, type), url: `/articles/${p.slug}`, category: p.category, tags: p.tags }
  }
  if (type === 'DISH') {
    const d = item as Dish
    return { type, title: d.name, summary: d.summary, publishedAt: normalizeDate(d, type), url: `/recipes?dish=${d.slug}`, category: d.category, tags: [] }
  }
  const n = item as AdminNoteSummary
  return { type, title: n.title, summary: '', publishedAt: normalizeDate(n, type), url: `/notes?note=${n.id}`, category: n.folder, tags: n.tags }
}

const allEntries = computed(() => {
  const result: ArchiveEntry[] = [
    ...posts.value.map(p => toEntry(p, 'ARTICLE')),
    ...dishes.value.map(d => toEntry(d, 'DISH')),
    ...notes.value.map(n => toEntry(n, 'NOTE')),
  ]

  let filtered = result
  if (selectedRelation.value) {
    const rel = selectedRelation.value.toLowerCase()
    filtered = result.filter(e =>
      e.tags.some(t => t.toLowerCase() === rel) ||
      e.category.toLowerCase() === rel
    )
  }

  return filtered.sort((a, b) => b.publishedAt.localeCompare(a.publishedAt))
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

const ARCHIVE_PAGE_SIZE = 50

async function fetchAllPages<T>(
  fetcher: (page: number, size: number) => Promise<{ items: T[]; totalPages: number }>
): Promise<{ items: T[]; error: boolean }> {
  try {
    const first = await fetcher(0, ARCHIVE_PAGE_SIZE)
    const acc = [...(first.items ?? [])]
    const totalPages = first.totalPages ?? 1
    let pageError = false
    if (totalPages > 1) {
      const pages = []
      for (let p = 1; p < totalPages; p++) {
        pages.push(fetcher(p, ARCHIVE_PAGE_SIZE).then(r => r.items ?? []).catch(() => { pageError = true; return [] as T[] }))
      }
      const results = await Promise.all(pages)
      for (const items of results) {
        acc.push(...items)
      }
    }
    return { items: acc, error: pageError }
  } catch {
    return { items: [], error: true }
  }
}

async function load() {
  loading.value = true
  loadError.value = ''
  // L-16/D-17：学习笔记接口已收权——游客不请求（也不算失败），登录后时间轴恢复笔记条目
  const includeNotes = useAuthStore().isAuthenticated
  const [postResult, dishResult, noteResult] = await Promise.all([
    fetchAllPages<PostSummary>((page, size) => fetchPosts(page, size)),
    fetchAllPages<Dish>((page, size) => fetchDishes(page, size)),
    includeNotes
      ? fetchAllPages<AdminNoteSummary>((page, size) => fetchPublishedNotes(page, size))
      : Promise.resolve({ items: [] as AdminNoteSummary[], error: false }),
  ])
  posts.value = postResult.items
  dishes.value = dishResult.items
  notes.value = noteResult.items
  if (postResult.error && dishResult.error && (noteResult.error || !includeNotes)) {
    loadError.value = '归档数据暂时无法加载，请稍后重试。'
    partialError.value = ''
  } else {
    const failed = [postResult.error ? '文章' : '', dishResult.error ? '菜谱' : '', noteResult.error ? '学习笔记' : ''].filter(Boolean)
    partialError.value = failed.length ? `部分内容（${failed.join('、')}）加载失败，以下列表可能不完整。` : ''
  }
  loading.value = false
}

function handleSelectTag(tagText: string) {
  updateQuery({ relation: tagText || null })
}

onMounted(load)
</script>

<template>
  <section class="archive-page section-wrap">
    <header class="archive-head">
      <div class="head-top">
        <p class="eyebrow"><span /> ARCHIVE / 内容存档</p>
      </div>

      <h1>从开始到现在，<br><em>所有记录都在这里。</em></h1>
      <p>知识图谱呈现内容脉络，时间轴按时间倒序铺陈全部公开记录。</p>
    </header>

    <!-- L-13：知识图谱常驻时间轴上方，单页纵向流（旧 ?view=graph 链接落在同一页，参数忽略） -->
    <div class="archive-graph-view">
      <KnowledgeGraph
        :selected-relation="selectedRelation"
        @select-tag="handleSelectTag"
      />
    </div>

    <!-- Timeline -->
    <div class="archive-timeline-view">
      <div class="archive-toolbar">
        <div class="toolbar-left">
          <strong>{{ totalCount }} 条记录</strong>
          <span v-if="selectedRelation" class="relation-pill">
            关联: {{ selectedRelation }}
            <button type="button" aria-label="清除关联筛选" @click="updateQuery({ relation: null })">✕</button>
          </span>
        </div>
      </div>

      <div v-if="!loading && partialError" class="archive-partial-notice" role="alert">
        <span>{{ partialError }}</span>
        <button type="button" @click="load">重试</button>
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
    </div>
  </section>
</template>

<style scoped>
.archive-page {
  padding-top: clamp(72px, 9vw, 120px);
  padding-bottom: 110px;
}
/* L-13：图谱常驻时间轴上方的纵向间距 */
.archive-graph-view { margin-bottom: 44px; }

/* NF-8：部分失败提示条 */
.archive-partial-notice {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 24px;
  padding: 12px 18px;
  border: 1px solid color-mix(in srgb, #c47b2c 45%, var(--line));
  border-radius: 14px;
  background: color-mix(in srgb, #c47b2c 8%, var(--surface));
  color: var(--ink);
  font-size: 14px;
}
.archive-partial-notice button {
  flex-shrink: 0;
  padding: 6px 16px;
  border: 1px solid var(--line-strong);
  border-radius: 999px;
  background: var(--surface-solid);
  color: var(--ink);
  font-size: 13px;
  cursor: pointer;
}
.archive-partial-notice button:hover { border-color: var(--accent); }
.archive-head { margin-bottom: 40px; }
.head-top { display: flex; justify-content: space-between; align-items: center; gap: 16px; flex-wrap: wrap; }
.view-switch-nav { display: flex; gap: 4px; background: var(--surface); padding: 4px; border-radius: 999px; border: 1px solid var(--line); }
.view-switch-btn { display: flex; align-items: center; gap: 6px; padding: 6px 14px; border-radius: 999px; border: 0; background: transparent; color: var(--muted); font-size: 0.8rem; font-weight: 500; cursor: pointer; transition: background 0.2s, color 0.2s; }
.view-switch-btn.active { background: var(--ink); color: var(--paper); }
.view-switch-btn i { font-style: normal; }

.archive-head h1 { margin: 16px 0 0; font: 400 clamp(2.5rem, 5vw, 4.2rem)/1.04 Georgia, "Songti SC", serif; letter-spacing: -.05em; }
.archive-head h1 em { color: var(--accent); font-style: normal; }
.archive-head p { margin-top: 18px; color: var(--muted); font-size: .95rem; line-height: 1.7; max-width: 560px; }

.archive-toolbar { display: flex; justify-content: space-between; align-items: center; gap: 20px; flex-wrap: wrap; padding: 14px 0; border-top: 1px solid var(--line); border-bottom: 1px solid var(--line); margin-bottom: 36px; }
.toolbar-left { display: flex; align-items: center; gap: 12px; }
.archive-toolbar strong { font-size: .85rem; font-weight: 520; color: var(--muted); white-space: nowrap; }
.relation-pill { display: flex; align-items: center; gap: 6px; padding: 3px 10px; border-radius: 999px; background: var(--accent-soft); color: var(--accent); font-size: 0.78rem; font-weight: 500; }
.relation-pill button { border: 0; background: none; color: inherit; cursor: pointer; padding: 0; font-size: 0.75rem; }

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
