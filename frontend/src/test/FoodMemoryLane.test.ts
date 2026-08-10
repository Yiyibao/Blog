import { describe, it, expect, beforeEach, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createRouter, createMemoryHistory, type Router } from 'vue-router';
import { createPinia, setActivePinia, type Pinia } from 'pinia';
import FoodSection from '../components/food/FoodSection.vue';
import FoodTimeline from '../components/food/FoodTimeline.vue';
import { useAuthStore } from '../stores/auth';
import { useUiStore } from '../stores/uiStore';
import { todayISO } from '../stores/foodStore';
import type { MealLog } from '../api/kitchen';

const mockFetchDailyMenu = vi.fn();
const mockFetchMealLogs = vi.fn();
const mockCreateMealLog = vi.fn();
const mockDeleteMealLog = vi.fn();
const mockFetchDishStats = vi.fn();

vi.mock('../api/kitchen', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/kitchen')>();
  return {
    ...actual,
    fetchDailyMenu: (...args: unknown[]) => mockFetchDailyMenu(...args),
    fetchMealLogs: (...args: unknown[]) => mockFetchMealLogs(...args),
    createMealLog: (...args: unknown[]) => mockCreateMealLog(...args),
    deleteMealLog: (...args: unknown[]) => mockDeleteMealLog(...args),
    fetchDishStats: (...args: unknown[]) => mockFetchDishStats(...args),
    appendMenuItem: vi.fn(),
    putDailyMenu: vi.fn(),
    deleteMenuItem: vi.fn(),
  };
});

const mockFetchDishes = vi.fn();
const mockFetchDishFavorites = vi.fn();
const mockFetchDishCategories = vi.fn();

vi.mock('../api/content', () => ({
  fetchDishes: (...args: unknown[]) => mockFetchDishes(...args),
  fetchDish: vi.fn(),
  favoriteDish: vi.fn(),
  fetchDishFavorites: (...args: unknown[]) => mockFetchDishFavorites(...args),
  fetchDishCategories: (...args: unknown[]) => mockFetchDishCategories(...args),
}));

function logOf(id: number, overrides: Partial<MealLog> = {}): MealLog {
  return {
    id,
    logDate: '2026-08-10',
    dishId: null,
    dishSlug: null,
    title: `记录${id}`,
    mealSlot: 'DINNER',
    rating: null,
    note: '',
    authorId: 1,
    authorName: '站长',
    createdAt: '2026-08-10T12:00:00Z',
    ...overrides,
  };
}

function logPage(items: MealLog[], totalPages = 1) {
  return { items, page: 0, size: 20, totalElements: items.length, totalPages };
}

let pinia: Pinia;
let router: Router;

function loginAdmin() {
  useAuthStore(pinia).saveSession({
    token: 't',
    tokenType: 'Bearer',
    username: 'gxynf',
    expiresAt: '2099-12-31T23:59:59Z',
    role: 'ADMIN',
    displayName: '站长',
  });
}

beforeEach(() => {
  sessionStorage.clear();
  localStorage.clear();
  document.body.innerHTML = '';
  pinia = createPinia();
  setActivePinia(pinia);
  mockFetchDailyMenu.mockReset().mockResolvedValue({
    exists: true,
    date: todayISO(),
    status: 'DRAFT',
    note: '',
    version: 0,
    items: [
      {
        id: 1,
        dishId: 9,
        dishSlug: 'mapo-tofu',
        title: '麻婆豆腐',
        mealSlot: 'DINNER',
        note: '',
        sortOrder: 0,
        authorId: 2,
        authorName: '小伙伴',
        createdAt: '2026-07-27T10:00:00Z',
      },
    ],
    updatedBy: 1,
    updatedAt: null,
  });
  mockFetchMealLogs.mockReset().mockResolvedValue(logPage([]));
  mockCreateMealLog.mockReset().mockResolvedValue(logOf(50));
  mockDeleteMealLog.mockReset().mockResolvedValue(undefined);
  mockFetchDishStats.mockReset().mockResolvedValue([]);
  mockFetchDishes
    .mockReset()
    .mockResolvedValue({ items: [], page: 0, size: 12, totalElements: 0, totalPages: 1 });
  mockFetchDishFavorites
    .mockReset()
    .mockResolvedValue({ items: [], page: 0, size: 5, totalElements: 0, totalPages: 1 });
  mockFetchDishCategories.mockReset().mockResolvedValue([]);
});

async function mountSection(url = '/recipes') {
  router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/recipes', name: 'recipes', component: { template: '<div />' } }],
  });
  await router.push(url);
  await router.isReady();
  const wrapper = mount(FoodSection, { global: { plugins: [router, pinia] }, attachTo: document.body });
  await flushPromises();
  return wrapper;
}

describe('FD-17 时光机', () => {
  async function mountTimeline() {
    router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/recipes', name: 'recipes', component: { template: '<div />' } }],
    });
    await router.push('/recipes');
    await router.isReady();
    loginAdmin();
    const wrapper = mount(FoodTimeline, { global: { plugins: [router, pinia] } });
    await flushPromises();
    return wrapper;
  }

  it('按日期分组渲染，含餐次/署名/星级/感想', async () => {
    mockFetchMealLogs.mockResolvedValue(
      logPage([
        logOf(1, {
          logDate: '2026-08-10',
          title: '麻婆豆腐',
          rating: 5,
          note: '今天特别嫩',
          authorName: '小伙伴',
        }),
        logOf(2, { logDate: '2026-08-10', title: '深夜泡面', mealSlot: 'SNACK' }),
        logOf(3, { logDate: '2026-08-09', title: '白粥' }),
      ]),
    );
    const wrapper = await mountTimeline();
    const text = wrapper.text();
    expect(wrapper.findAll('.timeline-day')).toHaveLength(2);
    expect(text).toContain('麻婆豆腐');
    expect(text).toContain('小伙伴 记的');
    expect(text).toContain('★★★★★');
    expect(text).toContain('今天特别嫩');
    expect(text).toContain('加餐');
    expect(text).toContain('3 顿');
  });

  it('零数据整块换邀请文案', async () => {
    const wrapper = await mountTimeline();
    expect(wrapper.find('.food-timeline').exists()).toBe(false);
    expect(wrapper.find('.food-timeline-empty').text()).toContain('第一帧回忆');
  });

  it('多页时提供"加载更早"，点击取下一页并追加', async () => {
    mockFetchMealLogs.mockResolvedValueOnce(logPage([logOf(1)], 2)).mockResolvedValueOnce({
      items: [logOf(2, { logDate: '2026-08-01' })],
      page: 1,
      size: 20,
      totalElements: 2,
      totalPages: 2,
    });
    const wrapper = await mountTimeline();
    await wrapper.find('.timeline-more').trigger('click');
    await flushPromises();
    expect(mockFetchMealLogs).toHaveBeenLastCalledWith(1, 20);
    expect(wrapper.findAll('.timeline-entry')).toHaveLength(2);
  });

  it('删除自己的记录并从列表移除', async () => {
    mockFetchMealLogs.mockResolvedValue(logPage([logOf(1, { authorName: '站长' })]));
    const wrapper = await mountTimeline();
    await wrapper.find('.entry-remove').trigger('click');
    await flushPromises();
    expect(mockDeleteMealLog).toHaveBeenCalledWith(1);
    expect(wrapper.findAll('.timeline-entry')).toHaveLength(0);
  });
});

describe('FD-18 一键打卡入口', () => {
  it('菜单卡 ✓ 以菜单项形状打卡并弹提示', async () => {
    loginAdmin();
    const wrapper = await mountSection();
    await wrapper.find('.menu-check').trigger('click');
    await flushPromises();
    expect(mockCreateMealLog).toHaveBeenCalledWith({
      dishSlug: 'mapo-tofu',
      mealSlot: 'DINNER',
      logDate: todayISO(),
    });
    expect(useUiStore(pinia).toast).toContain('已记一笔');
  });
});

describe('FD-19 榜单主口径', () => {
  it('有做菜数据时主口径为"你们做过 N 次"，点亮为副口径', async () => {
    loginAdmin();
    mockFetchDishFavorites.mockResolvedValue({
      items: [
        { slug: 'mapo-tofu', name: '麻婆豆腐', summary: '', imageUrl: '/f/m.jpg', favoriteCount: 9 },
        { slug: 'plain-congee', name: '白粥', summary: '', imageUrl: '/f/c.jpg', favoriteCount: 2 },
      ],
      page: 0,
      size: 5,
      totalElements: 2,
      totalPages: 1,
    });
    mockFetchDishStats.mockResolvedValue([
      { dishId: 2, slug: 'plain-congee', cookCount: 7, lastCookedAt: '2026-08-10' },
      { dishId: 1, slug: 'mapo-tofu', cookCount: 3, lastCookedAt: '2026-08-09' },
    ]);
    const wrapper = await mountSection();
    const board = wrapper.find('.food-ranking');
    expect(board.exists()).toBe(true);
    expect(board.text()).toContain('你们最常做的');
    expect(board.text()).toContain('白粥');
    expect(board.text()).toContain('你们做过 3 次');
    expect(board.text()).toContain('大家点亮 9 次');
  });

  it('匿名访客仍是点亮榜（拿不到 kitchen 聚合）', async () => {
    useAuthStore(pinia).clearSession();
    mockFetchDishFavorites.mockResolvedValue({
      items: [{ slug: 'mapo-tofu', name: '麻婆豆腐', summary: '', imageUrl: '/f/m.jpg', favoriteCount: 9 }],
      page: 0,
      size: 5,
      totalElements: 1,
      totalPages: 1,
    });
    const wrapper = await mountSection();
    const board = wrapper.find('.food-ranking');
    expect(board.text()).toContain('本期味蕾冠军');
    expect(board.text()).not.toContain('你们做过');
    expect(mockFetchDishStats).not.toHaveBeenCalled();
  });
});
