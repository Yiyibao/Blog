<script setup lang="ts">
import axios from 'axios';
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import {
  logout as apiLogout,
  batchUpdatePosts,
  cancelPostSchedule,
  convertPostsMarkdown,
  createDish,
  createDishCategory,
  createPost,
  createPostCategory,
  deleteDish,
  deleteDishCategory,
  deletePost,
  deletePostCategory,
  fetchAdminCategories,
  fetchAdminDishCategories,
  fetchAdminDishes,
  fetchAdminPost,
  fetchAdminPosts,
  fetchAdminStats,
  fetchPostPublicationAudit,
  fetchNotes,
  getAdminSessionName,
  hasValidAdminSession,
  schedulePost,
  updateDish,
  updateDishCategory,
  uploadDishImage,
  attachDishImage,
  deleteDishImage,
  updatePost,
  updatePostCategory,
  type AdminDish,
  type AdminDishCategory,
  type AdminNoteSummary,
  type AdminPostCategory,
  type AdminPostSummary,
  type DishPayload,
  type DishImageUpload,
  type PostPayload,
  type PostBatchAction,
  type PostPublicationAudit,
  previewDishImport,
  commitDishImport,
  cancelDishImport,
  exportDish,
  type YrecipePreview,
} from '../api/admin';
import type { PostStatus } from '../data';

import AdminSidebar from './AdminSidebar.vue';
import AdminEditorModal from './AdminEditorModal.vue';
import type { AiActionKind } from './AiActionChips.vue';
import PostRevisionDrawer from './PostRevisionDrawer.vue';
import DashboardTrends from './DashboardTrends.vue';
import PaginationNav from './PaginationNav.vue';
import RecipeExtractionModal from './RecipeExtractionModal.vue';
import type { AdminPost, AdminStats } from '../api/admin';
import { useContentStore } from '../stores/contentStore';

const router = useRouter();
const route = useRoute();
const tab = ref<'posts' | 'dishes'>('posts');

const isOverview = computed(() => {
  const section = Array.isArray(route.query.section) ? route.query.section[0] : route.query.section;
  return !section || section === 'overview';
});

const breadcrumbTitle = computed(() => {
  const section = Array.isArray(route.query.section) ? route.query.section[0] : route.query.section;
  if (section === 'posts') return '后台管理 / 文章管理';
  if (section === 'dishes') return '后台管理 / 菜品管理';
  return '后台管理 / 总览';
});

function syncTabFromQuery() {
  const section = Array.isArray(route.query.section) ? route.query.section[0] : route.query.section;
  if (section === 'dishes') tab.value = 'dishes';
  else tab.value = 'posts';
}

watch(() => route.query.section, syncTabFromQuery, { immediate: true });

function setTab(nextTab: 'posts' | 'dishes') {
  tab.value = nextTab;
  void router.push({ path: '/admin', query: { section: nextTab } });
}
const posts = ref<AdminPostSummary[]>([]);
const dishes = ref<AdminDish[]>([]);
const notes = ref<AdminNoteSummary[]>([]);
const categories = ref<AdminPostCategory[]>([]);
const dishCategories = ref<AdminDishCategory[]>([]);
const loading = ref(true);
const saving = ref(false);
const error = ref('');
const editorOpen = ref(false);
const editingId = ref<number | null>(null);
const editorKind = ref<'post' | 'dish'>('post');
const categoryManagerOpen = ref(false);
const categoryEditingId = ref<number | null>(null);
const categoryForm = reactive({ name: '', description: '' });
const dishCategoryManagerOpen = ref(false);
const dishCategoryEditingId = ref<number | null>(null);
const dishCategoryForm = reactive({ name: '', description: '' });
const postStatusFilter = ref<'' | PostStatus>('');
const selectedPostIds = ref<number[]>([]);
const postBatchAction = ref<PostBatchAction>('PUBLISH');
const postBatchTags = ref('');
const publicationAudit = ref<PostPublicationAudit[]>([]);
const publicationAuditOpen = ref(false);
const workflowSaving = ref(false);
const postPage = ref(0);
const postPageSize = 6;
const postTotalPages = ref(1);
const postTotal = ref(0);
const dishPage = ref(0);
const dishTotalPages = ref(1);
const dishTotal = ref(0);
const noteTotal = ref(0);
const contentPageSize = 6;
const username = getAdminSessionName() || 'Admin';
const greeting = (() => {
  const hour = new Date().getHours();
  if (hour < 6) return '夜深了';
  if (hour < 12) return '上午好';
  if (hour < 18) return '下午好';
  return '晚上好';
})();

const postForm = reactive({
  slug: '',
  title: '',
  excerpt: '',
  date: new Date().toISOString().slice(0, 10),
  readTime: 5,
  category: '工程实践',
  tags: '',
  color: '#A6784C',
  number: '01',
  featured: false,
  status: 'DRAFT' as PostStatus,
  // 3A-3：新文章全程 Markdown；content 仅承载存量 HTML（快照随存量篇一起带回）
  content: '',
  markdownContent: '# 新文章\n\n从这里开始写正文。',
  contentFormat: 'MARKDOWN' as 'HTML' | 'MARKDOWN',
});

/** 3A-3：Markdown 模式判定——MARKDOWN 篇或已有转换稿的存量篇都走 TyporaEditor；纯 HTML 未转换篇保留旧文本域 */
const postMarkdownMode = computed(
  () => postForm.contentFormat === 'MARKDOWN' || postForm.markdownContent.trim().length > 0,
);

const converting = ref(false);

/** 存量未转换篇的一键转换：跑 3A-2 端点后重取本篇详情（markdown 稿即到位）。 */
async function convertLegacyPost() {
  if (!editingId.value) return;
  converting.value = true;
  error.value = '';
  try {
    await convertPostsMarkdown();
    const full = await fetchAdminPost(editingId.value);
    postForm.markdownContent = full.markdownContent ?? '';
    postForm.contentFormat = full.contentFormat ?? 'HTML';
  } catch (cause) {
    if (!handleAuthError(cause)) error.value = '转换失败，请稍后重试。';
  } finally {
    converting.value = false;
  }
}

/** 文章暂无独立图床——编辑器里请使用既有图片外链或站内路径。 */
async function rejectPostImageUpload(): Promise<string> {
  throw new Error('文章编辑器暂不支持直传图片');
}

/** 4A-5：AI 动作结果一键回填——只填入表单，保存仍是作者显式动作。 */
function currentPostContext(): string {
  return postMarkdownMode.value ? postForm.markdownContent : postForm.content;
}

function applyAiAction(action: AiActionKind, text: string) {
  if (action === 'summary') {
    postForm.excerpt = text;
  } else if (action === 'title') {
    const first = text
      .split('\n')
      .map((line) => line.replace(/^\s*(?:[-*]|\d+[.、])\s*/, '').trim())
      .find(Boolean);
    if (first) postForm.title = first;
  } else if (action === 'tags') {
    postForm.tags = text
      .split(/[,，、\n]/)
      .map((t) => t.trim())
      .filter(Boolean)
      .slice(0, 6)
      .join(', ');
  } else if (action === 'polish') {
    if (postMarkdownMode.value) postForm.markdownContent = text;
    else postForm.content = text;
  } else if (action === 'continue') {
    if (postMarkdownMode.value) postForm.markdownContent = `${postForm.markdownContent.trimEnd()}\n\n${text}`;
    else postForm.content = `${postForm.content.trimEnd()}\n\n${text}`;
  }
}
/** 4D：完整统计（趋势/TOP5/容量/AI 用量）。 */
const adminStats = ref<AdminStats | null>(null);

/** 4C：版本历史抽屉——恢复后回填表单（恢复只回写正文相关字段，meta 保持编辑器现值）。 */
const revisionDrawerOpen = ref(false);

function applyRestoredPost(post: AdminPost) {
  Object.assign(postForm, {
    title: post.title,
    excerpt: post.excerpt,
    content: post.content,
    markdownContent: post.markdownContent ?? '',
    contentFormat: post.contentFormat ?? 'HTML',
  });
  revisionDrawerOpen.value = false;
}

const importOpen = ref(false);
const importLoading = ref(false);
const importError = ref('');
const importPreview = ref<YrecipePreview | null>(null);
const extractionOpen = ref(false);

function openExtraction() {
  extractionOpen.value = true;
}

async function onExtractionDone() {
  extractionOpen.value = false;
  await load();
  const contentStore = useContentStore();
  await contentStore.loadRemoteContent().catch(() => null);
}
const importCommitCategory = ref('');

const dishForm = reactive({
  name: '',
  summary: '',
  category: '十分钟菜',
  imageUrl: '',
  imageAlt: '',
  prepMinutes: 20,
  difficulty: '家常' as AdminDish['difficulty'],
  rating: 4.5,
  featured: false,
  published: true,
  displayOrder: 0,
  baseServings: 2,
  ingredients: '',
  steps: '',
});
const dishImagePreviewUrl = ref('');
const dishImageUpload = ref<DishImageUpload | null>(null);
const dishImageOriginalUrl = ref('');
const dishImageUploading = ref(false);
const dishImageError = ref('');
let dishImageSelectionToken = 0;
const contentTitle = computed(() => ({ posts: '文章管理', dishes: '菜品管理' })[tab.value]);
const contentNoun = computed(() => ({ posts: '文章', dishes: '菜品' })[tab.value]);
const editorNoun = computed(() => ({ post: '文章', dish: '菜品' })[editorKind.value]);

function changePostPage(page: number) {
  postPage.value = page;
  void load();
}

function changeDishPage(page: number) {
  dishPage.value = page;
  void load();
}

function handleAuthError(cause: unknown) {
  if (axios.isAxiosError(cause) && cause.response?.status === 401) {
    logout();
    return true;
  }
  return false;
}

const MAX_DISH_IMAGE_BYTES = 8 * 1024 * 1024;
const DISH_IMAGE_TYPES = ['image/png', 'image/jpeg', 'image/webp', 'image/gif'];

function releaseDishImagePreview() {
  if (dishImagePreviewUrl.value.startsWith('blob:')) URL.revokeObjectURL(dishImagePreviewUrl.value);
  dishImagePreviewUrl.value = '';
}

function discardDishImageUpload() {
  const pending = dishImageUpload.value;
  dishImageUpload.value = null;
  if (pending) void deleteDishImage(pending.publicId).catch(() => null);
}

function resetDishImageState(originalUrl = '') {
  dishImageSelectionToken += 1;
  discardDishImageUpload();
  releaseDishImagePreview();
  dishImageOriginalUrl.value = originalUrl;
  dishImageUploading.value = false;
  dishImageError.value = '';
}

async function handleDishImageChange(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file) return;

  if (!DISH_IMAGE_TYPES.includes(file.type) || file.size > MAX_DISH_IMAGE_BYTES) {
    dishImageError.value = '请选择 PNG、JPG/JPEG、WebP 或 GIF 图片，文件不能超过 8 MB。';
    return;
  }

  const token = ++dishImageSelectionToken;
  const previousUpload = dishImageUpload.value;
  dishImageUpload.value = null;
  dishImageError.value = '';
  releaseDishImagePreview();
  dishImagePreviewUrl.value = URL.createObjectURL(file);
  dishImageUploading.value = true;

  try {
    const uploaded = await uploadDishImage(file);
    if (token !== dishImageSelectionToken) {
      await deleteDishImage(uploaded.publicId).catch(() => null);
      return;
    }
    dishImageUpload.value = uploaded;
    dishForm.imageUrl = uploaded.url;
    if (previousUpload) void deleteDishImage(previousUpload.publicId).catch(() => null);
  } catch (cause) {
    if (token === dishImageSelectionToken) {
      dishImageUpload.value = previousUpload;
      dishForm.imageUrl = previousUpload?.url || dishImageOriginalUrl.value;
      dishImageError.value =
        axios.isAxiosError(cause) && typeof cause.response?.data?.message === 'string'
          ? cause.response.data.message
          : '图片上传失败，请重试。';
      releaseDishImagePreview();
    }
  } finally {
    if (token === dishImageSelectionToken) dishImageUploading.value = false;
  }
}

function finishDishImageState() {
  dishImageSelectionToken += 1;
  dishImageUpload.value = null;
  releaseDishImagePreview();
  dishImageUploading.value = false;
  dishImageError.value = '';
}

function closeEditor() {
  resetDishImageState();
  editorOpen.value = false;
}

watch(editorOpen, (isOpen) => {
  if (isOpen) return;
  dishImageSelectionToken += 1;
  discardDishImageUpload();
  releaseDishImagePreview();
  dishImageUploading.value = false;
});

async function loadPosts() {
  const page = await fetchAdminPosts(postPage.value, postPageSize, postStatusFilter.value);
  posts.value = page.items;
  selectedPostIds.value = selectedPostIds.value.filter((id) => page.items.some((post) => post.id === id));
  postTotal.value = page.totalElements;
  postTotalPages.value = Math.max(1, page.totalPages);
  if (postPage.value > 0 && postPage.value >= page.totalPages) {
    postPage.value = Math.max(0, page.totalPages - 1);
    return loadPosts();
  }
}

async function load() {
  if (!hasValidAdminSession()) return logout();
  loading.value = true;
  error.value = '';
  try {
    const [, remoteDishes, remoteNotes, remoteCategories, remoteDishCategories] = await Promise.all([
      loadPosts(),
      fetchAdminDishes(dishPage.value, contentPageSize),
      fetchNotes(0, 1),
      fetchAdminCategories(),
      fetchAdminDishCategories(),
    ]);
    dishes.value = remoteDishes.items;
    dishTotal.value = remoteDishes.totalElements;
    dishTotalPages.value = Math.max(1, remoteDishes.totalPages);
    notes.value = remoteNotes.items;
    categories.value = remoteCategories;
    dishCategories.value = remoteDishCategories;
    noteTotal.value = remoteNotes.totalElements;
    if (dishPage.value > 0 && dishPage.value >= remoteDishes.totalPages) {
      dishPage.value = Math.max(0, remoteDishes.totalPages - 1);
      return load();
    }
  } catch (cause) {
    if (!handleAuthError(cause)) error.value = '暂时无法读取内容，请确认后端服务正在运行。';
  } finally {
    loading.value = false;
  }
  fetchAdminStats()
    .then((stats) => {
      postTotal.value = stats.posts;
      dishTotal.value = stats.dishes;
      noteTotal.value = stats.notes;
      // 4D：趋势卡片数据——防御旧响应形状（滚动部署窗口内后端可能还没带扩展字段）
      if (Array.isArray(stats.viewTrend)) adminStats.value = stats;
    })
    .catch(() => {});
}

function logout() {
  apiLogout();
  void router.replace('/admin/login');
}

function newItem() {
  resetDishImageState();
  // 使在途的 editPost 详情请求作废，避免其迟到响应覆盖新建表单
  editRequestToken += 1;
  editingId.value = null;
  editorKind.value = tab.value === 'posts' ? 'post' : 'dish';
  if (editorKind.value === 'post')
    Object.assign(postForm, {
      slug: '',
      title: '',
      excerpt: '',
      date: new Date().toISOString().slice(0, 10),
      readTime: 5,
      category: categories.value[0]?.name ?? '',
      tags: '',
      color: '#A6784C',
      number: String(postTotal.value + 1).padStart(2, '0'),
      featured: false,
      status: 'DRAFT',
      content: '',
      markdownContent: '# 新文章\n\n从这里开始写正文。',
      contentFormat: 'MARKDOWN',
    });
  else
    Object.assign(dishForm, {
      name: '',
      summary: '',
      category: dishCategories.value[0]?.name ?? '',
      imageUrl: '',
      imageAlt: '',
      prepMinutes: 20,
      difficulty: '家常',
      rating: 4.5,
      featured: false,
      published: true,
      displayOrder: dishTotal.value + 1,
      baseServings: 2,
      ingredients: '',
      steps: '',
    });
  editorOpen.value = true;
}

// P1-2：管理端列表为摘要 DTO（不含正文），编辑前必须先取详情，
// 否则表单里的旧正文会在保存时覆盖该文章的真实内容。
let editRequestToken = 0;

async function editPost(post: AdminPostSummary) {
  error.value = '';
  const token = ++editRequestToken;
  try {
    const full = await fetchAdminPost(post.id);
    // 等待期间用户已另开编辑（再点编辑/新建）时丢弃迟到的响应
    if (token !== editRequestToken || editorOpen.value) return;
    editingId.value = full.id;
    editorKind.value = 'post';
    Object.assign(postForm, {
      slug: full.slug,
      title: full.title,
      excerpt: full.excerpt,
      date: full.date,
      readTime: full.readTime,
      category: full.category,
      tags: full.tags.join(', '),
      color: full.color,
      number: full.number,
      featured: full.featured ?? false,
      status: full.status || 'PUBLISHED',
      content: full.content,
      markdownContent: full.markdownContent ?? '',
      contentFormat: full.contentFormat ?? 'HTML',
    });
    editorOpen.value = true;
  } catch (cause) {
    if (!handleAuthError(cause)) error.value = '读取文章正文失败，请稍后重试。';
  }
}

function editDish(dish: AdminDish) {
  resetDishImageState(dish.imageUrl);
  editingId.value = dish.id;
  editorKind.value = 'dish';
  Object.assign(dishForm, {
    ...dish,
    ingredients: dish.ingredients.join('\n'),
    steps: dish.steps.join('\n'),
  });
  editorOpen.value = true;
}

function postPayload(): PostPayload {
  // 3A-3/3A-5：Markdown 模式保存即按篇切换 MARKDOWN（编辑并保存 = 该篇校对签收）；
  // content 原样回传保留 HTML 快照，双列并存随时可回退
  return {
    ...postForm,
    slug: postForm.slug.trim() || null,
    tags: postForm.tags
      .split(',')
      .map((item) => item.trim())
      .filter(Boolean),
    contentFormat: postMarkdownMode.value ? 'MARKDOWN' : 'HTML',
    markdownContent: postForm.markdownContent || null,
  };
}

function dishPayload(): DishPayload {
  return {
    name: dishForm.name,
    summary: dishForm.summary,
    category: dishForm.category,
    imageUrl: dishForm.imageUrl,
    imageAlt: dishForm.imageAlt,
    prepMinutes: dishForm.prepMinutes,
    difficulty: dishForm.difficulty,
    rating: dishForm.rating,
    featured: dishForm.featured,
    published: dishForm.published,
    displayOrder: dishForm.displayOrder,
    baseServings: dishForm.baseServings,
    ingredients: dishForm.ingredients
      .split('\n')
      .map((item) => item.trim())
      .filter(Boolean),
    steps: dishForm.steps
      .split('\n')
      .map((item) => item.trim())
      .filter(Boolean),
  };
}

async function save() {
  if (editorKind.value === 'dish') {
    if (dishImageUploading.value) {
      error.value = '图片仍在上传，请稍候。';
      return;
    }
    if (!dishForm.imageUrl.trim()) {
      error.value = '请先选择并上传菜品图片。';
      return;
    }
  }
  saving.value = true;
  error.value = '';
  try {
    if (editorKind.value === 'post') {
      if (editingId.value) await updatePost(editingId.value, postPayload());
      else await createPost(postPayload());
    } else {
      const payload = dishPayload();
      const pendingUpload = dishImageUpload.value;
      const savedDish = editingId.value
        ? await updateDish(editingId.value, payload)
        : await createDish(payload);
      editingId.value = savedDish.id;
      if (pendingUpload) await attachDishImage(pendingUpload.publicId, savedDish.id);
      finishDishImageState();
    }
    editorOpen.value = false;
    await load();
    const contentStore = useContentStore();
    await contentStore.loadRemoteContent().catch(() => null);
  } catch (cause) {
    if (!handleAuthError(cause))
      error.value =
        axios.isAxiosError(cause) && cause.response?.status === 409
          ? '保存失败：唯一字段与现有记录冲突。'
          : '保存失败，请检查必填项和字段格式。';
  } finally {
    saving.value = false;
  }
}

async function remove(kind: 'post' | 'dish', id: number, title: string) {
  if (!window.confirm(`确认删除“${title}”？此操作无法撤销。`)) return;
  try {
    if (kind === 'post') await deletePost(id);
    else await deleteDish(id);
    await load();
    const contentStore = useContentStore();
    await contentStore.loadRemoteContent().catch(() => null);
  } catch (cause) {
    if (!handleAuthError(cause)) error.value = '删除失败，请稍后再试。';
  }
}

const importFileInput = ref<HTMLInputElement | null>(null);

function openImportFileInput() {
  importError.value = '';
  importPreview.value = null;
  importCommitCategory.value = '';
  importOpen.value = true;
  setTimeout(() => importFileInput.value?.click(), 100);
}

async function handleImportFile(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input?.files?.[0];
  if (!file) {
    importOpen.value = false;
    return;
  }
  importLoading.value = true;
  importError.value = '';
  try {
    const preview = await previewDishImport(file);
    importPreview.value = preview;
    importCommitCategory.value = preview.categoryMatch || (dishCategories.value[0]?.name ?? '');
  } catch (cause) {
    if (!handleAuthError(cause)) {
      importError.value =
        axios.isAxiosError(cause) && cause.response?.data?.message
          ? cause.response.data.message
          : '导入菜谱失败，请检查文件格式。';
    }
  } finally {
    importLoading.value = false;
    if (input) {
      (input as HTMLInputElement).value = '';
    }
  }
}

async function commitImport() {
  if (!importPreview.value) return;
  importLoading.value = true;
  importError.value = '';
  try {
    await commitDishImport(importPreview.value.token, {
      category: importCommitCategory.value,
    });
    importOpen.value = false;
    importPreview.value = null;
    await load();
    const contentStore = useContentStore();
    await contentStore.loadRemoteContent().catch(() => null);
  } catch (cause) {
    if (!handleAuthError(cause)) {
      importError.value =
        axios.isAxiosError(cause) && cause.response?.data?.message
          ? cause.response.data.message
          : '创建菜品草稿失败。';
    }
  } finally {
    importLoading.value = false;
  }
}

async function cancelImport() {
  if (importPreview.value) {
    try {
      await cancelDishImport(importPreview.value.token);
    } catch {}
  }
  importOpen.value = false;
  importPreview.value = null;
  importError.value = '';
}

async function handleExportDish(dish: AdminDish) {
  try {
    await exportDish(dish.id);
  } catch (cause) {
    if (!handleAuthError(cause)) {
      error.value =
        axios.isAxiosError(cause) && cause.response?.data?.message
          ? cause.response.data.message
          : '导出失败。';
    }
  }
}

function newCategory() {
  categoryEditingId.value = null;
  Object.assign(categoryForm, { name: '', description: '' });
  categoryManagerOpen.value = true;
}

function editCategory(category: AdminPostCategory) {
  categoryEditingId.value = category.id;
  Object.assign(categoryForm, { name: category.name, description: category.description });
}

async function saveCategory() {
  saving.value = true;
  error.value = '';
  try {
    const payload = { name: categoryForm.name.trim(), description: categoryForm.description.trim() };
    const saved = categoryEditingId.value
      ? await updatePostCategory(categoryEditingId.value, payload)
      : await createPostCategory(payload);
    categories.value = await fetchAdminCategories();
    postForm.category = saved.name;
    categoryEditingId.value = null;
    Object.assign(categoryForm, { name: '', description: '' });
  } catch (cause) {
    if (!handleAuthError(cause))
      error.value =
        axios.isAxiosError(cause) && cause.response?.status === 409
          ? '类别名称已存在。'
          : '类别保存失败，请检查名称。';
  } finally {
    saving.value = false;
  }
}

async function removeCategory(category: AdminPostCategory) {
  if (category.postCount > 0 || !window.confirm(`确认删除类别“${category.name}”？`)) return;
  try {
    await deletePostCategory(category.id);
    categories.value = await fetchAdminCategories();
    if (!categories.value.some((item) => item.name === postForm.category)) {
      postForm.category = categories.value[0]?.name ?? '';
    }
  } catch (cause) {
    if (!handleAuthError(cause)) error.value = '类别仍被文章使用，无法删除。';
  }
}

function newDishCategory() {
  dishCategoryEditingId.value = null;
  Object.assign(dishCategoryForm, { name: '', description: '' });
  dishCategoryManagerOpen.value = true;
}

function editDishCategory(category: AdminDishCategory) {
  dishCategoryEditingId.value = category.id;
  Object.assign(dishCategoryForm, { name: category.name, description: category.description });
}

async function saveDishCategory() {
  saving.value = true;
  error.value = '';
  try {
    const payload = { name: dishCategoryForm.name.trim(), description: dishCategoryForm.description.trim() };
    const saved = dishCategoryEditingId.value
      ? await updateDishCategory(dishCategoryEditingId.value, payload)
      : await createDishCategory(payload);
    dishCategories.value = await fetchAdminDishCategories();
    dishForm.category = saved.name;
    dishCategoryEditingId.value = null;
    Object.assign(dishCategoryForm, { name: '', description: '' });
  } catch (cause) {
    if (!handleAuthError(cause))
      error.value =
        axios.isAxiosError(cause) && cause.response?.status === 409
          ? '菜品分类名称已存在。'
          : '菜品分类保存失败，请检查名称。';
  } finally {
    saving.value = false;
  }
}

async function removeDishCategory(category: AdminDishCategory) {
  if (category.dishCount > 0 || !window.confirm(`确认删除分类“${category.name}”？`)) return;
  try {
    await deleteDishCategory(category.id);
    dishCategories.value = await fetchAdminDishCategories();
    if (!dishCategories.value.some((item) => item.name === dishForm.category)) {
      dishForm.category = dishCategories.value[0]?.name ?? '';
    }
  } catch (cause) {
    if (!handleAuthError(cause)) error.value = '分类仍被菜品使用，无法删除。';
  }
}

function postStatusText(post: AdminPostSummary) {
  if (post.status === 'DRAFT') return '草稿';
  if (post.status === 'ARCHIVED') return '已归档';
  return post.featured ? '精选' : '已发布';
}

function toggleAllPosts(checked: boolean) {
  selectedPostIds.value = checked ? posts.value.map((post) => post.id) : [];
}

function formatWorkflowTime(value: string | null | undefined) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '';
}

async function applyPostBatch() {
  if (!selectedPostIds.value.length) return;
  const tags = postBatchTags.value
    .split(/[,，]/)
    .map((tag) => tag.trim())
    .filter(Boolean);
  if (postBatchAction.value === 'ADD_TAGS' && !tags.length) {
    error.value = '批量加标签时请至少填写一个标签。';
    return;
  }
  workflowSaving.value = true;
  error.value = '';
  try {
    await batchUpdatePosts(selectedPostIds.value, postBatchAction.value, tags);
    selectedPostIds.value = [];
    postBatchTags.value = '';
    await loadPosts();
    if (publicationAuditOpen.value) publicationAudit.value = await fetchPostPublicationAudit();
  } catch (cause) {
    if (!handleAuthError(cause)) error.value = '批量操作失败，请稍后重试。';
  } finally {
    workflowSaving.value = false;
  }
}

async function promptPostSchedule(post: AdminPostSummary) {
  const defaultValue = new Date(Date.now() + 60 * 60 * 1000).toISOString().slice(0, 16);
  const input = window.prompt('请输入定时发布时间（本地时间，例如 2026-08-10T09:30）', defaultValue);
  if (!input) return;
  const publishAt = new Date(input);
  if (Number.isNaN(publishAt.getTime()) || publishAt <= new Date()) {
    error.value = '定时发布时间必须是有效的未来时间。';
    return;
  }
  workflowSaving.value = true;
  error.value = '';
  try {
    await schedulePost(post.id, publishAt.toISOString());
    await loadPosts();
  } catch (cause) {
    if (!handleAuthError(cause)) error.value = '设置定时发布失败，请稍后重试。';
  } finally {
    workflowSaving.value = false;
  }
}

async function cancelPostPublicationSchedule(post: AdminPostSummary) {
  workflowSaving.value = true;
  error.value = '';
  try {
    await cancelPostSchedule(post.id);
    await loadPosts();
  } catch (cause) {
    if (!handleAuthError(cause)) error.value = '取消定时发布失败，请稍后重试。';
  } finally {
    workflowSaving.value = false;
  }
}

async function togglePublicationAudit() {
  publicationAuditOpen.value = !publicationAuditOpen.value;
  if (!publicationAuditOpen.value) return;
  try {
    publicationAudit.value = await fetchPostPublicationAudit();
  } catch (cause) {
    if (!handleAuthError(cause)) error.value = '读取发布审计记录失败。';
  }
}

watch([postStatusFilter], () => {
  postPage.value = 0;
  void load();
});

onMounted(load);
</script>

<template>
  <section class="admin-console">
    <AdminSidebar :post-total="postTotal" :dish-total="dishTotal" :note-total="noteTotal" />

    <main class="admin-main">
      <header class="admin-topbar">
        <div>
          <span class="admin-breadcrumb">{{ breadcrumbTitle }}</span>
          <h1>{{ greeting }}，{{ username }}</h1>
        </div>
        <div><RouterLink to="/">查看博客 ↗</RouterLink><button @click="logout">退出登录</button></div>
      </header>

      <template v-if="isOverview">
        <section class="admin-writing-hero">
          <div>
            <span class="writing-kicker">WRITING STUDIO · TYPORA MODE</span>
            <h2>开始写一篇<br /><em>学习笔记。</em></h2>
            <p>所见即所得 Markdown、图片粘贴、KaTeX 公式、任务清单与自动保存，都在同一个安静的写作空间。</p>
            <RouterLink class="admin-write-button" to="/admin/notes"
              ><span>打开 Typora 写作台</span><b>→</b></RouterLink
            >
          </div>
          <div class="writing-preview" aria-hidden="true">
            <header><i /><i /><i /><span>learning-note.md</span></header>
            <div>
              <small># 今天学到的东西</small><strong>让知识留下结构，<br />而不只是痕迹。</strong>
              <p>输入 <b>/</b> 快速插入 · 自动保存中</p>
            </div>
            <footer>
              <span>Markdown</span><span>{{ noteTotal }} NOTES</span>
            </footer>
          </div>
        </section>

        <section class="admin-stat-grid">
          <article>
            <span>文章</span><strong>{{ postTotal }}</strong
            ><small>POSTS</small>
          </article>
          <article>
            <span>菜品</span><strong>{{ dishTotal }}</strong
            ><small>DISHES</small>
          </article>
          <article>
            <span>学习笔记</span><strong>{{ noteTotal }}</strong
            ><small>NOTES</small>
          </article>
        </section>

        <!-- 4D：趋势/热文/容量/AI 用量 -->
        <DashboardTrends v-if="adminStats" :stats="adminStats" />
      </template>

      <section v-if="!isOverview" class="admin-content-section">
        <header>
          <div>
            <span>CONTENT MANAGEMENT</span>
            <h2>{{ contentTitle }}</h2>
          </div>
          <div class="admin-tabs">
            <button :class="{ active: tab === 'posts' }" @click="setTab('posts')">文章</button
            ><button :class="{ active: tab === 'dishes' }" @click="setTab('dishes')">菜品</button>
          </div>
          <div class="content-head-actions">
            <button
              class="button secondary"
              type="button"
              @click="tab === 'posts' ? newCategory() : newDishCategory()"
            >
              分类管理</button
            ><button
              v-if="tab === 'dishes'"
              class="button secondary"
              type="button"
              @click="openImportFileInput"
            >
              导入菜谱</button
            ><button v-if="tab === 'dishes'" class="button secondary" type="button" @click="openExtraction">
              AI 提取菜谱</button
            ><button class="button primary" type="button" @click="newItem">＋ 新建{{ contentNoun }}</button>
          </div>
        </header>
        <div v-if="tab === 'posts'" class="admin-tabs" style="margin-bottom: 16px">
          <button :class="{ active: postStatusFilter === '' }" @click="postStatusFilter = ''">全部</button>
          <button
            :class="{ active: postStatusFilter === 'PUBLISHED' }"
            @click="postStatusFilter = 'PUBLISHED'"
          >
            已发布
          </button>
          <button :class="{ active: postStatusFilter === 'DRAFT' }" @click="postStatusFilter = 'DRAFT'">
            草稿
          </button>
          <button :class="{ active: postStatusFilter === 'ARCHIVED' }" @click="postStatusFilter = 'ARCHIVED'">
            已归档
          </button>
        </div>
        <section v-if="tab === 'posts'" class="post-workflow-toolbar" aria-label="文章批量操作">
          <label>
            <input
              type="checkbox"
              :checked="posts.length > 0 && selectedPostIds.length === posts.length"
              @change="toggleAllPosts(($event.target as HTMLInputElement).checked)"
            />
            本页全选
          </label>
          <select v-model="postBatchAction" aria-label="批量操作类型">
            <option value="PUBLISH">发布</option>
            <option value="DRAFT">转为草稿</option>
            <option value="ARCHIVE">归档</option>
            <option value="ADD_TAGS">添加标签</option>
          </select>
          <input
            v-if="postBatchAction === 'ADD_TAGS'"
            v-model="postBatchTags"
            type="text"
            placeholder="标签，逗号分隔"
            aria-label="批量添加的标签"
          />
          <button
            class="button secondary"
            type="button"
            :disabled="workflowSaving || !selectedPostIds.length"
            @click="applyPostBatch"
          >
            执行（{{ selectedPostIds.length }}）
          </button>
          <button class="button secondary" type="button" @click="togglePublicationAudit">
            {{ publicationAuditOpen ? '收起审计' : '发布审计' }}
          </button>
        </section>
        <section v-if="tab === 'posts' && publicationAuditOpen" class="post-publication-audit">
          <p v-if="!publicationAudit.length">暂无发布操作记录。</p>
          <ol v-else>
            <li v-for="entry in publicationAudit" :key="entry.id">
              <strong>{{ entry.action }}</strong>
              <span>文章 #{{ entry.postId ?? '已删除' }} · {{ entry.actor }}</span>
              <time :datetime="entry.createdAt">{{ formatWorkflowTime(entry.createdAt) }}</time>
              <small v-if="entry.detail">{{ entry.detail }}</small>
            </li>
          </ol>
        </section>
        <p v-if="error" class="admin-error admin-page-error" role="alert">{{ error }}</p>
        <div v-if="loading" class="admin-empty">正在读取管理数据…</div>
        <div v-else-if="tab === 'posts'" class="admin-table">
          <div class="admin-table-head">
            <span>序号</span><span>内容</span><span>状态</span><span>操作</span>
          </div>
          <article v-for="(post, index) in posts" :key="post.id">
            <span class="admin-index post-select-index">
              <input
                v-model="selectedPostIds"
                type="checkbox"
                :value="post.id"
                :aria-label="`选择${post.title}`"
              />
              {{ String(postPage * postPageSize + index + 1).padStart(2, '0') }}
            </span>
            <div>
              <small>{{ post.category }} · {{ post.date }}</small
              ><strong>{{ post.title }}</strong>
              <p>{{ post.excerpt }}</p>
              <small v-if="post.scheduledPublishAt" class="post-scheduled-time">
                定时发布：{{ formatWorkflowTime(post.scheduledPublishAt) }}
              </small>
            </div>
            <span class="admin-status" :class="{ featured: post.featured && post.status !== 'DRAFT' }">{{
              postStatusText(post)
            }}</span>
            <div class="admin-row-actions">
              <button @click="editPost(post)">编辑</button
              ><button
                v-if="!post.scheduledPublishAt"
                :disabled="workflowSaving"
                @click="promptPostSchedule(post)"
              >
                定时</button
              ><button v-else :disabled="workflowSaving" @click="cancelPostPublicationSchedule(post)">
                取消定时</button
              ><button class="danger" @click="remove('post', post.id, post.title)">删除</button>
            </div>
          </article>
          <PaginationNav
            :page="postPage"
            :total-pages="postTotalPages"
            aria-label="后台文章分页"
            @change="changePostPage"
          />
        </div>
        <div v-else class="admin-table admin-dish-table">
          <div class="admin-table-head">
            <span>序号</span><span>菜品</span><span>状态</span><span>操作</span>
          </div>
          <article v-for="(dish, index) in dishes" :key="dish.id">
            <span class="admin-index">{{
              String(dishPage * contentPageSize + index + 1).padStart(2, '0')
            }}</span>
            <div class="admin-dish-cell">
              <img :src="dish.imageUrl" :alt="dish.imageAlt" loading="lazy" decoding="async" />
              <div>
                <small
                  >{{ dish.category }} · {{ dish.prepMinutes }} 分钟 · ★ {{ dish.rating.toFixed(1) }}</small
                ><strong>{{ dish.name }}</strong>
                <p>{{ dish.summary }}</p>
              </div>
            </div>
            <span class="admin-status" :class="{ featured: dish.featured && dish.published }">{{
              dish.published ? (dish.featured ? '精选' : '已发布') : '草稿'
            }}</span>
            <div class="admin-row-actions">
              <button @click="editDish(dish)">编辑</button
              ><button @click="handleExportDish(dish)">导出</button
              ><button class="danger" @click="remove('dish', dish.id, dish.name)">删除</button>
            </div>
          </article>
          <PaginationNav
            :page="dishPage"
            :total-pages="dishTotalPages"
            aria-label="后台菜品分页"
            @change="changeDishPage"
          />
        </div>
      </section>
    </main>

    <AdminEditorModal
      v-if="editorOpen"
      :editing-id="editingId"
      :editor-kind="editorKind"
      :editor-noun="editorNoun"
      :post-form="postForm"
      :dish-form="dishForm"
      :categories="categories"
      :dish-categories="dishCategories"
      :post-markdown-mode="postMarkdownMode"
      :converting="converting"
      :error="error"
      :saving="saving"
      :current-post-context="currentPostContext"
      :apply-ai-action="applyAiAction"
      :convert-legacy-post="convertLegacyPost"
      :reject-post-image-upload="rejectPostImageUpload"
      :dish-image-preview-url="dishImagePreviewUrl"
      :dish-image-uploading="dishImageUploading"
      :dish-image-error="dishImageError"
      :handle-dish-image-change="handleDishImageChange"
      @close="closeEditor"
      @save="save"
      @open-revision="revisionDrawerOpen = true"
      @new-category="newCategory"
      @new-dish-category="newDishCategory"
      @error="error = $event"
    />

    <div v-if="categoryManagerOpen" class="admin-editor-backdrop" @click.self="categoryManagerOpen = false">
      <section
        class="admin-editor category-manager"
        role="dialog"
        aria-modal="true"
        aria-label="文章类别管理"
      >
        <header>
          <div>
            <small>POST CATEGORIES</small>
            <h2>类别管理</h2>
          </div>
          <button type="button" aria-label="关闭类别管理" @click="categoryManagerOpen = false">×</button>
        </header>
        <form class="editor-card category-form" @submit.prevent="saveCategory">
          <label
            ><span>类别名称</span
            ><input
              v-model="categoryForm.name"
              type="text"
              required
              maxlength="80"
              placeholder="如：工程实践"
          /></label>
          <label
            ><span>类别说明（可选）</span
            ><input
              v-model="categoryForm.description"
              type="text"
              maxlength="500"
              placeholder="简单说明该类别收录的内容"
          /></label>
          <div class="category-form-actions">
            <button
              v-if="categoryEditingId"
              class="button secondary"
              type="button"
              @click="
                categoryEditingId = null;
                categoryForm.name = '';
                categoryForm.description = '';
              "
            >
              取消编辑</button
            ><button class="button primary" type="submit" :disabled="saving">
              {{ categoryEditingId ? '保存修改' : '新建类别' }}
            </button>
          </div>
        </form>
        <div class="category-list">
          <article v-for="category in categories" :key="category.id">
            <div>
              <strong>{{ category.name }}</strong
              ><small
                >{{ category.slug }} · {{ category.publishedPostCount }}/{{
                  category.postCount
                }}
                篇已发布</small
              >
              <p v-if="category.description">{{ category.description }}</p>
            </div>
            <div class="admin-row-actions">
              <button type="button" @click="editCategory(category)">编辑</button
              ><button
                class="danger"
                type="button"
                :disabled="category.postCount > 0"
                :title="category.postCount > 0 ? '请先调整使用该类别的文章' : ''"
                @click="removeCategory(category)"
              >
                删除
              </button>
            </div>
          </article>
          <p v-if="!categories.length" class="admin-empty">还没有类别，请先新建一个类别。</p>
        </div>
        <p v-if="error" class="admin-error" role="alert">{{ error }}</p>
      </section>
    </div>

    <div
      v-if="dishCategoryManagerOpen"
      class="admin-editor-backdrop"
      @click.self="dishCategoryManagerOpen = false"
    >
      <section
        class="admin-editor category-manager"
        role="dialog"
        aria-modal="true"
        aria-label="菜品分类管理"
      >
        <header>
          <div>
            <small>DISH CATEGORIES</small>
            <h2>菜品分类管理</h2>
          </div>
          <button type="button" aria-label="关闭菜品分类管理" @click="dishCategoryManagerOpen = false">
            ×
          </button>
        </header>
        <form class="editor-card category-form" @submit.prevent="saveDishCategory">
          <label
            ><span>分类名称</span
            ><input
              v-model="dishCategoryForm.name"
              type="text"
              required
              maxlength="60"
              placeholder="如：十分钟菜"
          /></label>
          <label
            ><span>分类说明（可选）</span
            ><input v-model="dishCategoryForm.description" type="text" maxlength="500"
          /></label>
          <div class="category-form-actions">
            <button
              v-if="dishCategoryEditingId"
              class="button secondary"
              type="button"
              @click="
                dishCategoryEditingId = null;
                dishCategoryForm.name = '';
                dishCategoryForm.description = '';
              "
            >
              取消编辑</button
            ><button class="button primary" type="submit" :disabled="saving">
              {{ dishCategoryEditingId ? '保存修改' : '新建分类' }}
            </button>
          </div>
        </form>
        <div class="category-list">
          <article v-for="category in dishCategories" :key="category.id">
            <div>
              <strong>{{ category.name }}</strong
              ><small
                >{{ category.slug }} · {{ category.publishedDishCount }}/{{
                  category.dishCount
                }}
                道已发布</small
              >
              <p v-if="category.description">{{ category.description }}</p>
            </div>
            <div class="admin-row-actions">
              <button type="button" @click="editDishCategory(category)">编辑</button
              ><button
                class="danger"
                type="button"
                :disabled="category.dishCount > 0"
                :title="category.dishCount > 0 ? '请先调整使用该分类的菜品' : ''"
                @click="removeDishCategory(category)"
              >
                删除
              </button>
            </div>
          </article>
          <p v-if="!dishCategories.length" class="admin-empty">还没有菜品分类，请先新建。</p>
        </div>
        <p v-if="error" class="admin-error" role="alert">{{ error }}</p>
      </section>
    </div>

    <!-- 7：AI 提取菜谱对话框 -->
    <RecipeExtractionModal v-if="extractionOpen" @done="onExtractionDone" />

    <!-- 6D：菜谱导入预览对话框 -->
    <div v-if="importOpen" class="admin-editor-backdrop" @click.self="cancelImport">
      <section class="admin-editor import-modal" role="dialog" aria-modal="true" aria-label="导入菜谱">
        <header>
          <div>
            <small>IMPORT RECIPE</small>
            <h2>导入菜谱</h2>
          </div>
          <button type="button" aria-label="关闭导入" @click="cancelImport">×</button>
        </header>
        <div v-if="importLoading && !importPreview" class="admin-empty">正在解析菜谱包…</div>
        <template v-else-if="!importPreview">
          <p class="admin-empty">请选择一个 .yrecipe 文件</p>
          <input
            ref="importFileInput"
            type="file"
            accept=".yrecipe,application/vnd.yubai.recipe+zip"
            style="display: none"
            @change="handleImportFile"
          />
          <footer>
            <button class="button primary" type="button" @click="importFileInput?.click()">选择文件</button>
          </footer>
          <p v-if="importError" class="admin-error" role="alert">{{ importError }}</p>
        </template>
        <template v-else>
          <div class="import-preview-layout">
            <div class="import-cover">
              <img
                :src="importPreview.coverPreviewUrl"
                :alt="importPreview.recipe.cover.alt || importPreview.recipe.recipe.name"
              />
            </div>
            <div class="import-details">
              <h3>{{ importPreview.recipe.recipe.name }}</h3>
              <p class="import-summary">{{ importPreview.recipe.recipe.summary }}</p>
              <div class="import-meta">
                <span>{{ importPreview.recipe.recipe.prepMinutes }} 分钟</span>
                <span>{{ importPreview.recipe.recipe.difficulty || '未指定难度' }}</span>
                <span>{{ importPreview.recipe.recipe.baseServings }} 人份</span>
              </div>
              <div v-if="importPreview.recipe.source" class="import-source">
                <small
                  >来源：{{
                    importPreview.recipe.source.title || importPreview.recipe.source.creator || '未知'
                  }}</small
                >
              </div>
              <div v-if="importPreview.warnings.length" class="import-warnings">
                <p v-for="(w, i) in importPreview.warnings" :key="i" class="import-warning">{{ w }}</p>
              </div>
            </div>
          </div>
          <div class="editor-card">
            <div class="card-title"><span class="badge-dot" />导入设置</div>
            <label
              ><span>菜品分类</span>
              <select v-model="importCommitCategory" required>
                <option v-for="cat in dishCategories" :key="cat.id" :value="cat.name">
                  {{ cat.name }}
                </option>
              </select>
            </label>
          </div>
          <div class="editor-card">
            <div class="card-title"><span class="badge-dot" />食材</div>
            <ul class="import-list">
              <li v-for="item in importPreview.recipe.recipe.ingredients" :key="item">{{ item }}</li>
            </ul>
          </div>
          <div class="editor-card">
            <div class="card-title"><span class="badge-dot" />制作步骤</div>
            <ol class="import-list">
              <li v-for="step in importPreview.recipe.recipe.steps" :key="step">{{ step }}</li>
            </ol>
          </div>
          <p v-if="importError" class="admin-error" role="alert">{{ importError }}</p>
          <footer>
            <button class="button secondary" type="button" :disabled="importLoading" @click="cancelImport">
              取消
            </button>
            <button
              class="button primary"
              type="button"
              :disabled="importLoading || !importCommitCategory"
              @click="commitImport"
            >
              {{ importLoading ? '正在创建…' : '创建菜品草稿' }}
            </button>
          </footer>
        </template>
      </section>
    </div>

    <!-- 4C：版本历史抽屉（仅编辑既有文章时可用） -->
    <PostRevisionDrawer
      v-if="revisionDrawerOpen && editingId && editorKind === 'post'"
      :post-id="editingId"
      :current-text="currentPostContext()"
      @close="revisionDrawerOpen = false"
      @restored="applyRestoredPost"
    />
  </section>
</template>

<style scoped src="./AdminDashboard.css"></style>
