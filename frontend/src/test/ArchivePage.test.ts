import { describe, it, expect, beforeEach, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import ArchivePage from '../pages/ArchivePage.vue'

const mockPosts = vi.fn()
const mockDishes = vi.fn()
const mockNotes = vi.fn()

vi.mock('../api/content', () => ({
  fetchPosts: (...args: unknown[]) => mockPosts(...args),
  fetchDishes: (...args: unknown[]) => mockDishes(...args),
  fetchPublishedNotes: (...args: unknown[]) => mockNotes(...args),
}))

beforeEach(() => {
  mockPosts.mockReset()
  mockDishes.mockReset()
  mockNotes.mockReset()
})

function mountPage() {
  const router = createRouter({ history: createWebHistory(), routes: [] })
  return mount(ArchivePage, { global: { plugins: [router] } })
}

describe('ArchivePage', () => {
  it('shows loading state initially', () => {
    mockPosts.mockReturnValue(new Promise(() => {}))
    mockDishes.mockReturnValue(new Promise(() => {}))
    mockNotes.mockReturnValue(new Promise(() => {}))
    const wrapper = mountPage()
    expect(wrapper.text()).toContain('正在加载')
  })

  it('shows all types by default after loading', async () => {
    mockPosts.mockResolvedValue({
      items: [{ slug: 'p', title: '文章标题', excerpt: '', date: '2026-07-01', readTime: 1, category: '测试', tags: [], color: '#000', number: '01', featured: false, status: 'PUBLISHED', content: '' }],
      page: 0, size: 10, totalElements: 1, totalPages: 1,
    })
    mockDishes.mockResolvedValue({
      items: [{ id: 1, slug: 'd', name: '菜品名称', summary: '', category: '家常', imageUrl: '', imageAlt: '', imageCredit: '', imageSourceUrl: '', prepMinutes: 10, difficulty: '简单', rating: 4, featured: false, published: true, displayOrder: 1, ingredients: [], steps: [], createdAt: '2026-06-01T00:00:00Z', updatedAt: '2026-06-01T00:00:00Z' }],
      page: 0, size: 12, totalElements: 1, totalPages: 1,
    })
    mockNotes.mockResolvedValue({
      items: [{ id: 1, title: '笔记标题', markdownContent: '', folder: 'Test', status: 'PUBLISHED', tags: [], sourceFileName: null, wordCount: 0, version: 1, createdAt: '2026-05-01T00:00:00Z', updatedAt: '2026-05-01T00:00:00Z' }],
      page: 0, size: 20, totalElements: 1, totalPages: 1,
    })
    const wrapper = mountPage()
    await flushPromises()
    const text = wrapper.text()
    expect(text).toContain('文章标题')
    expect(text).toContain('笔记标题')
    expect(text).toContain('菜品名称')
  })

  it('generates correct article URLs', async () => {
    mockPosts.mockResolvedValue({
      items: [{ slug: 'test-slug', title: 'Test Article', excerpt: '', date: '2026-07-01', readTime: 1, category: '', tags: [], color: '#000', number: '01', featured: false, status: 'PUBLISHED', content: '' }],
      page: 0, size: 10, totalElements: 1, totalPages: 1,
    })
    mockDishes.mockResolvedValue({ items: [], page: 0, size: 12, totalElements: 0, totalPages: 1 })
    mockNotes.mockResolvedValue({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 1 })
    const wrapper = mountPage()
    await flushPromises()
    expect(wrapper.find('a.archive-entry').attributes('href')).toBe('/articles/test-slug')
  })

  it('generates correct note URLs', async () => {
    mockPosts.mockResolvedValue({ items: [], page: 0, size: 10, totalElements: 0, totalPages: 1 })
    mockDishes.mockResolvedValue({ items: [], page: 0, size: 12, totalElements: 0, totalPages: 1 })
    mockNotes.mockResolvedValue({
      items: [{ id: 42, title: 'Test Note', markdownContent: '', folder: '', status: 'PUBLISHED', tags: [], sourceFileName: null, wordCount: 0, version: 1, createdAt: '2026-07-01T00:00:00Z', updatedAt: '2026-07-01T00:00:00Z' }],
      page: 0, size: 20, totalElements: 1, totalPages: 1,
    })
    const wrapper = mountPage()
    await flushPromises()
    expect(wrapper.find('a.archive-entry').attributes('href')).toBe('/notes?note=42')
  })

  it('generates correct dish URLs', async () => {
    mockPosts.mockResolvedValue({ items: [], page: 0, size: 10, totalElements: 0, totalPages: 1 })
    mockDishes.mockResolvedValue({
      items: [{ id: 1, slug: 'test-dish', name: 'Test Dish', summary: '', category: '', imageUrl: '', imageAlt: '', imageCredit: '', imageSourceUrl: '', prepMinutes: 10, difficulty: '简单', rating: 4, featured: false, published: true, displayOrder: 1, ingredients: [], steps: [], createdAt: '2026-07-01T00:00:00Z', updatedAt: '2026-07-01T00:00:00Z' }],
      page: 0, size: 12, totalElements: 1, totalPages: 1,
    })
    mockNotes.mockResolvedValue({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 1 })
    const wrapper = mountPage()
    await flushPromises()
    expect(wrapper.find('a.archive-entry').attributes('href')).toBe('/recipes?dish=test-dish')
  })

  it('shows error and retry button when all fetches fail', async () => {
    mockPosts.mockRejectedValue(new Error('fail'))
    mockDishes.mockRejectedValue(new Error('fail'))
    mockNotes.mockRejectedValue(new Error('fail'))
    const wrapper = mountPage()
    await flushPromises()
    expect(wrapper.text()).toContain('重试')
  })
})
