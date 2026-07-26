import { describe, it, expect, beforeEach, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import { createPinia } from 'pinia'
import FoodSection from '../components/food/FoodSection.vue'
import type { Dish } from '../data'

const mockFetchDishes = vi.fn()
const mockFetchDish = vi.fn()
const mockFavoriteDish = vi.fn()
const mockFetchDishFavorites = vi.fn()

vi.mock('../api/content', () => ({
  fetchDishes: (...args: unknown[]) => mockFetchDishes(...args),
  fetchDish: (...args: unknown[]) => mockFetchDish(...args),
  favoriteDish: (...args: unknown[]) => mockFavoriteDish(...args),
  fetchDishFavorites: (...args: unknown[]) => mockFetchDishFavorites(...args),
}))

let dishSeq = 0
function makeDish(overrides: Partial<Dish> = {}): Dish {
  dishSeq += 1
  return {
    id: dishSeq,
    slug: `dish-${dishSeq}`,
    name: `菜品${dishSeq}`,
    summary: '好吃的',
    category: '家常菜',
    imageUrl: '/food/x.jpg',
    imageAlt: '图',
    imageCredit: '摄影师',
    imageSourceUrl: 'https://example.com',
    prepMinutes: 20,
    difficulty: '家常',
    rating: 4.5,
    featured: false,
    published: true,
    displayOrder: 0,
    favoriteCount: 0,
    ingredients: ['食材 100 克'],
    steps: ['做'],
    createdAt: '2026-06-01T00:00:00Z',
    updatedAt: '2026-06-01T00:00:00Z',
    ...overrides,
  }
}

function pageOf<T>(items: T[], extra: Partial<{ totalElements: number; totalPages: number }> = {}) {
  return { items, page: 0, size: 12, totalElements: extra.totalElements ?? items.length, totalPages: extra.totalPages ?? 1 }
}

let router: Router

async function mountSection(initialUrl = '/recipes') {
  router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/recipes', name: 'recipes', component: { template: '<div />' } }],
  })
  await router.push(initialUrl)
  await router.isReady()
  const wrapper = mount(FoodSection, {
    global: { plugins: [router, createPinia()] },
    attachTo: document.body,
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  dishSeq = 0
  mockFetchDishes.mockReset()
  mockFetchDish.mockReset()
  mockFavoriteDish.mockReset()
  mockFetchDishFavorites.mockReset()
  document.body.innerHTML = ''
  mockFetchDishFavorites.mockResolvedValue(pageOf([]))
  mockFetchDishes.mockResolvedValue(pageOf([
    makeDish({ name: '红烧肉', category: '硬菜', featured: true }),
    makeDish({ name: '拍黄瓜', category: '凉菜' }),
    makeDish({ name: '蒜蓉西兰花', category: '家常菜' }),
  ]))
})

describe('FoodSection baseline', () => {
  it('shows the skeleton grid while loading', async () => {
    mockFetchDishes.mockReturnValue(new Promise(() => {}))
    mockFetchDishFavorites.mockReturnValue(new Promise(() => {}))
    const wrapper = await mountSection()
    expect(wrapper.find('.food-skeleton-grid').exists()).toBe(true)
  })

  it('shows an error state and retries on demand', async () => {
    mockFetchDishes.mockRejectedValueOnce(new Error('boom'))
    const wrapper = await mountSection()
    expect(wrapper.find('.food-empty').exists()).toBe(true)
    await wrapper.find('.food-empty button').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.dish-card')).toHaveLength(3)
  })

  it('renders all dishes with the featured layout only on the first card of 全部', async () => {
    const wrapper = await mountSection()
    const cards = wrapper.findAll('.dish-card')
    expect(cards).toHaveLength(3)
    expect(cards[0].classes()).toContain('featured')
    expect(cards[1].classes()).not.toContain('featured')
  })

  it('filters by category tab and drops the featured treatment outside 全部', async () => {
    const wrapper = await mountSection()
    const tab = wrapper.findAll('.food-filter-tabs button').find(b => b.text() === '凉菜')!
    await tab.trigger('click')
    await flushPromises()
    const cards = wrapper.findAll('.dish-card')
    expect(cards).toHaveLength(1)
    expect(cards[0].text()).toContain('拍黄瓜')
    expect(cards[0].classes()).not.toContain('featured')
    expect(wrapper.find('.food-catalog-head h2').text()).toContain('凉菜')
  })

  it('filters by search keyword across name/summary/category', async () => {
    const wrapper = await mountSection()
    await wrapper.find('.food-search input').setValue('西兰花')
    await flushPromises()
    const cards = wrapper.findAll('.dish-card')
    expect(cards).toHaveLength(1)
    expect(cards[0].text()).toContain('蒜蓉西兰花')
  })

  it('shows a status empty state when nothing matches the query', async () => {
    const wrapper = await mountSection()
    await wrapper.find('.food-search input').setValue('佛跳墙')
    await flushPromises()
    const empty = wrapper.find('.food-no-result')
    expect(empty.exists()).toBe(true)
    expect(empty.attributes('role')).toBe('status')
    expect(empty.text()).toContain('佛跳墙')
    expect(wrapper.findAll('.dish-card')).toHaveLength(0)
  })

  it('renders hero stats from global totals and accumulated categories', async () => {
    const wrapper = await mountSection()
    const stats = wrapper.find('.food-stats')
    expect(stats.text()).toContain('03')
    expect(stats.text()).toContain('3')
  })

  it('keeps hero stats monotonic across pagination instead of shrinking', async () => {
    mockFetchDishes.mockResolvedValueOnce(pageOf([
      makeDish({ name: '红烧肉', category: '硬菜', featured: true }),
      makeDish({ name: '拍黄瓜', category: '凉菜' }),
    ], { totalElements: 3, totalPages: 2 }))
    const wrapper = await mountSection()
    expect(wrapper.find('.food-stats').text()).toContain('2')
    mockFetchDishes.mockResolvedValueOnce(pageOf([
      makeDish({ name: '蒜蓉西兰花', category: '家常菜' }),
    ], { totalElements: 3, totalPages: 2 }))
    const next = wrapper.findAll('.pagination button')[1]
    await next.trigger('click')
    await flushPromises()
    expect(mockFetchDishes).toHaveBeenLastCalledWith(1, 12)
    const statsText = wrapper.find('.food-stats').text()
    expect(statsText).toContain('3')
  })

  it('hides pagination for a single page', async () => {
    const wrapper = await mountSection()
    expect(wrapper.find('.pagination').exists()).toBe(false)
  })

  it('opens the roulette from the catalog trigger and lands on the drawn dish', async () => {
    const wrapper = await mountSection()
    await wrapper.find('.food-search input').setValue('拍黄瓜')
    await wrapper.find('.roulette-trigger').trigger('click')
    await flushPromises()
    expect(document.body.querySelector('.roulette-dialog')).not.toBeNull()
    document.body.querySelector<HTMLButtonElement>('.roulette-spin')!.click()
    await vi.advanceTimersByTimeAsync(2000)
    await flushPromises()
    document.body.querySelector<HTMLButtonElement>('.roulette-open')!.click()
    await flushPromises()
    expect(document.body.querySelector('.roulette-dialog')).toBeNull()
    expect(document.body.querySelector('.dish-panel h2')?.textContent).toContain('拍黄瓜')
    expect(router.currentRoute.value.query.dish).toBe('dish-2')
  })

  it('shows the mobile roulette fab once content is ready', async () => {
    const wrapper = await mountSection()
    expect(wrapper.find('.roulette-fab').exists()).toBe(true)
    expect(wrapper.find('.roulette-fab').classes()).toContain('tap-44')
  })
})
