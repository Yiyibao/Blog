import { describe, it, expect, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { classifyError, isKitchenError } from '../api/kitchen'
import { useAuthStore } from '../stores/auth'

function axiosLikeError(status: number | undefined, message?: string, headers: Record<string, string> = {}) {
  return Object.assign(new Error(message ?? 'boom'), {
    isAxiosError: true,
    response: status === undefined ? undefined : { status, data: message ? { message } : {}, headers },
  })
}

beforeEach(() => {
  sessionStorage.clear()
  localStorage.clear()
  setActivePinia(createPinia())
})

describe('FD-12 kitchen 错误分类', () => {
  it('429 提取 Retry-After 秒数，缺失时默认 60', () => {
    const limited = classifyError(axiosLikeError(429, '太快了', { 'retry-after': '90' }))
    expect(limited.kind).toBe('rate-limited')
    expect(limited.retryAfterSeconds).toBe(90)
    expect(limited.message).toBe('太快了')
    expect(classifyError(axiosLikeError(429)).retryAfterSeconds).toBe(60)
  })

  it('409 归类 conflict 并透传服务端文案', () => {
    const conflict = classifyError(axiosLikeError(409, '菜单刚被对方更新过，请刷新后再提交'))
    expect(conflict.kind).toBe('conflict')
    expect(conflict.message).toContain('对方')
  })

  it('401/403/404/400 各归其类', () => {
    expect(classifyError(axiosLikeError(401)).kind).toBe('auth')
    expect(classifyError(axiosLikeError(403)).kind).toBe('forbidden')
    expect(classifyError(axiosLikeError(404)).kind).toBe('not-found')
    expect(classifyError(axiosLikeError(400, '日期格式不对')).message).toContain('日期')
  })

  it('无响应体的网络错误归 network，非 axios 异常归 server', () => {
    expect(classifyError(axiosLikeError(undefined)).kind).toBe('network')
    expect(classifyError(new Error('random')).kind).toBe('server')
  })

  it('已分类错误原样透传（幂等）', () => {
    const original = classifyError(axiosLikeError(409))
    expect(classifyError(original)).toBe(original)
    expect(isKitchenError(original)).toBe(true)
  })

  it('过期会话由请求拦截器同步清除（authStore 事实源）', async () => {
    const auth = useAuthStore()
    auth.saveSession({
      token: 'stale', tokenType: 'Bearer', username: 'gf',
      expiresAt: '2000-01-01T00:00:00Z', role: 'PARTNER', displayName: '小伙伴',
    })
    // isAuthenticated 读取即触发过期清理（saveSession 允许写入过期值）
    expect(auth.isAuthenticated).toBe(false)
    expect(auth.token).toBeNull()
  })
})
