import { flushPromises, mount } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import SearchPage from '../pages/SearchPage.vue';
import WeeklyKitchenPlanner from '../components/food/WeeklyKitchenPlanner.vue';
import { createPinia, setActivePinia } from 'pinia';
import { useAuthStore } from '../stores/auth';
import { kitchenQueueSize, readKitchenQueue, saveKitchenSnapshot } from '../utils/kitchenOfflineQueue';
import type { ShoppingList, ShoppingListItem } from '../api/kitchen';

const api = vi.hoisted(() => ({
  fetchCategories: vi.fn(),
  searchByType: vi.fn(),
  fetchDish: vi.fn(),
  fetchDailyMenu: vi.fn(),
  fetchShoppingList: vi.fn(),
  generateShoppingList: vi.fn(),
  updateShoppingList: vi.fn(),
  clearCheckedShoppingList: vi.fn(),
  replayShoppingListMutation: vi.fn(),
}));

vi.mock('../api/content', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/content')>()),
  fetchCategories: (...args: unknown[]) => api.fetchCategories(...args),
  searchByType: (...args: unknown[]) => api.searchByType(...args),
  fetchDish: (...args: unknown[]) => api.fetchDish(...args),
}));

vi.mock('../api/kitchen', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/kitchen')>()),
  fetchDailyMenu: (...args: unknown[]) => api.fetchDailyMenu(...args),
  fetchShoppingList: (...args: unknown[]) => api.fetchShoppingList(...args),
  generateShoppingList: (...args: unknown[]) => api.generateShoppingList(...args),
  updateShoppingList: (...args: unknown[]) => api.updateShoppingList(...args),
  clearCheckedShoppingList: (...args: unknown[]) => api.clearCheckedShoppingList(...args),
  replayShoppingListMutation: (...args: unknown[]) => api.replayShoppingListMutation(...args),
}));

beforeEach(() => {
  setActivePinia(createPinia());
  vi.clearAllMocks();
  localStorage.clear();
  api.fetchCategories.mockResolvedValue([{ name: '工程', slug: 'engineering' }]);
  api.searchByType.mockResolvedValue({
    results: [
      {
        type: 'POST',
        id: 1,
        title: 'Vue 架构实践',
        excerpt: '用 Vue 建立清晰边界',
        category: '工程',
        url: '/articles/vue',
        color: null,
        number: '01',
        date: '2026-08-01',
        tags: ['Vue'],
      },
    ],
    page: 0,
    size: 12,
    totalElements: 1,
    totalPages: 1,
  });
  api.fetchDailyMenu.mockImplementation(async (date: string) => ({
    exists: true,
    date,
    status: 'DRAFT',
    note: '',
    version: 1,
    updatedBy: 1,
    updatedAt: '2026-08-01T00:00:00Z',
    items: [
      {
        id: Number(date.slice(-2)),
        dishId: 1,
        dishSlug: 'tomato-eggs',
        title: '番茄炒蛋',
        mealSlot: 'DINNER',
        note: '',
        sortOrder: 0,
        authorId: 1,
        authorName: 'admin',
        createdAt: '2026-08-01T00:00:00Z',
      },
    ],
  }));
  api.fetchDish.mockResolvedValue({
    id: 1,
    slug: 'tomato-eggs',
    name: '番茄炒蛋',
    ingredients: ['番茄 2 个', '鸡蛋 3 个'],
    steps: [],
  });
  api.fetchShoppingList.mockRejectedValue({ kind: 'network', message: 'offline' });
  api.generateShoppingList.mockRejectedValue({ kind: 'network', message: 'offline' });
  api.updateShoppingList.mockRejectedValue({ kind: 'network', message: 'offline' });
  api.clearCheckedShoppingList.mockRejectedValue({ kind: 'network', message: 'offline' });
  api.replayShoppingListMutation.mockRejectedValue({ kind: 'network', message: 'offline' });
});

const LIST_ID = '00000000-0000-4000-8000-000000000001';
const ITEM_ID = '00000000-0000-4000-8000-000000000002';

function shoppingItem(overrides: Partial<ShoppingListItem> = {}): ShoppingListItem {
  return {
    id: ITEM_ID,
    displayName: '番茄',
    normalizedName: '番茄',
    quantity: 2,
    unit: '个',
    originalQuantity: '2 个',
    sourceRecipe: '番茄炒蛋',
    category: '蔬菜',
    checked: false,
    manual: false,
    note: '',
    sortOrder: 0,
    createdAt: '2026-08-13T00:00:00Z',
    ...overrides,
  };
}

function shoppingList(items: ShoppingListItem[] = [shoppingItem()]): ShoppingList {
  return {
    id: LIST_ID,
    weekStart: '2026-08-10',
    note: '',
    version: 1,
    createdAt: '2026-08-13T00:00:00Z',
    updatedAt: '2026-08-13T00:00:00Z',
    items,
  };
}

function authenticateKitchen() {
  useAuthStore().saveSession({
    token: 'kitchen-token',
    tokenType: 'Bearer',
    username: 'owner',
    expiresAt: '2099-12-31T23:59:59Z',
    role: 'ADMIN',
  });
}

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe('SearchPage', () => {
  it('syncs URL filters and renders highlighted typed results', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/search', component: SearchPage },
        { path: '/articles/:slug', component: { template: '<div />' } },
      ],
    });
    await router.push('/search?q=Vue&type=post&category=engineering');
    await router.isReady();
    const wrapper = mount(SearchPage, { global: { plugins: [router] } });
    await flushPromises();

    expect(api.searchByType).toHaveBeenCalledWith(
      'Vue',
      0,
      12,
      expect.objectContaining({ type: 'POST', categorySlug: 'engineering' }),
    );
    expect(wrapper.text()).toContain('Vue 架构实践');
    expect(wrapper.find('mark').text()).toBe('Vue');
    expect(wrapper.find('.search-center-result').attributes('href')).toBe('/articles/vue');
    expect(localStorage.getItem('yubai_search_history')).toContain('Vue');
  });

  it('searches all content types and applies the date filter', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/search', component: SearchPage }],
    });
    await router.push('/search?q=Vue&from=2026-09-01');
    await router.isReady();
    const wrapper = mount(SearchPage, { global: { plugins: [router] } });
    await flushPromises();
    expect(api.searchByType).toHaveBeenCalledTimes(1);
    expect(wrapper.text()).toContain('没有符合筛选条件的结果');
  });

  it('shows recoverable search errors and reuses local history', async () => {
    localStorage.setItem('yubai_search_history', JSON.stringify(['旧关键词']));
    api.searchByType.mockRejectedValueOnce(new Error('offline'));
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/search', component: SearchPage }],
    });
    await router.push('/search?q=失败&type=post');
    await router.isReady();
    const wrapper = mount(SearchPage, { global: { plugins: [router] } });
    await flushPromises();
    expect(wrapper.text()).toContain('搜索服务暂时不可用');

    await wrapper.get('input[type="search"]').setValue('');
    await wrapper.find('form').trigger('submit');
    await flushPromises();
    expect(wrapper.text()).toContain('旧关键词');
    await wrapper.get('.search-center-history button').trigger('click');
    expect((wrapper.get('input[type="search"]').element as HTMLInputElement).value).toBe('旧关键词');
  });
});

describe('WeeklyKitchenPlanner', () => {
  it('loads seven menus, merges ingredients and emits day editing', async () => {
    const wrapper = mount(WeeklyKitchenPlanner);
    await flushPromises();

    expect(api.fetchDailyMenu).toHaveBeenCalledTimes(7);
    expect(api.fetchDish).toHaveBeenCalledWith('tomato-eggs');
    expect(wrapper.text()).toContain('番茄 2 个');
    expect(wrapper.text()).toContain('鸡蛋 3 个');
    await wrapper.find('.weekly-days article button').trigger('click');
    expect(wrapper.emitted('editDay')?.[0]?.[0]).toMatch(/^\d{4}-\d{2}-\d{2}$/);

    const ingredient = wrapper.find('.shopping-list label');
    await ingredient.get('input').setValue(true);
    expect(ingredient.classes()).toContain('checked');
  });

  it('switches weeks, returns to this week and closes', async () => {
    const wrapper = mount(WeeklyKitchenPlanner);
    await flushPromises();
    const navigation = wrapper.findAll('.weekly-planner > nav button');
    await navigation[0].trigger('click');
    await flushPromises();
    expect(api.fetchDailyMenu).toHaveBeenCalledTimes(14);
    await navigation[1].trigger('click');
    await flushPromises();
    expect(api.fetchDailyMenu).toHaveBeenCalledTimes(21);
    await wrapper.get('header > button').trigger('click');
    expect(wrapper.emitted('close')).toBeTruthy();
  });

  it('exports and prints the merged shopping list', async () => {
    const createObjectUrl = vi.fn(() => 'blob:test');
    const revokeObjectUrl = vi.fn();
    vi.stubGlobal('URL', { createObjectURL: createObjectUrl, revokeObjectURL: revokeObjectUrl });
    const linkClick = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    const print = vi.spyOn(window, 'print').mockImplementation(() => undefined);
    const wrapper = mount(WeeklyKitchenPlanner);
    await flushPromises();
    const actions = wrapper.findAll('.shopping-list > header button');
    await actions.find((button) => button.text() === '打印')!.trigger('click');
    await actions.find((button) => button.text() === '导出 TXT')!.trigger('click');
    expect(print).toHaveBeenCalled();
    expect(createObjectUrl).toHaveBeenCalled();
    expect(linkClick).toHaveBeenCalled();
    expect(revokeObjectUrl).toHaveBeenCalledWith('blob:test');
  });

  it('shows a recoverable weekly menu load error', async () => {
    api.fetchDailyMenu.mockRejectedValueOnce(new Error('network'));
    const wrapper = mount(WeeklyKitchenPlanner);
    await flushPromises();
    expect(wrapper.get('[role="alert"]').text()).toContain('本周菜单读取失败');
    await wrapper.get('.weekly-planner-backdrop').trigger('click');
    expect(wrapper.emitted('close')).toBeTruthy();
  });

  it('loads the persisted list, edits manual fields, regenerates and clears checked items', async () => {
    authenticateKitchen();
    const initial = shoppingList([shoppingItem({ checked: true })]);
    api.fetchShoppingList.mockResolvedValue(initial);
    api.updateShoppingList.mockResolvedValue(shoppingList([shoppingItem({ checked: true })]));
    api.clearCheckedShoppingList.mockResolvedValue(shoppingList([]));
    api.generateShoppingList.mockResolvedValue(shoppingList([shoppingItem()]));

    const wrapper = mount(WeeklyKitchenPlanner);
    await flushPromises();
    expect(api.fetchShoppingList).toHaveBeenCalledTimes(1);
    expect(wrapper.find('.shopping-list').text()).toContain('番茄');

    const pantrySuggestion = wrapper.find('.pantry-suggestions button');
    await pantrySuggestion.trigger('click');
    await flushPromises();
    expect(api.updateShoppingList).toHaveBeenCalledWith(
      LIST_ID,
      expect.objectContaining({
        items: expect.arrayContaining([
          expect.objectContaining({ displayName: '食用油', category: '常备项', sourceRecipe: '常备项建议' }),
        ]),
      }),
      expect.any(String),
    );

    await wrapper.get('input[aria-label="手工食材名称"]').setValue('食用油');
    await wrapper.get('input[aria-label="手工食材数量"]').setValue('1');
    await wrapper.get('input[aria-label="手工食材单位"]').setValue('瓶');
    const addManualButton = wrapper.findAll('button').find((button) => button.text().includes('添加手工项'));
    await addManualButton!.trigger('click');
    await flushPromises();
    expect(api.updateShoppingList).toHaveBeenCalled();

    await wrapper.get('textarea').setValue('周末采购');
    await wrapper.get('textarea').trigger('change');
    await flushPromises();
    expect(api.updateShoppingList.mock.calls.length).toBeGreaterThanOrEqual(2);

    const clearCheckedButton = wrapper
      .findAll('button')
      .find((button) => button.text().includes('清理已勾选'));
    await clearCheckedButton!.trigger('click');
    await flushPromises();
    expect(api.clearCheckedShoppingList).toHaveBeenCalledWith(LIST_ID, 1, expect.any(String));

    const regenerateButton = wrapper
      .findAll('button')
      .find((button) => button.text().includes('重新按周菜单生成'));
    await regenerateButton!.trigger('click');
    await flushPromises();
    expect(api.generateShoppingList).toHaveBeenCalledTimes(1);
  });

  it('queues network mutations, replays them on reconnect and surfaces conflicts', async () => {
    authenticateKitchen();
    api.fetchShoppingList.mockResolvedValue(shoppingList());
    api.updateShoppingList.mockRejectedValue({ kind: 'network', message: 'offline' });
    const wrapper = mount(WeeklyKitchenPlanner);
    await flushPromises();

    await wrapper.get('.shopping-list input[type="checkbox"]').setValue(true);
    await flushPromises();
    expect(kitchenQueueSize('owner')).toBe(1);
    expect(wrapper.get('[role="alert"]').text()).toContain('1/50');

    api.replayShoppingListMutation.mockResolvedValue(shoppingList());
    Object.defineProperty(navigator, 'onLine', { configurable: true, value: true });
    window.dispatchEvent(new Event('online'));
    await flushPromises();
    expect(api.replayShoppingListMutation.mock.calls.length).toBeGreaterThanOrEqual(1);
    expect(readKitchenQueue('owner')).toEqual([]);

    api.updateShoppingList.mockRejectedValue({ kind: 'conflict', message: 'stale' });
    api.fetchShoppingList.mockResolvedValue(shoppingList([shoppingItem({ checked: true })]));
    await wrapper.get('.shopping-list input[type="checkbox"]').setValue(true);
    await flushPromises();
    expect(wrapper.get('.shopping-conflict').text()).toContain('并发修改');
  });

  it('uses a saved snapshot when the persisted list is unavailable', async () => {
    authenticateKitchen();
    const snapshot = shoppingList([shoppingItem({ displayName: '快照番茄' })]);
    saveKitchenSnapshot('owner', snapshot.weekStart, snapshot);
    api.fetchShoppingList.mockRejectedValue({ kind: 'network', message: 'offline' });
    const wrapper = mount(WeeklyKitchenPlanner);
    await flushPromises();
    expect(wrapper.text()).toContain('快照番茄');
    expect(wrapper.get('[role="alert"]').text()).toContain('离线');
  });

  it('falls back to public recipe aggregation for an anonymous visitor', async () => {
    useAuthStore().clearSession();
    const wrapper = mount(WeeklyKitchenPlanner);
    await flushPromises();
    expect(api.fetchShoppingList).not.toHaveBeenCalled();
    expect(api.fetchDish).toHaveBeenCalledWith('tomato-eggs');
    expect(wrapper.text()).toContain('番茄 2 个');
  });
});
