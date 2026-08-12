import { createMemoryHistory, createRouter } from 'vue-router';
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import CategoriesPage from '../pages/CategoriesPage.vue';
import CategoryPage from '../pages/CategoryPage.vue';

const fetchCategories = vi.fn();
const fetchCategoryDetail = vi.fn();

vi.mock('../api/content', () => ({
  fetchCategories: (...args: unknown[]) => fetchCategories(...args),
  fetchCategoryDetail: (...args: unknown[]) => fetchCategoryDetail(...args),
}));

const category = {
  name: '工程实践',
  slug: 'engineering',
  description: '把复杂系统讲清楚。',
  publishedPostCount: 2,
};

const post = {
  slug: 'typed-content',
  title: '用类型管理内容',
  excerpt: '让内容边界保持清晰。',
  date: '2026-08-01',
  readTime: 5,
  category: category.name,
  tags: ['TypeScript'],
  color: '#1649d8',
  number: '01',
};

function makeRouter(path: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/categories', name: 'categories', component: CategoriesPage },
      { path: '/categories/:slug', name: 'category-detail', component: CategoryPage },
      { path: '/articles/:slug', component: { template: '<div />' } },
    ],
  });
  return router
    .push(path)
    .then(() => router.isReady())
    .then(() => router);
}

describe('M4 public category routes', () => {
  beforeEach(() => {
    fetchCategories.mockReset();
    fetchCategoryDetail.mockReset();
    document.head.innerHTML = '';
  });

  it('renders category index from the public API without bundled fallback data', async () => {
    fetchCategories.mockResolvedValue([category]);
    const router = await makeRouter('/categories');
    const wrapper = mount(CategoriesPage, { global: { plugins: [router] } });

    await flushPromises();

    expect(wrapper.get('h1').text()).toContain('按主题阅读');
    expect(wrapper.get('a.category-card').attributes('href')).toBe('/categories/engineering');
    expect(wrapper.text()).toContain('2 篇公开文章');
  });

  it('loads a category detail and keeps pagination in the shareable URL', async () => {
    fetchCategoryDetail.mockResolvedValue({
      ...category,
      total: 2,
      posts: [post],
      page: 0,
      size: 10,
      totalPages: 2,
    });
    const router = await makeRouter('/categories/engineering');
    const wrapper = mount(CategoryPage, { global: { plugins: [router] } });

    await flushPromises();

    expect(wrapper.text()).toContain('用类型管理内容');
    expect(router.currentRoute.value.name).toBe('category-detail');
    await wrapper.get('[aria-label="分类文章分页"] button:last-of-type').trigger('click');
    await flushPromises();
    expect(router.currentRoute.value.query.page).toBe('2');
  });

  it('shows an explicit API failure instead of substituting local seed content', async () => {
    fetchCategories.mockRejectedValue(new Error('offline'));
    const router = await makeRouter('/categories');
    const wrapper = mount(CategoriesPage, { global: { plugins: [router] } });

    await flushPromises();

    expect(wrapper.get('[role="alert"]').text()).toContain('分类加载失败');
    expect(wrapper.text()).not.toContain('clarity-by-design');
  });
});
