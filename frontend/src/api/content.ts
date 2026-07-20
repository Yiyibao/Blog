import axios from 'axios'
import type { Post, Project } from '../data'

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

export function fetchPosts() {
  return unwrap<Post[]>(api.get('/posts'))
}

export function fetchProjects() {
  return unwrap<Project[]>(api.get('/projects'))
}
