import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createSiteConfig, resetSiteConfig, type SiteConfig } from '../config/site'

describe('createSiteConfig', () => {

  beforeEach(() => {
    resetSiteConfig()
    vi.stubEnv('VITE_SITE_NAME', '')
    vi.stubEnv('VITE_SITE_SUBTITLE', '')
    vi.stubEnv('VITE_SITE_DESCRIPTION', '')
    vi.stubEnv('VITE_SITE_URL', '')
    vi.stubEnv('VITE_SOCIAL_IMAGE', '')
    vi.stubEnv('VITE_AUTHOR_NAME', '')
    vi.stubEnv('VITE_COPYRIGHT_OWNER', '')
    vi.stubEnv('VITE_COPYRIGHT_YEAR', '')
    vi.stubEnv('VITE_CONTACT_EMAIL', '')
    vi.stubEnv('VITE_ICP_RECORD', '')
    vi.stubEnv('VITE_ICP_LINK', '')
    vi.stubEnv('VITE_POLICE_RECORD', '')
    vi.stubEnv('VITE_POLICE_LINK', '')
  })

  afterEach(() => {
    vi.unstubAllEnvs()
    resetSiteConfig()
  })

  it('returns defaults when no env vars are set', () => {
    const cfg = createSiteConfig()
    expect(cfg.siteName).toBe('余白')
    expect(cfg.siteSubtitle).toBe('')
    expect(cfg.siteDescription).toBe('记录代码、设计与日常生活的个人博客')
    expect(cfg.siteUrl).toBe('http://localhost:5173')
    expect(cfg.socialImage).toBe('/og.png')
    expect(cfg.authorName).toBe('Yubai')
    expect(cfg.copyrightOwner).toBe('')
    expect(cfg.copyrightYear).toBe(new Date().getFullYear())
    expect(cfg.contactEmail).toBe('')
    expect(cfg.icpRecord).toBe('')
    expect(cfg.icpLink).toBe('')
    expect(cfg.policeRecord).toBe('')
    expect(cfg.policeLink).toBe('')
  })

  it('reads custom values from env vars', () => {
    vi.stubEnv('VITE_SITE_NAME', '测试博客')
    vi.stubEnv('VITE_SITE_SUBTITLE', '副标题')
    vi.stubEnv('VITE_SITE_DESCRIPTION', '测试描述')
    vi.stubEnv('VITE_SITE_URL', 'https://example.com')
    vi.stubEnv('VITE_SOCIAL_IMAGE', '/custom-og.png')
    vi.stubEnv('VITE_AUTHOR_NAME', '测试作者')
    vi.stubEnv('VITE_COPYRIGHT_OWNER', '测试版权')
    vi.stubEnv('VITE_COPYRIGHT_YEAR', '2024')
    vi.stubEnv('VITE_CONTACT_EMAIL', 'test@example.com')
    vi.stubEnv('VITE_ICP_RECORD', '京ICP备2024XXXXXX号')
    vi.stubEnv('VITE_ICP_LINK', 'https://beian.miit.gov.cn')
    vi.stubEnv('VITE_POLICE_RECORD', '京公网安备11010802000000号')
    vi.stubEnv('VITE_POLICE_LINK', 'https://www.beian.gov.cn')

    const cfg = createSiteConfig()
    expect(cfg.siteName).toBe('测试博客')
    expect(cfg.siteSubtitle).toBe('副标题')
    expect(cfg.siteDescription).toBe('测试描述')
    expect(cfg.siteUrl).toBe('https://example.com')
    expect(cfg.socialImage).toBe('/custom-og.png')
    expect(cfg.authorName).toBe('测试作者')
    expect(cfg.copyrightOwner).toBe('测试版权')
    expect(cfg.copyrightYear).toBe(2024)
    expect(cfg.contactEmail).toBe('test@example.com')
    expect(cfg.icpRecord).toBe('京ICP备2024XXXXXX号')
    expect(cfg.icpLink).toBe('https://beian.miit.gov.cn')
    expect(cfg.policeRecord).toBe('京公网安备11010802000000号')
    expect(cfg.policeLink).toBe('https://www.beian.gov.cn')
  })

  it('normalizes siteUrl by removing trailing slash', () => {
    vi.stubEnv('VITE_SITE_URL', 'https://example.com/')
    expect(createSiteConfig().siteUrl).toBe('https://example.com')
  })

  it('normalizes siteUrl with multiple trailing slashes', () => {
    vi.stubEnv('VITE_SITE_URL', 'https://example.com///')
    expect(createSiteConfig().siteUrl).toBe('https://example.com')
  })

  it('falls back to localhost for invalid siteUrl', () => {
    vi.stubEnv('VITE_SITE_URL', 'not-a-url')
    expect(createSiteConfig().siteUrl).toBe('http://localhost:5173')
  })

  it('falls back to localhost for empty siteUrl', () => {
    vi.stubEnv('VITE_SITE_URL', '')
    expect(createSiteConfig().siteUrl).toBe('http://localhost:5173')
  })

  it('keeps optional fields empty when not set', () => {
    const cfg = createSiteConfig()
    expect(cfg.siteSubtitle).toBe('')
    expect(cfg.copyrightOwner).toBe('')
    expect(cfg.contactEmail).toBe('')
    expect(cfg.icpRecord).toBe('')
    expect(cfg.icpLink).toBe('')
    expect(cfg.policeRecord).toBe('')
    expect(cfg.policeLink).toBe('')
  })

  it('trims whitespace from string values', () => {
    vi.stubEnv('VITE_SITE_NAME', '  余白  ')
    vi.stubEnv('VITE_SITE_URL', '  https://example.com/  ')
    const cfg = createSiteConfig()
    expect(cfg.siteName).toBe('余白')
    expect(cfg.siteUrl).toBe('https://example.com')
  })

  it('copyrightYear falls back to current year for invalid input', () => {
    vi.stubEnv('VITE_COPYRIGHT_YEAR', 'not-a-number')
    expect(createSiteConfig().copyrightYear).toBe(new Date().getFullYear())
  })

  it('copyrightYear falls back for empty input', () => {
    vi.stubEnv('VITE_COPYRIGHT_YEAR', '')
    expect(createSiteConfig().copyrightYear).toBe(new Date().getFullYear())
  })

  it('returns a complete SiteConfig object with all fields', () => {
    const cfg = createSiteConfig()
    const keys: (keyof SiteConfig)[] = [
      'siteName', 'siteSubtitle', 'siteDescription', 'siteUrl',
      'socialImage', 'authorName', 'copyrightOwner', 'copyrightYear',
      'contactEmail', 'icpRecord', 'icpLink', 'policeRecord', 'policeLink',
    ]
    for (const key of keys) {
      expect(cfg).toHaveProperty(key)
    }
    expect(Object.keys(cfg).sort()).toEqual(keys.sort())
  })
})
