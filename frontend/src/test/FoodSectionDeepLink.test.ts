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

vi.mock('../api/kitchen', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/kitchen')>()
  return { ...actual, fetchDailyMenu: vi.fn().mockResolvedValue({ exists: false, date: '2026-07-27', status: 'DRAFT', note: '', version: null, items: [], updatedBy: null, updatedAt: null }) }
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
    favoriteCount: 0,
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

const drawerTitle = () => document.body.querySelector('.dish-panel h2')?.textContent ?? ''

beforeEach(() => {
  mockFetchDishes.mockReset()
  mockFetchDish.mockReset()
  mockFavoriteDish.mockReset()
  mockFetchDishFavorites.mockReset()
  document.body.innerHTML = ''
  mockFetchDishes.mockResolvedValue(pageOf([
    makeDish(),
    makeDish({ id: 2, slug: 'plain-congee', name: '白粥' }),
  ]))
  mockFetchDishFavorites.mockResolvedValue(pageOf([]))
})

describe('FoodSection deep link and race guards (FD-4)', () => {
  it('clears the ?dish= query when the drawer closes so reload stays closed', async () => {
    await mountSection('/recipes?dish=mapo-tofu')
    await flushPromises()
    expect(drawerTitle()).toContain('麻婆豆腐')
    document.body.querySelector<HTMLButtonElement>('button[aria-label="关闭菜谱详情"]')!.click()
    await flushPromises()
    expect(document.body.querySelector('.dish-panel')).toBeNull()
    expect(router.currentRoute.value.query.dish).toBeUndefined()
  })

  it('writes ?dish= into the URL when opening from a card and keeps focus return intact', async () => {
    const wrapper = await mountSection()
    const card = wrapper.findAll('.dish-card')[1]
    await card.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.dish).toBe('plain-congee')
    expect(drawerTitle()).toContain('白粥')
    document.body.querySelector<HTMLButtonElement>('button[aria-label="关闭菜谱详情"]')!.click()
    await flushPromises()
    expect(router.currentRoute.value.query.dish).toBeUndefined()
    expect(document.activeElement).toBe(card.element)
  })

  it('does not inject a deep-linked off-page dish into the grid (featured stays correct)', async () => {
    mockFetchDish.mockResolvedValue(makeDish({ id: 99, slug: 'secret-stew', name: '隐藏炖菜' }))
    const wrapper = await mountSection('/recipes?dish=secret-stew')
    await flushPromises()
    expect(drawerTitle()).toContain('隐藏炖菜')
    const cards = wrapper.findAll('.dish-card')
    expect(cards).toHaveLength(2)
    expect(wrapper.find('.dish-grid').text()).not.toContain('隐藏炖菜')
    expect(cards[0].text()).toContain('麻婆豆腐')
    expect(cards[0].classes()).toContain('featured')
  })

  it('caches fetched details and does not refetch the same slug', async () => {
    mockFetchDish.mockResolvedValue(makeDish({ id: 99, slug: 'secret-stew', name: '隐藏炖菜' }))
    await mountSection('/recipes?dish=secret-stew')
    await flushPromises()
    document.body.querySelector<HTMLButtonElement>('button[aria-label="关闭菜谱详情"]')!.click()
    await flushPromises()
    await router.replace({ query: { dish: 'secret-stew' } })
    await flushPromises()
    expect(drawerTitle()).toContain('隐藏炖菜')
    expect(mockFetchDish).toHaveBeenCalledTimes(1)
  })

  it('discards an in-flight detail response when the user has moved to an in-page dish', async () => {
    let resolveSlow!: (d: Dish) => void
    mockFetchDish.mockReturnValue(new Promise<Dish>((r) => { resolveSlow = r }))
    await mountSection('/recipes?dish=slow-dish')
    await flushPromises()
    await router.replace({ query: { dish: 'plain-congee' } })
    await flushPromises()
    expect(drawerTitle()).toContain('白粥')
    resolveSlow(makeDish({ id: 50, slug: 'slow-dish', name: '迟到的菜' }))
    await flushPromises()
    expect(drawerTitle()).toContain('白粥')
  })

  it('keeps only the latest of two racing detail fetches', async () => {
    const pending = new Map<string, (d: Dish) => void>()
    mockFetchDish.mockImplementation((slug: string) => new Promise<Dish>((r) => { pending.set(slug, r) }))
    await mountSection('/recipes?dish=dish-a')
    await flushPromises()
    await router.replace({ query: { dish: 'dish-b' } })
    await flushPromises()
    pending.get('dish-b')!(makeDish({ id: 61, slug: 'dish-b', name: '后到先赢' }))
    await flushPromises()
    expect(drawerTitle()).toContain('后到先赢')
    pending.get('dish-a')!(makeDish({ id: 60, slug: 'dish-a', name: '过期响应' }))
    await flushPromises()
    expect(drawerTitle()).toContain('后到先赢')
  })
})
