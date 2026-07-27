import { describe, it, expect, beforeEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useContentStore } from '../stores/contentStore'
import type { Post } from '../data'

const mockFetchPost = vi.fn()
const mockFetchPosts = vi.fn()
const mockSearchPosts = vi.fn()
const mockFetchCategories = vi.fn()

vi.mock('../api/content', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/content')>()
  return {
    ...actual,
    fetchPost: (...args: unknown[]) => mockFetchPost(...args),
    fetchPosts: (...args: unknown[]) => mockFetchPosts(...args),
    searchPosts: (...args: unknown[]) => mockSearchPosts(...args),
    fetchCategories: (...args: unknown[]) => mockFetchCategories(...args),
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
  mockSearchPosts.mockReset()
  mockFetchCategories.mockReset()
  mockFetchCategories.mockResolvedValue([])
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
})

describe('P1-2 摘要契约：正文只来自详情接口', () => {
  it('列表项为摘要（无 content）时 ensureArticleDetail 仍拉取详情', async () => {
    const store = useContentStore()
    const { content: _content, ...summary } = makePost('post-a')
    store.posts = [summary]

    store.setCurrentSlug('post-a')
    // 摘要在列表中：元信息可见但正文为空，等待详情
    expect(store.currentPost?.slug).toBe('post-a')
    expect(store.currentContent).toBe('')

    mockFetchPost.mockResolvedValue(makePost('post-a'))
    await store.ensureArticleDetail('post-a')
    expect(mockFetchPost).toHaveBeenCalledWith('post-a')
    expect(store.currentContent).toContain('post-a 正文')
  })

  it('内置回退模式下种子自带正文时不再拉取详情', async () => {
    const store = useContentStore()
    store.usingFallback = true
    store.posts = [makePost('post-a')]

    store.setCurrentSlug('post-a')
    await store.ensureArticleDetail('post-a')
    expect(mockFetchPost).not.toHaveBeenCalled()
    expect(store.currentContent).toContain('post-a 正文')
  })

  it('后端在线时即使本地种子带正文也必须拉取详情（种子 slug 与后端重叠的回归）', async () => {
    const store = useContentStore()
    // 默认非回退模式：posts 初始就是内置种子（自带 content），
    // 不能以此为由跳过详情——否则 loadRemoteContent 摘要替换后正文永久空白。
    store.posts = [makePost('clarity-by-design')]
    mockFetchPost.mockResolvedValue(makePost('clarity-by-design', { content: '<p>远端正文</p>' }))

    store.setCurrentSlug('clarity-by-design')
    await store.ensureArticleDetail('clarity-by-design')

    expect(mockFetchPost).toHaveBeenCalledWith('clarity-by-design')
    expect(store.currentContent).toBe('<p>远端正文</p>')
  })

  it('详情请求乱序返回时以最新 slug 为准（竞态守卫）', async () => {
    const store = useContentStore()
    let resolveA!: (value: Post) => void
    mockFetchPost
      .mockImplementationOnce(() => new Promise<Post>((resolve) => { resolveA = resolve }))
      .mockImplementationOnce(async () => makePost('post-b'))

    const callA = store.ensureArticleDetail('post-a')
    store.setCurrentSlug('post-b')
    await store.ensureArticleDetail('post-b')
    resolveA(makePost('post-a'))
    await callA

    expect(store.articleDetail?.slug).toBe('post-b')
    expect(store.currentContent).toContain('post-b 正文')
  })
})

describe('NF-5 归档服务端真分页', () => {
  it('loadArchive 以 page/size/sort 请求服务端，分页元数据来自响应', async () => {
    const store = useContentStore()
    mockFetchPosts.mockResolvedValue({
      items: [makePost('page-post')], page: 2, size: 6, totalElements: 20, totalPages: 4,
    })

    store.archivePage = 2
    await store.loadArchive()

    expect(mockFetchPosts).toHaveBeenCalledWith(2, 6, { sort: 'desc' })
    expect(store.archivePosts.map((p) => p.slug)).toEqual(['page-post'])
    expect(store.archiveTotal).toBe(20)
    expect(store.archiveTotalPages).toBe(4)
  })

  it('最早优先排序映射为 sort=asc', async () => {
    const store = useContentStore()
    mockFetchPosts.mockResolvedValue({ items: [], page: 0, size: 6, totalElements: 0, totalPages: 1 })

    store.sortOrder = 'oldest'
    await store.loadArchive()

    expect(mockFetchPosts).toHaveBeenLastCalledWith(0, 6, { sort: 'asc' })
  })

  it('搜索词经 POST /search 分页覆盖全部文章，命中带 L-8 元信息映射为摘要卡片', async () => {
    const store = useContentStore()
    mockSearchPosts.mockResolvedValue({
      results: [{
        type: 'POST', id: 9, title: '命中标题', excerpt: '命中摘要', category: '技术',
        url: '/articles/hit-post', color: null, number: null, slug: 'hit-post',
        date: '2026-06-15', readTime: 8, tags: ['vue', 'vite'],
      }],
      page: 0, size: 6, totalElements: 1, totalPages: 1,
    })

    store.query = '命中'
    await store.loadArchive()

    expect(mockSearchPosts).toHaveBeenCalledWith('命中', 0, 6, { sort: 'desc' })
    expect(store.archivePosts[0]?.slug).toBe('hit-post')
    expect(store.archivePosts[0]?.title).toBe('命中标题')
    // L-8：文章头元信息来自命中本身，不再伪造空值
    expect(store.archivePosts[0]?.date).toBe('2026-06-15')
    expect(store.archivePosts[0]?.readTime).toBe(8)
    expect(store.archivePosts[0]?.tags).toEqual(['vue', 'vite'])
    expect(store.archiveTotal).toBe(1)
  })

  it('搜索模式的分类过滤与排序下推服务端（L-8 契约透传，不再客户端补偿）', async () => {
    const store = useContentStore()
    // 先经 loadRemoteContent 装载分类页签（name→slug 映射来源）
    mockFetchPosts.mockResolvedValue({ items: [makePost('seed')], page: 0, size: 12, totalElements: 1, totalPages: 1 })
    mockFetchCategories.mockResolvedValue([{ name: '技术', slug: 'tech', publishedPostCount: 3 }])
    await store.loadRemoteContent()

    const hit = (slug: string, category: string) => ({
      type: 'POST', id: 1, title: slug, excerpt: '', category,
      url: `/articles/${slug}`, color: null, number: null, slug,
    })
    mockSearchPosts.mockResolvedValue({
      results: [hit('vue-post', '技术'), hit('vue-post-2', '技术')],
      page: 0, size: 6, totalElements: 2, totalPages: 1,
    })

    store.category = '技术'
    store.sortOrder = 'oldest'
    store.query = '关键词'
    await store.loadArchive()

    expect(mockSearchPosts).toHaveBeenCalledWith('关键词', 0, 6, { categorySlug: 'tech', sort: 'asc' })
    // 服务端已过滤，客户端不再二次筛掉命中
    expect(store.archivePosts.map((p) => p.slug)).toEqual(['vue-post', 'vue-post-2'])
    expect(store.archiveTotal).toBe(2)
  })

  it('L-9 精选文章由专用请求提供，出窗（不在最近列表）也能命中', async () => {
    const store = useContentStore()
    mockFetchPosts.mockImplementation((page: number, size: number, options?: { featured?: boolean }) =>
      Promise.resolve(options?.featured
        ? { items: [makePost('old-featured', { featured: true, date: '2024-01-01' })], page: 0, size: 1, totalElements: 1, totalPages: 1 }
        : { items: [makePost('recent-a'), makePost('recent-b')], page, size, totalElements: 2, totalPages: 1 }))
    await store.loadRemoteContent()

    expect(mockFetchPosts).toHaveBeenCalledWith(0, 1, { featured: true })
    expect(store.featuredPost?.slug).toBe('old-featured')
  })
})
