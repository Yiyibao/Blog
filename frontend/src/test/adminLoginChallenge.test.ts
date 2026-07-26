import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import { AxiosError, AxiosHeaders } from 'axios'
import AdminLogin from '../components/AdminLogin.vue'
import { useAuthStore } from '../stores/auth'

const mockLogin = vi.fn()
const mockFetchChallenge = vi.fn()

vi.mock('../api/admin', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/admin')>()
  return {
    ...actual,
    login: (...args: unknown[]) => mockLogin(...args),
    fetchLoginChallenge: (...args: unknown[]) => mockFetchChallenge(...args),
  }
})

vi.mock('../utils/pow', () => ({
  solvePow: vi.fn().mockResolvedValue('42'),
}))

const POW_CHALLENGE = {
  challengeId: 'pow-1',
  type: 'POW' as const,
  salt: 'aabb',
  difficulty: 4,
  captchaImage: null,
}

const IMAGE_CHALLENGE = {
  challengeId: 'img-1',
  type: 'IMAGE' as const,
  salt: 'ccdd',
  difficulty: 4,
  captchaImage: 'data:image/png;base64,fake',
}

const LOGIN_RESULT = {
  token: 'fresh-token',
  tokenType: 'Bearer',
  username: 'gxynf',
  expiresAt: '2099-12-31T23:59:59Z',
}

function axiosErrorWithStatus(status: number, message?: string, headers: Record<string, string> = {}): AxiosError {
  const error = new AxiosError('Request failed', String(status))
  error.response = {
    status,
    statusText: '',
    data: message ? { status, message } : {},
    headers,
    config: { headers: new AxiosHeaders() },
  }
  return error
}

function createTestRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div>Home</div>' } },
      { path: '/admin/login', name: 'admin-login', component: { template: '<div>Login</div>' } },
      { path: '/admin', name: 'admin', component: { template: '<div>Dashboard</div>' } },
    ],
  })
}

async function mountLogin(): Promise<{ wrapper: VueWrapper; router: Router }> {
  sessionStorage.clear()
  setActivePinia(createPinia())
  // auth.ts 的 memorySession 模块级缓存跨用例存活，经 clearSession 一并清除
  useAuthStore().clearSession()
  const router = createTestRouter()
  await router.push('/admin/login')
  await router.isReady()
  const wrapper = mount(AdminLogin, { global: { plugins: [router] } })
  await wrapper.find('input[autocomplete="username"]').setValue('gxynf')
  await wrapper.find('input[type="password"]').setValue('secret')
  return { wrapper, router }
}

// L-15：弹窗 Teleport 到 body，交互经 document 查询
function modalEl(): HTMLElement | null {
  return document.body.querySelector('.verify-modal')
}

function clickInModal(selector: string) {
  const btn = document.body.querySelector<HTMLElement>(selector)
  expect(btn, `modal element ${selector} should exist`).toBeTruthy()
  btn!.click()
}

/** 提交表单开弹窗 → 点「我不是机器人」→ 推进打勾停留计时。 */
async function submitAndVerify(wrapper: VueWrapper) {
  await wrapper.find('form').trigger('submit.prevent')
  await flushPromises()
  clickInModal('.verify-start')
  await flushPromises()
  vi.advanceTimersByTime(700)
  await flushPromises()
}

beforeEach(() => {
  vi.useFakeTimers()
  document.body.innerHTML = ''
  mockLogin.mockReset()
  mockFetchChallenge.mockReset()
})

afterEach(() => {
  vi.useRealTimers()
})

describe('L-15 人机验证弹窗状态机（复用 L-7 三层协议）', () => {
  it('层 1：点击「我不是机器人」→ PoW 打勾 → 登录携带 challengeId 与 nonce', async () => {
    mockFetchChallenge.mockResolvedValue(POW_CHALLENGE)
    mockLogin.mockResolvedValue(LOGIN_RESULT)
    const { wrapper, router } = await mountLogin()

    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()
    // 提交先开弹窗，不直接登录
    expect(modalEl()).toBeTruthy()
    expect(mockLogin).not.toHaveBeenCalled()

    clickInModal('.verify-start')
    await flushPromises()
    // 打勾态出现后延时回传
    expect(document.body.querySelector('.verify-check')).toBeTruthy()
    vi.advanceTimersByTime(700)
    await flushPromises()

    expect(mockFetchChallenge).toHaveBeenCalledWith('gxynf')
    expect(mockLogin).toHaveBeenCalledWith('gxynf', 'secret', {
      challengeId: 'pow-1',
      nonce: '42',
    }, false)
    expect(router.currentRoute.value.name).toBe('admin')
  })

  it('层 2：challenge 升级为 IMAGE 时同弹窗展示图形码，确认后携带答案', async () => {
    mockFetchChallenge.mockResolvedValue(IMAGE_CHALLENGE)
    mockLogin.mockResolvedValue(LOGIN_RESULT)
    const { wrapper } = await mountLogin()

    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()
    clickInModal('.verify-start')
    await flushPromises()

    const img = document.body.querySelector<HTMLImageElement>('.verify-captcha-image img')
    expect(img?.getAttribute('src')).toBe('data:image/png;base64,fake')
    expect(mockLogin).not.toHaveBeenCalled()

    const input = document.body.querySelector<HTMLInputElement>('#verify-captcha-answer')!
    input.value = 'AB3CD'
    input.dispatchEvent(new Event('input'))
    await flushPromises()
    clickInModal('.verify-primary')
    await flushPromises()
    vi.advanceTimersByTime(700)
    await flushPromises()

    expect(mockLogin).toHaveBeenCalledWith('gxynf', 'secret', {
      challengeId: 'img-1',
      nonce: '42',
      captchaAnswer: 'AB3CD',
    }, false)
  })

  it('「换一张」重新取 challenge（一次性协议）并清空已填答案', async () => {
    mockFetchChallenge
      .mockResolvedValueOnce(IMAGE_CHALLENGE)
      .mockResolvedValueOnce({ ...IMAGE_CHALLENGE, challengeId: 'img-2', captchaImage: 'data:image/png;base64,fake2' })
    const { wrapper } = await mountLogin()

    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()
    clickInModal('.verify-start')
    await flushPromises()

    const input = document.body.querySelector<HTMLInputElement>('#verify-captcha-answer')!
    input.value = 'WRONG'
    input.dispatchEvent(new Event('input'))
    await flushPromises()

    clickInModal('.verify-secondary')
    await flushPromises()

    expect(mockFetchChallenge).toHaveBeenCalledTimes(2)
    const img = document.body.querySelector<HTMLImageElement>('.verify-captcha-image img')
    expect(img?.getAttribute('src')).toBe('data:image/png;base64,fake2')
    expect(document.body.querySelector<HTMLInputElement>('#verify-captcha-answer')!.value).toBe('')
  })

  it('关闭弹窗 = 中止登录：不发登录请求且无错误提示', async () => {
    mockFetchChallenge.mockResolvedValue(POW_CHALLENGE)
    const { wrapper } = await mountLogin()

    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()
    clickInModal('.verify-close')
    await flushPromises()

    expect(modalEl()).toBeFalsy()
    expect(mockLogin).not.toHaveBeenCalled()
    expect(wrapper.find('.admin-error').exists()).toBe(false)
  })

  it('层 3：challenge 冷却 429 在弹窗内展示剩余秒数并倒计时', async () => {
    mockFetchChallenge.mockRejectedValue(
      axiosErrorWithStatus(429, '失败次数过多，账号保护已启动', { 'retry-after': '30' }))
    const { wrapper } = await mountLogin()

    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()
    clickInModal('.verify-start')
    await flushPromises()

    expect(document.body.textContent).toContain('账号保护已启动')
    expect(document.body.querySelector('.verify-cooldown')?.textContent).toContain('30')
    vi.advanceTimersByTime(2000)
    await flushPromises()
    expect(document.body.querySelector('.verify-cooldown')?.textContent).toContain('28')
    expect(mockLogin).not.toHaveBeenCalled()
  })

  it('登录 401/400/429 的错误分级文案回到表单区', async () => {
    mockFetchChallenge.mockResolvedValue(POW_CHALLENGE)
    mockLogin.mockRejectedValueOnce(axiosErrorWithStatus(401))
    const { wrapper } = await mountLogin()

    await submitAndVerify(wrapper)
    expect(wrapper.find('.admin-error').text()).toContain('用户名或密码不正确')

    mockLogin.mockRejectedValueOnce(axiosErrorWithStatus(400))
    await submitAndVerify(wrapper)
    expect(wrapper.find('.admin-error').text()).toContain('人机验证未通过')

    mockLogin.mockRejectedValueOnce(axiosErrorWithStatus(429, '尝试过于频繁，请 30 分钟后再试'))
    await submitAndVerify(wrapper)
    expect(wrapper.find('.admin-error').text()).toContain('尝试过于频繁')
  })
})
