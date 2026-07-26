<script setup lang="ts">
import axios from 'axios'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  clearAdminSession, createDish, createPost, deleteDish, deletePost, fetchAdminDishes, fetchAdminPost, fetchAdminPosts,
  fetchAdminStats, fetchNotes, getAdminSessionName, hasValidAdminSession, updateDish, updatePost, type AdminDish,
  type AdminNoteSummary, type AdminPostSummary, type DishPayload, type PostPayload,
} from '../api/admin'
import type { PostStatus } from '../data'

import AdminSidebar from './AdminSidebar.vue'

const router = useRouter()
const route = useRoute()
const tab = ref<'posts' | 'dishes'>('posts')

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
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const editorOpen = ref(false)
const editingId = ref<number | null>(null)
const editorKind = ref<'post' | 'dish'>('post')
const postStatusFilter = ref<'' | PostStatus>('')
const postPage = ref(0)
const postPageSize = 10
const postTotalPages = ref(1)
const postTotal = ref(0)
const dishPage = ref(0)
const dishTotalPages = ref(1)
const dishTotal = ref(0)
const noteTotal = ref(0)
const contentPageSize = 10
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
  content: '<p class="lead">从这里开始写正文。</p>',
})
const dishForm = reactive({
  slug: '', name: '', summary: '', category: '十分钟菜', imageUrl: '', imageAlt: '', imageCredit: '', imageSourceUrl: '',
  prepMinutes: 20, difficulty: '家常' as AdminDish['difficulty'], rating: 4.5, featured: false, published: true,
  displayOrder: 0, ingredients: '', steps: '',
})
const contentTitle = computed(() => ({ posts: '文章管理', dishes: '菜品管理' })[tab.value])
const contentNoun = computed(() => ({ posts: '文章', dishes: '菜品' })[tab.value])
const editorNoun = computed(() => ({ post: '文章', dish: '菜品' })[editorKind.value])

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
    const [, remoteDishes, remoteNotes] = await Promise.all([
      loadPosts(),
      fetchAdminDishes(dishPage.value, contentPageSize),
      fetchNotes(0, 1),
    ])
    dishes.value = remoteDishes.items
    dishTotal.value = remoteDishes.totalElements
    dishTotalPages.value = Math.max(1, remoteDishes.totalPages)
    notes.value = remoteNotes.items
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
    .then(stats => { postTotal.value = stats.posts; dishTotal.value = stats.dishes; noteTotal.value = stats.notes })
    .catch(() => {})
}

function logout() {
  clearAdminSession()
  void router.replace('/admin/login')
}

function newItem() {
  // 使在途的 editPost 详情请求作废，避免其迟到响应覆盖新建表单
  editRequestToken += 1
  editingId.value = null
  editorKind.value = tab.value === 'posts' ? 'post' : 'dish'
  if (editorKind.value === 'post') Object.assign(postForm, {
    slug: '', title: '', excerpt: '', date: new Date().toISOString().slice(0, 10), readTime: 5,
    category: '工程实践', tags: '', color: '#A6784C', number: String(postTotal.value + 1).padStart(2, '0'), featured: false, status: 'DRAFT',
    content: '<p class="lead">从这里开始写正文。</p>',
  })
  else Object.assign(dishForm, {
    slug: '', name: '', summary: '', category: '十分钟菜', imageUrl: '', imageAlt: '', imageCredit: '', imageSourceUrl: '',
    prepMinutes: 20, difficulty: '家常', rating: 4.5, featured: false, published: true,
    displayOrder: dishTotal.value + 1, ingredients: '', steps: '',
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
  return { ...postForm, tags: postForm.tags.split(',').map((item) => item.trim()).filter(Boolean) }
}

function dishPayload(): DishPayload {
  return {
    slug: dishForm.slug, name: dishForm.name, summary: dishForm.summary, category: dishForm.category,
    imageUrl: dishForm.imageUrl, imageAlt: dishForm.imageAlt, imageCredit: dishForm.imageCredit, imageSourceUrl: dishForm.imageSourceUrl,
    prepMinutes: dishForm.prepMinutes, difficulty: dishForm.difficulty, rating: dishForm.rating,
    featured: dishForm.featured, published: dishForm.published, displayOrder: dishForm.displayOrder,
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
  } catch (cause) {
    if (!handleAuthError(cause)) error.value = '删除失败，请稍后再试。'
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
      <header class="admin-topbar"><div><span class="admin-breadcrumb">后台管理 / 总览</span><h1>{{ greeting }}，{{ username }}</h1></div><div><RouterLink to="/">查看博客 ↗</RouterLink><button @click="logout">退出登录</button></div></header>

      <section class="admin-writing-hero">
        <div><span class="writing-kicker">WRITING STUDIO · TYPORA MODE</span><h2>开始写一篇<br><em>学习笔记。</em></h2><p>所见即所得 Markdown、图片粘贴、KaTeX 公式、任务清单与自动保存，都在同一个安静的写作空间。</p><RouterLink class="admin-write-button" to="/admin/notes"><span>打开 Typora 写作台</span><b>→</b></RouterLink></div>
        <div class="writing-preview" aria-hidden="true"><header><i /><i /><i /><span>learning-note.md</span></header><div><small># 今天学到的东西</small><strong>让知识留下结构，<br>而不只是痕迹。</strong><p>输入 <b>/</b> 快速插入 · 自动保存中</p></div><footer><span>Markdown</span><span>{{ noteTotal }} NOTES</span></footer></div>
      </section>

      <section class="admin-stat-grid"><article><span>文章</span><strong>{{ postTotal }}</strong><small>POSTS</small></article><article><span>菜品</span><strong>{{ dishTotal }}</strong><small>DISHES</small></article><article><span>学习笔记</span><strong>{{ noteTotal }}</strong><small>NOTES</small></article></section>

      <section class="admin-content-section">
        <header><div><span>CONTENT MANAGEMENT</span><h2>{{ contentTitle }}</h2></div><div class="admin-tabs"><button :class="{ active: tab === 'posts' }" @click="setTab('posts')">文章</button><button :class="{ active: tab === 'dishes' }" @click="setTab('dishes')">菜品</button></div><button class="button primary" type="button" @click="newItem">＋ 新建{{ contentNoun }}</button></header>
        <div v-if="tab === 'posts'" class="admin-tabs" style="margin-bottom: 16px">
          <button :class="{ active: postStatusFilter === '' }" @click="postStatusFilter = ''">全部</button>
          <button :class="{ active: postStatusFilter === 'PUBLISHED' }" @click="postStatusFilter = 'PUBLISHED'">已发布</button>
          <button :class="{ active: postStatusFilter === 'DRAFT' }" @click="postStatusFilter = 'DRAFT'">草稿</button>
        </div>
        <p v-if="error" class="admin-error admin-page-error" role="alert">{{ error }}</p>
        <div v-if="loading" class="admin-empty">正在读取管理数据…</div>
        <div v-else-if="tab === 'posts'" class="admin-table">
          <div class="admin-table-head"><span>编号</span><span>内容</span><span>状态</span><span>操作</span></div>
          <article v-for="post in posts" :key="post.id"><span class="admin-index">{{ post.number }}</span><div><small>{{ post.category }} · {{ post.date }}</small><strong>{{ post.title }}</strong><p>{{ post.excerpt }}</p></div><span class="admin-status" :class="{ featured: post.featured && post.status !== 'DRAFT' }">{{ postStatusText(post) }}</span><div class="admin-row-actions"><button @click="editPost(post)">编辑</button><button class="danger" @click="remove('post', post.id, post.title)">删除</button></div></article>
          <nav v-if="postTotalPages > 1" class="pagination" aria-label="后台文章分页">
            <button type="button" :disabled="postPage <= 0" @click="postPage -= 1; load()">上一页</button>
            <span>{{ postPage + 1 }} / {{ postTotalPages }}</span>
            <button type="button" :disabled="postPage >= postTotalPages - 1" @click="postPage += 1; load()">下一页</button>
          </nav>
        </div>
        <div v-else class="admin-table admin-dish-table">
          <div class="admin-table-head"><span>顺序</span><span>菜品</span><span>状态</span><span>操作</span></div>
          <article v-for="dish in dishes" :key="dish.id"><span class="admin-index">{{ String(dish.displayOrder).padStart(2, '0') }}</span><div class="admin-dish-cell"><img :src="dish.imageUrl" :alt="dish.imageAlt"><div><small>{{ dish.category }} · {{ dish.prepMinutes }} 分钟 · ★ {{ dish.rating.toFixed(1) }}</small><strong>{{ dish.name }}</strong><p>{{ dish.summary }}</p></div></div><span class="admin-status" :class="{ featured: dish.featured && dish.published }">{{ dish.published ? (dish.featured ? '精选' : '已发布') : '草稿' }}</span><div class="admin-row-actions"><button @click="editDish(dish)">编辑</button><button class="danger" @click="remove('dish', dish.id, dish.name)">删除</button></div></article>
          <nav v-if="dishTotalPages > 1" class="pagination" aria-label="后台菜品分页"><button type="button" :disabled="dishPage <= 0" @click="dishPage -= 1; load()">上一页</button><span>{{ dishPage + 1 }} / {{ dishTotalPages }}</span><button type="button" :disabled="dishPage >= dishTotalPages - 1" @click="dishPage += 1; load()">下一页</button></nav>
        </div>
      </section>
    </main>

    <div v-if="editorOpen" class="admin-editor-backdrop" @click.self="editorOpen = false">
      <form class="admin-editor" @submit.prevent="save">
        <header><div><small>{{ editingId ? 'EDIT RECORD' : 'NEW RECORD' }}</small><h2>{{ editingId ? '编辑' : '新建' }}{{ editorNoun }}</h2></div><button type="button" aria-label="关闭编辑器" @click="editorOpen = false">×</button></header>
        <template v-if="editorKind === 'post'">
          <div class="admin-form-grid"><label>标题<input v-model="postForm.title" required maxlength="200"></label><label>Slug<input v-model="postForm.slug" required pattern="[a-z0-9]+(?:-[a-z0-9]+)*"></label><label>分类<input v-model="postForm.category" required></label><label>发布日期<input v-model="postForm.date" type="date" required></label><label>阅读时间（分钟）<input v-model.number="postForm.readTime" type="number" min="1" max="180" required></label><label>编号<input v-model="postForm.number" required maxlength="10"></label><label>颜色<input v-model="postForm.color" type="color" required></label><label>状态<select v-model="postForm.status" required><option value="DRAFT">草稿</option><option value="PUBLISHED">发布</option></select></label><label class="admin-check"><input v-model="postForm.featured" type="checkbox">设为精选文章</label></div>
          <label>标签（逗号分隔）<input v-model="postForm.tags" required placeholder="Vue, TypeScript"></label>
          <label>摘要<textarea v-model="postForm.excerpt" rows="3" required /></label>
          <label>HTML 正文<textarea v-model="postForm.content" class="admin-code-editor" rows="14" required spellcheck="false" /></label>
        </template>
        <template v-else>
          <div class="admin-form-grid"><label>菜品名称<input v-model="dishForm.name" required maxlength="120"></label><label>Slug<input v-model="dishForm.slug" required pattern="[a-z0-9]+(?:-[a-z0-9]+)*"></label><label>分类<input v-model="dishForm.category" required maxlength="60"></label><label>准备时间（分钟）<input v-model.number="dishForm.prepMinutes" type="number" min="1" max="1440" required></label><label>难度<select v-model="dishForm.difficulty" required><option value="简单">简单</option><option value="家常">家常</option><option value="进阶">进阶</option></select></label><label>评分<input v-model.number="dishForm.rating" type="number" min="0" max="5" step="0.1" required></label><label>展示顺序<input v-model.number="dishForm.displayOrder" type="number" min="0" required></label><label class="admin-check"><input v-model="dishForm.featured" type="checkbox">设为精选菜品</label><label class="admin-check"><input v-model="dishForm.published" type="checkbox">公开发布</label></div>
          <label>简介<textarea v-model="dishForm.summary" rows="3" required maxlength="1000" /></label>
          <label>图片地址<input v-model="dishForm.imageUrl" required placeholder="/food/example.jpg 或 https://..."></label>
          <label>图片替代文本<input v-model="dishForm.imageAlt" required maxlength="240"></label>
          <div class="admin-form-grid"><label>图片署名<input v-model="dishForm.imageCredit" required maxlength="240" placeholder="作者 · 许可"></label><label>图片来源页面<input v-model="dishForm.imageSourceUrl" type="url" required></label></div>
          <label>食材清单（每行一项）<textarea v-model="dishForm.ingredients" rows="7" required placeholder="嫩豆腐 400 克&#10;牛肉末 80 克" /></label>
          <label>制作步骤（每行一步）<textarea v-model="dishForm.steps" rows="8" required placeholder="豆腐切块并焯水。&#10;炒香肉末与豆瓣酱。" /></label>
        </template>
        <p v-if="error" class="admin-error" role="alert">{{ error }}</p>
        <footer><button class="button secondary" type="button" @click="editorOpen = false">取消</button><button class="button primary" type="submit" :disabled="saving">{{ saving ? '正在保存…' : '保存内容 ↗' }}</button></footer>
      </form>
    </div>
  </section>
</template>
