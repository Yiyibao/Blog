export interface SiteConfig {
  siteName: string;
  siteSubtitle: string;
  siteDescription: string;
  siteUrl: string;
  socialImage: string;
  authorName: string;
  copyrightOwner: string;
  copyrightYear: number;
  contactEmail: string;
  icpRecord: string;
  icpLink: string;
  policeRecord: string;
  policeLink: string;
}

function normalizeUrl(raw: string, fallback: string): string {
  const trimmed = raw.trim();
  if (!trimmed) return fallback;
  if (!/^https?:\/\//i.test(trimmed)) return fallback;
  return trimmed.replace(/\/+$/, '');
}

function readStr(key: string, fallback = ''): string {
  const val = import.meta.env[key] as string | undefined;
  return val !== undefined && val !== '' ? val.trim() : fallback;
}

function readInt(key: string, fallback: number): number {
  const val = import.meta.env[key] as string | undefined;
  if (val === undefined || val === '') return fallback;
  const n = parseInt(val, 10);
  return Number.isFinite(n) ? n : fallback;
}

let cached: SiteConfig | null = null;

/** Clear the cached config (used in tests to reset between cases). */
export function resetSiteConfig() {
  cached = null;
}

export function createSiteConfig(): SiteConfig {
  if (cached) return cached;
  cached = {
    siteName: readStr('VITE_SITE_NAME', '日常拾光录'),
    siteSubtitle: readStr('VITE_SITE_SUBTITLE', "hxnf's Memoir."),
    siteDescription: readStr(
      'VITE_SITE_DESCRIPTION',
      '拾起代码、阅读、料理与日常生活里的微光，记录思考，也珍藏时间。',
    ),
    siteUrl: normalizeUrl(readStr('VITE_SITE_URL', ''), 'http://localhost:5173'),
    socialImage: readStr('VITE_SOCIAL_IMAGE', '/og.png'),
    authorName: readStr('VITE_AUTHOR_NAME', 'hxnf'),
    copyrightOwner: readStr('VITE_COPYRIGHT_OWNER', ''),
    copyrightYear: readInt('VITE_COPYRIGHT_YEAR', new Date().getFullYear()),
    contactEmail: readStr('VITE_CONTACT_EMAIL', ''),
    // Keep the development preview uncluttered while ensuring every production
    // build carries the required MIIT filing record even when CI omits env vars.
    icpRecord: readStr('VITE_ICP_RECORD', import.meta.env.PROD ? '苏ICP备2026052529号-1' : ''),
    icpLink: readStr('VITE_ICP_LINK', import.meta.env.PROD ? 'https://beian.miit.gov.cn/' : ''),
    policeRecord: readStr('VITE_POLICE_RECORD', ''),
    policeLink: readStr('VITE_POLICE_LINK', ''),
  };
  if (import.meta.env.PROD && !readStr('VITE_ICP_RECORD', '')) {
    cached.icpRecord = '\u82cfICP\u59072026052529\u53f7-1';
    cached.icpLink = readStr('VITE_ICP_LINK', 'https://beian.miit.gov.cn/');
  }
  return cached;
}

/** Resolve a relative path to an absolute URL using the configured site root. */
export function resolveUrl(path: string): string {
  if (!path) return '';
  if (/^https?:\/\//i.test(path)) return path;
  const base = createSiteConfig().siteUrl.replace(/\/+$/, '');
  const clean = path.replace(/^\/+/, '');
  return `${base}/${clean}`;
}
