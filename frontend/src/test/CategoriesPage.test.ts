import { describe, it, expect, beforeEach, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import CategoriesPage from '../pages/CategoriesPage.vue'

const mockCategories = vi.fn()

vi.mock('../api/content', () => ({
  fetchCategories: (...args: unknown[]) => mockCategories(...args),
}))

beforeEach(() => {
  mockCategories.mockReset()
})

function mountPage() {
  const router = createRouter({ history: createWebHistory(), routes: [] })
  return mount(CategoriesPage, { global: { plugins: [router] } })
}

describe('CategoriesPage', () => {
  it('shows loading state initially', () => {
    mockCategories.mockReturnValue(new Promise(() => {}))
    const wrapper = mountPage()
    expect(wrapper.text()).toContain('正在加载')
  })

  it('shows error state and retry button', async () => {
    mockCategories.mockRejectedValue(new Error('network error'))
    const wrapper = mountPage()
    await flushPromises()
    expect(wrapper.text()).toContain('加载失败')
    expect(wrapper.find('button').exists()).toBe(true)
  })

  it('shows empty state when no categories', async () => {
    mockCategories.mockResolvedValue([])
    const wrapper = mountPage()
    await flushPromises()
    expect(wrapper.text()).toContain('暂无分类')
  })

  it('displays categories with names and counts', async () => {
    mockCategories.mockResolvedValue([
      { name: '工程实践', slug: '\u5de5\u7a0b\u5b9e\u8df5', publishedPostCount: 2 },
      { name: '日常观察', slug: '\u65e5\u5e38\u89c2\u5bdf', publishedPostCount: 2 },
    ])
    const wrapper = mountPage()
    await flushPromises()
    expect(wrapper.text()).toContain('工程实践')
    expect(wrapper.text()).toContain('日常观察')
    expect(wrapper.text()).toContain('2 篇已发布文章')
  })

  it('generates correct category links', async () => {
    mockCategories.mockResolvedValue([
      { name: '\u8bbe\u8ba1\u672d\u8bb0', slug: '\u8bbe\u8ba1\u672d\u8bb0', publishedPostCount: 1 },
    ])
    const wrapper = mountPage()
    await flushPromises()
    const link = wrapper.find('a.category-card')
    expect(link.attributes('href')).toContain(encodeURIComponent('\u8bbe\u8ba1\u672d\u8bb0'))
  })

  it('displays categories in API response order', async () => {
    mockCategories.mockResolvedValue([
      { name: 'B分类', slug: 'b', publishedPostCount: 3 },
      { name: 'A分类', slug: 'a', publishedPostCount: 1 },
      { name: 'C分类', slug: 'c', publishedPostCount: 1 },
    ])
    const wrapper = mountPage()
    await flushPromises()
    const names = wrapper.findAll('.category-body h2').map(el => el.text())
    expect(names).toEqual(['B分类', 'A分类', 'C分类'])
  })
})
