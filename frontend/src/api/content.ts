import axios from 'axios'
import type { Dish, PageResult, Post, SearchHit } from '../data'
import type { AdminNote } from './admin'

interface ApiEnvelope<T> {
  data: T
  timestamp: string
}

const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api/v1',
  timeout: 5000,
  headers: { Accept: 'application/json' },
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

export async function fetchPosts(page = 0, size = 10) {
  const data = await unwrap<PageResult<Post> | Post[]>(api.get('/posts', { params: { page, size } }))
  return asPage(data, page, size)
}

export function fetchPost(slug: string) {
  return unwrap<Post>(api.get(`/posts/${encodeURIComponent(slug)}`))
}

export async function fetchDishes(page = 0, size = 12) {
  const data = await unwrap<PageResult<Dish> | Dish[]>(api.get('/dishes', { params: { page, size } }))
  return asPage(data, page, size)
}

export async function fetchPublishedNotes(page = 0, size = 20) {
  const data = await unwrap<PageResult<AdminNote> | AdminNote[]>(api.get('/notes', { params: { page, size } }))
  return asPage(data, page, size)
}

export function fetchPublishedNote(id: number) {
  return unwrap<AdminNote>(api.get(`/notes/${id}`))
}

export function fetchDish(slug: string) {
  return unwrap<Dish>(api.get(`/dishes/${encodeURIComponent(slug)}`))
}

export interface SearchGroup {
  articles: SearchHit[]
  notes: SearchHit[]
  dishes: SearchHit[]
  total: number
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
