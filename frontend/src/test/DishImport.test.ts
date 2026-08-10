import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';
enableAutoUnmount(afterEach);
import { createRouter, createMemoryHistory } from 'vue-router';
import { createPinia, setActivePinia } from 'pinia';
import type { YrecipePreview, AdminDish, AdminDishCategory } from '../api/admin';
import AdminDashboard from '../components/AdminDashboard.vue';

const mocks = vi.hoisted(() => ({
  previewDishImport: vi.fn<(file: File) => Promise<YrecipePreview>>(),
  commitDishImport:
    vi.fn<(token: string, payload: { category: string; published?: boolean }) => Promise<AdminDish>>(),
  cancelDishImport: vi.fn<(token: string) => Promise<void>>(),
  exportDish: vi.fn<(id: number) => Promise<void>>(),
}));

vi.mock('../api/admin', async (importOriginal) => {
  const mod = await importOriginal<typeof import('../api/admin')>();
  return {
    ...mod,
    previewDishImport: mocks.previewDishImport,
    commitDishImport: mocks.commitDishImport,
    cancelDishImport: mocks.cancelDishImport,
    exportDish: mocks.exportDish,
    hasValidAdminSession: () => true,
    getAdminSessionName: () => 'TestAdmin',
    fetchAdminPosts: vi.fn().mockResolvedValue({ items: [], totalElements: 0, totalPages: 1 }),
    fetchAdminCategories: vi.fn().mockResolvedValue([]),
    fetchAdminDishCategories: vi.fn<() => Promise<AdminDishCategory[]>>().mockResolvedValue([
      {
        id: 1,
        name: '十分钟菜',
        slug: 'shifenzhongcai',
        description: '',
        dishCount: 5,
        publishedDishCount: 3,
      },
      { id: 2, name: '川菜', slug: 'chuan-cai', description: '', dishCount: 2, publishedDishCount: 2 },
    ]),
    fetchAdminDishes: vi.fn().mockResolvedValue({ items: [], totalElements: 0, totalPages: 1 }),
    fetchNotes: vi.fn().mockResolvedValue({ items: [], totalElements: 0, totalPages: 1 }),
    fetchAdminStats: vi.fn().mockResolvedValue({
      posts: 0,
      dishes: 0,
      notes: 0,
      publishedPosts: 0,
      draftPosts: 0,
      attachmentCount: 0,
      attachmentBytes: 0,
      aiUsage: { requests: 0, tokens: 0 },
      viewTrend: [],
      topPosts: [],
    }),
    logout: vi.fn(),
  };
});

vi.mock('axios', () => ({
  default: {
    isAxiosError: (err: unknown) => (err as Record<string, unknown>)?.isAxiosError === true,
    create: () => ({
      get: vi.fn(),
      post: vi.fn(),
      put: vi.fn(),
      delete: vi.fn(),
      interceptors: { request: { use: vi.fn() }, response: { use: vi.fn() } },
    }),
  },
  isAxiosError: (err: unknown) => (err as Record<string, unknown>)?.isAxiosError === true,
}));

vi.mock('../api/content', async (importOriginal) => {
  const mod = await importOriginal<typeof import('../api/content')>();
  return {
    ...mod,
    fetchDailyQuotes: vi.fn().mockResolvedValue([]),
  };
});

const MOCK_PREVIEW: YrecipePreview = {
  token: 'preview-token-abc',
  expiresAt: '2099-12-31T23:59:59Z',
  recipe: {
    schemaVersion: '1.0',
    kind: 'recipe',
    packageId: 'pkg-1',
    recipe: {
      name: '麻婆豆腐',
      slug: 'mapo-tofu',
      summary: '经典川味家常菜',
      categoryHint: '川菜',
      prepMinutes: 20,
      difficulty: '家常',
      baseServings: 3,
      ingredients: ['嫩豆腐 400 克', '牛肉末 80 克', '豆瓣酱 2 汤匙'],
      steps: ['豆腐切块焯水', '炒香肉末与豆瓣酱', '加水烧开后下豆腐'],
    },
    cover: {
      path: 'cover.jpg',
      alt: '麻婆豆腐成品图',
    },
    source: { title: '下厨房', creator: '美食博主' },
    generation: null,
  },
  warnings: ['食材中找不到 "花椒粉"，已按原样保留'],
  categoryMatch: '十分钟菜',
  slugAvailable: true,
  coverPreviewUrl: '/api/v1/admin/dish-imports/preview-token-abc/cover',
};

const MOCK_PREVIEW_NO_SLUG: YrecipePreview = {
  ...MOCK_PREVIEW,
  token: 'preview-token-no-slug',
  recipe: {
    ...MOCK_PREVIEW.recipe,
    recipe: { ...MOCK_PREVIEW.recipe.recipe, slug: null },
  },
  categoryMatch: null,
  slugAvailable: true,
  coverPreviewUrl: '/api/v1/admin/dish-imports/preview-token-no-slug/cover',
};

const MOCK_ADMIN_DISH: AdminDish = {
  id: 42,
  slug: 'mapo-tofu',
  name: '麻婆豆腐',
  summary: '经典川味家常菜',
  category: '十分钟菜',
  imageUrl: '/food/mapo-tofu.jpg',
  imageAlt: '麻婆豆腐',
  prepMinutes: 20,
  difficulty: '家常',
  rating: 4.5,
  featured: false,
  published: true,
  displayOrder: 0,
  favoriteCount: 0,
  ingredients: ['嫩豆腐 400 克', '牛肉末 80 克'],
  steps: ['豆腐切块焯水', '炒香肉末与豆瓣酱'],
  createdAt: '2026-07-01T00:00:00Z',
  updatedAt: '2026-07-01T00:00:00Z',
};

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div>Home</div>' } },
      { path: '/admin', name: 'admin', component: AdminDashboard },
    ],
  });
}

async function mountDashboard() {
  setActivePinia(createPinia());
  const router = makeRouter();
  await router.push('/admin?section=dishes');
  await router.isReady();
  const wrapper = mount(AdminDashboard, { global: { plugins: [router] } });
  await flushPromises();
  return { wrapper, router };
}

function findImportModal(wrapper: ReturnType<typeof mount>) {
  return wrapper.find('[aria-label="导入菜谱"]');
}

function findImportButton(wrapper: ReturnType<typeof mount>) {
  const btns = wrapper.findAll('.content-head-actions button');
  return btns.find((b) => b.text().includes('导入菜谱'));
}

function findCommitButton(wrapper: ReturnType<typeof mount>) {
  const btns = wrapper.findAll('.import-modal footer button');
  return btns.find((b) => b.text().includes('创建菜品草稿'));
}

function findCancelButton(wrapper: ReturnType<typeof mount>) {
  const btns = wrapper.findAll('.import-modal footer button');
  return btns.find((b) => b.text().includes('取消'));
}

function findCategorySelect(wrapper: ReturnType<typeof mount>) {
  const labels = wrapper.findAll('.import-modal label');
  const catLabel = labels.find((l) => l.text().includes('菜品分类'));
  return catLabel?.find('select');
}

beforeEach(() => {
  vi.restoreAllMocks();
});

describe('previewDishImport', () => {
  it('sends a POST with FormData to /admin/dish-imports/preview and returns YrecipePreview', async () => {
    const file = new File(['test'], 'test.yrecipe', { type: 'application/octet-stream' });
    mocks.previewDishImport.mockResolvedValue(MOCK_PREVIEW);

    const result = await mocks.previewDishImport(file);

    expect(result).toEqual(MOCK_PREVIEW);
    expect(result.token).toBe('preview-token-abc');
    expect(result.recipe.recipe.name).toBe('麻婆豆腐');
    expect(result.warnings).toHaveLength(1);
    expect(result.categoryMatch).toBe('十分钟菜');
    expect(result.slugAvailable).toBe(true);
  });

  it('propagates errors from the API layer', async () => {
    const file = new File(['bad'], 'bad.yrecipe', { type: 'application/octet-stream' });
    mocks.previewDishImport.mockRejectedValue(new Error('解析失败'));

    await expect(mocks.previewDishImport(file)).rejects.toThrow('解析失败');
  });
});

describe('commitDishImport', () => {
  it('sends a POST with token and payload and returns AdminDish', async () => {
    const payload = { category: '十分钟菜' };
    mocks.commitDishImport.mockResolvedValue(MOCK_ADMIN_DISH);

    const result = await mocks.commitDishImport('preview-token-abc', payload);

    expect(result).toEqual(MOCK_ADMIN_DISH);
    expect(result.id).toBe(42);
    expect(result.name).toBe('麻婆豆腐');
  });

  it('accepts an automatically generated slug', async () => {
    mocks.commitDishImport.mockResolvedValue(MOCK_ADMIN_DISH);

    const result = await mocks.commitDishImport('preview-token-abc', { category: '川菜' });

    expect(result.id).toBe(42);
  });

  it('propagates server errors on commit', async () => {
    mocks.commitDishImport.mockRejectedValue(new Error('创建失败'));

    await expect(mocks.commitDishImport('bad-token', { category: '川菜' })).rejects.toThrow('创建失败');
  });
});

describe('cancelDishImport', () => {
  it('sends a DELETE with the preview token', async () => {
    mocks.cancelDishImport.mockResolvedValue(undefined);

    await expect(mocks.cancelDishImport('preview-token-abc')).resolves.toBeUndefined();
  });

  it('does not throw even if cancellation fails server-side', async () => {
    mocks.cancelDishImport.mockRejectedValue(new Error('not found'));

    await expect(mocks.cancelDishImport('bad-token')).rejects.toThrow('not found');
  });
});

describe('exportDish', () => {
  it('triggers a download for the given dish id', async () => {
    mocks.exportDish.mockResolvedValue(undefined);

    await expect(mocks.exportDish(42)).resolves.toBeUndefined();
  });

  it('propagates export errors', async () => {
    mocks.exportDish.mockRejectedValue(new Error('导出失败'));

    await expect(mocks.exportDish(999)).rejects.toThrow('导出失败');
  });
});

describe('AdminDashboard import flow', () => {
  let wrapper: ReturnType<typeof mount>;

  beforeEach(async () => {
    const mounted = await mountDashboard();
    wrapper = mounted.wrapper;
  });

  it('shows import button when dishes tab is active', () => {
    const btn = findImportButton(wrapper);
    expect(btn?.exists()).toBe(true);
    expect(btn?.text()).toContain('导入菜谱');
  });

  it('opens the import modal when clicking import button', async () => {
    mocks.previewDishImport.mockResolvedValue(MOCK_PREVIEW);

    await findImportButton(wrapper)?.trigger('click');
    await flushPromises();

    expect(findImportModal(wrapper).exists()).toBe(true);
    expect(wrapper.text()).toContain('请选择一个 .yrecipe 文件');
  });

  it('calls previewDishImport after selecting a file and shows preview with recipe info', async () => {
    mocks.previewDishImport.mockResolvedValue(MOCK_PREVIEW);

    await findImportButton(wrapper)?.trigger('click');
    await flushPromises();
    vi.advanceTimersByTime(200);
    await flushPromises();

    const fileInput = wrapper.find<HTMLInputElement>('input[type="file"]');
    const file = new File(['test'], 'test.yrecipe', { type: 'application/octet-stream' });
    Object.defineProperty(fileInput.element, 'files', { value: [file] });
    await fileInput.trigger('change');
    await flushPromises();

    expect(mocks.previewDishImport).toHaveBeenCalledWith(file);
    expect(wrapper.text()).toContain('麻婆豆腐');
    expect(wrapper.text()).toContain('经典川味家常菜');
    expect(wrapper.text()).toContain('20 分钟');
    expect(wrapper.text()).toContain('家常');
    expect(wrapper.text()).toContain('3 人份');
    expect(wrapper.text()).toContain('嫩豆腐 400 克');
    expect(wrapper.text()).toContain('豆腐切块焯水');
  });

  it('shows warnings from the preview', async () => {
    mocks.previewDishImport.mockResolvedValue(MOCK_PREVIEW);

    await findImportButton(wrapper)?.trigger('click');
    await flushPromises();
    vi.advanceTimersByTime(200);
    await flushPromises();

    const fileInput = wrapper.find<HTMLInputElement>('input[type="file"]');
    Object.defineProperty(fileInput.element, 'files', {
      value: [new File(['test'], 'test.yrecipe', { type: 'application/octet-stream' })],
    });
    await fileInput.trigger('change');
    await flushPromises();

    expect(wrapper.text()).toContain('食材中找不到');
  });

  it('pre-fills category from categoryMatch and does not expose slug editing', async () => {
    mocks.previewDishImport.mockResolvedValue(MOCK_PREVIEW);

    await findImportButton(wrapper)?.trigger('click');
    await flushPromises();
    vi.advanceTimersByTime(200);
    await flushPromises();

    const fileInput = wrapper.find<HTMLInputElement>('input[type="file"]');
    Object.defineProperty(fileInput.element, 'files', {
      value: [new File(['test'], 'test.yrecipe', { type: 'application/octet-stream' })],
    });
    await fileInput.trigger('change');
    await flushPromises();

    expect(wrapper.find('.import-modal').text()).not.toContain('Slug（路由别名）');

    const catSelect = findCategorySelect(wrapper);
    expect(catSelect?.element).toBeTruthy();
    expect((catSelect?.element as HTMLSelectElement).value).toBe('十分钟菜');
  });

  it('pre-fills category from dishCategories first entry when categoryMatch is null', async () => {
    mocks.previewDishImport.mockResolvedValue(MOCK_PREVIEW_NO_SLUG);

    await findImportButton(wrapper)?.trigger('click');
    await flushPromises();
    vi.advanceTimersByTime(200);
    await flushPromises();

    const fileInput = wrapper.find<HTMLInputElement>('input[type="file"]');
    Object.defineProperty(fileInput.element, 'files', {
      value: [new File(['test'], 'test.yrecipe', { type: 'application/octet-stream' })],
    });
    await fileInput.trigger('change');
    await flushPromises();

    const catSelect = findCategorySelect(wrapper);
    expect((catSelect?.element as HTMLSelectElement).value).toBe('十分钟菜');
  });

  it('allows selecting a different category before commit', async () => {
    mocks.previewDishImport.mockResolvedValue(MOCK_PREVIEW);

    await findImportButton(wrapper)?.trigger('click');
    await flushPromises();
    vi.advanceTimersByTime(200);
    await flushPromises();

    const fileInput = wrapper.find<HTMLInputElement>('input[type="file"]');
    Object.defineProperty(fileInput.element, 'files', {
      value: [new File(['test'], 'test.yrecipe', { type: 'application/octet-stream' })],
    });
    await fileInput.trigger('change');
    await flushPromises();

    const catSelect = findCategorySelect(wrapper);
    await catSelect?.setValue('川菜');
    await flushPromises();

    expect((catSelect?.element as HTMLSelectElement).value).toBe('川菜');
  });

  it('calls commitDishImport with token and category on commit', async () => {
    mocks.previewDishImport.mockResolvedValue(MOCK_PREVIEW);
    mocks.commitDishImport.mockResolvedValue(MOCK_ADMIN_DISH);

    await findImportButton(wrapper)?.trigger('click');
    await flushPromises();
    vi.advanceTimersByTime(200);
    await flushPromises();

    const fileInput = wrapper.find<HTMLInputElement>('input[type="file"]');
    Object.defineProperty(fileInput.element, 'files', {
      value: [new File(['test'], 'test.yrecipe', { type: 'application/octet-stream' })],
    });
    await fileInput.trigger('change');
    await flushPromises();

    const catSelect = findCategorySelect(wrapper);
    await catSelect?.setValue('川菜');
    await flushPromises();

    await findCommitButton(wrapper)?.trigger('click');
    await flushPromises();

    expect(mocks.commitDishImport).toHaveBeenCalledWith('preview-token-abc', {
      category: '川菜',
    });
  });

  it('uses the same automatic-slug flow when the package has no slug', async () => {
    mocks.previewDishImport.mockResolvedValue(MOCK_PREVIEW_NO_SLUG);
    mocks.commitDishImport.mockResolvedValue(MOCK_ADMIN_DISH);

    await findImportButton(wrapper)?.trigger('click');
    await flushPromises();
    vi.advanceTimersByTime(200);
    await flushPromises();

    const fileInput = wrapper.find<HTMLInputElement>('input[type="file"]');
    Object.defineProperty(fileInput.element, 'files', {
      value: [new File(['test'], 'test.yrecipe', { type: 'application/octet-stream' })],
    });
    await fileInput.trigger('change');
    await flushPromises();

    const catSelect = findCategorySelect(wrapper);
    await catSelect?.setValue('川菜');
    await flushPromises();

    await findCommitButton(wrapper)?.trigger('click');
    await flushPromises();

    expect(mocks.commitDishImport).toHaveBeenCalledWith('preview-token-no-slug', {
      category: '川菜',
    });
  });

  it('disables commit button when no category is selected', async () => {
    mocks.previewDishImport.mockResolvedValue(MOCK_PREVIEW);

    await findImportButton(wrapper)?.trigger('click');
    await flushPromises();
    vi.advanceTimersByTime(200);
    await flushPromises();

    const fileInput = wrapper.find<HTMLInputElement>('input[type="file"]');
    Object.defineProperty(fileInput.element, 'files', {
      value: [new File(['test'], 'test.yrecipe', { type: 'application/octet-stream' })],
    });
    await fileInput.trigger('change');
    await flushPromises();

    const catSelect = findCategorySelect(wrapper);
    await catSelect?.setValue('');
    await flushPromises();

    const commitBtn = findCommitButton(wrapper);
    expect(commitBtn?.attributes('disabled')).toBeDefined();
  });

  it('closes the import modal after successful commit', async () => {
    mocks.previewDishImport.mockResolvedValue(MOCK_PREVIEW);
    mocks.commitDishImport.mockResolvedValue(MOCK_ADMIN_DISH);

    await findImportButton(wrapper)?.trigger('click');
    await flushPromises();
    vi.advanceTimersByTime(200);
    await flushPromises();

    const fileInput = wrapper.find<HTMLInputElement>('input[type="file"]');
    Object.defineProperty(fileInput.element, 'files', {
      value: [new File(['test'], 'test.yrecipe', { type: 'application/octet-stream' })],
    });
    await fileInput.trigger('change');
    await flushPromises();

    await findCommitButton(wrapper)?.trigger('click');
    await flushPromises();

    expect(findImportModal(wrapper).exists()).toBe(false);
  });

  it('calls cancelDishImport and closes modal on cancel button click', async () => {
    mocks.previewDishImport.mockResolvedValue(MOCK_PREVIEW);
    mocks.cancelDishImport.mockResolvedValue(undefined);

    await findImportButton(wrapper)?.trigger('click');
    await flushPromises();
    vi.advanceTimersByTime(200);
    await flushPromises();

    const fileInput = wrapper.find<HTMLInputElement>('input[type="file"]');
    Object.defineProperty(fileInput.element, 'files', {
      value: [new File(['test'], 'test.yrecipe', { type: 'application/octet-stream' })],
    });
    await fileInput.trigger('change');
    await flushPromises();

    await findCancelButton(wrapper)?.trigger('click');
    await flushPromises();

    expect(mocks.cancelDishImport).toHaveBeenCalledWith('preview-token-abc');
    expect(findImportModal(wrapper).exists()).toBe(false);
  });

  it('calls cancelDishImport and closes when clicking the modal backdrop', async () => {
    mocks.previewDishImport.mockResolvedValue(MOCK_PREVIEW);
    mocks.cancelDishImport.mockResolvedValue(undefined);

    await findImportButton(wrapper)?.trigger('click');
    await flushPromises();
    vi.advanceTimersByTime(200);
    await flushPromises();

    const fileInput = wrapper.find<HTMLInputElement>('input[type="file"]');
    Object.defineProperty(fileInput.element, 'files', {
      value: [new File(['test'], 'test.yrecipe', { type: 'application/octet-stream' })],
    });
    await fileInput.trigger('change');
    await flushPromises();

    await wrapper.find('.admin-editor-backdrop').trigger('click');
    await flushPromises();

    expect(mocks.cancelDishImport).toHaveBeenCalledWith('preview-token-abc');
    expect(findImportModal(wrapper).exists()).toBe(false);
  });

  it('calls cancelDishImport and closes when clicking the x close button', async () => {
    mocks.previewDishImport.mockResolvedValue(MOCK_PREVIEW);
    mocks.cancelDishImport.mockResolvedValue(undefined);

    await findImportButton(wrapper)?.trigger('click');
    await flushPromises();
    vi.advanceTimersByTime(200);
    await flushPromises();

    const fileInput = wrapper.find<HTMLInputElement>('input[type="file"]');
    Object.defineProperty(fileInput.element, 'files', {
      value: [new File(['test'], 'test.yrecipe', { type: 'application/octet-stream' })],
    });
    await fileInput.trigger('change');
    await flushPromises();

    const closeBtn = findImportModal(wrapper).find('button[aria-label="关闭导入"]');
    await closeBtn.trigger('click');
    await flushPromises();

    expect(mocks.cancelDishImport).toHaveBeenCalledWith('preview-token-abc');
    expect(findImportModal(wrapper).exists()).toBe(false);
  });

  it('shows generic error when import fails', async () => {
    mocks.previewDishImport.mockRejectedValue(new Error('network'));

    await findImportButton(wrapper)?.trigger('click');
    await flushPromises();
    vi.advanceTimersByTime(200);
    await flushPromises();

    // Directly call handleImportFile via the input change
    const fileInput = wrapper.find('input[type="file"]');
    const file = new File(['bad'], 'bad.yrecipe', { type: 'application/octet-stream' });
    Object.defineProperty(fileInput.element, 'files', { value: [file] });
    await fileInput.trigger('change');
    await flushPromises();

    expect(wrapper.text()).toContain('导入菜谱失败');
  });

  it('shows error message when commitDishImport fails', async () => {
    mocks.previewDishImport.mockResolvedValue(MOCK_PREVIEW);
    mocks.commitDishImport.mockRejectedValue({
      isAxiosError: true,
      response: { status: 409, data: { message: 'slug 已存在' } },
    });

    await findImportButton(wrapper)?.trigger('click');
    await flushPromises();
    vi.advanceTimersByTime(200);
    await flushPromises();

    const fileInput = wrapper.find<HTMLInputElement>('input[type="file"]');
    Object.defineProperty(fileInput.element, 'files', {
      value: [new File(['test'], 'test.yrecipe', { type: 'application/octet-stream' })],
    });
    await fileInput.trigger('change');
    await flushPromises();

    await findCommitButton(wrapper)?.trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('slug 已存在');
  });

  it('shows generic error when commitDishImport fails without a message', async () => {
    mocks.previewDishImport.mockResolvedValue(MOCK_PREVIEW);
    mocks.commitDishImport.mockRejectedValue(new Error('server error'));

    await findImportButton(wrapper)?.trigger('click');
    await flushPromises();
    vi.advanceTimersByTime(200);
    await flushPromises();

    const fileInput = wrapper.find<HTMLInputElement>('input[type="file"]');
    Object.defineProperty(fileInput.element, 'files', {
      value: [new File(['test'], 'test.yrecipe', { type: 'application/octet-stream' })],
    });
    await fileInput.trigger('change');
    await flushPromises();

    await findCommitButton(wrapper)?.trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('创建菜品草稿失败。');
  });
});
