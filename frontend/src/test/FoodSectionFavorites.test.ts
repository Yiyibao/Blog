import { describe, it, expect, beforeEach, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import { createPinia, type Pinia } from 'pinia'
import FoodSection from '../components/food/FoodSection.vue'
import { useUiStore } from '../stores/uiStore'
import type { Dish } from '../data'

const mockFetchDishes = vi.fn()
const mockFetchDish = vi.fn()
const mockFavoriteDish = vi.fn()
const mockFetchDishFavorites = vi.fn()

vi.mock('../api/kitchen', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/kitchen')>()
  return { ...actual,
    fetchDailyMenu: vi.fn().mockResolvedValue({ exists: false, date: '2026-07-27', status: 'DRAFT', note: '', version: null, items: [], updatedBy: null, updatedAt: null }),
    fetchMealLogs: vi.fn().mockResolvedValue({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 1 }),
    fetchDishStats: vi.fn().mockResolvedValue([]),
    createMealLog: vi.fn().mockResolvedValue({}),
  }
})

vi.mock('../api/content', () => ({
  fetchDishes: (...args: unknown[]) => mockFetchDishes(...args),
  fetchDish: (...args: unknown[]) => mockFetchDish(...args),
  favoriteDish: (...args: unknown[]) => mockFavoriteDish(...args),
  fetchDishFavorites: (...args: unknown[]) => mockFetchDishFavorites(...args),
}))

function makeDish(overrides: Partial<Dish> = {}): Dish {
  return {
    id: 1,
    slug: 'mapo-tofu',
    name: '麻婆豆腐',
    summary: '下饭神器',
    category: '家常菜',
    imageUrl: '/food/mapo.jpg',
    imageAlt: '麻婆豆腐',
    imageCredit: '摄影师',
    imageSourceUrl: 'https://example.com',
    prepMinutes: 20,
    difficulty: '家常',
    rating: 4.8,
    featured: false,
    published: true,
    displayOrder: 0,
    favoriteCount: 3,
    ingredients: ['豆腐 300 克'],
    steps: ['煮'],
    createdAt: '2026-06-01T00:00:00Z',
    updatedAt: '2026-06-01T00:00:00Z',
    ...overrides,
  }
}

function pageOf<T>(items: T[]) {
  return { items, page: 0, size: 12, totalElements: items.length, totalPages: 1 }
}

const favoriteItem = (slug: string, name: string, favoriteCount: number) => ({
  slug, name, summary: `${name}的简介`, imageUrl: `/food/${slug}.jpg`, favoriteCount,
})

let pinia: Pinia
let router: Router

async function mountSection(initialUrl = '/recipes') {
  pinia = createPinia()
  router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/recipes', name: 'recipes', component: { template: '<div />' } }],
  })
  await router.push(initialUrl)
  await router.isReady()
  const wrapper = mount(FoodSection, {
    global: { plugins: [router, pinia] },
    attachTo: document.body,
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  mockFetchDishes.mockReset()
  mockFetchDish.mockReset()
  mockFavoriteDish.mockReset()
  mockFetchDishFavorites.mockReset()
  document.body.innerHTML = ''
  localStorage.clear()
  mockFetchDishes.mockResolvedValue(pageOf([
    makeDish(),
    makeDish({ id: 2, slug: 'plain-congee', name: '白粥', favoriteCount: 0 }),
  ]))
  mockFetchDishFavorites.mockResolvedValue(pageOf([
    favoriteItem('mapo-tofu', '麻婆豆腐', 9),
    favoriteItem('plain-congee', '白粥', 2),
  ]))
})

describe('FoodSection favorites integration', () => {
  it('shows a read-only heart badge on cards with favorites and none at zero', async () => {
    const wrapper = await mountSection()
    const cards = wrapper.findAll('.dish-card')
    expect(cards[0].text()).toContain('♥ 3')
    expect(cards[0].attributes('aria-label')).toContain('点亮 3 次')
    expect(cards[1].text()).not.toContain('♥')
    expect(cards[1].attributes('aria-label')).not.toContain('点亮')
  })

  it('renders the taste ranking from the real favorites endpoint', async () => {
    const wrapper = await mountSection()
    expect(mockFetchDishFavorites).toHaveBeenCalled()
    const board = wrapper.find('.food-ranking')
    expect(board.exists()).toBe(true)
    expect(board.text()).toContain('麻婆豆腐')
    expect(board.text()).toContain('点亮')
    expect(board.text()).toContain('9')
    expect(board.text()).not.toContain('SCORE')
  })

  it('hides the ranking board entirely when nobody has favorited yet', async () => {
    mockFetchDishFavorites.mockResolvedValue(pageOf([favoriteItem('mapo-tofu', '麻婆豆腐', 0)]))
    const wrapper = await mountSection()
    expect(wrapper.find('.food-ranking').exists()).toBe(false)
  })

  it('hides the ranking board when the favorites endpoint fails', async () => {
    mockFetchDishFavorites.mockRejectedValue(new Error('boom'))
    const wrapper = await mountSection()
    expect(wrapper.find('.food-ranking').exists()).toBe(false)
    expect(wrapper.findAll('.dish-card').length).toBeGreaterThan(0)
  })

  it('optimistically bumps the count then applies the server value on favorite', async () => {
    let resolve!: (v: { slug: string; favoriteCount: number }) => void
    mockFavoriteDish.mockReturnValue(new Promise((r) => { resolve = r }))
    const wrapper = await mountSection()
    await wrapper.findAll('.dish-card')[0].trigger('click')
    await flushPromises()
    const heart = document.body.querySelector<HTMLButtonElement>('.dish-heart-btn')!
    heart.click()
    await flushPromises()
    expect(heart.textContent).toContain('4')
    resolve({ slug: 'mapo-tofu', favoriteCount: 10 })
    await flushPromises()
    expect(heart.textContent).toContain('10')
    expect(wrapper.findAll('.dish-card')[0].text()).toContain('♥ 10')
  })

  it('rolls back and toasts when the favorite endpoint rate-limits', async () => {
    mockFavoriteDish.mockRejectedValue({ response: { status: 429 } })
    const wrapper = await mountSection()
    await wrapper.findAll('.dish-card')[0].trigger('click')
    await flushPromises()
    const heart = document.body.querySelector<HTMLButtonElement>('.dish-heart-btn')!
    heart.click()
    await flushPromises()
    expect(heart.textContent).toContain('3')
    expect(useUiStore(pinia).toast).toContain('太快')
  })

  it('cleans up the legacy dead-code favorites key on mount (NF-9)', async () => {
    localStorage.setItem('yubai_dish_favorites', '["a"]')
    await mountSection()
    expect(localStorage.getItem('yubai_dish_favorites')).toBeNull()
  })
})
