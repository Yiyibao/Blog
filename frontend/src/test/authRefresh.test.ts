import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

const http = vi.hoisted(() => ({
  adapter: null as null | ((config: Record<string, any>) => Promise<any>),
  requestInterceptor: null as null | ((config: Record<string, any>) => Record<string, any>),
  responseRejected: null as null | ((error: any) => Promise<any>),
}))

vi.mock('axios', async (importOriginal) => {
  const actual = await importOriginal<typeof import('axios')>()
  const request = async (config: Record<string, any>): Promise<any> => {
    const prepared = http.requestInterceptor ? await http.requestInterceptor(config) : config
    try {
      return await http.adapter!(prepared)
    } catch (error) {
      if (http.responseRejected) return http.responseRejected(error)
      throw error
    }
  }
  const instance = {
    interceptors: {
      request: { use: (fulfilled: typeof http.requestInterceptor) => { http.requestInterceptor = fulfilled } },
      response: { use: (_fulfilled: unknown, rejected: typeof http.responseRejected) => { http.responseRejected = rejected } },
    },
    request,
    get: (url: string, config: Record<string, any> = {}) => request({ ...config, url, method: 'get', headers: config.headers ?? {} }),
    post: (url: string, data?: unknown, config: Record<string, any> = {}) => request({ ...config, url, data, method: 'post', headers: config.headers ?? {} }),
    put: (url: string, data?: unknown, config: Record<string, any> = {}) => request({ ...config, url, data, method: 'put', headers: config.headers ?? {} }),
    delete: (url: string, config: Record<string, any> = {}) => request({ ...config, url, method: 'delete', headers: config.headers ?? {} }),
  }
  return {
    ...actual,
    default: {
      ...actual.default,
      create: () => instance,
      post: (url: string, data?: unknown, config: Record<string, any> = {}) =>
        http.adapter!({ ...config, url, data, method: 'post', headers: config.headers ?? {} }),
      isAxiosError: actual.default.isAxiosError,
    },
  }
})

import * as admin from '../api/admin'
import { useAuthStore } from '../stores/auth'

const REFRESH_RESULT = {
  token: 'new-token', tokenType: 'Bearer', username: 'admin',
  expiresAt: '2099-12-31T23:59:59Z', role: 'ADMIN', displayName: '站长',
}

function ok(config: Record<string, any>, data: unknown) {
  return { data, status: 200, statusText: 'OK', headers: {}, config }
}

function unauthorized(config: Record<string, any>) {
  return { isAxiosError: true, message: 'Unauthorized', config, response: { status: 401, data: {}, config } }
}

beforeEach(() => {
  sessionStorage.clear()
  localStorage.clear()
  setActivePinia(createPinia())
  useAuthStore().clearSession()
})

describe('6C-1 refresh flow', () => {
  it('returns a TOTP challenge from a 202 login response', async () => {
    http.adapter = async (config) => ({
      data: { data: { challengeId: 'totp-challenge' } },
      status: 202,
      statusText: 'Accepted',
      headers: {},
      config,
    })

    await expect(admin.login('admin', 'secret', { challengeId: 'human', nonce: '0' }))
      .resolves.toEqual({ totpRequired: true, challengeId: 'totp-challenge' })
  })

  it('coalesces concurrent 401 responses into one refresh and replays both requests', async () => {
    expect(http.requestInterceptor).not.toBeNull()
    let refreshCalls = 0
    let protectedCalls = 0
    http.adapter = async (config) => {
      if (config.url.endsWith('/auth/refresh')) {
        refreshCalls++
        return ok(config, { data: REFRESH_RESULT })
      }
      protectedCalls++
      if (config.headers.Authorization !== 'Bearer new-token') throw unauthorized(config)
      return ok(config, { data: { items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 } })
    }
    useAuthStore().saveSession({ ...REFRESH_RESULT, token: 'stale-token' })

    await Promise.all([admin.fetchAdminPosts(), admin.fetchAdminPosts()])

    expect(refreshCalls).toBe(1)
    expect(protectedCalls).toBe(4)
    expect(useAuthStore().token).toBe('new-token')
  })

  it('clears state when refresh fails without recursively calling refresh', async () => {
    let refreshCalls = 0
    http.adapter = async (config) => {
      if (config.url.endsWith('/auth/refresh')) refreshCalls++
      throw unauthorized(config)
    }
    useAuthStore().saveSession({ ...REFRESH_RESULT, token: 'stale-token' })

    await expect(admin.fetchAdminPosts()).rejects.toMatchObject({ isAxiosError: true })
    expect(refreshCalls).toBe(1)
    expect(useAuthStore().token).toBeNull()
  })

  it('does not refresh after a login 401', async () => {
    let refreshCalls = 0
    http.adapter = async (config) => {
      if (config.url.endsWith('/auth/refresh')) refreshCalls++
      throw unauthorized(config)
    }

    await expect(admin.login('bad', 'bad', { challengeId: 'id', nonce: '0' })).rejects.toMatchObject({ isAxiosError: true })
    expect(refreshCalls).toBe(0)
  })

  it('shares one refresh between route recovery and a concurrent API 401', async () => {
    let refreshCalls = 0
    let releaseRefresh!: () => void
    const refreshGate = new Promise<void>(resolve => { releaseRefresh = resolve })
    http.adapter = async (config) => {
      if (config.url.endsWith('/auth/refresh')) {
        refreshCalls++
        await refreshGate
        return ok(config, { data: REFRESH_RESULT })
      }
      if (config.headers.Authorization === 'Bearer new-token') {
        return ok(config, { data: { items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 } })
      }
      throw unauthorized(config)
    }

    const routeRecovery = admin.refreshSession()
    await Promise.resolve()
    const apiRequest = admin.fetchAdminPosts()
    await Promise.resolve()
    expect(refreshCalls).toBe(1)
    releaseRefresh()
    const results = await Promise.allSettled([routeRecovery, apiRequest])
    expect(results[0]).toMatchObject({ status: 'fulfilled', value: true })
    expect(results[1].status).toBe('fulfilled')
    expect(refreshCalls).toBe(1)
  })

  it('recovers an authenticated route from the refresh cookie on startup', async () => {
    let refreshCalls = 0
    http.adapter = async (config) => {
      if (config.url.endsWith('/auth/refresh')) {
        refreshCalls++
        return ok(config, { data: REFRESH_RESULT })
      }
      throw new Error(`Unexpected request: ${config.url}`)
    }
    const { default: router } = await import('../router')

    await router.push('/notes')
    await router.isReady()

    expect(router.currentRoute.value.path).toBe('/notes')
    expect(refreshCalls).toBe(1)
    expect(useAuthStore().isAuthenticated).toBe(true)
  })
})
