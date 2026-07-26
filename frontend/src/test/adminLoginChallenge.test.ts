import { describe, it, expect, beforeEach, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import { AxiosError, AxiosHeaders } from 'axios'
import AdminLogin from '../components/AdminLogin.vue'

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

function axiosErrorWithStatus(status: number, message?: string): AxiosError {
  const error = new AxiosError('Request failed', String(status))
  error.response = {
    status,
    statusText: '',
    data: message ? { status, message } : {},
    headers: {},
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
  const router = createTestRouter()
  await router.push('/admin/login')
  await router.isReady()
  const wrapper = mount(AdminLogin, { global: { plugins: [router] } })
  await wrapper.find('input[autocomplete="username"]').setValue('gxynf')
  await wrapper.find('input[type="password"]').setValue('secret')
  return { wrapper, router }
}

beforeEach(() => {
  mockLogin.mockReset()
  mockFetchChallenge.mockReset()
})

describe('L-7 登录人机验证三层状态机', () => {
  it('层 1：纯 PoW 无感通过，登录请求携带 challengeId 与 nonce', async () => {
    mockFetchChallenge.mockResolvedValue(POW_CHALLENGE)
    mockLogin.mockResolvedValue(LOGIN_RESULT)
    const { wrapper, router } = await mountLogin()

    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    expect(mockFetchChallenge).toHaveBeenCalledWith('gxynf')
    expect(mockLogin).toHaveBeenCalledWith('gxynf', 'secret', {
      challengeId: 'pow-1',
      nonce: '42',
      captchaAnswer: undefined,
    }, false)
    expect(wrapper.find('.admin-captcha').exists()).toBe(false)
    expect(router.currentRoute.value.name).toBe('admin')
  })

  it('层 2：challenge 升级为 IMAGE 时先展示图形码，填写后携带答案登录', async () => {
    mockFetchChallenge.mockResolvedValue(IMAGE_CHALLENGE)
    mockLogin.mockResolvedValue(LOGIN_RESULT)
    const { wrapper } = await mountLogin()

    // 第一次提交：发现需要图形码，展示输入区且不发起登录
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()
    expect(mockLogin).not.toHaveBeenCalled()
    expect(wrapper.find('.admin-captcha').exists()).toBe(true)
    expect(wrapper.find('.admin-captcha img').attributes('src')).toBe('data:image/png;base64,fake')
    expect(wrapper.find('.admin-error').text()).toContain('图形验证码')

    // 填写答案后再次提交：复用未消费的 challenge（fetch 仅一次），携带答案
    await wrapper.find('#captcha-answer').setValue('AB3CD')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()
    expect(mockFetchChallenge).toHaveBeenCalledTimes(1)
    expect(mockLogin).toHaveBeenCalledWith('gxynf', 'secret', {
      challengeId: 'img-1',
      nonce: '42',
      captchaAnswer: 'AB3CD',
    }, false)
  })

  it('登录失败后重新取 challenge：升级为图形码时立即渲染', async () => {
    mockFetchChallenge.mockResolvedValueOnce(POW_CHALLENGE).mockResolvedValueOnce(IMAGE_CHALLENGE)
    mockLogin.mockRejectedValue(axiosErrorWithStatus(401))
    const { wrapper } = await mountLogin()

    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    expect(wrapper.find('.admin-error').text()).toContain('用户名或密码不正确')
    // 失败后预取到 IMAGE challenge → 图形码输入区已出现
    expect(mockFetchChallenge).toHaveBeenCalledTimes(2)
    expect(wrapper.find('.admin-captcha').exists()).toBe(true)
  })

  it('人机验证 400 给出重试提示', async () => {
    mockFetchChallenge.mockResolvedValue(POW_CHALLENGE)
    mockLogin.mockRejectedValue(axiosErrorWithStatus(400))
    const { wrapper } = await mountLogin()

    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    expect(wrapper.find('.admin-error').text()).toContain('人机验证未通过')
  })

  it('层 3：冷却 429 显示服务端文案', async () => {
    mockFetchChallenge.mockResolvedValue(POW_CHALLENGE)
    mockLogin.mockRejectedValue(axiosErrorWithStatus(429, '失败次数过多，账号保护已启动，请 30 分钟后再试'))
    const { wrapper } = await mountLogin()

    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    expect(wrapper.find('.admin-error').text()).toContain('账号保护已启动')
  })

  it('「换一张」重新取 challenge 并清空已填答案', async () => {
    mockFetchChallenge
      .mockResolvedValueOnce(IMAGE_CHALLENGE)
      .mockResolvedValueOnce({ ...IMAGE_CHALLENGE, challengeId: 'img-2', captchaImage: 'data:image/png;base64,fake2' })
    const { wrapper } = await mountLogin()

    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()
    await wrapper.find('#captcha-answer').setValue('WRONG')

    await wrapper.find('.admin-captcha-refresh').trigger('click')
    await flushPromises()

    expect(mockFetchChallenge).toHaveBeenCalledTimes(2)
    expect(wrapper.find('.admin-captcha img').attributes('src')).toBe('data:image/png;base64,fake2')
    expect((wrapper.find('#captcha-answer').element as HTMLInputElement).value).toBe('')
  })
})
