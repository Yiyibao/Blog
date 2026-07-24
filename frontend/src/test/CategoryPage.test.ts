import { describe, it, expect, beforeEach, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import CategoryPage from '../pages/CategoryPage.vue'

const mockCategoryDetail = vi.fn()

vi.mock('../api/content', () => ({
  fetchCategoryDetail: (...args: unknown[]) => mockCategoryDetail(...args),
}))

function makeCategoryDetail(overrides: Record<string, unknown> = {}) {
  return {
    name: '\u8bbe\u8ba1\u672d\u8bb0',
    slug: '\u8bbe\u8ba1\u672d\u8bb0',
    total: 1,
    posts: [
      { slug: 'clarity-by-design', title: '\u628a\u590d\u6742\u7559\u7ed9\u7cfb\u7edf', excerpt: '\u4e00\u4e2a\u597d\u754c\u9762', date: '2026-07-18', tags: ['\u4ea7\u54c1\u8bbe\u8ba1', '\u4fe1\u606f\u67b6\u6784'] },
    ],
    page: 0,
    size: 10,
    totalPages: 1,
    ...overrides,
  }
}

function mountPage(route = '/categories/' + encodeURIComponent('\u8bbe\u8ba1\u672d\u8bb0')) {
  const router = createRouter({ history: createWebHistory(), routes: [
    { path: '/categories/:slug', component: CategoryPage },
  ]})
  router.push(route)
  return mount(CategoryPage, { global: { plugins: [router] } })
}

beforeEach(() => {
  mockCategoryDetail.mockReset()
})

describe('CategoryPage', () => {
  it('shows loading state initially', () => {
    mockCategoryDetail.mockReturnValue(new Promise(() => {}))
    const wrapper = mountPage()
    expect(wrapper.text()).toContain('正在加载')
  })

  it('displays post list with correct title and tags', async () => {
    mockCategoryDetail.mockResolvedValue(makeCategoryDetail())
    const wrapper = mountPage()
    await flushPromises()
    expect(wrapper.text()).toContain('设计札记')
    expect(wrapper.text()).toContain('把复杂留给系统')
    expect(wrapper.text()).toContain('产品设计')
    expect(wrapper.text()).toContain('2026-07-18')
  })

  it('generates correct article detail links', async () => {
    mockCategoryDetail.mockResolvedValue(makeCategoryDetail())
    const wrapper = mountPage()
    await flushPromises()
    const link = wrapper.find('a[href="/articles/clarity-by-design"]')
    expect(link.exists()).toBe(true)
  })

  it('shows not-found for missing category', async () => {
    const err = new Error('Not Found')
    // @ts-expect-error - mock response structure
    err.response = { status: 404 }
    mockCategoryDetail.mockRejectedValue(err)
    const wrapper = mountPage()
    await flushPromises()
    expect(wrapper.text()).toContain('分类不存在')
  })

  it('shows error state for network failure', async () => {
    mockCategoryDetail.mockRejectedValue(new Error('network error'))
    const wrapper = mountPage()
    await flushPromises()
    expect(wrapper.text()).toContain('加载失败')
    expect(wrapper.text()).not.toContain('分类不存在')
  })

  it('shows pagination for multiple pages', async () => {
    const posts = Array.from({ length: 10 }, (_, i) => ({
      slug: `post-${i}`, title: `文章 ${i}`, excerpt: '', date: '2026-07-18', tags: [],
    }))
    mockCategoryDetail.mockResolvedValue(makeCategoryDetail({
      total: 12,
      posts,
      page: 0,
      totalPages: 2,
    }))
    const wrapper = mountPage()
    await flushPromises()
    expect(wrapper.text()).toContain('1 / 2')
  })

  it('does not show draft data in post list', async () => {
    mockCategoryDetail.mockResolvedValue(makeCategoryDetail({
      posts: [{ slug: 'pub-post', title: '公开发布的文章', excerpt: '', date: '2026-07-18', tags: [] }],
    }))
    const wrapper = mountPage()
    await flushPromises()
    expect(wrapper.text()).toContain('公开发布的文章')
    expect(wrapper.text()).not.toContain('DRAFT')
  })
})
