import axios from 'axios'
import type { Post, Project } from '../data'

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

export interface AdminProject extends Project {
  id: number
  displayOrder: number
}

export interface PostPayload extends Omit<AdminPost, 'id'> {}
export interface ProjectPayload extends Omit<AdminProject, 'id'> {}

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

const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api/v1',
  timeout: 8000,
  headers: { Accept: 'application/json' },
})

function tokenHeader() {
  const token = sessionStorage.getItem('yubai-admin-token')
  return token ? { Authorization: `Bearer ${token}` } : {}
}

async function unwrap<T>(request: Promise<{ data: ApiEnvelope<T> }>): Promise<T> {
  return (await request).data.data
}

export function login(username: string, password: string) {
  return unwrap<LoginResult>(api.post('/auth/login', { username, password }))
}

export function fetchAdminPosts() {
  return unwrap<AdminPost[]>(api.get('/admin/posts', { headers: tokenHeader() }))
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

export function fetchAdminProjects() {
  return unwrap<AdminProject[]>(api.get('/admin/projects', { headers: tokenHeader() }))
}

export function createProject(payload: ProjectPayload) {
  return unwrap<AdminProject>(api.post('/admin/projects', payload, { headers: tokenHeader() }))
}

export function updateProject(id: number, payload: ProjectPayload) {
  return unwrap<AdminProject>(api.put(`/admin/projects/${id}`, payload, { headers: tokenHeader() }))
}

export function deleteProject(id: number) {
  return api.delete(`/admin/projects/${id}`, { headers: tokenHeader() })
}

export function fetchNotes() {
  return unwrap<AdminNote[]>(api.get('/admin/notes', { headers: tokenHeader() }))
}

export function createNote(payload: NotePayload) {
  return unwrap<AdminNote>(api.post('/admin/notes', payload, { headers: tokenHeader() }))
}

export function updateNote(id: number, payload: NotePayload) {
  return unwrap<AdminNote>(api.put(`/admin/notes/${id}`, payload, { headers: tokenHeader() }))
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
