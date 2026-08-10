import { describe, it, expect, beforeEach, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createRouter, createMemoryHistory, type Router } from 'vue-router';
import { createPinia } from 'pinia';
import FoodSection from '../components/food/FoodSection.vue';
import type { Dish } from '../data';

const mockFetchDishes = vi.fn();
const mockFetchDish = vi.fn();
const mockFavoriteDish = vi.fn();
const mockFetchDishFavorites = vi.fn();
const mockFetchDishCategories = vi.fn();

vi.mock('../api/kitchen', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/kitchen')>();
  return {
    ...actual,
    fetchDailyMenu: vi.fn().mockResolvedValue({
      exists: false,
      date: '2026-07-27',
      status: 'DRAFT',
      note: '',
      version: null,
      items: [],
      updatedBy: null,
      updatedAt: null,
    }),
    fetchMealLogs: vi
      .fn()
      .mockResolvedValue({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 1 }),
    fetchDishStats: vi.fn().mockResolvedValue([]),
    createMealLog: vi.fn().mockResolvedValue({}),
  };
});

vi.mock('../api/content', () => ({
  fetchDishes: (...args: unknown[]) => mockFetchDishes(...args),
  fetchDish: (...args: unknown[]) => mockFetchDish(...args),
  favoriteDish: (...args: unknown[]) => mockFavoriteDish(...args),
  fetchDishFavorites: (...args: unknown[]) => mockFetchDishFavorites(...args),
  fetchDishCategories: (...args: unknown[]) => mockFetchDishCategories(...args),
}));

let dishSeq = 0;

const CATEGORY_ITEMS = [
  { name: '硬菜', slug: '硬菜' },
  { name: '凉菜', slug: '凉菜' },
  { name: '家常菜', slug: '家常菜' },
];

function makeDish(overrides: Partial<Dish> = {}): Dish {
  dishSeq += 1;
  return {
    id: dishSeq,
    slug: `dish-${dishSeq}`,
    name: `菜品${dishSeq}`,
    summary: '好吃的',
    category: '家常菜',
    imageUrl: '/food/x.jpg',
    imageAlt: '图',
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
  };
}

const ALL_TEST_DISHES = [
  makeDish({ name: '红烧肉', category: '硬菜', featured: true }),
  makeDish({ name: '拍黄瓜', category: '凉菜' }),
  makeDish({ name: '蒜蓉西兰花', category: '家常菜' }),
];

function mockFetchDishesImpl(page: number, size: number, categorySlug?: string, query?: string) {
  let filtered = [...ALL_TEST_DISHES];
  if (categorySlug) {
    filtered = filtered.filter((d) => d.category.toLowerCase() === categorySlug.toLowerCase());
  }
  if (query) {
    const q = query.toLowerCase();
    filtered = filtered.filter(
      (d) =>
        d.name.toLowerCase().includes(q) ||
        d.summary.toLowerCase().includes(q) ||
        d.category.toLowerCase().includes(q),
    );
  }
  return Promise.resolve({
    items: filtered,
    page,
    size,
    totalElements: filtered.length,
    totalPages: Math.max(1, Math.ceil(filtered.length / size)),
  });
}

function pageOf<T>(items: T[], extra: Partial<{ totalElements: number; totalPages: number }> = {}) {
  const total = extra.totalElements ?? items.length;
  return {
    items,
    page: 0,
    size: 4,
    totalElements: total,
    totalPages: extra.totalPages ?? Math.max(1, Math.ceil(total / 4)),
  };
}

let router: Router;

async function mountSection(initialUrl = '/recipes') {
  router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/recipes', name: 'recipes', component: { template: '<div />' } }],
  });
  await router.push(initialUrl);
  await router.isReady();
  const wrapper = mount(FoodSection, {
    global: { plugins: [router, createPinia()] },
    attachTo: document.body,
  });
  await flushPromises();
  return wrapper;
}

beforeEach(() => {
  dishSeq = 0;
  mockFetchDishes.mockReset();
  mockFetchDish.mockReset();
  mockFavoriteDish.mockReset();
  mockFetchDishFavorites.mockReset();
  mockFetchDishCategories.mockReset();
  document.body.innerHTML = '';
  mockFetchDishFavorites.mockResolvedValue(pageOf([]));
  mockFetchDishCategories.mockResolvedValue(CATEGORY_ITEMS);
  mockFetchDishes.mockImplementation(mockFetchDishesImpl);
});

describe('FoodSection baseline', () => {
  it('shows the skeleton grid while loading', async () => {
    mockFetchDishes.mockReturnValue(new Promise(() => {}));
    mockFetchDishFavorites.mockReturnValue(new Promise(() => {}));
    const wrapper = await mountSection();
    expect(wrapper.find('.food-skeleton-grid').exists()).toBe(true);
  });

  it('shows an error state and retries on demand', async () => {
    mockFetchDishes.mockRejectedValueOnce(new Error('boom'));
    const wrapper = await mountSection();
    expect(wrapper.find('.food-empty').exists()).toBe(true);
    await wrapper.find('.food-empty button').trigger('click');
    await flushPromises();
    expect(wrapper.findAll('.dish-card')).toHaveLength(3);
  });

  it('renders all dishes with the featured layout only on the first card of 全部', async () => {
    const wrapper = await mountSection();
    const cards = wrapper.findAll('.dish-card');
    expect(cards).toHaveLength(3);
    expect(cards[0].classes()).toContain('featured');
    expect(cards[1].classes()).not.toContain('featured');
  });

  it('filters by category tab, calls server with categorySlug, resets to page 0', async () => {
    const wrapper = await mountSection();
    const tab = wrapper.findAll('.food-filter-tabs button').find((b) => b.text() === '凉菜')!;
    await tab.trigger('click');
    await flushPromises();
    expect(mockFetchDishes).toHaveBeenLastCalledWith(0, 4, '凉菜', undefined);
    const cards = wrapper.findAll('.dish-card');
    expect(cards).toHaveLength(1);
    expect(cards[0].text()).toContain('拍黄瓜');
    expect(cards[0].classes()).not.toContain('featured');
    expect(wrapper.find('.food-catalog-head h2').text()).toContain('凉菜');
  });

  it('filters by search keyword with debounce, calls server with query param', async () => {
    const wrapper = await mountSection();
    await wrapper.find('.food-search input').setValue('西兰花');
    await vi.advanceTimersByTimeAsync(500);
    await flushPromises();
    expect(mockFetchDishes).toHaveBeenLastCalledWith(0, 4, undefined, '西兰花');
    const cards = wrapper.findAll('.dish-card');
    expect(cards).toHaveLength(1);
    expect(cards[0].text()).toContain('蒜蓉西兰花');
  });

  it('shows a status empty state when nothing matches the query', async () => {
    const wrapper = await mountSection();
    await wrapper.find('.food-search input').setValue('佛跳墙');
    await vi.advanceTimersByTimeAsync(500);
    await flushPromises();
    const empty = wrapper.find('.food-no-result');
    expect(empty.exists()).toBe(true);
    expect(empty.attributes('role')).toBe('status');
    expect(empty.text()).toContain('佛跳墙');
    expect(wrapper.findAll('.dish-card')).toHaveLength(0);
  });

  it('renders hero stats from global totals and accumulated categories', async () => {
    sessionStorage.clear();
    const wrapper = await mountSection();
    const stats = wrapper.find('.food-stats');
    expect(stats.text()).toContain('03');
    expect(stats.text()).toContain('3');
  });

  it('fires only one fetchDishes call per pagination click (no duplicate requests)', async () => {
    mockFetchDishes
      .mockReset()
      .mockImplementationOnce(() =>
        Promise.resolve({
          items: [
            makeDish({ name: '红烧肉', category: '硬菜', featured: true }),
            makeDish({ name: '拍黄瓜', category: '凉菜' }),
          ],
          page: 0,
          size: 4,
          totalElements: 3,
          totalPages: 2,
        }),
      )
      .mockImplementationOnce(() =>
        Promise.resolve({
          items: [makeDish({ name: '蒜蓉西兰花', category: '家常菜' })],
          page: 1,
          size: 4,
          totalElements: 3,
          totalPages: 2,
        }),
      )
      .mockImplementation(() => {
        throw new Error('unexpected extra fetchDishes call');
      });
    const wrapper = await mountSection();
    expect(mockFetchDishes).toHaveBeenCalledTimes(1);
    const next = wrapper.get('[aria-label="下一页"]');
    await next.trigger('click');
    await flushPromises();
    expect(mockFetchDishes).toHaveBeenCalledTimes(2);
  });

  it('fires only one fetchDishes call per category filter change (no duplicate requests)', async () => {
    mockFetchDishes
      .mockReset()
      .mockImplementationOnce(() =>
        Promise.resolve({
          items: [
            makeDish({ name: '红烧肉', category: '硬菜', featured: true }),
            makeDish({ name: '拍黄瓜', category: '凉菜' }),
            makeDish({ name: '蒜蓉西兰花', category: '家常菜' }),
          ],
          page: 0,
          size: 4,
          totalElements: 3,
          totalPages: 1,
        }),
      )
      .mockImplementationOnce(() =>
        Promise.resolve({
          items: [makeDish({ name: '拍黄瓜', category: '凉菜' })],
          page: 0,
          size: 4,
          totalElements: 1,
          totalPages: 1,
        }),
      )
      .mockImplementation(() => {
        throw new Error('unexpected extra fetchDishes call');
      });
    const wrapper = await mountSection();
    expect(mockFetchDishes).toHaveBeenCalledTimes(1);
    const tab = wrapper.findAll('.food-filter-tabs button').find((b) => b.text() === '凉菜')!;
    await tab.trigger('click');
    await flushPromises();
    expect(mockFetchDishes).toHaveBeenCalledTimes(2);
  });

  it('fires only one fetchDishes call per search (no duplicate requests)', async () => {
    mockFetchDishes
      .mockReset()
      .mockImplementationOnce(() =>
        Promise.resolve({
          items: [
            makeDish({ name: '红烧肉', category: '硬菜', featured: true }),
            makeDish({ name: '拍黄瓜', category: '凉菜' }),
            makeDish({ name: '蒜蓉西兰花', category: '家常菜' }),
          ],
          page: 0,
          size: 4,
          totalElements: 3,
          totalPages: 1,
        }),
      )
      .mockImplementationOnce(() =>
        Promise.resolve({
          items: [makeDish({ name: '蒜蓉西兰花', category: '家常菜' })],
          page: 0,
          size: 4,
          totalElements: 1,
          totalPages: 1,
        }),
      )
      .mockImplementation(() => {
        throw new Error('unexpected extra fetchDishes call');
      });
    const wrapper = await mountSection();
    expect(mockFetchDishes).toHaveBeenCalledTimes(1);
    await wrapper.find('.food-search input').setValue('西兰花');
    await vi.advanceTimersByTimeAsync(500);
    await flushPromises();
    expect(mockFetchDishes).toHaveBeenCalledTimes(2);
  });

  it('keeps hero stats monotonic across pagination instead of shrinking', async () => {
    sessionStorage.clear();
    mockFetchDishes
      .mockReset()
      .mockImplementationOnce(() =>
        Promise.resolve({
          items: [
            makeDish({ name: '红烧肉', category: '硬菜', featured: true }),
            makeDish({ name: '拍黄瓜', category: '凉菜' }),
          ],
          page: 0,
          size: 4,
          totalElements: 3,
          totalPages: 2,
        }),
      )
      .mockImplementationOnce(() =>
        Promise.resolve({
          items: [makeDish({ name: '蒜蓉西兰花', category: '家常菜' })],
          page: 1,
          size: 4,
          totalElements: 3,
          totalPages: 2,
        }),
      );
    const wrapper = await mountSection();
    expect(wrapper.find('.food-stats').text()).toContain('2');
    const next = wrapper.get('[aria-label="下一页"]');
    await next.trigger('click');
    await flushPromises();
    expect(mockFetchDishes).toHaveBeenLastCalledWith(1, 4, undefined, undefined);
    const statsText = wrapper.find('.food-stats').text();
    expect(statsText).toContain('3');
  });

  it('hides pagination for a single page', async () => {
    const wrapper = await mountSection();
    expect(wrapper.find('.pagination').exists()).toBe(false);
  });

  it('opens the roulette from the catalog trigger and lands on the drawn dish', async () => {
    const wrapper = await mountSection();
    await wrapper.find('.roulette-trigger').trigger('click');
    await flushPromises();
    expect(document.body.querySelector('.roulette-dialog')).not.toBeNull();
    vi.spyOn(Math, 'random').mockReturnValue(0.01);
    document.body.querySelector<HTMLButtonElement>('.roulette-spin')!.click();
    await vi.advanceTimersByTimeAsync(2000);
    await flushPromises();
    document.body.querySelector<HTMLButtonElement>('.roulette-open')!.click();
    await flushPromises();
    expect(document.body.querySelector('.roulette-dialog')).toBeNull();
    expect(document.body.querySelector('.dish-panel h2')?.textContent).toContain('红烧肉');
    expect(router.currentRoute.value.query.dish).toBe('dish-1');
  });

  it('shows the mobile roulette fab once content is ready', async () => {
    const wrapper = await mountSection();
    expect(wrapper.find('.roulette-fab').exists()).toBe(true);
    expect(wrapper.find('.roulette-fab').classes()).toContain('tap-44');
  });
});
