import { computed, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { fetchCategories, fetchPost, fetchPosts, searchPosts } from '../api/content'
import { posts as seedPosts, type Post, type PostSummary, type SearchHit } from '../data'

interface CategoryTab {
  name: string
  slug: string
}

/** 归档每页条数（NF-5 服务端真分页与内置回退共用） */
const ARCHIVE_PAGE_SIZE = 6
/** 收藏视图一次性拉取全量摘要的兜底上限（50 条/页 × 20 页） */
const ALL_SUMMARY_PAGE_SIZE = 50
const ALL_SUMMARY_MAX_PAGES = 20

function searchHitToSummary(hit: SearchHit): PostSummary {
  return {
    id: hit.id,
    slug: hit.slug || hit.url.split('/').filter(Boolean).pop() || '',
    title: hit.title,
    excerpt: hit.excerpt,
    // L-8：POST 命中已携带真实 date/readTime/tags，不再伪造空值
    date: hit.date ?? '',
    readTime: hit.readTime ?? 0,
    category: hit.category ?? '',
    tags: hit.tags ?? [],
    color: hit.color ?? '#1649d8',
    number: hit.number ?? '',
  }
}

export const useContentStore = defineStore('content', () => {
  // ── 首页「最近文章」与内置回退 ────────────────────────────────────────────
  const posts = ref<PostSummary[]>([...seedPosts])
  const postTotal = ref(seedPosts.length)
  const contentReady = ref(false)
  const contentError = ref(false)
  /** 后端不可用且允许内置内容时为 true：归档退回种子数据的客户端过滤分页 */
  const usingFallback = ref(false)

  // ── 归档（NF-5 服务端真分页）─────────────────────────────────────────────
  const query = ref('')
  const category = ref('全部')
  const sortOrder = ref<'newest' | 'oldest'>('newest')
  const showFavoritesOnly = ref(false)
  const archivePage = ref(0)
  const archivePosts = ref<PostSummary[]>([])
  const archiveTotal = ref(0)
  const archiveTotalPages = ref(1)
  const archiveLoading = ref(false)
  const categoryTabs = ref<CategoryTab[]>([{ name: '全部', slug: '' }])

  const favorites = ref<string[]>([])
  const articleDetail = ref<Post | null>(null)
  /** 详情请求在途标记：页面据此区分「加载中」与「真 404」，避免闪现不存在提示与错误 noindex */
  const articleDetailLoading = ref(false)

  /** 收藏视图的全量摘要缓存（P1-2 后摘要极轻，全量拉取成本可控） */
  const allSummaries = ref<PostSummary[]>([])
  let allSummariesLoaded = false

  const categories = computed(() => categoryTabs.value.map((tab) => tab.name))

  function slugForCategory(name: string): string {
    return categoryTabs.value.find((tab) => tab.name === name)?.slug ?? ''
  }

  /** 本会话见过的全部文章摘要（首页 + 归档页 + 收藏缓存 + 当前详情），供 currentPost/relatedPosts 检索 */
  const knownPosts = computed<PostSummary[]>(() => {
    const map = new Map<string, PostSummary>()
    for (const p of [...allSummaries.value, ...archivePosts.value, ...posts.value]) map.set(p.slug, p)
    if (articleDetail.value) map.set(articleDetail.value.slug, articleDetail.value)
    return [...map.values()]
  })

  // NF-3：当前文章 slug 作为响应式输入（由 ArticlePage 依据 route.params.slug 维护），
  // 不再读取 window.location（非响应式，文章间跳转会渲染旧文章）。
  const currentSlug = ref('')

  function setCurrentSlug(slug: string) {
    currentSlug.value = slug
  }

  const currentPost = computed<PostSummary | Post | null>(() => {
    const slug = currentSlug.value
    if (!slug) return null
    if (articleDetail.value?.slug === slug) return articleDetail.value
    return knownPosts.value.find((p) => p.slug === slug) ?? null
  })

  /** P1-2：列表项不含正文，正文只可能来自详情（或内置种子）；摘要项返回空串等待详情到达 */
  const currentContent = computed(() => {
    const post = currentPost.value
    return post && 'content' in post ? (post as Post).content : ''
  })

  const relatedPosts = computed(() => {
    const current = currentPost.value
    if (!current) return []
    return knownPosts.value
      .filter((p) => p.slug !== current.slug && p.tags.some((tag) => current.tags.includes(tag)))
      .slice(0, 2)
  })

  const articleOutline = computed(() => {
    if (!currentContent.value) return []
    return [...currentContent.value.matchAll(/<h2\s+id=["']([^"']+)["'][^>]*>(.*?)<\/h2>/gi)]
      .map((m) => ({ id: m[1], title: m[2].replace(/<[^>]+>/g, '') }))
  })

  /** L-9：精选文章由专用请求提供（任意日期均可命中），窗口内检索与首篇仅作回退。 */
  const remoteFeatured = ref<PostSummary | null>(null)
  const featuredPost = computed(() =>
    remoteFeatured.value ?? posts.value.find((p) => p.featured) ?? posts.value[0] ?? null)

  function allowBundledFallback() {
    return import.meta.env.DEV || import.meta.env.VITE_ALLOW_BUNDLED_CONTENT === 'true'
  }

  async function loadRemoteContent() {
    try {
      contentError.value = false
      const [remotePage, remoteCategories, featuredPage] = await Promise.all([
        fetchPosts(0, 12),
        fetchCategories().catch(() => null),
        // L-9：精选出窗（不在最近 12 条内）也能正确展示；失败不阻塞首页
        fetchPosts(0, 1, { featured: true }).catch(() => null),
      ])
      if (remotePage?.items?.length) {
        posts.value = remotePage.items
        postTotal.value = remotePage.totalElements
        usingFallback.value = false
      }
      remoteFeatured.value = featuredPage?.items?.[0] ?? null
      if (remoteCategories?.length) {
        categoryTabs.value = [{ name: '全部', slug: '' }, ...remoteCategories.map((c) => ({ name: c.name, slug: c.slug }))]
      }
    } catch (error) {
      contentError.value = true
      if (!allowBundledFallback()) {
        posts.value = []
        postTotal.value = 0
        console.error('Backend API is unavailable; bundled content fallback is disabled.', error)
      } else {
        usingFallback.value = true
        applyFallbackTabs()
        console.info('Backend API is unavailable; using bundled content in development.', error)
      }
    } finally {
      contentReady.value = true
    }
  }

  function applyFallbackTabs() {
    categoryTabs.value = [
      { name: '全部', slug: '' },
      ...[...new Set(seedPosts.map((p) => p.category))].map((name) => ({ name, slug: '' })),
    ]
  }

  // ── 归档加载（服务端真分页 / 搜索 / 收藏 / 内置回退四种模式）────────────────
  let archiveRevision = 0

  function byDate(a: PostSummary, b: PostSummary) {
    return sortOrder.value === 'newest' ? b.date.localeCompare(a.date) : a.date.localeCompare(b.date)
  }

  /** 页码越界（如筛选后总页数变小、URL 手填大页码）时回夹到最后一页；返回 true 表示已触发重载 */
  function clampArchivePage(totalPages: number) {
    if (archivePage.value > 0 && archivePage.value >= totalPages) {
      archivePage.value = Math.max(0, totalPages - 1)
      return true
    }
    return false
  }

  function applyClientPage(items: PostSummary[]) {
    const totalPages = Math.max(1, Math.ceil(items.length / ARCHIVE_PAGE_SIZE))
    clampArchivePage(totalPages)
    const page = Math.min(archivePage.value, totalPages - 1)
    archivePosts.value = items.slice(page * ARCHIVE_PAGE_SIZE, (page + 1) * ARCHIVE_PAGE_SIZE)
    archiveTotal.value = items.length
    archiveTotalPages.value = totalPages
  }

  function loadFallbackArchive() {
    let result = seedPosts.filter((p) => category.value === '全部' || p.category === category.value)
    if (showFavoritesOnly.value) result = result.filter((p) => favorites.value.includes(p.slug))
    const normalized = query.value.trim().toLowerCase()
    if (normalized) {
      result = result.filter((p) =>
        p.title.toLowerCase().includes(normalized) ||
        p.tags.some((tag) => tag.toLowerCase().includes(normalized))
      )
    }
    applyClientPage([...result].sort(byDate))
  }

  async function ensureAllSummaries() {
    if (allSummariesLoaded) return
    const first = await fetchPosts(0, ALL_SUMMARY_PAGE_SIZE)
    const acc = [...first.items]
    const totalPages = Math.min(first.totalPages, ALL_SUMMARY_MAX_PAGES)
    for (let page = 1; page < totalPages; page += 1) {
      acc.push(...(await fetchPosts(page, ALL_SUMMARY_PAGE_SIZE)).items)
    }
    allSummaries.value = acc
    allSummariesLoaded = true
  }

  async function loadArchive() {
    const revision = ++archiveRevision
    archiveLoading.value = true
    try {
      if (usingFallback.value) {
        loadFallbackArchive()
        return
      }
      const q = query.value.trim()
      if (q) {
        // L-8：搜索模式的分类过滤与排序下推服务端，分页计数与条件一致；
        // 收藏筛选仍在当前页内应用（收藏 slug 只存在于本地）
        const categorySlug = slugForCategory(category.value)
        const page = await searchPosts(q, archivePage.value, ARCHIVE_PAGE_SIZE, {
          ...(categorySlug ? { categorySlug } : {}),
          sort: sortOrder.value === 'oldest' ? 'asc' : 'desc',
        })
        if (revision !== archiveRevision) return
        let items = page.results.map(searchHitToSummary)
        if (showFavoritesOnly.value) items = items.filter((p) => favorites.value.includes(p.slug))
        archivePosts.value = items
        archiveTotal.value = page.totalElements
        archiveTotalPages.value = page.totalPages
        clampArchivePage(page.totalPages)
      } else if (showFavoritesOnly.value) {
        // 收藏模式：收藏 slug 只存在于本地，需全量摘要后客户端过滤
        await ensureAllSummaries()
        if (revision !== archiveRevision) return
        let items = allSummaries.value.filter((p) => favorites.value.includes(p.slug))
        if (category.value !== '全部') items = items.filter((p) => p.category === category.value)
        applyClientPage([...items].sort(byDate))
      } else {
        const categorySlug = slugForCategory(category.value)
        const page = await fetchPosts(archivePage.value, ARCHIVE_PAGE_SIZE, {
          ...(categorySlug ? { categorySlug } : {}),
          sort: sortOrder.value === 'oldest' ? 'asc' : 'desc',
        })
        if (revision !== archiveRevision) return
        archivePosts.value = page.items
        archiveTotal.value = page.totalElements
        archiveTotalPages.value = page.totalPages
        clampArchivePage(page.totalPages)
      }
      contentError.value = false
    } catch (error) {
      if (revision !== archiveRevision) return
      contentError.value = true
      if (allowBundledFallback()) {
        usingFallback.value = true
        applyFallbackTabs()
        loadFallbackArchive()
        console.info('Backend API is unavailable; archive falls back to bundled content.', error)
      } else {
        archivePosts.value = []
        archiveTotal.value = 0
        archiveTotalPages.value = 1
      }
    } finally {
      if (revision === archiveRevision) archiveLoading.value = false
    }
  }

  // 筛选条件变化：回到第一页并重载；翻页：直接重载。
  // query 防抖 300ms，避免每个按键都打后端。
  let queryDebounceTimer: ReturnType<typeof setTimeout> | undefined
  watch(query, () => {
    clearTimeout(queryDebounceTimer)
    queryDebounceTimer = setTimeout(() => {
      archivePage.value = 0
      void loadArchive()
    }, 300)
  })
  watch([category, sortOrder, showFavoritesOnly], () => {
    archivePage.value = 0
    void loadArchive()
  })
  watch(archivePage, () => { void loadArchive() })
  // 收藏增删直接影响收藏视图的过滤结果（含搜索+收藏组合），需要即时重载
  watch(favorites, () => {
    if (showFavoritesOnly.value) void loadArchive()
  })

  let detailRequestId = 0

  async function ensureArticleDetail(slug: string) {
    if (!slug) {
      articleDetail.value = null
      return
    }
    // 仅内置回退模式（后端确认不可用）才信任本地种子正文——种子 slug 与后端
    // 种子重叠，后端在线时以「本地有 content」为由跳过拉取会把摘要替换后的
    // 文章渲染成空白甚至误报 404，正文必须始终来自详情接口。
    if (usingFallback.value) {
      const local = knownPosts.value.find((p) => p.slug === slug)
      if (local && 'content' in local && (local as Post).content) {
        articleDetail.value = null
        return
      }
    }
    if (articleDetail.value?.slug === slug) return
    const requestId = ++detailRequestId
    articleDetailLoading.value = true
    try {
      const detail = await fetchPost(slug)
      if (requestId !== detailRequestId) return
      articleDetail.value = detail
    } catch {
      if (requestId !== detailRequestId) return
      articleDetail.value = null
    } finally {
      if (requestId === detailRequestId) articleDetailLoading.value = false
    }
  }

  function toggleFavorite(slug: string) {
    if (favorites.value.includes(slug)) {
      favorites.value = favorites.value.filter((s) => s !== slug)
    } else {
      favorites.value = [...favorites.value, slug]
    }
    localStorage.setItem('yubai-favorites', JSON.stringify(favorites.value))
  }

  function initFavorites() {
    try {
      favorites.value = JSON.parse(localStorage.getItem('yubai-favorites') ?? '[]')
    } catch {
      favorites.value = []
    }
  }

  return {
    posts, postTotal, favorites, query, category, sortOrder, showFavoritesOnly,
    contentReady, contentError, usingFallback, articleDetail, articleDetailLoading,
    categories, featuredPost, currentSlug, setCurrentSlug, currentPost, currentContent,
    archivePage, archivePosts, archiveTotal, archiveTotalPages, archiveLoading,
    archivePageSize: ARCHIVE_PAGE_SIZE,
    relatedPosts, articleOutline,
    loadRemoteContent, loadArchive, ensureArticleDetail, toggleFavorite, initFavorites,
  }
})
