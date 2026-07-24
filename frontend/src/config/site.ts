export interface SiteConfig {
  siteName: string
  siteSubtitle: string
  siteDescription: string
  siteUrl: string
  socialImage: string
  authorName: string
  copyrightOwner: string
  copyrightYear: number
  contactEmail: string
  icpRecord: string
  icpLink: string
  policeRecord: string
  policeLink: string
}

function normalizeUrl(raw: string, fallback: string): string {
  const trimmed = raw.trim()
  if (!trimmed) return fallback
  if (!/^https?:\/\//i.test(trimmed)) return fallback
  return trimmed.replace(/\/+$/, '')
}

function readStr(key: string, fallback = ''): string {
  const val = import.meta.env[key] as string | undefined
  return val !== undefined && val !== '' ? val.trim() : fallback
}

function readInt(key: string, fallback: number): number {
  const val = import.meta.env[key] as string | undefined
  if (val === undefined || val === '') return fallback
  const n = parseInt(val, 10)
  return Number.isFinite(n) ? n : fallback
}

let cached: SiteConfig | null = null

/** Clear the cached config (used in tests to reset between cases). */
export function resetSiteConfig() {
  cached = null
}

export function createSiteConfig(): SiteConfig {
  if (cached) return cached
  cached = {
    siteName: readStr('VITE_SITE_NAME', '余白'),
    siteSubtitle: readStr('VITE_SITE_SUBTITLE', ''),
    siteDescription: readStr('VITE_SITE_DESCRIPTION', '记录代码、设计与日常生活的个人博客'),
    siteUrl: normalizeUrl(
      readStr('VITE_SITE_URL', ''),
      'http://localhost:5173',
    ),
    socialImage: readStr('VITE_SOCIAL_IMAGE', '/og.png'),
    authorName: readStr('VITE_AUTHOR_NAME', 'Yubai'),
    copyrightOwner: readStr('VITE_COPYRIGHT_OWNER', ''),
    copyrightYear: readInt('VITE_COPYRIGHT_YEAR', new Date().getFullYear()),
    contactEmail: readStr('VITE_CONTACT_EMAIL', ''),
    icpRecord: readStr('VITE_ICP_RECORD', ''),
    icpLink: readStr('VITE_ICP_LINK', ''),
    policeRecord: readStr('VITE_POLICE_RECORD', ''),
    policeLink: readStr('VITE_POLICE_LINK', ''),
  }
  return cached
}

/** Resolve a relative path to an absolute URL using the configured site root. */
export function resolveUrl(path: string): string {
  if (!path) return ''
  if (/^https?:\/\//i.test(path)) return path
  const base = createSiteConfig().siteUrl.replace(/\/+$/, '')
  const clean = path.replace(/^\/+/, '')
  return `${base}/${clean}`
}
