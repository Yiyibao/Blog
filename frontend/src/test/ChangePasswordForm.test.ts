import { describe, it, expect, beforeEach, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import ChangePasswordForm from '../components/ChangePasswordForm.vue'
import { useAuthStore } from '../stores/auth'
import { useUiStore } from '../stores/uiStore'

const mockChangePassword = vi.fn()

vi.mock('../api/admin', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/admin')>()
  return {
    ...actual,
    changePassword: (...args: unknown[]) => mockChangePassword(...args),
  }
})

let pinia: Pinia
let router: Router

async function mountForm() {
  pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore().saveSession({
    token: 't', tokenType: 'Bearer', username: 'gf',
    expiresAt: '2099-12-31T23:59:59Z', role: 'PARTNER', displayName: '小伙伴',
  })
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/login', name: 'login', component: { template: '<div>Login</div>' } },
    ],
  })
  await router.push('/')
  await router.isReady()
  const wrapper = mount(ChangePasswordForm, { global: { plugins: [router, pinia] } })
  return wrapper
}

async function fill(wrapper: Awaited<ReturnType<typeof mountForm>>, current: string, next: string, confirm = next) {
  const inputs = wrapper.findAll('input[type="password"]')
  await inputs[0].setValue(current)
  await inputs[1].setValue(next)
  await inputs[2].setValue(confirm)
  await wrapper.find('form').trigger('submit.prevent')
  await flushPromises()
}

beforeEach(() => {
  sessionStorage.clear()
  localStorage.clear()
  mockChangePassword.mockReset()
})

describe('FD-25 自助改密表单', () => {
  it('成功后提示、清会话并送去 /login', async () => {
    mockChangePassword.mockResolvedValue(undefined)
    const wrapper = await mountForm()
    await fill(wrapper, 'old-pass-current', '新密码是一句好记的短语呀')
    expect(mockChangePassword).toHaveBeenCalledWith('old-pass-current', '新密码是一句好记的短语呀')
    expect(useAuthStore(pinia).isAuthenticated).toBe(false)
    expect(useUiStore(pinia).toast).toContain('重新登录')
    expect(router.currentRoute.value.name).toBe('login')
  })

  it('新密码不足 12 位在客户端就拦下，不发请求', async () => {
    const wrapper = await mountForm()
    await fill(wrapper, 'old-pass-current', 'short')
    expect(mockChangePassword).not.toHaveBeenCalled()
    expect(wrapper.find('.password-error').text()).toContain('12')
  })

  it('两次新密码不一致时拦下', async () => {
    const wrapper = await mountForm()
    await fill(wrapper, 'old-pass-current', '新密码是一句好记的短语呀', '不一样的确认输入内容呀')
    expect(mockChangePassword).not.toHaveBeenCalled()
    expect(wrapper.find('.password-error').text()).toContain('不一致')
  })

  it('服务端 400 时原样展示 message 且会话保留', async () => {
    mockChangePassword.mockRejectedValue({
      isAxiosError: true,
      response: { status: 400, data: { message: '当前密码不正确' } },
    })
    const wrapper = await mountForm()
    await fill(wrapper, 'wrong-current-pass', '新密码是一句好记的短语呀')
    expect(wrapper.find('.password-error').text()).toContain('当前密码不正确')
    expect(useAuthStore(pinia).isAuthenticated).toBe(true)
  })
})
