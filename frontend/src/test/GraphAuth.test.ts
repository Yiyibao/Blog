import { describe, it, expect, beforeEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '../stores/auth'

interface AxiosReqConfig {
  url?: string
  headers?: Record<string, string>
  params?: Record<string, unknown>
}

const mockGet = vi.fn()
const requestInterceptors: Array<(config: AxiosReqConfig) => AxiosReqConfig> = []
let getHandler: ((...args: unknown[]) => unknown) | null = null

vi.mock('axios', () => {
  const createInstance = () => {
    const instance = {
      get: (url: string, config?: AxiosReqConfig) => {
        let finalConfig: AxiosReqConfig = { headers: {}, ...config }
        for (const interceptor of requestInterceptors) {
          finalConfig = interceptor({ url, headers: {}, ...finalConfig })
        }
        return getHandler ? (getHandler as CallableFunction)(url, finalConfig) : mockGet(url, finalConfig)
      },
      post: vi.fn(),
      interceptors: {
        request: { use: (fn: (c: AxiosReqConfig) => AxiosReqConfig) => { requestInterceptors.push(fn) } },
        response: { use: vi.fn() },
      },
    }
    return instance
  }
  return {
    default: {
      create: createInstance,
      isAxiosError: (e: unknown) => e instanceof Error,
    },
  }
})

describe('Graph API Auth Headers', () => {
  beforeEach(() => {
    mockGet.mockReset()
    setActivePinia(createPinia())
    useAuthStore().clearSession()
  })

  it('游客调用 fetchGraphSubgraph 不带 Authorization', async () => {
    mockGet.mockResolvedValue({ data: { data: { nodes: [], edges: [] }, timestamp: '' } })
    const { fetchGraphSubgraph } = await import('../api/content')
    await fetchGraphSubgraph('test-center', 2)
    expect(mockGet).toHaveBeenCalled()
    const [url, config] = mockGet.mock.calls[0]
    expect(url).toContain(encodeURIComponent('test-center'))
    expect(config?.headers?.Authorization).toBeUndefined()
  })

  it('登录用户调用 fetchGraphSubgraph 带 Bearer token', async () => {
    useAuthStore().saveSession({
      token: 'my-token-123',
      tokenType: 'Bearer',
      username: 'test',
      expiresAt: '2099-12-31T23:59:59Z',
    })
    mockGet.mockResolvedValue({ data: { data: { nodes: [], edges: [] }, timestamp: '' } })
    const { fetchGraphSubgraph } = await import('../api/content')
    await fetchGraphSubgraph('center-a', 2)
    expect(mockGet).toHaveBeenCalled()
    const [, config] = mockGet.mock.calls[0]
    expect(config?.headers?.Authorization).toBe('Bearer my-token-123')
  })

  it('过期 token 不携带 Authorization', async () => {
    useAuthStore().saveSession({
      token: 'expired-token',
      tokenType: 'Bearer',
      username: 'test',
      expiresAt: '2020-01-01T00:00:00Z',
    })
    mockGet.mockResolvedValue({ data: { data: { nodes: [], edges: [] }, timestamp: '' } })
    const { fetchGraphSubgraph } = await import('../api/content')
    await fetchGraphSubgraph('center-b', 2)
    expect(mockGet).toHaveBeenCalled()
    const [, config] = mockGet.mock.calls[0]
    expect(config?.headers?.Authorization).toBeUndefined()
  })

  it('游客调用 fetchGraphNodes 不带 Authorization', async () => {
    mockGet.mockResolvedValue({ data: { data: { nodes: [], edges: [] }, timestamp: '' } })
    const { fetchGraphNodes } = await import('../api/content')
    await fetchGraphNodes()
    expect(mockGet).toHaveBeenCalled()
    const [, config] = mockGet.mock.calls[0]
    expect(config?.headers?.Authorization).toBeUndefined()
  })

  it('登录用户调用 fetchGraphNodes 带 Bearer token', async () => {
    useAuthStore().saveSession({
      token: 'graph-node-token',
      tokenType: 'Bearer',
      username: 'test',
      expiresAt: '2099-12-31T23:59:59Z',
    })
    mockGet.mockResolvedValue({ data: { data: { nodes: [], edges: [] }, timestamp: '' } })
    const { fetchGraphNodes } = await import('../api/content')
    await fetchGraphNodes()
    expect(mockGet).toHaveBeenCalled()
    const [, config] = mockGet.mock.calls[0]
    expect(config?.headers?.Authorization).toBe('Bearer graph-node-token')
  })

  it('非图谱 API 不携带 Authorization', async () => {
    useAuthStore().saveSession({
      token: 'non-graph-token',
      tokenType: 'Bearer',
      username: 'test',
      expiresAt: '2099-12-31T23:59:59Z',
    })
    mockGet.mockResolvedValue({ data: { data: [], timestamp: '' } })
    const { fetchCategories } = await import('../api/content')
    await fetchCategories()
    expect(mockGet).toHaveBeenCalled()
    const [, config] = mockGet.mock.calls[0]
    expect(config?.headers?.Authorization).toBeUndefined()
  })
})
