import axios from 'axios'
import type { CategorySummary, Dish, PageResult, Post, PostSummary, SearchHit } from '../data'
import type { AdminNote, AdminNoteSummary } from './admin'
import { useAuthStore } from '../stores/auth'

interface ApiEnvelope<T> {
  data: T
  timestamp: string
}

const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api/v1',
  timeout: 5000,
  headers: { Accept: 'application/json' },
})

api.interceptors.request.use((config) => {
  if (typeof config.url === 'string' && config.url.includes('/graph/')) {
    const authStore = useAuthStore()
    if (authStore.isAuthenticated && authStore.token) {
      config.headers.Authorization = `Bearer ${authStore.token}`
    }
  }
  return config
})

async function unwrap<T>(request: Promise<{ data: ApiEnvelope<T> }>): Promise<T> {
  const response = await request
  return response.data.data
}

function asPage<T>(data: PageResult<T> | T[], page = 0, size = 10): PageResult<T> {
  if (Array.isArray(data)) {
    return {
      items: data,
      page,
      size: data.length || size,
      totalElements: data.length,
      totalPages: data.length ? 1 : 0,
    }
  }
  return {
    items: data.items ?? [],
    page: data.page ?? page,
    size: data.size ?? size,
    totalElements: data.totalElements ?? data.items?.length ?? 0,
    totalPages: Math.max(1, data.totalPages ?? 1),
  }
}

export interface FetchPostsOptions {
  categorySlug?: string
  sort?: 'asc' | 'desc'
  /** L-9：只取精选文章（服务端按标记检索，不受首页取窗限制）。 */
  featured?: boolean
}

// P1-2：列表返回 PostSummary（不含正文）；categorySlug 过滤分类，sort=asc 最早优先。
export async function fetchPosts(page = 0, size = 10, options: FetchPostsOptions = {}) {
  const data = await unwrap<PageResult<PostSummary> | PostSummary[]>(api.get('/posts', {
    params: {
      page, size,
      ...(options.categorySlug ? { categorySlug: options.categorySlug } : {}),
      ...(options.sort ? { sort: options.sort } : {}),
      ...(options.featured ? { featured: true } : {}),
    },
  }))
  return asPage(data, page, size)
}

export function fetchPost(slug: string) {
  return unwrap<Post>(api.get(`/posts/${encodeURIComponent(slug)}`))
}

export function fetchCategories() {
  return unwrap<CategorySummary[]>(api.get('/categories'))
}

export async function fetchDishes(page = 0, size = 12, categorySlug?: string, query?: string) {
  const params: Record<string, unknown> = { page, size }
  if (categorySlug) params.categorySlug = categorySlug
  if (query) params.query = query
  const data = await unwrap<PageResult<Dish> | Dish[]>(api.get('/dishes', { params }))
  return asPage(data, page, size)
}

export interface DishCategorySummary {
  name: string
  slug: string
}

export function fetchDishCategories() {
  return unwrap<DishCategorySummary[]>(api.get('/dish-categories'))
}

// P1-2：公开笔记列表为摘要 DTO（不含 markdownContent），正文经 fetchPublishedNote 详情获取。
export async function fetchPublishedNotes(page = 0, size = 20) {
  const data = await unwrap<PageResult<AdminNoteSummary> | AdminNoteSummary[]>(api.get('/notes', { params: { page, size } }))
  return asPage(data, page, size)
}

export function fetchPublishedNote(id: number) {
  return unwrap<AdminNote>(api.get(`/notes/${id}`))
}

export function fetchDish(slug: string) {
  return unwrap<Dish>(api.get(`/dishes/${encodeURIComponent(slug)}`))
}

// FD-3：收藏榜条目是后端刻意的轻量投影（无 imageAlt/category/食材步骤），不复用 Dish
export interface DishFavoriteItem {
  slug: string
  name: string
  summary: string
  imageUrl: string
  favoriteCount: number
}

export interface DishFavoriteResult {
  slug: string
  favoriteCount: number
}

export async function fetchDishFavorites(page = 0, size = 5) {
  const data = await unwrap<PageResult<DishFavoriteItem> | DishFavoriteItem[]>(
    api.get('/dishes/favorites', { params: { page, size } }),
  )
  return asPage(data, page, size)
}

// 纯计数 +1（非 toggle），免登录，后端按 IP+slug 限流 10 次/分，超限 429。
// ⚠️ 计数回显只信本端点响应；GET /dishes/{slug} 带 5 分钟公共缓存，禁止用它回写 favoriteCount。
export function favoriteDish(slug: string) {
  return unwrap<DishFavoriteResult>(api.post(`/dishes/${encodeURIComponent(slug)}/favorite`))
}

export type GraphNodeKind = 'ROOT' | 'GROUP' | 'CONTENT'
export type GraphEdgeKind = 'STRUCTURE' | 'RELATION'

export interface GraphOverviewNode {
  id: string
  label: string
  type: string
  kind: GraphNodeKind
  groupId: string | null
  url: string | null
  subtitle: string | null
  imageUrl: string | null
  updatedAt: string | null
  degree: number
  importance: number
}

export interface GraphOverviewEdge {
  source: string
  target: string
  kind: GraphEdgeKind
  strength: number
}

export interface GraphOverviewLegendItem {
  type: string
  label: string
  color: string
  count: number
}

export interface GraphOverviewStats {
  contentNodeCount: number
  visualNodeCount: number
  relationCount: number
  lastUpdatedAt: string | null
  recommendedCenterId: string
  localModeRecommended: boolean
}

export interface GraphOverview {
  schemaVersion: string
  stats: GraphOverviewStats
  legend: GraphOverviewLegendItem[]
  nodes: GraphOverviewNode[]
  edges: GraphOverviewEdge[]
}

export function fetchGraphOverview() {
  return unwrap<GraphOverview>(api.get('/graph/overview'))
}

export interface GraphApiNode {
  id: string
  label: string
  type: 'POST' | 'DISH' | 'NOTE' | 'TAG' | 'SERIES'
  url: string | null
}

export interface GraphApiEdge {
  source: string
  target: string
}

export function fetchGraphNodes() {
  return unwrap<{ nodes: GraphApiNode[]; edges: GraphApiEdge[] }>(api.get('/graph/nodes'))
}

export function fetchGraphSubgraph(center: string, depth = 2) {
  return unwrap<{ nodes: GraphApiNode[]; edges: GraphApiEdge[] }>(
    api.get(`/graph/nodes/${encodeURIComponent(center)}`, { params: { depth } })
  )
}

export interface RemoteMusicTrack {
  id: number | string
  title: string
  artist: string
  duration?: number
  audioUrl: string
  coverUrl?: string
}

export function fetchMusicTracks() {
  return unwrap<RemoteMusicTrack[]>(api.get('/music/tracks'))
}

export interface RemoteQuote {
  id: number | string
  content: string
  author: string
  category: string
}

export function fetchDailyQuotes() {
  return unwrap<RemoteQuote[] | RemoteQuote>(api.get('/quotes/daily'))
}

// 5B：标签一等公民
export interface TagSummary {
  tag: string
  count: number
}

export function fetchTags() {
  return unwrap<TagSummary[]>(api.get('/tags'))
}

export async function fetchTagPosts(tag: string, page = 0, size = 10) {
  const data = await unwrap<PageResult<PostSummary> | PostSummary[]>(
    api.get(`/tags/${encodeURIComponent(tag)}`, { params: { page, size } }))
  return asPage(data, page, size)
}

// 4B：合集公开读
export interface SeriesEntryItem {
  postId: number
  slug: string
  title: string
  date: string
  chapterTitle: string | null
  position: number
}

export interface SeriesSummary {
  slug: string
  name: string
  description: string
  coverImage: string | null
  entryCount: number
  publishedAt: string | null
}

export interface SeriesDetail {
  slug: string
  name: string
  description: string
  coverImage: string | null
  publishedAt: string | null
  entries: SeriesEntryItem[]
}

export function fetchSeriesList() {
  return unwrap<SeriesSummary[]>(api.get('/series'))
}

export function fetchSeriesDetail(slug: string) {
  return unwrap<SeriesDetail>(api.get(`/series/${encodeURIComponent(slug)}`))
}

export interface SearchGroup {
  articles: SearchHit[]
  notes: SearchHit[]
  dishes: SearchHit[]
  total: number
}

export interface PostSearchPage {
  results: SearchHit[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface SearchPostsOptions {
  categorySlug?: string
  sort?: 'asc' | 'desc'
}

// NF-5：归档搜索走 POST /search 的分页模式（type=POST），覆盖全部已发布文章而非仅当前页。
// L-8：categorySlug/sort 下推服务端，分页计数与过滤条件一致。
export async function searchPosts(q: string, page = 0, size = 6, options: SearchPostsOptions = {}): Promise<PostSearchPage> {
  const data = await unwrap<PostSearchPage>(api.post('/search', {
    query: q.trim(), type: 'POST', page, size,
    ...(options.categorySlug ? { categorySlug: options.categorySlug } : {}),
    ...(options.sort ? { sort: options.sort === 'asc' ? 'DATE_ASC' : 'DATE_DESC' } : {}),
  }))
  return {
    results: data.results ?? [],
    page: data.page ?? page,
    size: data.size ?? size,
    totalElements: data.totalElements ?? 0,
    totalPages: Math.max(1, data.totalPages ?? 1),
  }
}

export async function searchContent(q: string, limit = 10, signal?: AbortSignal): Promise<SearchGroup> {
  if (!q.trim()) return { articles: [], notes: [], dishes: [], total: 0 }
  const data = await unwrap<SearchGroup>(api.get('/search', {
    params: { q: q.trim(), limit },
    signal,
  }))
  return {
    articles: data.articles ?? [],
    notes: data.notes ?? [],
    dishes: data.dishes ?? [],
    total: data.total ?? 0,
  }
}
