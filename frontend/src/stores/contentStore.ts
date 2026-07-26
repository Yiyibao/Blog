import { computed, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { fetchPost, fetchPosts } from '../api/content'
import { posts as seedPosts, type Post } from '../data'

export const useContentStore = defineStore('content', () => {
  const posts = ref<Post[]>([...seedPosts])
  const favorites = ref<string[]>([])
  const query = ref('')
  const category = ref('全部')
  const sortOrder = ref<'newest' | 'oldest'>('newest')
  const archivePage = ref(0)
  const contentReady = ref(false)
  const contentError = ref(false)
  const articleDetail = ref<Post | null>(null)

  const categories = computed(() => ['全部', ...new Set(posts.value.map((p) => p.category))])

  const showFavoritesOnly = ref(false)

  watch([query, category, sortOrder, showFavoritesOnly], () => { archivePage.value = 0 })

  const filteredPosts = computed(() => {
    let result = posts.value
      .filter((p) => category.value === '全部' || p.category === category.value)
    if (showFavoritesOnly.value) {
      result = result.filter((p) => favorites.value.includes(p.slug))
    }
    const normalized = query.value.trim().toLowerCase()
    if (normalized) {
      result = result.filter((p) =>
        p.title.toLowerCase().includes(normalized) ||
        p.tags.some((tag) => tag.toLowerCase().includes(normalized))
      )
    }
    return result.sort((a, b) => sortOrder.value === 'newest' ? b.date.localeCompare(a.date) : a.date.localeCompare(b.date))
  })

  const featuredPost = computed(() => posts.value.find((p) => p.featured) ?? posts.value[0] ?? null)

  // NF-3：当前文章 slug 作为响应式输入（由 ArticlePage 依据 route.params.slug 维护），
  // 不再读取 window.location（非响应式，文章间跳转会渲染旧文章）。
  const currentSlug = ref('')

  function setCurrentSlug(slug: string) {
    currentSlug.value = slug
  }

  const currentPost = computed(() => {
    const slug = currentSlug.value
    if (!slug) return null
    return posts.value.find((p) => p.slug === slug) ?? (articleDetail.value?.slug === slug ? articleDetail.value : null)
  })

  const archivePageSize = 6
  const archiveTotalPages = computed(() => Math.max(1, Math.ceil(filteredPosts.value.length / archivePageSize)))

  const pagedPosts = computed(() => {
    const page = Math.min(archivePage.value, archiveTotalPages.value - 1)
    const start = page * archivePageSize
    return filteredPosts.value.slice(start, start + archivePageSize)
  })

  const relatedPosts = computed(() => {
    if (!currentPost.value) return []
    return posts.value
      .filter((p) => p.slug !== currentPost.value?.slug && p.tags.some((tag) => currentPost.value?.tags.includes(tag)))
      .slice(0, 2)
  })

  const articleOutline = computed(() => {
    if (!currentPost.value?.content) return []
    return [...currentPost.value.content.matchAll(/<h2\s+id=["']([^"']+)["'][^>]*>(.*?)<\/h2>/gi)]
      .map((m) => ({ id: m[1], title: m[2].replace(/<[^>]+>/g, '') }))
  })

  async function loadRemoteContent() {
    const allowBundledFallback = import.meta.env.DEV || import.meta.env.VITE_ALLOW_BUNDLED_CONTENT === 'true'
    try {
      contentError.value = false
      const remotePage = await fetchPosts(0, 50)
      if (remotePage?.items?.length) posts.value = remotePage.items
    } catch (error) {
      contentError.value = true
      if (!allowBundledFallback) {
        posts.value = []
        console.error('Backend API is unavailable; bundled content fallback is disabled.', error)
      } else {
        console.info('Backend API is unavailable; using bundled content in development.', error)
      }
    } finally {
      contentReady.value = true
    }
  }

  async function ensureArticleDetail(slug: string) {
    if (!slug || posts.value.some((p) => p.slug === slug)) {
      articleDetail.value = null
      return
    }
    try {
      articleDetail.value = await fetchPost(slug)
    } catch {
      articleDetail.value = null
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
    posts, favorites, query, category, sortOrder, archivePage, showFavoritesOnly,
    contentReady, contentError, articleDetail,
    categories, filteredPosts, featuredPost, currentSlug, setCurrentSlug, currentPost,
    archivePageSize, archiveTotalPages, pagedPosts,
    relatedPosts, articleOutline,
    loadRemoteContent, ensureArticleDetail, toggleFavorite, initFavorites,
  }
})
