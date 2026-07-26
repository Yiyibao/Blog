import axios from 'axios'
import type { Dish, PageResult, Post, PostStatus, PostSummary } from '../data'
import { useAuthStore } from '../stores/auth'
import type { LoginResult } from '../stores/auth'

export type { LoginResult }

interface ApiEnvelope<T> {
  data: T
  timestamp: string
}

export interface AdminPost extends Post {
  id: number
}

// P1-2：管理端列表为摘要 DTO（不含 content），编辑前必须经 fetchAdminPost 拉取全文。
export type AdminPostSummary = PostSummary & { id: number }

export interface PostPayload extends Omit<AdminPost, 'id'> {}

export interface AdminDish extends Dish {}
// favoriteCount 只经收藏端点原子自增，管理端编辑不提交也不覆盖（后端 DishRequest 亦无此字段）
export type DishPayload = Omit<AdminDish, 'id' | 'createdAt' | 'updatedAt' | 'favoriteCount'>

export type NoteStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'

// P1-2：笔记列表为摘要 DTO（不含 markdownContent），正文经 fetchAdminNote 详情获取。
export interface AdminNoteSummary {
  id: number
  title: string
  folder: string
  status: NoteStatus
  tags: string[]
  sourceFileName: string | null
  wordCount: number
  version: number
  createdAt: string
  updatedAt: string
}

export interface AdminNote extends AdminNoteSummary {
  markdownContent: string
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

// 管理端登录态的单一事实源是 Pinia useAuthStore（NF-1）。
// 本模块不再直接读写 sessionStorage，全部委托给 store，
// 保证路由守卫、登录页与 API 拦截器看到的是同一份状态。

api.interceptors.request.use((config) => {
  const auth = useAuthStore()
  if (auth.token && auth.expiresAt && Date.parse(auth.expiresAt) <= Date.now()) {
    auth.clearSession()
    return Promise.reject(new axios.Cancel('登录已过期'))
  }
  if (auth.token) {
    config.headers = config.headers ?? {}
    config.headers.Authorization = `Bearer ${auth.token}`
  }
  return config
})

api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (axios.isAxiosError(error) && error.response?.status === 401) useAuthStore().clearSession()
    return Promise.reject(error)
  },
)

export function clearAdminSession() {
  useAuthStore().clearSession()
}

export function saveAdminSession(result: LoginResult, options: { remember?: boolean } = {}) {
  useAuthStore().saveSession(result, options)
}

export function getAdminSessionName() {
  return useAuthStore().username
}

export function hasValidAdminSession() {
  return useAuthStore().isAuthenticated
}

function tokenHeader() {
  const token = useAuthStore().token
  return token ? { Authorization: `Bearer ${token}` } : {}
}

async function unwrap<T>(request: Promise<{ data: ApiEnvelope<T> }>): Promise<T> {
  return (await request).data.data
}

/** L-7：登录人机验证 challenge；type 为 IMAGE 时需展示 captchaImage 并提交答案。 */
export interface LoginChallenge {
  challengeId: string
  type: 'POW' | 'IMAGE'
  salt: string
  difficulty: number
  captchaImage: string | null
}

export interface LoginVerification {
  challengeId: string
  nonce: string
  captchaAnswer?: string
}

export function fetchLoginChallenge(username?: string) {
  return unwrap<LoginChallenge>(
    api.get('/auth/challenge', { params: username ? { username } : undefined }),
  )
}

// FD-25：自助改密——成功后服务端推进 sessions_valid_from，本端应清会话重登
export function changePassword(currentPassword: string, newPassword: string) {
  return api.put('/auth/password', { currentPassword, newPassword })
}

// FD-9：remember=true 请求 24h 长 token，配合 authStore 的 localStorage 持久化
export function login(username: string, password: string, verification: LoginVerification, remember = false) {
  return unwrap<LoginResult>(api.post('/auth/login', { username, password, remember, ...verification }))
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
  const data = await unwrap<PageResult<AdminPostSummary> | AdminPostSummary[]>(api.get('/admin/posts', {
    headers: tokenHeader(),
    params: { page, size, ...(status ? { status } : {}) },
  }))
  return asPage(data, page, size)
}

export function fetchAdminPost(id: number) {
  return unwrap<AdminPost>(api.get(`/admin/posts/${id}`, { headers: tokenHeader() }))
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

// 4F：曲目与语录管理
export interface AdminMusicTrack {
  id: number
  trackId: string
  title: string
  artist: string
  duration: number
  audioUrl: string
  coverUrl: string
  sortOrder: number
  createdAt: string
}

export type MusicTrackPayload = Omit<AdminMusicTrack, 'id' | 'createdAt'>

export interface AdminQuote {
  id: number
  content: string
  author: string
  category: string
  displayOrder: number
  createdAt: string
}

export type QuotePayload = Omit<AdminQuote, 'id' | 'createdAt'>

export function fetchAdminTracks() {
  return unwrap<AdminMusicTrack[]>(api.get('/admin/library/tracks', { headers: tokenHeader() }))
}

export function createAdminTrack(payload: MusicTrackPayload) {
  return unwrap<AdminMusicTrack>(api.post('/admin/library/tracks', payload, { headers: tokenHeader() }))
}

export function updateAdminTrack(id: number, payload: MusicTrackPayload) {
  return unwrap<AdminMusicTrack>(api.put(`/admin/library/tracks/${id}`, payload, { headers: tokenHeader() }))
}

export function deleteAdminTrack(id: number) {
  return api.delete(`/admin/library/tracks/${id}`, { headers: tokenHeader() })
}

export function fetchAdminQuotes() {
  return unwrap<AdminQuote[]>(api.get('/admin/library/quotes', { headers: tokenHeader() }))
}

export function createAdminQuote(payload: QuotePayload) {
  return unwrap<AdminQuote>(api.post('/admin/library/quotes', payload, { headers: tokenHeader() }))
}

export function updateAdminQuote(id: number, payload: QuotePayload) {
  return unwrap<AdminQuote>(api.put(`/admin/library/quotes/${id}`, payload, { headers: tokenHeader() }))
}

export function deleteAdminQuote(id: number) {
  return api.delete(`/admin/library/quotes/${id}`, { headers: tokenHeader() })
}

// 3A-2：存量 HTML→Markdown 一次性转换（响应即人工校对清单）
export interface MarkdownConversionReport {
  id: number
  slug: string
  converted: boolean
  risks: string[]
}

export function convertPostsMarkdown(force = false) {
  return unwrap<MarkdownConversionReport[]>(api.post('/admin/posts/convert-markdown', null, {
    headers: tokenHeader(),
    params: force ? { force: true } : undefined,
  }))
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
  const data = await unwrap<PageResult<AdminNoteSummary> | AdminNoteSummary[]>(api.get('/admin/notes', {
    headers: tokenHeader(), params: { page, size, ...(status ? { status } : {}) },
  }))
  return asPage(data, page, size)
}

export function fetchAdminNote(id: number) {
  return unwrap<AdminNote>(api.get(`/admin/notes/${id}`, { headers: tokenHeader() }))
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

export async function exportNote(note: AdminNoteSummary) {
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

export interface AdminStats {
  posts: number
  dishes: number
  notes: number
}

export function fetchAdminStats() {
  return unwrap<AdminStats>(api.get('/admin/stats', { headers: tokenHeader() }))
}

export type AiChatRole = 'user' | 'assistant'

export interface AiChatMessage {
  role: AiChatRole
  content: string
}

export interface AiChatResult {
  content: string
  model: string
  usage?: {
    promptTokens: number
    completionTokens: number
    totalTokens: number
  } | null
}

export class AiStreamHttpError extends Error {
  status: number

  constructor(status: number, message: string) {
    super(message)
    this.name = 'AiStreamHttpError'
    this.status = status
  }
}

export interface AiStreamDone {
  model?: string
  usage?: AiChatResult['usage']
}

export interface AiStreamCallbacks {
  onDelta: (text: string) => void
  onDone?: (info: AiStreamDone) => void
}

export interface AiStreamOptions {
  providerId?: number | null
  model?: string | null
  signal?: AbortSignal
}

// 4A-2：SSE 流式对话。EventSource 无法携带 Authorization 头，
// 改用 fetch + ReadableStream 手工解析 SSE，JWT 走标准请求头、绝不进 URL。
// 建流前的校验错误以普通 HTTP 错误返回；建流后的错误以 error 事件抛出。
export async function streamAiChat(
  messages: AiChatMessage[],
  callbacks: AiStreamCallbacks,
  options: AiStreamOptions = {},
): Promise<void> {
  const auth = useAuthStore()
  if (auth.token && auth.expiresAt && Date.parse(auth.expiresAt) <= Date.now()) {
    auth.clearSession()
    throw new AiStreamHttpError(401, '登录已过期')
  }
  const base = import.meta.env.VITE_API_BASE_URL || '/api/v1'
  const response = await fetch(`${base}/admin/ai/chat/stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...(auth.token ? { Authorization: `Bearer ${auth.token}` } : {}),
    },
    body: JSON.stringify({
      messages,
      ...(options.providerId != null ? { providerId: options.providerId } : {}),
      ...(options.model ? { model: options.model } : {}),
    }),
    signal: options.signal,
  })
  if (response.status === 401) {
    auth.clearSession()
    throw new AiStreamHttpError(401, '未登录或登录已过期')
  }
  if (!response.ok || !response.body) {
    let message = 'AI 响应失败'
    try {
      const parsed: unknown = await response.json()
      if (parsed && typeof parsed === 'object'
        && typeof (parsed as { message?: unknown }).message === 'string') {
        message = (parsed as { message: string }).message
      }
    } catch {
      // 非 JSON 错误体，使用默认文案
    }
    throw new AiStreamHttpError(response.status, message)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let currentEvent = ''

  const handleLine = (rawLine: string) => {
    const line = rawLine.replace(/\r$/, '')
    if (line === '') {
      // SSE 事件边界：事件类型复位，避免粘滞到下一个事件
      currentEvent = ''
      return
    }
    if (line.startsWith('event:')) {
      currentEvent = line.slice(6).trim()
      return
    }
    if (!line.startsWith('data:')) return
    const payload = line.slice(5).trim()
    if (!payload) return
    let parsed: unknown
    try {
      parsed = JSON.parse(payload)
    } catch {
      return
    }
    if (!parsed || typeof parsed !== 'object') return
    const record = parsed as { content?: unknown; status?: unknown; message?: unknown }
    const eventType = currentEvent
    currentEvent = ''
    if (eventType === 'delta' && typeof record.content === 'string') {
      callbacks.onDelta(record.content)
    } else if (eventType === 'done') {
      callbacks.onDone?.(parsed as AiStreamDone)
    } else if (eventType === 'error') {
      const status = typeof record.status === 'number' ? record.status : 502
      const detail = typeof record.message === 'string' ? record.message : 'AI 响应失败'
      throw new AiStreamHttpError(status, detail)
    }
  }

  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let newlineIndex = buffer.indexOf('\n')
      while (newlineIndex >= 0) {
        const line = buffer.slice(0, newlineIndex)
        buffer = buffer.slice(newlineIndex + 1)
        handleLine(line)
        newlineIndex = buffer.indexOf('\n')
      }
    }
    if (buffer) handleLine(buffer)
  } finally {
    // error 事件抛出或调用方中止时释放底层连接，避免 reader 悬挂
    reader.cancel().catch(() => {})
  }
}

// 4A-3：AI 供应商管理。密钥只写不回显——响应仅含 hasKey 与 keyTail（尾 4 位）。
export interface AiProvider {
  id: number
  name: string
  baseUrl: string
  models: string[]
  defaultModel: string
  enabled: boolean
  isDefault: boolean
  hasKey: boolean
  keyTail: string | null
  dailyRequestLimit: number
  dailyTokenLimit: number
  createdAt: string
  updatedAt: string
}

export interface AiProviderPayload {
  name: string
  baseUrl: string
  /** 新建可留空（无鉴权端点）；编辑时省略或留空表示保留原密钥。 */
  apiKey?: string
  models: string[]
  defaultModel: string
  enabled: boolean
  dailyRequestLimit: number
  dailyTokenLimit: number
}

export interface AiProviderTestResult {
  ok: boolean
  message: string
  models: string[]
}

export function fetchAiProviders() {
  return unwrap<AiProvider[]>(api.get('/admin/ai/providers', { headers: tokenHeader() }))
}

export function createAiProvider(payload: AiProviderPayload) {
  return unwrap<AiProvider>(api.post('/admin/ai/providers', payload, { headers: tokenHeader() }))
}

export function updateAiProvider(id: number, payload: AiProviderPayload) {
  return unwrap<AiProvider>(api.put(`/admin/ai/providers/${id}`, payload, { headers: tokenHeader() }))
}

export function deleteAiProvider(id: number) {
  return api.delete(`/admin/ai/providers/${id}`, { headers: tokenHeader() })
}

export function setDefaultAiProvider(id: number) {
  return unwrap<AiProvider>(api.put(`/admin/ai/providers/${id}/default`, null, { headers: tokenHeader() }))
}

// 连通测试由后端代发一次最小上游请求，可能较慢，放宽超时。
export function testAiProvider(id: number) {
  return unwrap<AiProviderTestResult>(
    api.post(`/admin/ai/providers/${id}/test`, null, { timeout: 30000, headers: tokenHeader() }),
  )
}
