import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { dirname, resolve, sep } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { loadEnv } from 'vite'

const scriptDir = import.meta.url.startsWith('file:')
  ? dirname(fileURLToPath(import.meta.url))
  : resolve(process.cwd(), 'scripts')
const defaultDistClient = resolve(scriptDir, '..', 'dist', 'client')
const projectRoot = resolve(scriptDir, '..')
const fileEnv = loadEnv(process.env.MODE || 'production', projectRoot, '')
let DIST_CLIENT = defaultDistClient
const MAX_DYNAMIC_PAGES = 1000
const API_PAGE_SIZE = 50
const SEO_START = '<!--SEO:start-->'
const SEO_END = '<!--SEO:end-->'

function envStr(key, fallback = '') {
  return String(process.env[key] ?? fileEnv[key] ?? '').trim() || fallback
}

let SITE_URL = normalizeHttpBase(envStr('VITE_SITE_URL', 'https://hxnf.top'), 'VITE_SITE_URL')
let SITE_NAME = envStr('VITE_SITE_NAME', '日常拾光录')
let SITE_DESCRIPTION = envStr('VITE_SITE_DESCRIPTION', '拾起代码、阅读、料理与日常生活里的微光，记录思考，也珍藏时间。')
let AUTHOR_NAME = envStr('VITE_AUTHOR_NAME', 'Yubai')
let SOCIAL_IMAGE = envStr('VITE_SOCIAL_IMAGE', '/og.png')
let API_BASE = normalizeApiBase(envStr('PRERENDER_API_BASE_URL'))
let REQUIRE_DYNAMIC = envStr('PRERENDER_REQUIRE_DYNAMIC') === 'true'

export function __resetConfig() {
  SITE_URL = normalizeHttpBase(envStr('VITE_SITE_URL', 'https://hxnf.top'), 'VITE_SITE_URL')
  SITE_NAME = envStr('VITE_SITE_NAME', '日常拾光录')
  SITE_DESCRIPTION = envStr('VITE_SITE_DESCRIPTION', '拾起代码、阅读、料理与日常生活里的微光，记录思考，也珍藏时间。')
  AUTHOR_NAME = envStr('VITE_AUTHOR_NAME', 'Yubai')
  SOCIAL_IMAGE = envStr('VITE_SOCIAL_IMAGE', '/og.png')
  API_BASE = normalizeApiBase(envStr('PRERENDER_API_BASE_URL'))
  REQUIRE_DYNAMIC = envStr('PRERENDER_REQUIRE_DYNAMIC') === 'true'
  DIST_CLIENT = defaultDistClient
}

/** Override config for testing. Call before importing other functions. */
export function __setConfig(overrides) {
  if (overrides.siteUrl !== undefined) SITE_URL = normalizeHttpBase(overrides.siteUrl, 'site URL')
  if (overrides.siteName !== undefined) SITE_NAME = overrides.siteName
  if (overrides.siteDescription !== undefined) SITE_DESCRIPTION = overrides.siteDescription
  if (overrides.authorName !== undefined) AUTHOR_NAME = overrides.authorName
  if (overrides.socialImage !== undefined) SOCIAL_IMAGE = overrides.socialImage
  if (overrides.apiBase !== undefined) API_BASE = normalizeApiBase(overrides.apiBase)
  if (overrides.distClient !== undefined) DIST_CLIENT = overrides.distClient
  if (overrides.requireDynamic !== undefined) REQUIRE_DYNAMIC = overrides.requireDynamic
}

export function escapeHtml(str) {
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

export function safeJsonLd(obj) {
  return JSON.stringify(obj)
    .replace(/</g, '\\u003c')
    .replace(/\u2028/g, '\\u2028')
    .replace(/\u2029/g, '\\u2029')
}

function normalizeHttpBase(raw, label) {
  const value = String(raw || '').trim()
  let url
  try {
    url = new URL(value)
  } catch {
    throw new Error(`${label} must be an absolute HTTP(S) URL`)
  }
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash) {
    throw new Error(`${label} must be an absolute HTTP(S) URL without credentials, query, or fragment`)
  }
  return url.href.replace(/\/+$/, '')
}

export function normalizeApiBase(raw) {
  if (!String(raw || '').trim()) return ''
  return normalizeHttpBase(raw, 'PRERENDER_API_BASE_URL')
}

export function formatTitle(pageTitle) {
  if (!pageTitle) return SITE_NAME
  return `${pageTitle} | ${SITE_NAME}`
}

export function resolveUrl(path) {
  if (!path) return ''
  if (/^https?:\/\//i.test(path)) return path
  return new URL(path, `${SITE_URL}/`).href
}

export function safeOutputDir(routePath, baseDir) {
  if (routePath.includes('\\') || routePath.includes('\0')) {
    throw new Error(`Path traversal blocked: ${routePath}`)
  }
  const segments = routePath.split('/').filter(Boolean)
  for (const seg of segments) {
    if (seg === '..' || seg.includes('\0') || seg === '.') {
      throw new Error(`Path traversal blocked: ${routePath}`)
    }
  }
  const resolvedBase = resolve(baseDir)
  const dir = segments.length === 0 ? resolvedBase : resolve(resolvedBase, ...segments)
  if (!dir.startsWith(resolvedBase + sep) && dir !== resolvedBase) {
    throw new Error(`Path traversal blocked: ${routePath}`)
  }
  return dir
}

export function seoBlock(params) {
  const { title = '', description = '', canonicalPath = '', robots = '', ogType = 'website', ogImage = '', jsonLd = null, social = true } = params
  const fullTitle = formatTitle(title)
  const canonical = canonicalPath ? resolveUrl(canonicalPath) : ''
  const ogImg = ogImage ? resolveUrl(ogImage) : resolveUrl(SOCIAL_IMAGE)
  const ind = '    '
  let h = ''
  h += `${ind}<title>${escapeHtml(fullTitle)}</title>\n`
  if (description) h += `${ind}<meta name="description" content="${escapeHtml(description)}" />\n`
  if (robots) h += `${ind}<meta name="robots" content="${escapeHtml(robots)}" />\n`
  if (canonical) h += `${ind}<link rel="canonical" href="${escapeHtml(canonical)}" />\n`
  if (social) {
    h += `${ind}<meta property="og:title" content="${escapeHtml(ogType === 'article' ? title : fullTitle)}" />\n`
    if (description) h += `${ind}<meta property="og:description" content="${escapeHtml(description)}" />\n`
    h += `${ind}<meta property="og:type" content="${escapeHtml(ogType)}" />\n`
    h += `${ind}<meta property="og:url" content="${escapeHtml(canonical || SITE_URL)}" />\n`
    if (ogImg) h += `${ind}<meta property="og:image" content="${escapeHtml(ogImg)}" />\n`
    h += `${ind}<meta name="twitter:card" content="summary_large_image" />\n`
    h += `${ind}<meta name="twitter:title" content="${escapeHtml(ogType === 'article' ? title : fullTitle)}" />\n`
    if (description) h += `${ind}<meta name="twitter:description" content="${escapeHtml(description)}" />\n`
    if (ogImg) h += `${ind}<meta name="twitter:image" content="${escapeHtml(ogImg)}" />\n`
  }
  if (jsonLd) h += `${ind}<script type="application/ld+json" data-structured>${safeJsonLd(jsonLd)}</script>\n`
  return h
}

export function buildPageHtml(template, seoBlockContent) {
  const startIdx = template.indexOf(SEO_START)
  const endIdx = template.indexOf(SEO_END)
  if (startIdx === -1 || endIdx === -1) {
    throw new Error('SEO markers <!--SEO:start--> and <!--SEO:end--> not found in template')
  }
  const before = template.slice(0, startIdx + SEO_START.length)
  const after = template.slice(endIdx)
  return before + '\n' + seoBlockContent + after
}

export function validateSlug(slug, label) {
  if (!slug || typeof slug !== 'string') throw new Error(`Invalid ${label}: ${slug}`)
  if (slug.includes('..') || slug.includes('\0') || slug.includes('/') || slug.includes('\\')) {
    throw new Error(`Invalid ${label} (rejected): ${slug}`)
  }
}

async function fetchJson(url) {
  const res = await fetch(url)
  if (!res.ok) throw new Error(`HTTP ${res.status} from ${url}`)
  const json = await res.json()
  if (json && typeof json === 'object' && 'code' in json && 'data' in json) {
    if (json.code !== 200) throw new Error(`API error ${json.code} from ${url}: ${json.message || ''}`)
    return json.data
  }
  throw new Error(`Unexpected API response from ${url}: missing code/data envelope`)
}

async function writeRoute(template, block, routePath) {
  const html = buildPageHtml(template, block)
  const dir = safeOutputDir(routePath, DIST_CLIENT)
  await mkdir(dir, { recursive: true })
  await writeFile(resolve(dir, 'index.html'), html, 'utf-8')
}

const STATIC_ROUTES_CONFIG = [
  { path: '/', title: '', ogType: 'website', jsonLdType: 'WebSite' },
  { path: '/articles', title: '文章', description: '阅读所有技术文章与日常随笔', ogType: 'website' },
  { path: '/recipes', title: '美食', description: '家常菜谱与美食记录', ogType: 'website' },
]

const NOINDEX_ROUTES_CONFIG = [
  { path: '/login', title: '登录' },
  { path: '/admin/login', title: '管理员登录' },
]

export async function generateStaticRoutes(template) {
  const results = []
  for (const route of STATIC_ROUTES_CONFIG) {
    let jsonLd = null
    if (route.jsonLdType === 'WebSite') {
      jsonLd = {
        '@context': 'https://schema.org',
        '@type': 'WebSite',
        name: SITE_NAME,
        description: SITE_DESCRIPTION,
        url: SITE_URL,
      }
    }
    const block = seoBlock({
      title: route.title,
      description: route.description ?? SITE_DESCRIPTION,
      canonicalPath: route.path,
      ogType: route.ogType,
      jsonLd,
    })
    await writeRoute(template, block, route.path)
    results.push(route.path)
  }
  return results
}

export async function generateNoindexRoutes(template) {
  const results = []
  for (const route of NOINDEX_ROUTES_CONFIG) {
    const block = seoBlock({
      title: route.title,
      robots: 'noindex, nofollow',
      social: false,
    })
    await writeRoute(template, block, route.path)
    results.push(route.path)
  }
  return results
}

export async function generateDynamicArticleRoutes(template, apiBase) {
  const normalizedApiBase = normalizeApiBase(apiBase)
  let page = 0
  const results = []
  for (;;) {
    const url = new URL(`${normalizedApiBase}/posts`)
    url.searchParams.set('page', String(page))
    url.searchParams.set('size', String(API_PAGE_SIZE))
    const data = await fetchJson(url.href)
    if (!data || !Array.isArray(data.items)) {
      throw new Error(`Invalid posts response at page ${page}: expected { items: [...] }`)
    }
    const { items, totalPages, totalElements } = data
    if (!Number.isInteger(totalPages) || totalPages < 0 || !Number.isInteger(totalElements) || totalElements < 0) {
      throw new Error(`Invalid posts response at page ${page}: invalid pagination metadata`)
    }
    if (totalElements > MAX_DYNAMIC_PAGES) {
      throw new Error(`Published post count ${totalElements} exceeds prerender limit ${MAX_DYNAMIC_PAGES}`)
    }
    if (totalPages > Math.ceil(MAX_DYNAMIC_PAGES / API_PAGE_SIZE)) {
      throw new Error(`Post page count ${totalPages} exceeds prerender limit`)
    }
    for (const post of items) {
      if (post.status && post.status !== 'PUBLISHED') continue
      const slug = post.slug
      validateSlug(slug, 'article slug')
      if (typeof post.title !== 'string' || !post.title.trim()) {
        throw new Error(`Invalid article title for slug ${slug}`)
      }
      if (typeof post.date !== 'string' || !post.date) {
        throw new Error(`Invalid article date for slug ${slug}`)
      }
      const canonicalPath = `/articles/${encodeURIComponent(slug)}`
      const description = cleanText(post.excerpt || post.title)
      const block = seoBlock({
        title: post.title,
        description,
        canonicalPath,
        ogType: 'article',
        jsonLd: {
          '@context': 'https://schema.org',
          '@type': 'BlogPosting',
          headline: post.title,
          description,
          url: resolveUrl(canonicalPath),
          datePublished: post.date,
          dateModified: post.updatedAt || post.date,
          author: { '@type': 'Person', name: AUTHOR_NAME },
        },
      })
      await writeRoute(template, block, `/articles/${slug}`)
      results.push(`/articles/${slug}`)
    }
    page++
    if (page >= totalPages) break
  }
  return results
}

export async function generateDynamicSeriesRoutes(template, apiBase) {
  const normalizedApiBase = normalizeApiBase(apiBase)
  const data = await fetchJson(`${normalizedApiBase}/series`)
  if (!Array.isArray(data)) throw new Error('Invalid series response: expected array')
  if (data.length > MAX_DYNAMIC_PAGES) throw new Error(`Series count exceeds prerender limit ${MAX_DYNAMIC_PAGES}`)
  const results = []
  for (const series of data) {
    const slug = series.slug
    validateSlug(slug, 'series slug')
    if (typeof series.name !== 'string' || !series.name.trim()) {
      throw new Error(`Invalid series name for slug ${slug}`)
    }
    const canonicalPath = `/series/${encodeURIComponent(slug)}`
    const description = cleanText(series.description || series.name)
    const block = seoBlock({
      title: series.name,
      description,
      canonicalPath,
      jsonLd: {
        '@context': 'https://schema.org',
        '@type': 'CollectionPage',
        name: series.name,
        description,
        url: resolveUrl(canonicalPath),
      },
    })
    await writeRoute(template, block, `/series/${slug}`)
    results.push(`/series/${slug}`)
  }
  return results
}

export async function generateDynamicTagRoutes(template, apiBase) {
  const normalizedApiBase = normalizeApiBase(apiBase)
  const data = await fetchJson(`${normalizedApiBase}/tags`)
  if (!Array.isArray(data)) throw new Error('Invalid tags response: expected array')
  if (data.length > MAX_DYNAMIC_PAGES) throw new Error(`Tag count exceeds prerender limit ${MAX_DYNAMIC_PAGES}`)
  const results = []
  for (const tag of data) {
    const tagName = tag.tag
    validateSlug(tagName, 'tag name')
    if (!Number.isInteger(tag.count) || tag.count < 0) throw new Error(`Invalid count for tag ${tagName}`)
    const canonicalPath = `/tags/${encodeURIComponent(tagName)}`
    const block = seoBlock({
      title: `#${tagName}`,
      description: `浏览标签 #${tagName} 下的 ${tag.count} 篇文章`,
      canonicalPath,
    })
    await writeRoute(template, block, `/tags/${tagName}`)
    results.push(`/tags/${tagName}`)
  }
  return results
}

function cleanText(raw) {
  return String(raw).replace(/<[^>]*>/g, ' ').replace(/[#*_~`>\[\]()-]+/g, ' ')
    .replace(/\s+/g, ' ').trim().slice(0, 200)
}

export async function prerender({ template, apiBase }) {
  const results = { static: [], noindex: [], dynamic: { articles: [], series: [], tags: [] } }
  results.static = await generateStaticRoutes(template)
  results.noindex = await generateNoindexRoutes(template)
  if (apiBase) {
    results.dynamic.articles = await generateDynamicArticleRoutes(template, apiBase)
    // Series, tags, and archive are restricted to authenticated users and
    // must remain SPA-only so their content is never served as guest HTML.
  } else if (REQUIRE_DYNAMIC) {
    throw new Error(
      'PRERENDER_REQUIRE_DYNAMIC is true but PRERENDER_API_BASE_URL is not set. ' +
      'Set PRERENDER_API_BASE_URL to enable dynamic route prerendering, ' +
      'or unset PRERENDER_REQUIRE_DYNAMIC for offline/local builds without dynamic routes.'
    )
  }
  return results
}

async function main() {
  const templatePath = resolve(DIST_CLIENT, 'index.html')
  let template
  try {
    template = await readFile(templatePath, 'utf-8')
  } catch {
    console.error(`Cannot read ${templatePath}. Run vite build first.`)
    process.exit(1)
  }
  const results = await prerender({ template, apiBase: API_BASE || '' })
  console.log(`Prerendered ${results.static.length} static routes: ${results.static.join(', ')}`)
  console.log(`Prerendered ${results.noindex.length} noindex routes: ${results.noindex.join(', ')}`)
  if (API_BASE) {
    console.log(`Prerendered ${results.dynamic.articles.length} dynamic articles`)
    console.log(`Prerendered ${results.dynamic.series.length} dynamic series`)
    console.log(`Prerendered ${results.dynamic.tags.length} dynamic tags`)
  }
}

const isMain = process.argv[1]
  && import.meta.url.startsWith('file:')
  && pathToFileURL(resolve(process.argv[1])).href === import.meta.url
if (isMain) {
  main().catch(err => {
    console.error('Prerender failed:', err.message)
    process.exit(1)
  })
}
