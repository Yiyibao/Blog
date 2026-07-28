import { describe, it, expect, beforeEach, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import TodayMenuCard from '../components/food/TodayMenuCard.vue'
import TodayMenuBoard from '../components/food/TodayMenuBoard.vue'
import FoodSection from '../components/food/FoodSection.vue'
import { useFoodStore, todayISO } from '../stores/foodStore'
import { useAuthStore } from '../stores/auth'
import type { DailyMenu } from '../api/kitchen'
import type { Dish } from '../data'

const mockFetchDailyMenu = vi.fn()
const mockAppendMenuItem = vi.fn()
const mockPutDailyMenu = vi.fn()
const mockDeleteMenuItem = vi.fn()
const mockCreateMealLog = vi.fn()

vi.mock('../api/kitchen', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/kitchen')>()
  return {
    ...actual,
    fetchDailyMenu: (...args: unknown[]) => mockFetchDailyMenu(...args),
    appendMenuItem: (...args: unknown[]) => mockAppendMenuItem(...args),
    putDailyMenu: (...args: unknown[]) => mockPutDailyMenu(...args),
    deleteMenuItem: (...args: unknown[]) => mockDeleteMenuItem(...args),
    fetchMealLogs: vi.fn().mockResolvedValue({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 1 }),
    fetchDishStats: vi.fn().mockResolvedValue([]),
    createMealLog: (...args: unknown[]) => mockCreateMealLog(...args),
  }
})

const mockFetchDishes = vi.fn()
const mockFetchDish = vi.fn()
const mockFavoriteDish = vi.fn()
const mockFetchDishFavorites = vi.fn()
const mockFetchDishCategories = vi.fn()

vi.mock('../api/content', () => ({
  fetchDishes: (...args: unknown[]) => mockFetchDishes(...args),
  fetchDish: (...args: unknown[]) => mockFetchDish(...args),
  favoriteDish: (...args: unknown[]) => mockFavoriteDish(...args),
  fetchDishFavorites: (...args: unknown[]) => mockFetchDishFavorites(...args),
  fetchDishCategories: (...args: unknown[]) => mockFetchDishCategories(...args),
}))

function menuOf(items: Partial<DailyMenu['items'][number]>[], overrides: Partial<DailyMenu> = {}): DailyMenu {
  return {
    exists: items.length > 0,
    date: todayISO(),
    status: 'DRAFT',
    note: '',
    version: 0,
    updatedBy: 1,
    updatedAt: '2026-07-27T10:00:00Z',
    items: items.map((item, index) => ({
      id: index + 1,
      dishId: null,
      dishSlug: null,
      title: `菜${index + 1}`,
      mealSlot: 'DINNER',
      note: '',
      sortOrder: index,
      authorId: 1,
      authorName: '站长',
      createdAt: '2026-07-27T10:00:00Z',
      ...item,
    })),
    ...overrides,
  }
}

function dishOf(slug: string, name: string): Dish {
  return {
    id: 1, slug, name, summary: '', category: '家常菜', imageUrl: '', imageAlt: '',
    imageCredit: '', imageSourceUrl: '', prepMinutes: 10, difficulty: '家常', rating: 4,
    featured: false, published: true, displayOrder: 0, favoriteCount: 0,
    ingredients: [], steps: [], createdAt: '', updatedAt: '',
  }
}

let pinia: Pinia

function loginAs(role: 'ADMIN' | 'PARTNER', displayName: string) {
  useAuthStore(pinia).saveSession({
    token: 't', tokenType: 'Bearer', username: role === 'ADMIN' ? 'gxynf' : 'gf',
    expiresAt: '2099-12-31T23:59:59Z', role, displayName,
  })
}

function setInput(element: HTMLInputElement | HTMLSelectElement, value: string) {
  element.value = value
  element.dispatchEvent(new Event(element instanceof HTMLSelectElement ? 'change' : 'input', { bubbles: true }))
}

beforeEach(() => {
  sessionStorage.clear()
  localStorage.clear()
  document.body.innerHTML = ''
  pinia = createPinia()
  setActivePinia(pinia)
  mockFetchDailyMenu.mockReset().mockResolvedValue(menuOf([]))
  mockAppendMenuItem.mockReset()
  mockPutDailyMenu.mockReset()
  mockDeleteMenuItem.mockReset()
  mockCreateMealLog.mockReset().mockResolvedValue({})
  mockFetchDishes.mockReset().mockResolvedValue({ items: [], page: 0, size: 12, totalElements: 0, totalPages: 1 })
  mockFetchDish.mockReset()
  mockFavoriteDish.mockReset()
  mockFetchDishFavorites.mockReset().mockResolvedValue({ items: [], page: 0, size: 5, totalElements: 0, totalPages: 1 })
  mockFetchDishCategories.mockReset().mockResolvedValue([])
})

describe('FD-13 TodayMenuCard', () => {
  function mountCard(menu: DailyMenu | null, arrivals: number[] = [], canEdit = true) {
    return mount(TodayMenuCard, { props: { menu, loading: false, canEdit, arrivals } })
  }

  it('渲染餐次小签、菜名与点菜人署名', () => {
    const wrapper = mountCard(menuOf([
      { title: '麻婆豆腐', mealSlot: 'DINNER', authorName: '小伙伴' },
      { title: '白粥', mealSlot: 'BREAKFAST', authorName: '站长' },
    ]))
    const text = wrapper.text()
    expect(text).toContain('麻婆豆腐')
    expect(text).toContain('小伙伴 点的')
    expect(text).toContain('早')
    expect(text).toContain('还在点菜中')
  })

  it('定档后展示已定状态与"再改改"', () => {
    const wrapper = mountCard(menuOf([{ title: '水煮鱼' }], { status: 'CONFIRMED' }))
    expect(wrapper.text()).toContain('菜单已定 ✓')
    expect(wrapper.find('.menu-open').text()).toContain('再改改')
  })

  it('空态展示三只虚线餐盘并可发起点第一道', async () => {
    const wrapper = mountCard(menuOf([]))
    expect(wrapper.find('.menu-plates').exists()).toBe(true)
    expect(wrapper.text()).toContain('今天还没定吃什么')
    await wrapper.find('.menu-open').trigger('click')
    expect(wrapper.emitted('open')).toHaveLength(1)
  })

  it('arrivals 中的项带到达动画类（对方刚点的菜）', () => {
    const wrapper = mountCard(menuOf([
      { id: 1, title: '旧菜' },
      { id: 2, title: '她刚点的', authorName: '小伙伴' },
    ]), [2])
    const rows = wrapper.findAll('.menu-lines li')
    expect(rows[0].classes()).not.toContain('arrived')
    expect(rows[1].classes()).toContain('arrived')
  })

  it('不可编辑时不出编辑按钮', () => {
    const wrapper = mountCard(menuOf([]), [], false)
    expect(wrapper.find('.menu-open').exists()).toBe(false)
    expect(wrapper.text()).toContain('登录后就能一起点菜')
  })
})

describe('FD-13 TodayMenuBoard', () => {
  async function mountBoard(menu: DailyMenu, role: 'ADMIN' | 'PARTNER' = 'PARTNER', displayName = '小伙伴') {
    loginAs(role, displayName)
    const store = useFoodStore(pinia)
    store.menu = menu
    const wrapper = mount(TodayMenuBoard, {
      props: { dishes: [dishOf('mapo-tofu', '麻婆豆腐')] },
      global: { plugins: [pinia] },
      attachTo: document.body,
    })
    await flushPromises()
    return { wrapper, store }
  }

  const board = () => document.body.querySelector<HTMLElement>('.menu-board')!

  it('自由文本加菜：提交表单以正确形状调用 store.append', async () => {
    const { store } = await mountBoard(menuOf([]))
    const appendSpy = vi.spyOn(store, 'append').mockResolvedValue()
    setInput(board().querySelector<HTMLInputElement>('.board-field input')!, '楼下的烤冷面')
    board().querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await flushPromises()
    expect(appendSpy).toHaveBeenCalledWith({ title: '楼下的烤冷面', mealSlot: 'DINNER' })
  })

  it('从菜谱挑选：以 dishSlug 调用 append（title 交给服务端快照）', async () => {
    const { store } = await mountBoard(menuOf([]))
    const appendSpy = vi.spyOn(store, 'append').mockResolvedValue()
    setInput(board().querySelectorAll<HTMLSelectElement>('select')[0]!, 'mapo-tofu')
    await flushPromises()
    board().querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await flushPromises()
    expect(appendSpy).toHaveBeenCalledWith({ dishSlug: 'mapo-tofu', mealSlot: 'DINNER' })
  })

  it('删除按钮只出现在自己（或 ADMIN）的菜上', async () => {
    await mountBoard(menuOf([
      { id: 1, title: '她点的', authorId: 2, authorName: '小伙伴' },
      { id: 2, title: '他点的', authorId: 1, authorName: '站长' },
    ]), 'PARTNER', '小伙伴')
    const rows = board().querySelectorAll('.board-items li')
    expect(rows[0].querySelector('.board-remove')).not.toBeNull()
    expect(rows[1].querySelector('.board-remove')).toBeNull()
  })

  it('定档：以 expectedVersion 与既有项（只含正 id）调用 submitMenu', async () => {
    const { store } = await mountBoard(menuOf([
      { id: 3, title: '正菜', mealSlot: 'DINNER' },
      { id: -99, title: '乐观中', mealSlot: 'DINNER' },
    ], { version: 4 }))
    const submitSpy = vi.spyOn(store, 'submitMenu').mockResolvedValue()
    board().querySelector<HTMLButtonElement>('.board-confirm')!.click()
    await flushPromises()
    expect(submitSpy).toHaveBeenCalledWith({
      status: 'CONFIRMED',
      note: '',
      expectedVersion: 4,
      items: [{ id: 3, mealSlot: 'DINNER', note: '' }],
    })
  })

  it('Esc 关闭编辑板', async () => {
    const { wrapper } = await mountBoard(menuOf([]))
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    expect(wrapper.emitted('close')).toHaveLength(1)
  })
})

describe('FD-13 FoodSection 英雄区接线', () => {
  let router: Router

  async function mountSection(initialUrl = '/recipes') {
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

  it('登录后英雄区右列换成今日菜单卡并开始拉取菜单', async () => {
    loginAs('PARTNER', '小伙伴')
    mockFetchDailyMenu.mockResolvedValue(menuOf([{ title: '晚饭的菜' }]))
    const wrapper = await mountSection()
    expect(wrapper.find('.menu-card').exists()).toBe(true)
    expect(wrapper.find('.food-stats').exists()).toBe(false)
    expect(mockFetchDailyMenu).toHaveBeenCalled()
    expect(wrapper.text()).toContain('晚饭的菜')
  })

  it('匿名访客仍看到统计盒，不请求菜单', async () => {
    // authStore 有模块级 memorySession 兜底，sessionStorage.clear() 清不掉——必须显式登出
    useAuthStore(pinia).clearSession()
    const wrapper = await mountSection()
    expect(wrapper.find('.food-stats').exists()).toBe(true)
    expect(wrapper.find('.menu-card').exists()).toBe(false)
    expect(mockFetchDailyMenu).not.toHaveBeenCalled()
  })

  it('FD-14：匿名侧栏展示"一起定菜单"邀请，指向带意图的登录链接', async () => {
    useAuthStore(pinia).clearSession()
    const wrapper = await mountSection()
    const invite = wrapper.find('.menu-invite')
    expect(invite.exists()).toBe(true)
    expect(invite.attributes('href')).toContain('/login')
    expect(decodeURIComponent(invite.attributes('href') ?? '')).toContain('next=/recipes?view=menu&intent=addDish')
  })

  it('FD-14：登录带 intent=addDish 回来——编辑板已开、欢迎语弹出、intent 被消费', async () => {
    loginAs('PARTNER', '小伙伴')
    await mountSection('/recipes?view=menu&intent=addDish')
    await flushPromises()
    expect(document.body.querySelector('.menu-board')).not.toBeNull()
    const { useUiStore } = await import('../stores/uiStore')
    expect(useUiStore(pinia).toast).toContain('接着点菜')
    expect(router.currentRoute.value.query.intent).toBeUndefined()
    expect(router.currentRoute.value.query.view).toBe('menu')
  })

  it('?view=menu 直达编辑板，关闭后从 URL 移除', async () => {
    loginAs('ADMIN', '站长')
    await mountSection('/recipes?view=menu')
    await flushPromises()
    expect(document.body.querySelector('.menu-board')).not.toBeNull()
    document.body.querySelector<HTMLButtonElement>('.board-close')!.click()
    await flushPromises()
    expect(router.currentRoute.value.query.view).toBeUndefined()
    expect(document.body.querySelector('.menu-board')).toBeNull()
  })
})
