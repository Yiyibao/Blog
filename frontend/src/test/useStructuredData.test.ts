import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import {
  useStructuredData,
  webSite,
  blogPosting,
  techArticle,
  recipe,
  breadcrumbList,
} from '../composables/useStructuredData';
import { resetSiteConfig } from '../config/site';

function scriptContents(): string[] {
  return [...document.querySelectorAll('script[type="application/ld+json"][data-structured]')].map(
    (el) => el.textContent ?? '',
  );
}

function ldJson(index = 0): Record<string, unknown> {
  const scripts = scriptContents();
  return JSON.parse(scripts[index]) as Record<string, unknown>;
}

beforeEach(() => {
  resetSiteConfig();
  vi.stubEnv('VITE_SITE_NAME', '测试站点');
  vi.stubEnv('VITE_SITE_URL', 'https://example.com');
  vi.stubEnv('VITE_SITE_DESCRIPTION', '测试描述');
  vi.stubEnv('VITE_AUTHOR_NAME', '测试作者');
});

afterEach(() => {
  vi.unstubAllEnvs();
  resetSiteConfig();
  document.head.querySelectorAll('script[data-structured]').forEach((el) => el.remove());
});

describe('webSite', () => {
  it('generates valid WebSite JSON-LD', () => {
    const data = webSite();
    expect(data['@context']).toBe('https://schema.org');
    expect(data['@type']).toBe('WebSite');
    expect(data.name).toBe('测试站点');
    expect(data.description).toBe('测试描述');
    expect(data.url).toBe('https://example.com');
  });
});

describe('blogPosting', () => {
  it('generates valid BlogPosting with all fields', () => {
    const data = blogPosting({
      headline: '测试文章',
      description: '文章摘要',
      url: 'https://example.com/articles/test-post',
      datePublished: '2026-07-18',
      dateModified: '2026-07-18',
      authorName: '测试作者',
    });
    expect(data['@type']).toBe('BlogPosting');
    expect(data.headline).toBe('测试文章');
    expect(data.description).toBe('文章摘要');
    expect(data.url).toBe('https://example.com/articles/test-post');
    expect(data.datePublished).toBe('2026-07-18');
    expect(data.author).toEqual({ '@type': 'Person', name: '测试作者' });
  });

  it('includes image when provided', () => {
    const data = blogPosting({
      headline: '有图文章',
      description: '摘要',
      url: 'https://example.com/articles/img-post',
      datePublished: '2026-07-01',
      dateModified: '2026-07-01',
      authorName: '作者',
      image: '/custom-og.png',
    });
    expect(data.image).toBe('https://example.com/custom-og.png');
  });

  it('omits image when not provided', () => {
    const data = blogPosting({
      headline: '无图',
      description: '摘要',
      url: 'https://example.com/articles/no-img',
      datePublished: '2026-07-01',
      dateModified: '2026-07-01',
      authorName: '作者',
    });
    expect(data).not.toHaveProperty('image');
  });
});

describe('techArticle', () => {
  it('generates valid TechArticle', () => {
    const data = techArticle({
      headline: '测试笔记',
      description: '笔记描述',
      url: 'https://example.com/notes?note=42',
      datePublished: '2026-07-20T08:00:00Z',
      dateModified: '2026-07-22T10:00:00Z',
      authorName: '测试作者',
    });
    expect(data['@type']).toBe('TechArticle');
    expect(data.headline).toBe('测试笔记');
    expect(data.url).toBe('https://example.com/notes?note=42');
    expect(data.datePublished).toBe('2026-07-20T08:00:00Z');
    expect(data.dateModified).toBe('2026-07-22T10:00:00Z');
  });
});

describe('recipe', () => {
  it('generates valid Recipe with ingredients and steps', () => {
    const data = recipe({
      name: '测试菜',
      description: '美味的测试菜',
      url: 'https://example.com/recipes?dish=test-dish',
      image: '/dish.jpg',
      recipeIngredient: ['食材A', '食材B'],
      recipeInstructions: ['步骤一', '步骤二'],
      recipeCategory: '家常菜',
      authorName: '测试作者',
      datePublished: '2026-07-10T12:00:00Z',
      dateModified: '2026-07-15T12:00:00Z',
    });
    expect(data['@type']).toBe('Recipe');
    expect(data.name).toBe('测试菜');
    expect(data.recipeIngredient).toEqual(['食材A', '食材B']);
    expect(data.recipeInstructions).toEqual([
      { '@type': 'HowToStep', text: '步骤一' },
      { '@type': 'HowToStep', text: '步骤二' },
    ]);
    expect(data.image).toBe('https://example.com/dish.jpg');
    expect(data.author).toEqual({ '@type': 'Person', name: '测试作者' });
  });

  it('does not include aggregateRating', () => {
    const data = recipe({
      name: '测试菜',
      description: '描述',
      url: 'https://example.com/recipes?dish=test',
      image: '/img.jpg',
      recipeIngredient: [],
      recipeInstructions: [],
      recipeCategory: '家常菜',
      authorName: '作者',
      datePublished: '2026-01-01T00:00:00Z',
      dateModified: '2026-01-01T00:00:00Z',
    });
    expect(data).not.toHaveProperty('aggregateRating');
  });
});

describe('breadcrumbList', () => {
  it('generates valid BreadcrumbList with correct positions', () => {
    const data = breadcrumbList([
      { name: '首页', path: '/' },
      { name: '文章', path: '/articles' },
      { name: '文章标题', path: '/articles/test-slug' },
    ]);
    expect(data['@type']).toBe('BreadcrumbList');
    const items = data.itemListElement as Array<Record<string, unknown>>;
    expect(items).toHaveLength(3);
    expect(items[0].position).toBe(1);
    expect(items[0].name).toBe('首页');
    expect(items[0].item).toBe('https://example.com/');
    expect(items[2].position).toBe(3);
    expect(items[2].item).toBe('https://example.com/articles/test-slug');
  });
});

describe('useStructuredData - DOM integration', () => {
  it('applies single JSON-LD script to head', () => {
    const { apply } = useStructuredData();
    apply({ '@context': 'https://schema.org', '@type': 'WebSite', name: '测试' });
    const scripts = scriptContents();
    expect(scripts).toHaveLength(1);
    const parsed = JSON.parse(scripts[0]);
    expect(parsed.name).toBe('测试');
  });

  it('applies multiple JSON-LD scripts', () => {
    const { apply } = useStructuredData();
    apply([
      { '@context': 'https://schema.org', '@type': 'WebSite', name: '站点' },
      { '@context': 'https://schema.org', '@type': 'BreadcrumbList', itemListElement: [] },
    ]);
    expect(scriptContents()).toHaveLength(2);
  });

  it('removes old scripts on new apply', () => {
    const { apply } = useStructuredData();
    apply({ '@context': 'https://schema.org', '@type': 'WebSite', name: '旧' });
    apply({ '@context': 'https://schema.org', '@type': 'WebSite', name: '新' });
    expect(scriptContents()).toHaveLength(1);
    expect(ldJson().name as string).toBe('新');
  });

  it('clear removes all scripts', () => {
    const { apply, clear } = useStructuredData();
    apply({ '@context': 'https://schema.org', '@type': 'WebSite', name: '测试' });
    expect(scriptContents()).toHaveLength(1);
    clear();
    expect(scriptContents()).toHaveLength(0);
  });

  it('page switch removes old structured data', () => {
    const { apply: applyA } = useStructuredData();
    applyA({ '@context': 'https://schema.org', '@type': 'BlogPosting', headline: '文章' });
    expect(scriptContents()).toHaveLength(1);
    const { apply: applyB } = useStructuredData();
    applyB({ '@context': 'https://schema.org', '@type': 'Recipe', name: '菜谱' });
    expect(scriptContents()).toHaveLength(1);
    expect(ldJson()['@type'] as string).toBe('Recipe');
  });

  it('admin page switch clears structured data', () => {
    const { apply } = useStructuredData();
    apply({ '@context': 'https://schema.org', '@type': 'BlogPosting', headline: '文章' });
    const { clear } = useStructuredData();
    clear();
    expect(scriptContents()).toHaveLength(0);
  });

  it('all scripts are valid JSON', () => {
    const { apply } = useStructuredData();
    apply({ '@context': 'https://schema.org', '@type': 'WebSite', name: '测试' });
    apply([{ '@context': 'https://schema.org', '@type': 'BlogPosting', headline: '标题' }]);
    for (const raw of scriptContents()) {
      expect(() => JSON.parse(raw)).not.toThrow();
    }
  });

  it('no image remains from previous page when current has no image', () => {
    const { apply: applyA } = useStructuredData();
    applyA(
      blogPosting({
        headline: '旧',
        description: '',
        url: 'https://example.com/a',
        datePublished: '2026-01-01',
        dateModified: '2026-01-01',
        authorName: '作者',
        image: '/old-img.png',
      }),
    );
    const { apply: applyB } = useStructuredData();
    applyB(webSite());
    expect(JSON.stringify(scriptContents())).not.toContain('image');
  });
});
