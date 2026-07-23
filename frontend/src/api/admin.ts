import axios from 'axios'
import type { Dish, PageResult, Post, PostStatus } from '../data'

interface ApiEnvelope<T> {
  data: T
  timestamp: string
}

export interface LoginResult {
  token: string
  tokenType: string
  username: string
  expiresAt: string
}

export interface AdminPost extends Post {
  id: number
}

export interface PostPayload extends Omit<AdminPost, 'id'> {}

export interface AdminDish extends Dish {}
export type DishPayload = Omit<AdminDish, 'id' | 'createdAt' | 'updatedAt'>

export type NoteStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'

export interface AdminNote {
  id: number
  title: string
  markdownContent: string
  folder: string
  status: NoteStatus
  tags: string[]
  sourceFileName: string | null
  wordCount: number
  version: number
  createdAt: string
  updatedAt: string
}

export interface NotePayload {
  title: string
  markdownContent: string
  folder: string
  status: NoteStatus
  tags: string[]
  version: number
}

export interface NoteAttachment {
  id: number
  publicId: string
  noteId: number
  fileName: string
  mediaType: string
  byteSize: number
  url: string
  createdAt: string
}

const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api/v1',
  timeout: 8000,
  headers: { Accept: 'application/json' },
})

const memorySession = new Map<string, string>()

function readSessionValue(key: string) {
  try {
    return window.sessionStorage?.getItem(key) ?? memorySession.get(key) ?? null
  } catch {
    return memorySession.get(key) ?? null
  }
}

function writeSessionValue(key: string, value: string) {
  memorySession.set(key, value)
  try {
    window.sessionStorage?.setItem(key, value)
  } catch {
    // Some privacy modes disable sessionStorage; keep the session in memory.
  }
}

function removeSessionValue(key: string) {
  memorySession.delete(key)
  try {
    window.sessionStorage?.removeItem(key)
  } catch {
    // The in-memory session has already been cleared.
  }
}

api.interceptors.request.use((config) => {
  const token = readSessionValue('yubai-admin-token')
  const expiry = readSessionValue('yubai-admin-expiry')
  if (token && expiry && Date.parse(expiry) <= Date.now()) {
    clearAdminSession()
    return Promise.reject(new axios.Cancel('登录已过期'))
  }
  if (token) {
    config.headers = config.headers ?? {}
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (axios.isAxiosError(error) && error.response?.status === 401) clearAdminSession()
    return Promise.reject(error)
  },
)

export function clearAdminSession() {
  removeSessionValue('yubai-admin-token')
  removeSessionValue('yubai-admin-name')
  removeSessionValue('yubai-admin-expiry')
}

export function saveAdminSession(result: LoginResult) {
  writeSessionValue('yubai-admin-token', result.token)
  writeSessionValue('yubai-admin-name', result.username)
  writeSessionValue('yubai-admin-expiry', result.expiresAt)
}

export function getAdminSessionName() {
  return readSessionValue('yubai-admin-name')
}

export function hasValidAdminSession() {
  const token = readSessionValue('yubai-admin-token')
  const expiry = readSessionValue('yubai-admin-expiry')
  if (!token) return false
  if (expiry && Date.parse(expiry) <= Date.now()) {
    clearAdminSession()
    return false
  }
  return true
}

function tokenHeader() {
  const token = readSessionValue('yubai-admin-token')
  return token ? { Authorization: `Bearer ${token}` } : {}
}

async function unwrap<T>(request: Promise<{ data: ApiEnvelope<T> }>): Promise<T> {
  return (await request).data.data
}

export function login(username: string, password: string) {
  return unwrap<LoginResult>(api.post('/auth/login', { username, password }))
}

function asPage<T>(data: PageResult<T> | T[], page = 0, size = 20): PageResult<T> {
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

export async function fetchAdminPosts(page = 0, size = 20, status?: PostStatus | '') {
  const data = await unwrap<PageResult<AdminPost> | AdminPost[]>(api.get('/admin/posts', {
    headers: tokenHeader(),
    params: { page, size, ...(status ? { status } : {}) },
  }))
  return asPage(data, page, size)
}

export function createPost(payload: PostPayload) {
  return unwrap<AdminPost>(api.post('/admin/posts', payload, { headers: tokenHeader() }))
}

export function updatePost(id: number, payload: PostPayload) {
  return unwrap<AdminPost>(api.put(`/admin/posts/${id}`, payload, { headers: tokenHeader() }))
}

export function deletePost(id: number) {
  return api.delete(`/admin/posts/${id}`, { headers: tokenHeader() })
}

export async function fetchAdminDishes(page = 0, size = 20) {
  const data = await unwrap<PageResult<AdminDish> | AdminDish[]>(api.get('/admin/dishes', {
    headers: tokenHeader(), params: { page, size },
  }))
  return asPage(data, page, size)
}

export function createDish(payload: DishPayload) {
  return unwrap<AdminDish>(api.post('/admin/dishes', payload, { headers: tokenHeader() }))
}

export function updateDish(id: number, payload: DishPayload) {
  return unwrap<AdminDish>(api.put(`/admin/dishes/${id}`, payload, { headers: tokenHeader() }))
}

export function deleteDish(id: number) {
  return api.delete(`/admin/dishes/${id}`, { headers: tokenHeader() })
}

export async function fetchNotes(page = 0, size = 20, status?: NoteStatus | '') {
  const data = await unwrap<PageResult<AdminNote> | AdminNote[]>(api.get('/admin/notes', {
    headers: tokenHeader(), params: { page, size, ...(status ? { status } : {}) },
  }))
  return asPage(data, page, size)
}

export function createNote(payload: NotePayload) {
  return unwrap<AdminNote>(api.post('/admin/notes', payload, { headers: tokenHeader() }))
}

export function updateNote(id: number, payload: NotePayload) {
  return unwrap<AdminNote>(api.put(`/admin/notes/${id}`, payload, { headers: tokenHeader() }))
}

export function publishNote(id: number, version: number) {
  return unwrap<AdminNote>(api.put(`/admin/notes/${id}/publish`, { version }, { headers: tokenHeader() }))
}

export function unpublishNote(id: number, version: number) {
  return unwrap<AdminNote>(api.put(`/admin/notes/${id}/unpublish`, { version }, { headers: tokenHeader() }))
}

export function archiveNote(id: number, version: number) {
  return unwrap<AdminNote>(api.put(`/admin/notes/${id}/archive`, { version }, { headers: tokenHeader() }))
}

export function deleteNote(id: number) {
  return api.delete(`/admin/notes/${id}`, { headers: tokenHeader() })
}

export function importNote(file: File) {
  const body = new FormData()
  body.append('file', file)
  return unwrap<AdminNote>(api.post('/admin/notes/import', body, { headers: tokenHeader() }))
}

export async function exportNote(note: AdminNote) {
  const response = await api.get<Blob>(`/admin/notes/${note.id}/export`, {
    headers: tokenHeader(), responseType: 'blob',
  })
  const url = URL.createObjectURL(response.data)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `${note.title.replace(/[\\/:*?\"<>|]/g, '_')}.md`
  anchor.click()
  URL.revokeObjectURL(url)
}

export function fetchNoteAttachments(noteId: number) {
  return unwrap<NoteAttachment[]>(api.get(`/admin/notes/${noteId}/attachments`, { headers: tokenHeader() }))
}

export async function fetchNoteAttachmentContent(noteId: number, attachmentId: number) {
  const response = await api.get<Blob>(`/admin/notes/${noteId}/attachments/${attachmentId}/content`, {
    headers: tokenHeader(),
    responseType: 'blob',
  })
  return response.data
}

export function uploadNoteAttachment(noteId: number, file: File) {
  const body = new FormData()
  body.append('file', file)
  return unwrap<NoteAttachment>(api.post(`/admin/notes/${noteId}/attachments`, body, { headers: tokenHeader() }))
}

export function deleteNoteAttachment(noteId: number, attachmentId: number) {
  return api.delete(`/admin/notes/${noteId}/attachments/${attachmentId}`, { headers: tokenHeader() })
}
