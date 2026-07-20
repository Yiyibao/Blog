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
