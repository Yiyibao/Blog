import { describe, it, expect, beforeEach, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import ArchivePage from '../pages/ArchivePage.vue'
import { useAuthStore } from '../stores/auth'

const mockPosts = vi.fn()
const mockDishes = vi.fn()
const mockNotes = vi.fn()

vi.mock('../api/content', () => ({
  fetchPosts: (...args: unknown[]) => mockPosts(...args),
  fetchDishes: (...args: unknown[]) => mockDishes(...args),
  fetchPublishedNotes: (...args: unknown[]) => mockNotes(...args),
}))

// L-16：归档 load() 按登录态决定是否请求笔记
function signIn() {
  useAuthStore().saveSession({
    token: 't', tokenType: 'Bearer', username: 'gxynf',
    expiresAt: '2099-12-31T23:59:59Z', role: 'ADMIN', displayName: '站长',
  })
}

beforeEach(() => {
  sessionStorage.clear()
  localStorage.clear()
  setActivePinia(createPinia())
  useAuthStore().clearSession()
  mockPosts.mockReset()
  mockDishes.mockReset()
  mockNotes.mockReset()
})

async function mountPage(initialUrl = '/archive') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div>Home</div>' } },
      { path: '/archive', name: 'archive', component: ArchivePage },
      { path: '/articles', name: 'articles', component: { template: '<div>Articles</div>' } },
      { path: '/articles/:slug', name: 'article', component: { template: '<div>Article</div>' } },
      { path: '/notes', name: 'notes', component: { template: '<div>Notes</div>' } },
      { path: '/recipes', name: 'recipes', component: { template: '<div>Recipes</div>' } },
    ],
  })
  await router.push(initialUrl)
  await router.isReady()
  return {
    wrapper: mount(ArchivePage, { global: { plugins: [router] } }),
    router,
  }
}

describe('ArchivePage', () => {
  it('shows loading state initially', async () => {
    mockPosts.mockReturnValue(new Promise(() => {}))
    mockDishes.mockReturnValue(new Promise(() => {}))
    mockNotes.mockReturnValue(new Promise(() => {}))
    const { wrapper } = await mountPage()
    expect(wrapper.text()).toContain('正在加载')
  })

  it('shows all types by default after loading in timeline view', async () => {
    // L-16：登录态下时间轴含笔记；P1-2：列表接口为摘要 DTO，不含 content 正文
    signIn()
    mockPosts.mockResolvedValue({
      items: [{ slug: 'p', title: '文章标题', excerpt: '', date: '2026-07-01', readTime: 1, category: '测试', tags: [], color: '#000', number: '01', featured: false, status: 'PUBLISHED' }],
      page: 0, size: 10, totalElements: 1, totalPages: 1,
    })
    mockDishes.mockResolvedValue({
      items: [{ id: 1, slug: 'd', name: '菜品名称', summary: '', category: '家常', imageUrl: '', imageAlt: '', imageCredit: '', imageSourceUrl: '', prepMinutes: 10, difficulty: '简单', rating: 4, featured: false, published: true, displayOrder: 1, favoriteCount: 0, ingredients: [], steps: [], createdAt: '2026-06-01T00:00:00Z', updatedAt: '2026-06-01T00:00:00Z' }],
      page: 0, size: 12, totalElements: 1, totalPages: 1,
    })
    mockNotes.mockResolvedValue({
      items: [{ id: 1, title: '笔记标题', folder: 'Test', status: 'PUBLISHED', tags: [], sourceFileName: null, wordCount: 0, version: 1, createdAt: '2026-05-01T00:00:00Z', updatedAt: '2026-05-01T00:00:00Z' }],
      page: 0, size: 20, totalElements: 1, totalPages: 1,
    })
    const { wrapper } = await mountPage()
    await flushPromises()
    const text = wrapper.text()
    expect(text).toContain('文章标题')
    expect(text).toContain('笔记标题')
    expect(text).toContain('菜品名称')
  })

  it('L-13: renders graph above timeline in one page flow (legacy ?view=graph ignored)', async () => {
    mockPosts.mockResolvedValue({ items: [], page: 0, size: 10, totalElements: 0, totalPages: 1 })
    mockDishes.mockResolvedValue({ items: [], page: 0, size: 12, totalElements: 0, totalPages: 1 })
    mockNotes.mockResolvedValue({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 1 })

    const { wrapper } = await mountPage('/archive?view=graph')
    await flushPromises()

    // 图谱与时间轴同页纵向呈现，图谱在前
    const graph = wrapper.find('.archive-graph-view')
    const timeline = wrapper.find('.archive-timeline-view')
    expect(graph.exists()).toBe(true)
    expect(timeline.exists()).toBe(true)
    const html = wrapper.html()
    expect(html.indexOf('archive-graph-view')).toBeLessThan(html.indexOf('archive-timeline-view'))
    // 视图切换与类型分类 tab 已移除
    expect(wrapper.find('.view-switch-btn').exists()).toBe(false)
    expect(wrapper.find('.archive-filters').exists()).toBe(false)
  })

  it('keeps relation filter in URL and strips legacy view/type params on update', async () => {
    mockPosts.mockResolvedValue({
      items: [{ slug: 'p1', title: 'Vue文章', excerpt: '', date: '2026-07-01', readTime: 1, category: 'Vue', tags: ['Vue'], color: '#000', number: '01', featured: false, status: 'PUBLISHED', content: '' }],
      page: 0, size: 10, totalElements: 1, totalPages: 1,
    })
    mockDishes.mockResolvedValue({ items: [], page: 0, size: 12, totalElements: 0, totalPages: 1 })
    mockNotes.mockResolvedValue({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 1 })

    const { wrapper, router } = await mountPage('/archive?type=article&relation=Vue')
    await flushPromises()

    expect(wrapper.text()).toContain('关联: Vue')

    // 清除关联时旧 view/type 参数一并剥离
    await wrapper.find('.relation-pill button').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.relation).toBeUndefined()
    expect(router.currentRoute.value.query.type).toBeUndefined()
    expect(router.currentRoute.value.query.view).toBeUndefined()
  })

  it('generates correct article, note, and dish URLs', async () => {
    mockPosts.mockResolvedValue({
      items: [{ slug: 'test-slug', title: 'Test Article', excerpt: '', date: '2026-07-01', readTime: 1, category: '', tags: [], color: '#000', number: '01', featured: false, status: 'PUBLISHED', content: '' }],
      page: 0, size: 10, totalElements: 1, totalPages: 1,
    })
    mockDishes.mockResolvedValue({ items: [], page: 0, size: 12, totalElements: 0, totalPages: 1 })
    mockNotes.mockResolvedValue({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 1 })
    const { wrapper } = await mountPage()
    await flushPromises()
    expect(wrapper.find('a.archive-entry').attributes('href')).toBe('/articles/test-slug')
  })

  it('shows error and retry button when all fetches fail', async () => {
    signIn()
    mockPosts.mockRejectedValue(new Error('fail'))
    mockDishes.mockRejectedValue(new Error('fail'))
    mockNotes.mockRejectedValue(new Error('fail'))
    const { wrapper } = await mountPage()
    await flushPromises()
    expect(wrapper.text()).toContain('重试')
  })

  it('L-16: guests never request notes and see no partial-failure notice', async () => {
    mockPosts.mockResolvedValue({ items: [], page: 0, size: 10, totalElements: 0, totalPages: 1 })
    mockDishes.mockResolvedValue({ items: [], page: 0, size: 12, totalElements: 0, totalPages: 1 })

    const { wrapper } = await mountPage()
    await flushPromises()

    expect(mockNotes).not.toHaveBeenCalled()
    expect(wrapper.find('.archive-partial-notice').exists()).toBe(false)
  })

  it('L-16: guests with both public fetches failing get the full error state', async () => {
    mockPosts.mockRejectedValue(new Error('fail'))
    mockDishes.mockRejectedValue(new Error('fail'))

    const { wrapper } = await mountPage()
    await flushPromises()

    expect(wrapper.find('.archive-error').exists()).toBe(true)
  })

  it('fetches all pages when total items exceed single page (>50)', async () => {
    const manyPosts = Array.from({ length: 55 }, (_, i) => ({
      slug: `post-${i}`, title: `文章${i}`, excerpt: '', date: '2026-07-01',
      readTime: 1, category: '测试', tags: [], color: '#000', number: String(i + 1).padStart(2, '0'),
      featured: false, status: 'PUBLISHED',
    }))
    const page0 = manyPosts.slice(0, 50)
    const page1 = manyPosts.slice(50, 55)
    mockPosts
      .mockResolvedValueOnce({ items: page0, page: 0, size: 50, totalElements: 55, totalPages: 2 })
      .mockResolvedValueOnce({ items: page1, page: 1, size: 50, totalElements: 55, totalPages: 2 })
    mockDishes.mockResolvedValue({ items: [], page: 0, size: 12, totalElements: 0, totalPages: 1 })
    const { wrapper } = await mountPage()
    await flushPromises()
    expect(mockPosts).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('文章0')
    expect(wrapper.text()).toContain('文章54')
    expect(wrapper.text()).toContain('55 条记录')
  })

  it('fetches pages beyond 19 when totalPages > 20 (no silent cap)', async () => {
    const pageSize = 50
    const pageCount = 25
    for (let p = 0; p < pageCount; p++) {
      mockPosts.mockResolvedValueOnce({
        items: [{
          slug: `p${p}`, title: `第${p}页`, excerpt: '', date: '2026-07-01',
          readTime: 1, category: '测试', tags: [], color: '#000',
          number: String(p + 1), featured: false, status: 'PUBLISHED',
        }],
        page: p, size: pageSize, totalElements: pageCount * pageSize, totalPages: pageCount,
      })
    }
    mockDishes.mockResolvedValue({ items: [], page: 0, size: 12, totalElements: 0, totalPages: 1 })
    const { wrapper } = await mountPage()
    await flushPromises()
    expect(mockPosts).toHaveBeenCalledTimes(25)
    expect(wrapper.text()).toContain('第20页')
    expect(wrapper.text()).toContain('第24页')
  })

  it('partial failure shows warning banner and still displays successful content', async () => {
    mockPosts.mockResolvedValue({
      items: [{ slug: 'ok-post', title: '正常文章', excerpt: '', date: '2026-07-01', readTime: 1, category: '测试', tags: [], color: '#000', number: '01', featured: false, status: 'PUBLISHED' }],
      page: 0, size: 50, totalElements: 1, totalPages: 1,
    })
    mockDishes.mockRejectedValue(new Error('dish fail'))
    const { wrapper } = await mountPage()
    await flushPromises()
    expect(wrapper.find('.archive-partial-notice').exists()).toBe(true)
    expect(wrapper.text()).toContain('菜谱')
    expect(wrapper.text()).toContain('部分内容')
    expect(wrapper.text()).toContain('正常文章')
  })

  it('second page fetch failure does not discard first page results', async () => {
    const page0 = Array.from({ length: 50 }, (_, i) => ({
      slug: `p${i}`, title: `文章${i}`, excerpt: '', date: '2026-07-01',
      readTime: 1, category: '测试', tags: [], color: '#000', number: String(i + 1), featured: false, status: 'PUBLISHED',
    }))
    mockPosts
      .mockResolvedValueOnce({ items: page0, page: 0, size: 50, totalElements: 75, totalPages: 2 })
      .mockRejectedValueOnce(new Error('page 1 fail'))
    mockDishes.mockResolvedValue({ items: [], page: 0, size: 12, totalElements: 0, totalPages: 1 })
    const { wrapper } = await mountPage()
    await flushPromises()
    expect(wrapper.text()).toContain('文章0')
    expect(wrapper.text()).toContain('文章49')
    expect(wrapper.find('.archive-partial-notice').exists()).toBe(true)
  })
})
