import { describe, it, expect, beforeAll, afterAll, vi } from 'vitest';
import { resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { mkdtempSync, readFileSync } from 'node:fs';
import { rm } from 'node:fs/promises';

let mod: any;
let tmpDir: string;
const TEMPLATE = `<!doctype html>
<html lang="zh-CN">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <meta name="theme-color" content="#f7f3e9" />
<!--SEO:start-->
    <title>Fallback Title</title>
    <meta name="description" content="Fallback description" />
<!--SEO:end-->
    <link rel="icon" href="/favicon.png" />
    <link rel="stylesheet" crossorigin href="/assets/index-CSS.css" />
  </head>
  <body>
    <div id="app"></div>
    <script type="module" crossorigin src="/assets/index-abc123.js"></script>
  </body>
</html>`;

beforeAll(async () => {
  tmpDir = mkdtempSync(resolve(tmpdir(), 'prerender-test-'));
  // @ts-expect-error The production build helper is intentionally plain ESM JavaScript.
  mod = await import('../../scripts/prerender-seo.mjs');
  mod.__setConfig({
    siteUrl: 'https://test.example.com',
    siteName: 'Test Blog',
    siteDescription: 'A test blog for testing',
    authorName: 'Tester',
    socialImage: '/test-og.png',
    distClient: tmpDir,
  });
});

afterAll(async () => {
  await rm(tmpDir, { recursive: true, force: true });
});

// ─── Pure function tests ───────────────────────────────────────────────────

describe('escapeHtml', () => {
  it('escapes & < > " \'', () => {
    expect(mod.escapeHtml('&<>"\'')).toBe('&amp;&lt;&gt;&quot;&#39;');
  });

  it('passes safe strings through', () => {
    expect(mod.escapeHtml('hello world')).toBe('hello world');
  });

  it('handles empty string', () => {
    expect(mod.escapeHtml('')).toBe('');
  });

  it('escapes </script> pattern', () => {
    expect(mod.escapeHtml('</script>')).toBe('&lt;/script&gt;');
  });
});

describe('safeJsonLd', () => {
  it('replaces </ with <\\/ to prevent script tag breakage', () => {
    const obj = { html: '</script><script>alert(1)' };
    const result = mod.safeJsonLd(obj);
    expect(result).not.toContain('</script>');
    expect(result).toContain('\\u003c/script>');
  });

  it('produces valid JSON', () => {
    const obj = { '@type': 'WebSite', name: 'Test' };
    const result = mod.safeJsonLd(obj);
    expect(() => JSON.parse(result)).not.toThrow();
    expect(JSON.parse(result)).toEqual(obj);
  });

  it('does not corrupt normal content', () => {
    const obj = { name: 'normal text with / character' };
    const result = mod.safeJsonLd(obj);
    expect(JSON.parse(result)).toEqual(obj);
  });
});

describe('formatTitle', () => {
  it('returns site name for empty title', () => {
    expect(mod.formatTitle('')).toBe('Test Blog');
  });

  it('formats page title with separator', () => {
    expect(mod.formatTitle('文章')).toBe('文章 | Test Blog');
  });
});

describe('resolveUrl', () => {
  it('resolves relative path', () => {
    expect(mod.resolveUrl('/articles/test')).toBe('https://test.example.com/articles/test');
  });

  it('keeps absolute URL unchanged', () => {
    expect(mod.resolveUrl('https://other.com/img.png')).toBe('https://other.com/img.png');
  });

  it('returns empty for empty input', () => {
    expect(mod.resolveUrl('')).toBe('');
  });
});

describe('safeOutputDir', () => {
  it('returns valid path for simple route', () => {
    const dir = mod.safeOutputDir('/articles/test', tmpDir);
    expect(dir).toBe(resolve(tmpDir, 'articles', 'test'));
  });

  it('rejects path traversal with ..', () => {
    expect(() => mod.safeOutputDir('/articles/../../etc/passwd', tmpDir)).toThrow('Path traversal');
  });

  it('rejects null byte', () => {
    expect(() => mod.safeOutputDir('/articles/\0null', tmpDir)).toThrow('Path traversal');
  });

  it('rejects backslash separators', () => {
    expect(() => mod.safeOutputDir('/articles\\evil', tmpDir)).toThrow('Path traversal');
  });

  it('rejects absolute path component', () => {
    expect(() => mod.safeOutputDir('/articles/../tmp/evil', tmpDir)).toThrow('Path traversal');
  });

  it('handles root route', () => {
    const dir = mod.safeOutputDir('/', tmpDir);
    expect(dir).toBe(tmpDir);
  });
});

describe('validateSlug', () => {
  it('passes valid slug', () => {
    expect(() => mod.validateSlug('my-article-123', 'slug')).not.toThrow();
  });

  it('rejects path traversal', () => {
    expect(() => mod.validateSlug('../../etc', 'slug')).toThrow('Invalid slug');
  });

  it('rejects null byte', () => {
    expect(() => mod.validateSlug('bad\0null', 'slug')).toThrow('Invalid slug');
  });

  it('rejects empty slug', () => {
    expect(() => mod.validateSlug('', 'slug')).toThrow('Invalid slug');
  });

  it('rejects non-string', () => {
    expect(() => mod.validateSlug(null, 'slug')).toThrow('Invalid slug');
  });
});

describe('seoBlock', () => {
  it('includes title and description', () => {
    const block = mod.seoBlock({ title: '文章', description: '阅读文章', canonicalPath: '/articles' });
    expect(block).toContain('<title>文章 | Test Blog</title>');
    expect(block).toContain('content="阅读文章"');
    expect(block).toContain('rel="canonical"');
  });

  it('includes robots when provided', () => {
    const block = mod.seoBlock({ title: 'Login', robots: 'noindex, nofollow' });
    expect(block).toContain('noindex, nofollow');
  });

  it('sets article og:type when specified', () => {
    const block = mod.seoBlock({ title: 'Post', ogType: 'article' });
    expect(block).toContain('og:type" content="article"');
  });

  it('includes JSON-LD when provided', () => {
    const block = mod.seoBlock({
      title: 'Home',
      jsonLd: { '@context': 'https://schema.org', '@type': 'WebSite' },
    });
    expect(block).toContain('application/ld+json');
    expect(block).toContain('WebSite');
  });

  it('escapes HTML in metadata values', () => {
    const block = mod.seoBlock({ title: '<script>alert(1)</script>' });
    expect(block).not.toContain('<script>');
    expect(block).toContain('&lt;script&gt;');
  });

  it('includes social image', () => {
    const block = mod.seoBlock({ title: 'Home' });
    expect(block).toContain('test-og.png');
    expect(block).toContain('twitter:image');
    expect(block).toContain('og:image');
  });
});

describe('buildPageHtml', () => {
  it('replaces content between markers', () => {
    const seo = '    <title>新标题</title>\n';
    const result = mod.buildPageHtml(TEMPLATE, seo);
    expect(result).toContain('<title>新标题</title>');
    expect(result).not.toContain('Fallback Title');
  });

  it('preserves content before start marker', () => {
    const seo = '    <title>X</title>\n';
    const result = mod.buildPageHtml(TEMPLATE, seo);
    expect(result).toContain('<meta charset="UTF-8" />');
    expect(result).toContain('<meta name="viewport"');
  });

  it('preserves content after end marker', () => {
    const seo = '    <title>X</title>\n';
    const result = mod.buildPageHtml(TEMPLATE, seo);
    expect(result).toContain('<link rel="icon"');
    expect(result).toContain('<div id="app">');
    expect(result).toContain('/assets/index-abc123.js');
  });

  it('throws when markers are missing', () => {
    expect(() => mod.buildPageHtml('<html></html>', '')).toThrow('SEO markers');
  });
});

// ─── Static route generation tests ────────────────────────────────────────

describe('generateStaticRoutes', () => {
  it('writes index.html for each static route', async () => {
    const paths = await mod.generateStaticRoutes(TEMPLATE);
    expect(paths).toContain('/');
    expect(paths).toContain('/articles');
    expect(paths).toContain('/about');
    expect(paths).toContain('/categories');
    expect(paths).toContain('/recipes');
    expect(paths).not.toContain('/series');
    expect(paths).not.toContain('/archive');
    expect(paths.length).toBe(5);
  });

  it('produces valid HTML with SPA assets preserved', async () => {
    await mod.generateStaticRoutes(TEMPLATE);
    const html = readFileSync(resolve(tmpDir, 'articles', 'index.html'), 'utf-8');
    expect(html).toContain('<div id="app">');
    expect(html).toContain('/assets/index-abc123.js');
    expect(html).toContain('/assets/index-CSS.css');
  });

  it('sets correct title for articles page', async () => {
    await mod.generateStaticRoutes(TEMPLATE);
    const html = readFileSync(resolve(tmpDir, 'articles', 'index.html'), 'utf-8');
    expect(html).toContain('<title>文章 | Test Blog</title>');
  });

  it('sets correct description for articles page', async () => {
    await mod.generateStaticRoutes(TEMPLATE);
    const html = readFileSync(resolve(tmpDir, 'articles', 'index.html'), 'utf-8');
    expect(html).toContain('阅读所有技术文章与日常随笔');
  });
});

describe('generateNoindexRoutes', () => {
  it('writes noindex shells for login routes', async () => {
    const paths = await mod.generateNoindexRoutes(TEMPLATE);
    expect(paths).toContain('/login');
    expect(paths).toContain('/admin/login');
  });

  it('sets robots noindex', async () => {
    await mod.generateNoindexRoutes(TEMPLATE);
    const html = readFileSync(resolve(tmpDir, 'login', 'index.html'), 'utf-8');
    expect(html).toContain('noindex, nofollow');
  });

  it('sets admin-login title', async () => {
    await mod.generateNoindexRoutes(TEMPLATE);
    const html = readFileSync(resolve(tmpDir, 'admin', 'login', 'index.html'), 'utf-8');
    expect(html).toContain('管理员登录');
    expect(html).not.toContain('property="og:');
  });
});

// ─── Dynamic route generation tests (mocked API) ──────────────────────────

describe('generateDynamicArticleRoutes', () => {
  const mockPosts = {
    code: 200,
    message: 'success',
    data: {
      items: [
        {
          slug: 'test-post',
          title: 'Test Post',
          excerpt: '<p>Test excerpt</p>',
          date: '2026-07-01',
          tags: ['test'],
          status: 'PUBLISHED',
        },
        { slug: 'draft-post', title: 'Draft', excerpt: 'Draft', date: '2026-07-02', status: 'DRAFT' },
        {
          slug: 'another',
          title: 'Another Post',
          excerpt: 'Clean excerpt',
          date: '2026-06-15',
          tags: ['dev'],
        },
      ],
      page: 0,
      size: 50,
      totalElements: 2,
      totalPages: 1,
    },
  };

  afterAll(() => {
    vi.unstubAllGlobals();
  });

  it('generates pages for published posts only', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: () => Promise.resolve(mockPosts),
      }),
    );
    const apiBase = 'https://test.example.com/api/v1';
    const results = await mod.generateDynamicArticleRoutes(TEMPLATE, apiBase);
    expect(results).toContain('/articles/test-post');
    expect(results).toContain('/articles/another');
    expect(results).not.toContain('/articles/draft-post');
  });

  it('sets BlogPosting JSON-LD with safe escaping', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: () => Promise.resolve(mockPosts),
      }),
    );
    await mod.generateDynamicArticleRoutes(TEMPLATE, 'https://test.example.com/api/v1');
    const html = readFileSync(resolve(tmpDir, 'articles', 'test-post', 'index.html'), 'utf-8');
    expect(html).toContain('application/ld+json');
    expect(html).toContain('BlogPosting');
    expect(html).toContain('Test Post');
    expect(html).toContain('test-og.png');
  });

  it('preserves SPA assets in dynamic routes', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: () => Promise.resolve(mockPosts),
      }),
    );
    await mod.generateDynamicArticleRoutes(TEMPLATE, 'https://test.example.com/api/v1');
    const html = readFileSync(resolve(tmpDir, 'articles', 'test-post', 'index.html'), 'utf-8');
    expect(html).toContain('/assets/index-abc123.js');
    expect(html).toContain('<div id="app">');
  });

  it('uses the backend pagination limit and normalizes a trailing slash', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: () => Promise.resolve(mockPosts) });
    vi.stubGlobal('fetch', fetchMock);
    await mod.generateDynamicArticleRoutes(TEMPLATE, 'https://test.example.com/api/v1/');
    expect(fetchMock).toHaveBeenCalledWith('https://test.example.com/api/v1/posts?page=0&size=50');
  });

  it('rejects HTTP errors from API', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status: 500,
      }),
    );
    await expect(
      mod.generateDynamicArticleRoutes(TEMPLATE, 'https://test.example.com/api/v1'),
    ).rejects.toThrow('HTTP 500');
  });

  it('rejects API error codes', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: () => Promise.resolve({ code: 500, message: 'Server Error', data: null, timestamp: '' }),
      }),
    );
    await expect(
      mod.generateDynamicArticleRoutes(TEMPLATE, 'https://test.example.com/api/v1'),
    ).rejects.toThrow('API error 500');
  });
});

describe('generateDynamicSeriesRoutes', () => {
  const mockSeries = {
    code: 200,
    message: 'success',
    data: [
      {
        slug: 'vue-guide',
        name: 'Vue Guide',
        description: 'A series about Vue.js',
        entryCount: 5,
        publishedAt: '2026-01-01T00:00:00Z',
      },
      {
        slug: 'design-patterns',
        name: 'Design Patterns',
        description: 'Common design patterns',
        entryCount: 3,
        publishedAt: '2026-02-01T00:00:00Z',
      },
    ],
  };

  afterAll(() => {
    vi.unstubAllGlobals();
  });

  it('generates pages for each series', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: () => Promise.resolve(mockSeries),
      }),
    );
    const results = await mod.generateDynamicSeriesRoutes(TEMPLATE, 'https://test.example.com/api/v1');
    expect(results).toContain('/series/vue-guide');
    expect(results).toContain('/series/design-patterns');
  });

  it('sets CollectionPage JSON-LD', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: () => Promise.resolve(mockSeries),
      }),
    );
    await mod.generateDynamicSeriesRoutes(TEMPLATE, 'https://test.example.com/api/v1');
    const html = readFileSync(resolve(tmpDir, 'series', 'vue-guide', 'index.html'), 'utf-8');
    expect(html).toContain('CollectionPage');
    expect(html).toContain('Vue Guide');
  });

  it('preserves SPA assets', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: () => Promise.resolve(mockSeries),
      }),
    );
    await mod.generateDynamicSeriesRoutes(TEMPLATE, 'https://test.example.com/api/v1');
    const html = readFileSync(resolve(tmpDir, 'series', 'vue-guide', 'index.html'), 'utf-8');
    expect(html).toContain('/assets/index-abc123.js');
  });
});

describe('generateDynamicTagRoutes', () => {
  const mockTags = {
    code: 200,
    message: 'success',
    data: [
      { tag: 'vue', count: 5 },
      { tag: 'design', count: 3 },
      { tag: 'C#', count: 2 },
    ],
  };

  afterAll(() => {
    vi.unstubAllGlobals();
  });

  it('generates pages for each tag', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: () => Promise.resolve(mockTags),
      }),
    );
    const results = await mod.generateDynamicTagRoutes(TEMPLATE, 'https://test.example.com/api/v1');
    expect(results).toContain('/tags/vue');
    expect(results).toContain('/tags/design');
  });

  it('handles tag names with special characters', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: () => Promise.resolve(mockTags),
      }),
    );
    const results = await mod.generateDynamicTagRoutes(TEMPLATE, 'https://test.example.com/api/v1');
    expect(results).toContain('/tags/C#');
    const html = readFileSync(resolve(tmpDir, 'tags', 'C#', 'index.html'), 'utf-8');
    expect(html).toContain('/tags/C%23');
  });

  it('preserves SPA assets in tag pages', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: () => Promise.resolve(mockTags),
      }),
    );
    await mod.generateDynamicTagRoutes(TEMPLATE, 'https://test.example.com/api/v1');
    const html = readFileSync(resolve(tmpDir, 'tags', 'vue', 'index.html'), 'utf-8');
    expect(html).toContain('/assets/index-abc123.js');
    expect(html).toContain('<div id="app">');
  });
});

// ─── Integration test ─────────────────────────────────────────────────────

describe('prerender', () => {
  afterAll(() => {
    vi.unstubAllGlobals();
  });

  it('generates static and noindex routes without API base', async () => {
    const result = await mod.prerender({ template: TEMPLATE, apiBase: '' });
    expect(result.static.length).toBe(5);
    expect(result.noindex.length).toBe(2);
    expect(result.dynamic.articles.length).toBe(0);
  });

  it('optionally fetches dynamic routes when apiBase is provided', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: () =>
          Promise.resolve({
            code: 200,
            message: 'success',
            data: {
              items: [{ slug: 'integrated-post', title: 'Integrated', excerpt: 'Test', date: '2026-07-01' }],
              page: 0,
              size: 50,
              totalElements: 1,
              totalPages: 1,
            },
          }),
      }),
    );
    // Need to mock series and tags endpoints differently — use sequential mock
    const fetchMock = vi.fn();
    fetchMock.mockImplementationOnce(() =>
      Promise.resolve({
        ok: true,
        json: () =>
          Promise.resolve({
            code: 200,
            message: 'success',
            data: {
              items: [{ slug: 'integrated-post', title: 'Integrated', excerpt: 'Test', date: '2026-07-01' }],
              page: 0,
              size: 50,
              totalElements: 1,
              totalPages: 1,
            },
          }),
      }),
    );
    fetchMock.mockImplementationOnce(() =>
      Promise.resolve({
        ok: true,
        json: () =>
          Promise.resolve({
            code: 200,
            message: 'success',
            data: [],
          }),
      }),
    );
    fetchMock.mockImplementationOnce(() =>
      Promise.resolve({
        ok: true,
        json: () =>
          Promise.resolve({
            code: 200,
            message: 'success',
            data: [],
          }),
      }),
    );
    vi.stubGlobal('fetch', fetchMock);
    const result = await mod.prerender({ template: TEMPLATE, apiBase: 'https://test.example.com/api/v1' });
    expect(result.static.length).toBe(5);
    expect(result.noindex.length).toBe(2);
    expect(result.dynamic.articles.length).toBe(1);
    expect(result.dynamic.series.length).toBe(0);
    expect(result.dynamic.tags.length).toBe(0);
  });

  it('fails when PRERENDER_REQUIRE_DYNAMIC is true but no API base', async () => {
    mod.__setConfig({ apiBase: '', requireDynamic: true });
    await expect(mod.prerender({ template: TEMPLATE, apiBase: '' })).rejects.toThrow(
      'PRERENDER_REQUIRE_DYNAMIC',
    );
    const fetchMock = vi.fn();
    fetchMock.mockImplementationOnce(() =>
      Promise.resolve({
        ok: true,
        json: () =>
          Promise.resolve({
            code: 200,
            data: { items: [], page: 0, size: 50, totalElements: 0, totalPages: 0 },
          }),
      }),
    );
    fetchMock.mockImplementationOnce(() =>
      Promise.resolve({ ok: true, json: () => Promise.resolve({ code: 200, data: [] }) }),
    );
    fetchMock.mockImplementationOnce(() =>
      Promise.resolve({ ok: true, json: () => Promise.resolve({ code: 200, data: [] }) }),
    );
    vi.stubGlobal('fetch', fetchMock);
    await expect(
      mod.prerender({ template: TEMPLATE, apiBase: 'https://test.example.com/api/v1' }),
    ).resolves.not.toThrow();
    mod.__setConfig({ apiBase: '', requireDynamic: false });
    vi.unstubAllGlobals();
  });

  it('succeeds without dynamic routes when REQUIRE_DYNAMIC is false', async () => {
    mod.__setConfig({ apiBase: '', requireDynamic: false });
    const result = await mod.prerender({ template: TEMPLATE, apiBase: '' });
    expect(result.static.length).toBe(5);
    expect(result.noindex.length).toBe(2);
    expect(result.dynamic.articles.length).toBe(0);
  });

  it('fails build on API HTTP error when opted in', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status: 503,
      }),
    );
    await expect(
      mod.prerender({ template: TEMPLATE, apiBase: 'https://test.example.com/api/v1' }),
    ).rejects.toThrow('HTTP 503');
  });
});
