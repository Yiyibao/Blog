import { describe, it, expect, beforeEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useContentStore } from '../stores/contentStore'
import type { Post } from '../data'

const mockFetchPost = vi.fn()
const mockFetchPosts = vi.fn()

vi.mock('../api/content', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/content')>()
  return {
    ...actual,
    fetchPost: (...args: unknown[]) => mockFetchPost(...args),
    fetchPosts: (...args: unknown[]) => mockFetchPosts(...args),
  }
})

function makePost(slug: string, overrides: Partial<Post> = {}): Post {
  return {
    slug,
    title: `标题 ${slug}`,
    excerpt: `摘要 ${slug}`,
    date: '2026-07-01',
    readTime: 5,
    category: '技术',
    tags: ['vue'],
    color: '#333',
    number: '01',
    content: `<h2 id="s1">${slug} 正文</h2>`,
    ...overrides,
  }
}

beforeEach(() => {
  setActivePinia(createPinia())
  mockFetchPost.mockReset()
  mockFetchPosts.mockReset()
})

describe('NF-3 currentPost 响应式', () => {
  it('currentSlug 变化时 currentPost 立即切换（文章间跳转渲染旧文章的回归测试）', () => {
    const store = useContentStore()
    store.posts = [makePost('post-a'), makePost('post-b')]

    store.setCurrentSlug('post-a')
    expect(store.currentPost?.slug).toBe('post-a')

    // 模拟从文章 A 点击相关文章 B：仅路由参数变化，posts 不变
    store.setCurrentSlug('post-b')
    expect(store.currentPost?.slug).toBe('post-b')
    expect(store.currentPost?.title).toBe('标题 post-b')
  })

  it('slug 为空时 currentPost 为 null', () => {
    const store = useContentStore()
    store.posts = [makePost('post-a')]
    store.setCurrentSlug('')
    expect(store.currentPost).toBeNull()
  })

  it('列表中不存在的 slug 通过 ensureArticleDetail 拉取详情后可见', async () => {
    const store = useContentStore()
    store.posts = [makePost('post-a')]
    const detail = makePost('post-hidden', { content: '<p>第 51 篇</p>' })
    mockFetchPost.mockResolvedValue(detail)

    store.setCurrentSlug('post-hidden')
    expect(store.currentPost).toBeNull()

    await store.ensureArticleDetail('post-hidden')
    expect(mockFetchPost).toHaveBeenCalledWith('post-hidden')
    expect(store.currentPost?.slug).toBe('post-hidden')
  })

  it('relatedPosts 随 currentSlug 联动', () => {
    const store = useContentStore()
    store.posts = [
      makePost('post-a', { tags: ['vue'] }),
      makePost('post-b', { tags: ['vue'] }),
      makePost('post-c', { tags: ['java'] }),
    ]
    store.setCurrentSlug('post-a')
    expect(store.relatedPosts.map((p) => p.slug)).toEqual(['post-b'])

    store.setCurrentSlug('post-c')
    expect(store.relatedPosts).toEqual([])
  })
})
