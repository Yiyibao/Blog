import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { usePageMeta, cleanText } from '../composables/usePageMeta';
import { resetSiteConfig } from '../config/site';

function metaContent(name: string): string {
  return document.querySelector(`meta[name="${name}"]`)?.getAttribute('content') ?? '';
}

function propertyContent(prop: string): string {
  return document.querySelector(`meta[property="${prop}"]`)?.getAttribute('content') ?? '';
}

function linkHref(rel: string): string {
  return document.querySelector(`link[rel="${rel}"]`)?.getAttribute('href') ?? '';
}

function countMeta(name: string): number {
  return document.querySelectorAll(`meta[name="${name}"]`).length;
}

function countProperty(prop: string): number {
  return document.querySelectorAll(`meta[property="${prop}"]`).length;
}

function countLink(rel: string): number {
  return document.querySelectorAll(`link[rel="${rel}"]`).length;
}

beforeEach(() => {
  resetSiteConfig();
  vi.stubEnv('VITE_SITE_NAME', '测试站点');
  vi.stubEnv('VITE_SITE_URL', 'https://example.com');
  vi.stubEnv('VITE_SITE_DESCRIPTION', '测试描述');
  vi.stubEnv('VITE_SOCIAL_IMAGE', '/default-og.png');
});

afterEach(() => {
  vi.unstubAllEnvs();
  resetSiteConfig();
  document.title = '';
  document.head.querySelectorAll('meta, link').forEach((el) => el.remove());
});

describe('cleanText', () => {
  it('strips HTML tags', () => {
    expect(cleanText('<p>Hello <b>world</b></p>', 100)).toBe('Hello world');
  });

  it('strips Markdown markers', () => {
    expect(cleanText('# Title\n\nSome **bold** text', 100)).toBe('Title Some bold text');
  });

  it('collapses whitespace', () => {
    expect(cleanText('  too    much   space  ', 100)).toBe('too much space');
  });

  it('truncates to max length', () => {
    expect(cleanText('abcdefghij', 5)).toBe('abcde');
  });

  it('returns empty string for empty input', () => {
    expect(cleanText('', 100)).toBe('');
  });
});

describe('usePageMeta', () => {
  it('sets document title', () => {
    const { apply } = usePageMeta();
    apply({ title: '首页' });
    expect(document.title).toBe('首页 | 测试站点');
  });

  it('uses site name only when title is empty string', () => {
    const { apply } = usePageMeta();
    apply({ title: '' });
    expect(document.title).toBe('测试站点');
  });

  it('sets description meta', () => {
    const { apply } = usePageMeta();
    apply({ title: '首页', description: '站点描述' });
    expect(metaContent('description')).toBe('站点描述');
  });

  it('removes description when not provided', () => {
    const { apply } = usePageMeta();
    apply({ title: '首页', description: '旧描述' });
    apply({ title: '关于' });
    expect(metaContent('description')).toBe('');
  });

  it('sets canonical link', () => {
    const { apply } = usePageMeta();
    apply({ title: '文章', canonicalPath: '/articles' });
    expect(linkHref('canonical')).toBe('https://example.com/articles');
  });

  it('removes canonical when not provided', () => {
    const { apply } = usePageMeta();
    apply({ title: '文章', canonicalPath: '/articles' });
    apply({ title: '首页' });
    expect(countLink('canonical')).toBe(0);
  });

  it('does not duplicate canonical when called multiple times', () => {
    const { apply } = usePageMeta();
    apply({ title: 'A', canonicalPath: '/a' });
    apply({ title: 'B', canonicalPath: '/b' });
    expect(countLink('canonical')).toBe(1);
    expect(linkHref('canonical')).toBe('https://example.com/b');
  });

  it('sets robots meta', () => {
    const { apply } = usePageMeta();
    apply({ title: '后台', robots: 'noindex, nofollow' });
    expect(metaContent('robots')).toBe('noindex, nofollow');
  });

  it('clears robots when not provided', () => {
    const { apply } = usePageMeta();
    apply({ title: '后台', robots: 'noindex, nofollow' });
    apply({ title: '首页' });
    expect(metaContent('robots')).toBe('');
  });
});

describe('Open Graph', () => {
  it('sets og:title and og:type', () => {
    const { apply } = usePageMeta();
    apply({ title: '文章', openGraph: { title: '文章', type: 'website' } });
    expect(propertyContent('og:title')).toBe('文章');
    expect(propertyContent('og:type')).toBe('website');
  });

  it('resolves relative og:image to absolute', () => {
    const { apply } = usePageMeta();
    apply({ title: '文章', openGraph: { title: '文章', image: '/og.png' } });
    expect(propertyContent('og:image')).toBe('https://example.com/og.png');
  });

  it('keeps absolute og:image unchanged', () => {
    const { apply } = usePageMeta();
    apply({ title: '菜品', openGraph: { title: '菜品', image: 'https://cdn.example.com/img.jpg' } });
    expect(propertyContent('og:image')).toBe('https://cdn.example.com/img.jpg');
  });

  it('removes all OG tags when not provided', () => {
    const { apply } = usePageMeta();
    apply({ title: '文章', openGraph: { title: '文章' } });
    apply({ title: '首页' });
    expect(countProperty('og:title')).toBe(0);
    expect(countProperty('og:type')).toBe(0);
  });

  it('does not leave duplicate og:title', () => {
    const { apply } = usePageMeta();
    apply({ title: 'A', openGraph: { title: 'A' } });
    apply({ title: 'B', openGraph: { title: 'B' } });
    expect(countProperty('og:title')).toBe(1);
  });
});

describe('Twitter Card', () => {
  it('sets twitter:title and twitter:card', () => {
    const { apply } = usePageMeta();
    apply({ title: '文章', twitter: { title: '文章', card: 'summary_large_image' } });
    expect(metaContent('twitter:title')).toBe('文章');
    expect(metaContent('twitter:card')).toBe('summary_large_image');
  });

  it('removes all twitter tags when not provided', () => {
    const { apply } = usePageMeta();
    apply({ title: '文章', twitter: { title: '文章' } });
    apply({ title: '首页' });
    expect(countMeta('twitter:title')).toBe(0);
    expect(countMeta('twitter:card')).toBe(0);
  });
});

describe('Route switch cleanup', () => {
  it('removes article image after switching to image-less page', () => {
    const { apply } = usePageMeta();
    apply({ title: '文章', openGraph: { title: '文章', image: '/post-img.jpg' } });
    expect(propertyContent('og:image')).toBe('https://example.com/post-img.jpg');
    apply({ title: '关于', openGraph: { title: '关于' } });
    expect(countProperty('og:image')).toBe(0);
  });

  it('sets noindex on admin page', () => {
    const { apply } = usePageMeta();
    apply({ title: '后台', robots: 'noindex, nofollow' });
    expect(metaContent('robots')).toBe('noindex, nofollow');
  });

  it('removes noindex when switching back to public page', () => {
    const { apply } = usePageMeta();
    apply({ title: '后台', robots: 'noindex, nofollow' });
    apply({ title: '首页' });
    expect(metaContent('robots')).toBe('');
  });

  it('does not keep previous og:type from article after switching pages', () => {
    const { apply } = usePageMeta();
    apply({ title: '文章', openGraph: { title: '文章', type: 'article' } });
    apply({ title: '首页', openGraph: { title: '首页', type: 'website' } });
    expect(propertyContent('og:type')).toBe('website');
  });

  it('clears canonical between unrelated pages', () => {
    const { apply } = usePageMeta();
    apply({ title: '文章', canonicalPath: '/articles/post-1' });
    apply({ title: '首页' });
    expect(countLink('canonical')).toBe(0);
  });
});

describe('Detail page meta', () => {
  it('applies article detail with excerpt', () => {
    const { apply } = usePageMeta();
    apply({
      title: '文章标题',
      description: '文章摘要内容',
      canonicalPath: '/articles/test-slug',
      openGraph: {
        title: '文章标题',
        description: '文章摘要内容',
        type: 'article',
        url: '/articles/test-slug',
      },
      twitter: { title: '文章标题', description: '文章摘要内容' },
    });
    expect(document.title).toBe('文章标题 | 测试站点');
    expect(metaContent('description')).toBe('文章摘要内容');
    expect(linkHref('canonical')).toBe('https://example.com/articles/test-slug');
    expect(propertyContent('og:type')).toBe('article');
    expect(metaContent('twitter:title')).toBe('文章标题');
  });

  it('applies 404 meta when content not found', () => {
    const { apply } = usePageMeta();
    apply({
      title: '页面不存在',
      description: '文章不存在或已被归档',
      robots: 'noindex, nofollow',
    });
    expect(document.title).toBe('页面不存在 | 测试站点');
    expect(metaContent('robots')).toBe('noindex, nofollow');
    expect(countLink('canonical')).toBe(0);
    expect(countProperty('og:title')).toBe(0);
  });
});
