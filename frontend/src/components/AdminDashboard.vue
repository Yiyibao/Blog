<script setup lang="ts">
import axios from 'axios'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  logout as apiLogout, convertPostsMarkdown, createDish, createDishCategory, createPost, createPostCategory, deleteDish,
  deleteDishCategory, deletePost, deletePostCategory, fetchAdminCategories, fetchAdminDishCategories, fetchAdminDishes,
  fetchAdminPost, fetchAdminPosts, fetchAdminStats, fetchNotes, getAdminSessionName, hasValidAdminSession, updateDish,
  updateDishCategory, updatePost, updatePostCategory, type AdminDish, type AdminDishCategory, type AdminNoteSummary,
  type AdminPostCategory, type AdminPostSummary, type DishPayload, type PostPayload,
  previewDishImport, commitDishImport, cancelDishImport, exportDish, type YrecipePreview,
} from '../api/admin'
import type { PostStatus } from '../data'

import AdminSidebar from './AdminSidebar.vue'
import TyporaEditor from './TyporaEditor.vue'
import AiActionChips, { type AiActionKind } from './AiActionChips.vue'
import PostRevisionDrawer from './PostRevisionDrawer.vue'
import DashboardTrends from './DashboardTrends.vue'
import PaginationNav from './PaginationNav.vue'
import type { AdminPost, AdminStats } from '../api/admin'
import { useContentStore } from '../stores/contentStore'

const router = useRouter()
const route = useRoute()
const tab = ref<'posts' | 'dishes'>('posts')

const isOverview = computed(() => {
  const section = Array.isArray(route.query.section) ? route.query.section[0] : route.query.section
  return !section || section === 'overview'
})

const breadcrumbTitle = computed(() => {
  const section = Array.isArray(route.query.section) ? route.query.section[0] : route.query.section
  if (section === 'posts') return '后台管理 / 文章管理'
  if (section === 'dishes') return '后台管理 / 菜品管理'
  return '后台管理 / 总览'
})

function syncTabFromQuery() {
  const section = Array.isArray(route.query.section) ? route.query.section[0] : route.query.section
  if (section === 'dishes') tab.value = 'dishes'
  else tab.value = 'posts'
}

watch(() => route.query.section, syncTabFromQuery, { immediate: true })

function setTab(nextTab: 'posts' | 'dishes') {
  tab.value = nextTab
  void router.push({ path: '/admin', query: { section: nextTab } })
}
const posts = ref<AdminPostSummary[]>([])
const dishes = ref<AdminDish[]>([])
const notes = ref<AdminNoteSummary[]>([])
const categories = ref<AdminPostCategory[]>([])
const dishCategories = ref<AdminDishCategory[]>([])
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const editorOpen = ref(false)
const editingId = ref<number | null>(null)
const editorKind = ref<'post' | 'dish'>('post')
const categoryManagerOpen = ref(false)
const categoryEditingId = ref<number | null>(null)
const categoryForm = reactive({ name: '', description: '' })
const dishCategoryManagerOpen = ref(false)
const dishCategoryEditingId = ref<number | null>(null)
const dishCategoryForm = reactive({ name: '', description: '' })
const postStatusFilter = ref<'' | PostStatus>('')
const postPage = ref(0)
const postPageSize = 6
const postTotalPages = ref(1)
const postTotal = ref(0)
const dishPage = ref(0)
const dishTotalPages = ref(1)
const dishTotal = ref(0)
const noteTotal = ref(0)
const contentPageSize = 6
const username = getAdminSessionName() || 'Admin'
const greeting = (() => {
  const hour = new Date().getHours()
  if (hour < 6) return '夜深了'
  if (hour < 12) return '上午好'
  if (hour < 18) return '下午好'
  return '晚上好'
})()

const postForm = reactive({
  slug: '', title: '', excerpt: '', date: new Date().toISOString().slice(0, 10), readTime: 5,
  category: '工程实践', tags: '', color: '#A6784C', number: '01', featured: false, status: 'DRAFT' as PostStatus,
  // 3A-3：新文章全程 Markdown；content 仅承载存量 HTML（快照随存量篇一起带回）
  content: '',
  markdownContent: '# 新文章\n\n从这里开始写正文。',
  contentFormat: 'MARKDOWN' as 'HTML' | 'MARKDOWN',
})

/** 3A-3：Markdown 模式判定——MARKDOWN 篇或已有转换稿的存量篇都走 TyporaEditor；纯 HTML 未转换篇保留旧文本域 */
const postMarkdownMode = computed(() =>
  postForm.contentFormat === 'MARKDOWN' || postForm.markdownContent.trim().length > 0)

const converting = ref(false)

/** 存量未转换篇的一键转换：跑 3A-2 端点后重取本篇详情（markdown 稿即到位）。 */
async function convertLegacyPost() {
  if (!editingId.value) return
  converting.value = true
  error.value = ''
  try {
    await convertPostsMarkdown()
    const full = await fetchAdminPost(editingId.value)
    postForm.markdownContent = full.markdownContent ?? ''
    postForm.contentFormat = full.contentFormat ?? 'HTML'
  } catch (cause) {
    if (!handleAuthError(cause)) error.value = '转换失败，请稍后重试。'
  } finally {
    converting.value = false
  }
}

/** 文章暂无独立图床——编辑器里请使用既有图片外链或站内路径。 */
async function rejectPostImageUpload(): Promise<string> {
  throw new Error('文章编辑器暂不支持直传图片')
}

/** 4A-5：AI 动作结果一键回填——只填入表单，保存仍是作者显式动作。 */
function currentPostContext(): string {
  return postMarkdownMode.value ? postForm.markdownContent : postForm.content
}

function applyAiAction(action: AiActionKind, text: string) {
  if (action === 'summary') {
    postForm.excerpt = text
  } else if (action === 'title') {
    const first = text.split('\n').map((line) => line.replace(/^\s*(?:[-*]|\d+[.、])\s*/, '').trim()).find(Boolean)
    if (first) postForm.title = first
  } else if (action === 'tags') {
    postForm.tags = text.split(/[,，、\n]/).map((t) => t.trim()).filter(Boolean).slice(0, 6).join(', ')
  } else if (action === 'polish') {
    if (postMarkdownMode.value) postForm.markdownContent = text
    else postForm.content = text
  } else if (action === 'continue') {
    if (postMarkdownMode.value) postForm.markdownContent = `${postForm.markdownContent.trimEnd()}\n\n${text}`
    else postForm.content = `${postForm.content.trimEnd()}\n\n${text}`
  }
}
/** 4D：完整统计（趋势/TOP5/容量/AI 用量）。 */
const adminStats = ref<AdminStats | null>(null)

/** 4C：版本历史抽屉——恢复后回填表单（恢复只回写正文相关字段，meta 保持编辑器现值）。 */
const revisionDrawerOpen = ref(false)

function applyRestoredPost(post: AdminPost) {
  Object.assign(postForm, {
    title: post.title,
    excerpt: post.excerpt,
    content: post.content,
    markdownContent: post.markdownContent ?? '',
    contentFormat: post.contentFormat ?? 'HTML',
  })
  revisionDrawerOpen.value = false
}

const importOpen = ref(false)
const importLoading = ref(false)
const importError = ref('')
const importPreview = ref<YrecipePreview | null>(null)
const importCommitSlug = ref('')
const importCommitCategory = ref('')

const dishForm = reactive({
  slug: '', name: '', summary: '', category: '十分钟菜', imageUrl: '', imageAlt: '', imageCredit: '', imageSourceUrl: '',
  prepMinutes: 20, difficulty: '家常' as AdminDish['difficulty'], rating: 4.5, featured: false, published: true,
  displayOrder: 0, baseServings: 2, ingredients: '', steps: '',
})
const contentTitle = computed(() => ({ posts: '文章管理', dishes: '菜品管理' })[tab.value])
const contentNoun = computed(() => ({ posts: '文章', dishes: '菜品' })[tab.value])
const editorNoun = computed(() => ({ post: '文章', dish: '菜品' })[editorKind.value])

function changePostPage(page: number) {
  postPage.value = page
  void load()
}

function changeDishPage(page: number) {
  dishPage.value = page
  void load()
}

function handleAuthError(cause: unknown) {
  if (axios.isAxiosError(cause) && cause.response?.status === 401) {
    logout()
    return true
  }
  return false
}

async function loadPosts() {
  const page = await fetchAdminPosts(postPage.value, postPageSize, postStatusFilter.value)
  posts.value = page.items
  postTotal.value = page.totalElements
  postTotalPages.value = Math.max(1, page.totalPages)
  if (postPage.value > 0 && postPage.value >= page.totalPages) {
    postPage.value = Math.max(0, page.totalPages - 1)
    return loadPosts()
  }
}

async function load() {
  if (!hasValidAdminSession()) return logout()
  loading.value = true
  error.value = ''
  try {
    const [, remoteDishes, remoteNotes, remoteCategories, remoteDishCategories] = await Promise.all([
      loadPosts(),
      fetchAdminDishes(dishPage.value, contentPageSize),
      fetchNotes(0, 1),
      fetchAdminCategories(),
      fetchAdminDishCategories(),
    ])
    dishes.value = remoteDishes.items
    dishTotal.value = remoteDishes.totalElements
    dishTotalPages.value = Math.max(1, remoteDishes.totalPages)
    notes.value = remoteNotes.items
    categories.value = remoteCategories
    dishCategories.value = remoteDishCategories
    noteTotal.value = remoteNotes.totalElements
    if (dishPage.value > 0 && dishPage.value >= remoteDishes.totalPages) {
      dishPage.value = Math.max(0, remoteDishes.totalPages - 1)
      return load()
    }
  } catch (cause) {
    if (!handleAuthError(cause)) error.value = '暂时无法读取内容，请确认后端服务正在运行。'
  } finally {
    loading.value = false
  }
  fetchAdminStats()
    .then(stats => {
      postTotal.value = stats.posts; dishTotal.value = stats.dishes; noteTotal.value = stats.notes
      // 4D：趋势卡片数据——防御旧响应形状（滚动部署窗口内后端可能还没带扩展字段）
      if (Array.isArray(stats.viewTrend)) adminStats.value = stats
    })
    .catch(() => {})
}

function logout() {
  apiLogout()
  void router.replace('/admin/login')
}

function newItem() {
  // 使在途的 editPost 详情请求作废，避免其迟到响应覆盖新建表单
  editRequestToken += 1
  editingId.value = null
  editorKind.value = tab.value === 'posts' ? 'post' : 'dish'
  if (editorKind.value === 'post') Object.assign(postForm, {
    slug: '', title: '', excerpt: '', date: new Date().toISOString().slice(0, 10), readTime: 5,
    category: categories.value[0]?.name ?? '', tags: '', color: '#A6784C', number: String(postTotal.value + 1).padStart(2, '0'), featured: false, status: 'DRAFT',
    content: '',
    markdownContent: '# 新文章\n\n从这里开始写正文。',
    contentFormat: 'MARKDOWN',
  })
  else Object.assign(dishForm, {
    slug: '', name: '', summary: '', category: dishCategories.value[0]?.name ?? '', imageUrl: '', imageAlt: '', imageCredit: '', imageSourceUrl: '',
    prepMinutes: 20, difficulty: '家常', rating: 4.5, featured: false, published: true,
    displayOrder: dishTotal.value + 1, baseServings: 2, ingredients: '', steps: '',
  })
  editorOpen.value = true
}

// P1-2：管理端列表为摘要 DTO（不含正文），编辑前必须先取详情，
// 否则表单里的旧正文会在保存时覆盖该文章的真实内容。
let editRequestToken = 0

async function editPost(post: AdminPostSummary) {
  error.value = ''
  const token = ++editRequestToken
  try {
    const full = await fetchAdminPost(post.id)
    // 等待期间用户已另开编辑（再点编辑/新建）时丢弃迟到的响应
    if (token !== editRequestToken || editorOpen.value) return
    editingId.value = full.id
    editorKind.value = 'post'
    Object.assign(postForm, {
      slug: full.slug, title: full.title, excerpt: full.excerpt, date: full.date, readTime: full.readTime,
      category: full.category, tags: full.tags.join(', '), color: full.color, number: full.number,
      featured: full.featured ?? false, status: full.status || 'PUBLISHED', content: full.content,
      markdownContent: full.markdownContent ?? '',
      contentFormat: full.contentFormat ?? 'HTML',
    })
    editorOpen.value = true
  } catch (cause) {
    if (!handleAuthError(cause)) error.value = '读取文章正文失败，请稍后重试。'
  }
}

function editDish(dish: AdminDish) {
  editingId.value = dish.id
  editorKind.value = 'dish'
  Object.assign(dishForm, { ...dish, ingredients: dish.ingredients.join('\n'), steps: dish.steps.join('\n') })
  editorOpen.value = true
}

function postPayload(): PostPayload {
  // 3A-3/3A-5：Markdown 模式保存即按篇切换 MARKDOWN（编辑并保存 = 该篇校对签收）；
  // content 原样回传保留 HTML 快照，双列并存随时可回退
  return {
    ...postForm,
    slug: postForm.slug.trim() || null,
    tags: postForm.tags.split(',').map((item) => item.trim()).filter(Boolean),
    contentFormat: postMarkdownMode.value ? 'MARKDOWN' : 'HTML',
    markdownContent: postForm.markdownContent || null,
  }
}

function dishPayload(): DishPayload {
  return {
    slug: dishForm.slug, name: dishForm.name, summary: dishForm.summary, category: dishForm.category,
    imageUrl: dishForm.imageUrl, imageAlt: dishForm.imageAlt, imageCredit: dishForm.imageCredit, imageSourceUrl: dishForm.imageSourceUrl,
    prepMinutes: dishForm.prepMinutes, difficulty: dishForm.difficulty, rating: dishForm.rating,
    featured: dishForm.featured, published: dishForm.published, displayOrder: dishForm.displayOrder,
    baseServings: dishForm.baseServings,
    ingredients: dishForm.ingredients.split('\n').map(item => item.trim()).filter(Boolean),
    steps: dishForm.steps.split('\n').map(item => item.trim()).filter(Boolean),
  }
}

async function save() {
  saving.value = true
  error.value = ''
  try {
    if (editorKind.value === 'post') {
      if (editingId.value) await updatePost(editingId.value, postPayload())
      else await createPost(postPayload())
    } else {
      if (editingId.value) await updateDish(editingId.value, dishPayload())
      else await createDish(dishPayload())
    }
    editorOpen.value = false
    await load()
    const contentStore = useContentStore()
    await contentStore.loadRemoteContent().catch(() => null)
  } catch (cause) {
    if (!handleAuthError(cause)) error.value = axios.isAxiosError(cause) && cause.response?.status === 409
      ? '保存失败：唯一字段与现有记录冲突。' : '保存失败，请检查必填项和字段格式。'
  } finally {
    saving.value = false
  }
}

async function remove(kind: 'post' | 'dish', id: number, title: string) {
  if (!window.confirm(`确认删除“${title}”？此操作无法撤销。`)) return
  try {
    if (kind === 'post') await deletePost(id)
    else await deleteDish(id)
    await load()
    const contentStore = useContentStore()
    await contentStore.loadRemoteContent().catch(() => null)
  } catch (cause) {
    if (!handleAuthError(cause)) error.value = '删除失败，请稍后再试。'
  }
}

const importFileInput = ref<HTMLInputElement | null>(null)

function openImportFileInput() {
  importError.value = ''
  importPreview.value = null
  importCommitSlug.value = ''
  importCommitCategory.value = ''
  importOpen.value = true
  setTimeout(() => importFileInput.value?.click(), 100)
}

async function handleImportFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input?.files?.[0]
  if (!file) { importOpen.value = false; return }
  importLoading.value = true
  importError.value = ''
  try {
    const preview = await previewDishImport(file)
    importPreview.value = preview
    importCommitSlug.value = preview.recipe.recipe.slug || ''
    importCommitCategory.value = preview.categoryMatch || (dishCategories.value[0]?.name ?? '')
  } catch (cause) {
    if (!handleAuthError(cause)) {
      importError.value = axios.isAxiosError(cause) && cause.response?.data?.message
        ? cause.response.data.message : '导入菜谱失败，请检查文件格式。'
    }
  } finally {
    importLoading.value = false
    if (input) { (input as HTMLInputElement).value = '' }
  }
}

async function commitImport() {
  if (!importPreview.value) return
  importLoading.value = true
  importError.value = ''
  try {
    await commitDishImport(importPreview.value.token, {
      category: importCommitCategory.value,
      correctedSlug: importCommitSlug.value || undefined,
    })
    importOpen.value = false
    importPreview.value = null
    await load()
    const contentStore = useContentStore()
    await contentStore.loadRemoteContent().catch(() => null)
  } catch (cause) {
    if (!handleAuthError(cause)) {
      importError.value = axios.isAxiosError(cause) && cause.response?.data?.message
        ? cause.response.data.message : '创建菜品草稿失败。'
    }
  } finally {
    importLoading.value = false
  }
}

async function cancelImport() {
  if (importPreview.value) {
    try { await cancelDishImport(importPreview.value.token) } catch {}
  }
  importOpen.value = false
  importPreview.value = null
  importError.value = ''
}

async function handleExportDish(dish: AdminDish) {
  try {
    await exportDish(dish.id)
  } catch (cause) {
    if (!handleAuthError(cause)) {
      error.value = axios.isAxiosError(cause) && cause.response?.data?.message
        ? cause.response.data.message : '导出失败。'
    }
  }
}

function newCategory() {
  categoryEditingId.value = null
  Object.assign(categoryForm, { name: '', description: '' })
  categoryManagerOpen.value = true
}

function editCategory(category: AdminPostCategory) {
  categoryEditingId.value = category.id
  Object.assign(categoryForm, { name: category.name, description: category.description })
}

async function saveCategory() {
  saving.value = true
  error.value = ''
  try {
    const payload = { name: categoryForm.name.trim(), description: categoryForm.description.trim() }
    const saved = categoryEditingId.value
      ? await updatePostCategory(categoryEditingId.value, payload)
      : await createPostCategory(payload)
    categories.value = await fetchAdminCategories()
    postForm.category = saved.name
    categoryEditingId.value = null
    Object.assign(categoryForm, { name: '', description: '' })
  } catch (cause) {
    if (!handleAuthError(cause)) error.value = axios.isAxiosError(cause) && cause.response?.status === 409
      ? '类别名称已存在。' : '类别保存失败，请检查名称。'
  } finally {
    saving.value = false
  }
}

async function removeCategory(category: AdminPostCategory) {
  if (category.postCount > 0 || !window.confirm(`确认删除类别“${category.name}”？`)) return
  try {
    await deletePostCategory(category.id)
    categories.value = await fetchAdminCategories()
    if (!categories.value.some((item) => item.name === postForm.category)) {
      postForm.category = categories.value[0]?.name ?? ''
    }
  } catch (cause) {
    if (!handleAuthError(cause)) error.value = '类别仍被文章使用，无法删除。'
  }
}

function newDishCategory() {
  dishCategoryEditingId.value = null
  Object.assign(dishCategoryForm, { name: '', description: '' })
  dishCategoryManagerOpen.value = true
}

function editDishCategory(category: AdminDishCategory) {
  dishCategoryEditingId.value = category.id
  Object.assign(dishCategoryForm, { name: category.name, description: category.description })
}

async function saveDishCategory() {
  saving.value = true
  error.value = ''
  try {
    const payload = { name: dishCategoryForm.name.trim(), description: dishCategoryForm.description.trim() }
    const saved = dishCategoryEditingId.value
      ? await updateDishCategory(dishCategoryEditingId.value, payload)
      : await createDishCategory(payload)
    dishCategories.value = await fetchAdminDishCategories()
    dishForm.category = saved.name
    dishCategoryEditingId.value = null
    Object.assign(dishCategoryForm, { name: '', description: '' })
  } catch (cause) {
    if (!handleAuthError(cause)) error.value = axios.isAxiosError(cause) && cause.response?.status === 409
      ? '菜品分类名称已存在。' : '菜品分类保存失败，请检查名称。'
  } finally {
    saving.value = false
  }
}

async function removeDishCategory(category: AdminDishCategory) {
  if (category.dishCount > 0 || !window.confirm(`确认删除分类“${category.name}”？`)) return
  try {
    await deleteDishCategory(category.id)
    dishCategories.value = await fetchAdminDishCategories()
    if (!dishCategories.value.some((item) => item.name === dishForm.category)) {
      dishForm.category = dishCategories.value[0]?.name ?? ''
    }
  } catch (cause) {
    if (!handleAuthError(cause)) error.value = '分类仍被菜品使用，无法删除。'
  }
}

function postStatusText(post: AdminPostSummary) {
  if (post.status === 'DRAFT') return '草稿'
  return post.featured ? '精选' : '已发布'
}

watch([postStatusFilter], () => {
  postPage.value = 0
  void load()
})

onMounted(load)
</script>

<template>
  <section class="admin-console">
    <AdminSidebar :post-total="postTotal" :dish-total="dishTotal" :note-total="noteTotal" />

    <main class="admin-main">
      <header class="admin-topbar"><div><span class="admin-breadcrumb">{{ breadcrumbTitle }}</span><h1>{{ greeting }}，{{ username }}</h1></div><div><RouterLink to="/">查看博客 ↗</RouterLink><button @click="logout">退出登录</button></div></header>

      <template v-if="isOverview">
        <section class="admin-writing-hero">
          <div><span class="writing-kicker">WRITING STUDIO · TYPORA MODE</span><h2>开始写一篇<br><em>学习笔记。</em></h2><p>所见即所得 Markdown、图片粘贴、KaTeX 公式、任务清单与自动保存，都在同一个安静的写作空间。</p><RouterLink class="admin-write-button" to="/admin/notes"><span>打开 Typora 写作台</span><b>→</b></RouterLink></div>
          <div class="writing-preview" aria-hidden="true"><header><i /><i /><i /><span>learning-note.md</span></header><div><small># 今天学到的东西</small><strong>让知识留下结构，<br>而不只是痕迹。</strong><p>输入 <b>/</b> 快速插入 · 自动保存中</p></div><footer><span>Markdown</span><span>{{ noteTotal }} NOTES</span></footer></div>
        </section>

        <section class="admin-stat-grid"><article><span>文章</span><strong>{{ postTotal }}</strong><small>POSTS</small></article><article><span>菜品</span><strong>{{ dishTotal }}</strong><small>DISHES</small></article><article><span>学习笔记</span><strong>{{ noteTotal }}</strong><small>NOTES</small></article></section>

        <!-- 4D：趋势/热文/容量/AI 用量 -->
        <DashboardTrends v-if="adminStats" :stats="adminStats" />
      </template>

      <section v-if="!isOverview" class="admin-content-section">
        <header><div><span>CONTENT MANAGEMENT</span><h2>{{ contentTitle }}</h2></div><div class="admin-tabs"><button :class="{ active: tab === 'posts' }" @click="setTab('posts')">文章</button><button :class="{ active: tab === 'dishes' }" @click="setTab('dishes')">菜品</button></div><div class="content-head-actions"><button class="button secondary" type="button" @click="tab === 'posts' ? newCategory() : newDishCategory()">分类管理</button><button v-if="tab === 'dishes'" class="button secondary" type="button" @click="openImportFileInput">导入菜谱</button><button class="button primary" type="button" @click="newItem">＋ 新建{{ contentNoun }}</button></div></header>
        <div v-if="tab === 'posts'" class="admin-tabs" style="margin-bottom: 16px">
          <button :class="{ active: postStatusFilter === '' }" @click="postStatusFilter = ''">全部</button>
          <button :class="{ active: postStatusFilter === 'PUBLISHED' }" @click="postStatusFilter = 'PUBLISHED'">已发布</button>
          <button :class="{ active: postStatusFilter === 'DRAFT' }" @click="postStatusFilter = 'DRAFT'">草稿</button>
        </div>
        <p v-if="error" class="admin-error admin-page-error" role="alert">{{ error }}</p>
        <div v-if="loading" class="admin-empty">正在读取管理数据…</div>
        <div v-else-if="tab === 'posts'" class="admin-table">
          <div class="admin-table-head"><span>序号</span><span>内容</span><span>状态</span><span>操作</span></div>
          <article v-for="(post, index) in posts" :key="post.id"><span class="admin-index">{{ String(postPage * postPageSize + index + 1).padStart(2, '0') }}</span><div><small>{{ post.category }} · {{ post.date }}</small><strong>{{ post.title }}</strong><p>{{ post.excerpt }}</p></div><span class="admin-status" :class="{ featured: post.featured && post.status !== 'DRAFT' }">{{ postStatusText(post) }}</span><div class="admin-row-actions"><button @click="editPost(post)">编辑</button><button class="danger" @click="remove('post', post.id, post.title)">删除</button></div></article>
          <PaginationNav :page="postPage" :total-pages="postTotalPages" aria-label="后台文章分页" @change="changePostPage" />
        </div>
        <div v-else class="admin-table admin-dish-table">
          <div class="admin-table-head"><span>序号</span><span>菜品</span><span>状态</span><span>操作</span></div>
          <article v-for="(dish, index) in dishes" :key="dish.id"><span class="admin-index">{{ String(dishPage * contentPageSize + index + 1).padStart(2, '0') }}</span><div class="admin-dish-cell"><img :src="dish.imageUrl" :alt="dish.imageAlt"><div><small>{{ dish.category }} · {{ dish.prepMinutes }} 分钟 · ★ {{ dish.rating.toFixed(1) }}</small><strong>{{ dish.name }}</strong><p>{{ dish.summary }}</p></div></div><span class="admin-status" :class="{ featured: dish.featured && dish.published }">{{ dish.published ? (dish.featured ? '精选' : '已发布') : '草稿' }}</span><div class="admin-row-actions"><button @click="editDish(dish)">编辑</button><button @click="handleExportDish(dish)">导出</button><button class="danger" @click="remove('dish', dish.id, dish.name)">删除</button></div></article>
          <PaginationNav :page="dishPage" :total-pages="dishTotalPages" aria-label="后台菜品分页" @change="changeDishPage" />
        </div>
      </section>
    </main>

    <div v-if="editorOpen" class="admin-editor-backdrop" @click.self="editorOpen = false">
      <form class="admin-editor" @submit.prevent="save">
        <header>
          <div>
            <small>{{ editingId ? 'EDIT RECORD' : 'NEW RECORD' }}</small>
            <h2>{{ editingId ? '编辑' : '新建' }}{{ editorNoun }}</h2>
          </div>
          <div class="editor-head-actions">
            <button v-if="editorKind === 'post' && editingId" type="button" class="revision-trigger" @click="revisionDrawerOpen = true">↺ 历史版本</button>
            <button type="button" aria-label="关闭编辑器" @click="editorOpen = false">×</button>
          </div>
        </header>

        <template v-if="editorKind === 'post'">
          <!-- 卡片 1：属性与元数据 -->
          <div class="editor-card">
            <div class="card-title"><span class="badge-dot" />基本属性与分类</div>
            <label class="full-width-label">
              <span>文章标题</span>
              <input v-model="postForm.title" type="text" required maxlength="200" placeholder="请输入清晰、具有概括性的文章标题…">
            </label>
            <div class="admin-form-grid">
              <label><span>Slug（路由别名，可选）</span><input v-model="postForm.slug" type="text" pattern="[a-z0-9]+(?:-[a-z0-9]+)*" placeholder="留空将根据标题自动生成"></label>
              <label><span>文章类别</span><span class="category-select-row"><select v-model="postForm.category" required><option value="" disabled>请先创建并选择类别</option><option v-for="category in categories" :key="category.id" :value="category.name">{{ category.name }}</option></select><button type="button" @click="newCategory">＋ 新建</button></span></label>
              <label><span>发布日期</span><input v-model="postForm.date" type="date" required></label>
              <label><span>预计阅读时间 (分钟)</span><input v-model.number="postForm.readTime" type="number" min="1" max="180" required></label>
              <label><span>文章编号</span><input v-model="postForm.number" type="text" required maxlength="10" placeholder="01"></label>
              <label><span>发布状态</span>
                <select v-model="postForm.status" required>
                  <option value="DRAFT">📝 草稿 (DRAFT)</option>
                  <option value="PUBLISHED">🚀 已发布 (PUBLISHED)</option>
                </select>
              </label>
              <label><span>主题色彩</span>
                <div class="color-picker-wrap">
                  <input v-model="postForm.color" type="color" required>
                  <span class="color-hex-val">{{ postForm.color }}</span>
                </div>
              </label>
              <label class="admin-check">
                <input v-model="postForm.featured" type="checkbox">
                <span>设为首页精选文章</span>
              </label>
            </div>
          </div>

          <!-- 卡片 2：标签与摘要 -->
          <div class="editor-card">
            <div class="card-title"><span class="badge-dot" />标签与摘要</div>
            <label>
              <span>文章标签（英文逗号分隔）</span>
              <input v-model="postForm.tags" type="text" required placeholder="Vue, TypeScript, WebGL">
            </label>
            <label>
              <span>内容摘要 (Excerpt)</span>
              <textarea v-model="postForm.excerpt" rows="3" required placeholder="简短总结本篇文章的核心洞见与主要内容…" />
            </label>
          </div>

          <!-- 卡片 3：AI 智能助手与正文编辑 -->
          <div class="editor-card">
            <div class="card-title"><span class="badge-dot" />AI 智能创作与正文内容</div>
            <!-- 4A-5：场景化 AI 动作（结果只填入不保存） -->
            <AiActionChips :get-context="currentPostContext" @apply="applyAiAction" />
            <!-- 3A-3：Markdown 模式复用 TyporaEditor；纯 HTML 存量篇先转换 -->
            <div v-if="postMarkdownMode" class="post-markdown-field">
              <TyporaEditor
                v-model="postForm.markdownContent"
                :upload-image="rejectPostImageUpload"
                @upload-error="error = '文章编辑器暂不支持直传图片，请使用站内路径或外链。'"
              />
            </div>
            <template v-else>
              <div class="legacy-html-notice" role="status">
                <span>该篇为存量 HTML，尚未生成 Markdown 稿。转换后即可用 Markdown 编辑器。</span>
                <button type="button" :disabled="converting" @click="convertLegacyPost">{{ converting ? '转换中…' : '一键转换' }}</button>
              </div>
              <label><span>HTML 正文</span><textarea v-model="postForm.content" class="admin-code-editor" rows="14" required spellcheck="false" /></label>
            </template>
          </div>
        </template>
        <template v-else>
          <div class="editor-card">
            <div class="card-title"><span class="badge-dot" />菜品基本属性</div>
            <div class="admin-form-grid">
              <label><span>菜品名称</span><input v-model="dishForm.name" required maxlength="120"></label>
              <label><span>Slug</span><input v-model="dishForm.slug" required pattern="[a-z0-9]+(?:-[a-z0-9]+)*"></label>
              <label><span>菜品分类</span><span class="category-select-row"><select v-model="dishForm.category" required><option value="" disabled>请先创建并选择分类</option><option v-for="category in dishCategories" :key="category.id" :value="category.name">{{ category.name }}</option></select><button type="button" @click="newDishCategory">＋ 新建</button></span></label>
              <label><span>准备时间（分钟）</span><input v-model.number="dishForm.prepMinutes" type="number" min="1" max="1440" required></label>
              <label><span>难度</span>
                <select v-model="dishForm.difficulty" required>
                  <option value="简单">简单</option>
                  <option value="家常">家常</option>
                  <option value="进阶">进阶</option>
                </select>
              </label>
              <label><span>评分</span><input v-model.number="dishForm.rating" type="number" min="0" max="5" step="0.1" required></label>
              <label><span>展示顺序</span><input v-model.number="dishForm.displayOrder" type="number" min="0" required></label>
              <label><span>份量基准（人份）</span><input v-model.number="dishForm.baseServings" type="number" min="1" max="20" required></label>
              <label class="admin-check"><input v-model="dishForm.featured" type="checkbox"><span>设为精选菜品</span></label>
              <label class="admin-check"><input v-model="dishForm.published" type="checkbox"><span>公开发布</span></label>
            </div>
          </div>
          <div class="editor-card">
            <div class="card-title"><span class="badge-dot" />简介与媒体图示</div>
            <label><span>简介</span><textarea v-model="dishForm.summary" rows="3" required maxlength="1000" /></label>
            <label><span>图片地址</span><input v-model="dishForm.imageUrl" required placeholder="/food/example.jpg 或 https://..."></label>
            <label><span>图片替代文本</span><input v-model="dishForm.imageAlt" required maxlength="240"></label>
            <div class="admin-form-grid">
              <label><span>图片署名</span><input v-model="dishForm.imageCredit" required maxlength="240" placeholder="作者 · 许可"></label>
              <label><span>图片来源页面</span><input v-model="dishForm.imageSourceUrl" type="url" required></label>
            </div>
          </div>
          <div class="editor-card">
            <div class="card-title"><span class="badge-dot" />食材与烹饪步骤</div>
            <label><span>食材清单（每行一项）</span><textarea v-model="dishForm.ingredients" rows="7" required placeholder="嫩豆腐 400 克&#10;牛肉末 80 克" /></label>
            <label><span>制作步骤（每行一步）</span><textarea v-model="dishForm.steps" rows="8" required placeholder="豆腐切块并焯水。&#10;炒香肉末与豆瓣酱。" /></label>
          </div>
        </template>
        <p v-if="error" class="admin-error" role="alert">{{ error }}</p>
        <footer><button class="button secondary" type="button" @click="editorOpen = false">取消</button><button class="button primary" type="submit" :disabled="saving">{{ saving ? '正在保存…' : '保存内容 ↗' }}</button></footer>
      </form>
    </div>

    <div v-if="categoryManagerOpen" class="admin-editor-backdrop" @click.self="categoryManagerOpen = false">
      <section class="admin-editor category-manager" role="dialog" aria-modal="true" aria-label="文章类别管理">
        <header><div><small>POST CATEGORIES</small><h2>类别管理</h2></div><button type="button" aria-label="关闭类别管理" @click="categoryManagerOpen = false">×</button></header>
        <form class="editor-card category-form" @submit.prevent="saveCategory">
          <label><span>类别名称</span><input v-model="categoryForm.name" type="text" required maxlength="80" placeholder="如：工程实践"></label>
          <label><span>类别说明（可选）</span><input v-model="categoryForm.description" type="text" maxlength="500" placeholder="简单说明该类别收录的内容"></label>
          <div class="category-form-actions"><button v-if="categoryEditingId" class="button secondary" type="button" @click="categoryEditingId = null; categoryForm.name = ''; categoryForm.description = ''">取消编辑</button><button class="button primary" type="submit" :disabled="saving">{{ categoryEditingId ? '保存修改' : '新建类别' }}</button></div>
        </form>
        <div class="category-list">
          <article v-for="category in categories" :key="category.id">
            <div><strong>{{ category.name }}</strong><small>{{ category.slug }} · {{ category.publishedPostCount }}/{{ category.postCount }} 篇已发布</small><p v-if="category.description">{{ category.description }}</p></div>
            <div class="admin-row-actions"><button type="button" @click="editCategory(category)">编辑</button><button class="danger" type="button" :disabled="category.postCount > 0" :title="category.postCount > 0 ? '请先调整使用该类别的文章' : ''" @click="removeCategory(category)">删除</button></div>
          </article>
          <p v-if="!categories.length" class="admin-empty">还没有类别，请先新建一个类别。</p>
        </div>
        <p v-if="error" class="admin-error" role="alert">{{ error }}</p>
      </section>
    </div>

    <div v-if="dishCategoryManagerOpen" class="admin-editor-backdrop" @click.self="dishCategoryManagerOpen = false">
      <section class="admin-editor category-manager" role="dialog" aria-modal="true" aria-label="菜品分类管理">
        <header><div><small>DISH CATEGORIES</small><h2>菜品分类管理</h2></div><button type="button" aria-label="关闭菜品分类管理" @click="dishCategoryManagerOpen = false">×</button></header>
        <form class="editor-card category-form" @submit.prevent="saveDishCategory">
          <label><span>分类名称</span><input v-model="dishCategoryForm.name" type="text" required maxlength="60" placeholder="如：十分钟菜"></label>
          <label><span>分类说明（可选）</span><input v-model="dishCategoryForm.description" type="text" maxlength="500"></label>
          <div class="category-form-actions"><button v-if="dishCategoryEditingId" class="button secondary" type="button" @click="dishCategoryEditingId = null; dishCategoryForm.name = ''; dishCategoryForm.description = ''">取消编辑</button><button class="button primary" type="submit" :disabled="saving">{{ dishCategoryEditingId ? '保存修改' : '新建分类' }}</button></div>
        </form>
        <div class="category-list">
          <article v-for="category in dishCategories" :key="category.id"><div><strong>{{ category.name }}</strong><small>{{ category.slug }} · {{ category.publishedDishCount }}/{{ category.dishCount }} 道已发布</small><p v-if="category.description">{{ category.description }}</p></div><div class="admin-row-actions"><button type="button" @click="editDishCategory(category)">编辑</button><button class="danger" type="button" :disabled="category.dishCount > 0" :title="category.dishCount > 0 ? '请先调整使用该分类的菜品' : ''" @click="removeDishCategory(category)">删除</button></div></article>
          <p v-if="!dishCategories.length" class="admin-empty">还没有菜品分类，请先新建。</p>
        </div>
        <p v-if="error" class="admin-error" role="alert">{{ error }}</p>
      </section>
    </div>

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
          <input ref="importFileInput" type="file" accept=".yrecipe,application/vnd.yubai.recipe+zip" style="display:none" @change="handleImportFile">
          <footer><button class="button primary" type="button" @click="importFileInput?.click()">选择文件</button></footer>
          <p v-if="importError" class="admin-error" role="alert">{{ importError }}</p>
        </template>
        <template v-else>
          <div class="import-preview-layout">
            <div class="import-cover">
              <img :src="importPreview.coverPreviewUrl" :alt="importPreview.recipe.cover.alt || importPreview.recipe.recipe.name">
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
                <small>来源：{{ importPreview.recipe.source.title || importPreview.recipe.source.creator || '未知' }}</small>
              </div>
              <div v-if="importPreview.warnings.length" class="import-warnings">
                <p v-for="(w, i) in importPreview.warnings" :key="i" class="import-warning">{{ w }}</p>
              </div>
            </div>
          </div>
          <div class="editor-card">
            <div class="card-title"><span class="badge-dot" />导入设置</div>
            <div class="admin-form-grid">
              <label><span>Slug（路由别名）</span>
                <input v-model="importCommitSlug" type="text" pattern="[a-z0-9]+(?:-[a-z0-9]+)*" maxlength="120" :placeholder="importPreview.recipe.recipe.slug || '根据名称自动生成'">
              </label>
              <label><span>菜品分类</span>
                <select v-model="importCommitCategory" required>
                  <option v-for="cat in dishCategories" :key="cat.id" :value="cat.name">{{ cat.name }}</option>
                </select>
              </label>
            </div>
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
            <button class="button secondary" type="button" :disabled="importLoading" @click="cancelImport">取消</button>
            <button class="button primary" type="button" :disabled="importLoading || !importCommitCategory" @click="commitImport">{{ importLoading ? '正在创建…' : '创建菜品草稿' }}</button>
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

<style scoped>
/* 4C：编辑器头部动作区与历史版本入口 */
.editor-head-actions { display: flex; align-items: center; gap: 8px; }
.revision-trigger {
  padding: 6px 12px;
  border: 1px solid var(--line-strong);
  border-radius: 8px;
  background: var(--surface);
  color: var(--ink);
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}
.revision-trigger:hover {
  border-color: var(--accent);
  color: var(--accent);
  background: color-mix(in srgb, var(--accent) 8%, var(--surface));
  transform: translateY(-1px);
  box-shadow: 0 4px 12px color-mix(in srgb, var(--accent) 15%, transparent);
}
.revision-trigger:active {
  transform: translateY(1px) scale(0.97);
  box-shadow: none;
}

.content-head-actions, .category-select-row, .category-form-actions { display: flex; align-items: center; gap: 10px; }
.category-select-row select { flex: 1; min-width: 0; }
.category-select-row button { flex: none; padding: 10px 12px; border: 1px solid var(--line-strong); border-radius: 9px; background: var(--surface-solid); color: var(--ink); cursor: pointer; }
.category-manager { max-width: 760px; }
.category-form { display: grid; grid-template-columns: 1fr 1.5fr auto; align-items: end; gap: 12px; }
.category-form-actions { padding-bottom: 1px; }
.category-list { display: grid; gap: 10px; }
.category-list article { display: flex; align-items: center; justify-content: space-between; gap: 18px; padding: 14px 16px; border: 1px solid var(--line-strong); border-radius: 12px; background: var(--surface-solid); }
.category-list article > div:first-child { display: grid; gap: 3px; }
.category-list small, .category-list p { color: var(--muted); font-size: 12px; }
.category-list p { margin: 2px 0 0; }
.category-list button:disabled { cursor: not-allowed; opacity: 0.45; }
@media (max-width: 720px) {
  .category-form { grid-template-columns: 1fr; }
  .category-list article { align-items: flex-start; flex-direction: column; }
  .content-head-actions { width: 100%; }
}

/* 3A-3：文章 Markdown 编辑区与存量转换提示条 */
.post-markdown-field {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.post-markdown-field .field-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
}
.legacy-html-notice {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 10px 14px;
  border: 1px solid color-mix(in srgb, #c47b2c 45%, var(--line));
  border-radius: 12px;
  background: color-mix(in srgb, #c47b2c 8%, var(--surface));
  font-size: 13px;
  color: var(--ink);
}
.legacy-html-notice button {
  flex-shrink: 0;
  padding: 6px 14px;
  border: 1px solid var(--line-strong);
  border-radius: 999px;
  background: var(--surface-solid);
  color: var(--ink);
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}
.legacy-html-notice button:hover:not(:disabled) {
  border-color: var(--accent);
  color: var(--accent);
  background: color-mix(in srgb, var(--accent) 10%, var(--surface-solid));
  transform: translateY(-1px);
}
.legacy-html-notice button:active:not(:disabled) {
  transform: translateY(1px) scale(0.97);
}

/* 6D：导入菜谱预览对话框 */
.import-modal { max-width: 800px; }
.import-preview-layout { display: flex; gap: 20px; padding: 20px 0; }
.import-cover { flex-shrink: 0; width: 200px; }
.import-cover img { width: 100%; border-radius: 12px; object-fit: cover; aspect-ratio: 1; }
.import-details { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 8px; }
.import-summary { color: var(--muted); font-size: 14px; line-height: 1.5; }
.import-meta { display: flex; gap: 12px; font-size: 12px; color: var(--muted); }
.import-source { font-size: 12px; color: var(--muted); }
.import-warnings { display: flex; flex-direction: column; gap: 4px; }
.import-warning { font-size: 12px; color: #c47b2c; padding: 4px 8px; border-radius: 6px; background: color-mix(in srgb, #c47b2c 10%, var(--surface)); }
.import-list { display: flex; flex-direction: column; gap: 6px; padding: 0; margin: 0; list-style-position: inside; }
.import-list li { font-size: 14px; line-height: 1.5; color: var(--ink); }
@media (max-width: 640px) {
  .import-preview-layout { flex-direction: column; }
  .import-cover { width: 100%; max-width: 300px; }
}
</style>
