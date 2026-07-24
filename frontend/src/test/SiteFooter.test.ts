import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import SiteFooter from '../components/SiteFooter.vue'
import { resetSiteConfig } from '../config/site'

beforeEach(() => {
  resetSiteConfig()
  vi.stubEnv('VITE_SITE_NAME', '测试站点')
  vi.stubEnv('VITE_COPYRIGHT_OWNER', '')
  vi.stubEnv('VITE_COPYRIGHT_YEAR', '')
  vi.stubEnv('VITE_ICP_RECORD', '')
  vi.stubEnv('VITE_ICP_LINK', '')
  vi.stubEnv('VITE_POLICE_RECORD', '')
  vi.stubEnv('VITE_POLICE_LINK', '')
  vi.stubEnv('VITE_CONTACT_EMAIL', '')
  vi.stubEnv('VITE_SITE_URL', 'https://example.com')
})

afterEach(() => {
  vi.unstubAllEnvs()
  resetSiteConfig()
})

describe('SiteFooter', () => {
  it('shows site name and current year', () => {
    vi.stubEnv('VITE_COPYRIGHT_YEAR', String(new Date().getFullYear()))
    const wrapper = mount(SiteFooter)
    expect(wrapper.text()).toContain(`© ${new Date().getFullYear()} 测试站点`)
  })

  it('shows year range when copyright year is earlier than current year', () => {
    vi.stubEnv('VITE_COPYRIGHT_YEAR', '2024')
    const wrapper = mount(SiteFooter)
    expect(wrapper.text()).toContain(`© 2024–${new Date().getFullYear()} 测试站点`)
  })

  it('shows single year when copyright year equals current year', () => {
    vi.stubEnv('VITE_COPYRIGHT_YEAR', String(new Date().getFullYear()))
    const wrapper = mount(SiteFooter)
    expect(wrapper.text()).toContain(`© ${new Date().getFullYear()}`)
    expect(wrapper.text()).not.toContain('–')
  })

  it('uses copyright owner when available', () => {
    vi.stubEnv('VITE_COPYRIGHT_OWNER', '自定义版权方')
    vi.stubEnv('VITE_COPYRIGHT_YEAR', '2025')
    const wrapper = mount(SiteFooter)
    expect(wrapper.text()).toContain('自定义版权方')
  })

  it('falls back to site name for copyright owner', () => {
    vi.stubEnv('VITE_COPYRIGHT_YEAR', String(new Date().getFullYear()))
    const wrapper = mount(SiteFooter)
    expect(wrapper.text()).toContain('测试站点')
  })

  it('hides ICP when record is empty', () => {
    vi.stubEnv('VITE_ICP_RECORD', '')
    const wrapper = mount(SiteFooter)
    expect(wrapper.find('.footer-icp').exists()).toBe(false)
  })

  it('renders ICP as link when both record and link exist', () => {
    vi.stubEnv('VITE_ICP_RECORD', '京ICP备2024XXXXXX号')
    vi.stubEnv('VITE_ICP_LINK', 'https://beian.miit.gov.cn')
    const wrapper = mount(SiteFooter)
    const link = wrapper.find('.footer-icp a')
    expect(link.exists()).toBe(true)
    expect(link.text()).toBe('京ICP备2024XXXXXX号')
    expect(link.attributes('href')).toBe('https://beian.miit.gov.cn')
    expect(link.attributes('rel')).toBe('noopener noreferrer')
  })

  it('renders ICP as text when only record exists without link', () => {
    vi.stubEnv('VITE_ICP_RECORD', '京ICP备2024XXXXXX号')
    vi.stubEnv('VITE_ICP_LINK', '')
    const wrapper = mount(SiteFooter)
    expect(wrapper.find('.footer-icp a').exists()).toBe(false)
    expect(wrapper.find('.footer-icp').text()).toBe('京ICP备2024XXXXXX号')
  })

  it('hides police when record is empty', () => {
    vi.stubEnv('VITE_POLICE_RECORD', '')
    const wrapper = mount(SiteFooter)
    expect(wrapper.find('.footer-police').exists()).toBe(false)
  })

  it('renders police as link when both record and link exist', () => {
    vi.stubEnv('VITE_POLICE_RECORD', '京公网安备11010802000000号')
    vi.stubEnv('VITE_POLICE_LINK', 'https://www.beian.gov.cn')
    const wrapper = mount(SiteFooter)
    const link = wrapper.find('.footer-police a')
    expect(link.exists()).toBe(true)
    expect(link.text()).toBe('京公网安备11010802000000号')
    expect(link.attributes('href')).toBe('https://www.beian.gov.cn')
  })

  it('hides email when empty', () => {
    vi.stubEnv('VITE_CONTACT_EMAIL', '')
    const wrapper = mount(SiteFooter)
    expect(wrapper.find('.footer-email').exists()).toBe(false)
  })

  it('renders email as mailto link', () => {
    vi.stubEnv('VITE_CONTACT_EMAIL', 'hello@example.com')
    const wrapper = mount(SiteFooter)
    const link = wrapper.find('.footer-email a')
    expect(link.exists()).toBe(true)
    expect(link.attributes('href')).toBe('mailto:hello@example.com')
    expect(link.text()).toBe('hello@example.com')
  })

  it('does not produce extra separators when optional fields are empty', () => {
    vi.stubEnv('VITE_ICP_RECORD', '')
    vi.stubEnv('VITE_POLICE_RECORD', '')
    vi.stubEnv('VITE_CONTACT_EMAIL', '')
    const wrapper = mount(SiteFooter)
    const text = wrapper.text()
    expect(text).not.toMatch(/\|\s*\|/)
    expect(text).not.toMatch(/\|\s*$/)
  })
})
