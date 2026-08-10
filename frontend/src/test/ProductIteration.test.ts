import { flushPromises, mount } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import SearchPage from '../pages/SearchPage.vue';
import WeeklyKitchenPlanner from '../components/food/WeeklyKitchenPlanner.vue';

const api = vi.hoisted(() => ({
  fetchCategories: vi.fn(),
  searchByType: vi.fn(),
  fetchDish: vi.fn(),
  fetchDailyMenu: vi.fn(),
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
}));

beforeEach(() => {
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
});

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
    expect(api.searchByType).toHaveBeenCalledTimes(3);
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
});
