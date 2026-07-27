import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import TotpPanel from '../components/TotpPanel.vue'

const fetchStatus = vi.fn()
const setup = vi.fn()
const enable = vi.fn()
const disable = vi.fn()

vi.mock('../api/admin', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/admin')>()
  return {
    ...actual,
    fetchTotpStatus: () => fetchStatus(),
    setupTotp: (...args: unknown[]) => setup(...args),
    enableTotp: (...args: unknown[]) => enable(...args),
    disableTotp: (...args: unknown[]) => disable(...args),
  }
})

beforeEach(() => {
  fetchStatus.mockReset()
  setup.mockReset()
  enable.mockReset()
  disable.mockReset()
})

describe('TOTP account settings', () => {
  it('sets up and enables TOTP without sending the secret to a third-party QR service', async () => {
    fetchStatus.mockResolvedValue({ enabled: false })
    setup.mockResolvedValue({ secret: 'BASE32SECRET', otpauthUri: 'otpauth://totp/yubai-blog%3Aadmin' })
    enable.mockResolvedValue(undefined)
    const wrapper = mount(TotpPanel)
    await flushPromises()

    await wrapper.get('button').trigger('click')
    await wrapper.get('input[type="password"]').setValue('current-password')
    await wrapper.findAll('button').find(button => button.text().includes('下一步'))!.trigger('click')
    await flushPromises()

    expect(setup).toHaveBeenCalledWith('current-password')
    expect(wrapper.text()).toContain('BASE32SECRET')
    expect(wrapper.find('a[href^="otpauth://"]').exists()).toBe(true)
    expect(wrapper.find('img[src^="http"]').exists()).toBe(false)

    await wrapper.get('input[inputmode="numeric"]').setValue('123456')
    await wrapper.findAll('button').find(button => button.text() === '启用')!.trigger('click')
    await flushPromises()

    expect(enable).toHaveBeenCalledWith('123456')
    expect(wrapper.text()).toContain('已启用')
  })

  it('requires password and code to disable TOTP', async () => {
    fetchStatus.mockResolvedValue({ enabled: true })
    disable.mockResolvedValue(undefined)
    const wrapper = mount(TotpPanel)
    await flushPromises()

    await wrapper.get('summary').trigger('click')
    await wrapper.get('input[type="password"]').setValue('current-password')
    await wrapper.get('input[inputmode="numeric"]').setValue('654321')
    await wrapper.findAll('button').find(button => button.text() === '关闭两步验证')!.trigger('click')
    await flushPromises()

    expect(disable).toHaveBeenCalledWith('current-password', '654321')
    expect(wrapper.text()).toContain('续期会话已撤销')
  })
})
