<script setup lang="ts">
import axios from 'axios'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  createPost, createProject, deletePost, deleteProject, fetchAdminPosts, fetchAdminProjects,
  updatePost, updateProject, type AdminPost, type AdminProject, type PostPayload, type ProjectPayload,
} from '../api/admin'

const router = useRouter()
const tab = ref<'posts' | 'projects'>('posts')
const posts = ref<AdminPost[]>([])
const projects = ref<AdminProject[]>([])
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const editorOpen = ref(false)
const editingId = ref<number | null>(null)
const editorKind = ref<'post' | 'project'>('post')
const username = sessionStorage.getItem('yubai-admin-name') || 'Admin'

const postForm = reactive({
  slug: '', title: '', excerpt: '', date: new Date().toISOString().slice(0, 10), readTime: 5,
  category: '工程实践', tags: '', color: '#A6784C', number: '01', featured: false,
  content: '<p class="lead">从这里开始写正文。</p>',
})
const projectForm = reactive({
  title: '', description: '', stack: '', year: String(new Date().getFullYear()),
  status: '进行中', color: '#A6784C', displayOrder: 0,
})

const currentCount = computed(() => tab.value === 'posts' ? posts.value.length : projects.value.length)

function handleAuthError(cause: unknown) {
  if (axios.isAxiosError(cause) && cause.response?.status === 401) {
    logout()
    return true
  }
  return false
}

async function load() {
  if (!sessionStorage.getItem('yubai-admin-token')) return logout()
  loading.value = true
  error.value = ''
  try {
    ;[posts.value, projects.value] = await Promise.all([fetchAdminPosts(), fetchAdminProjects()])
  } catch (cause) {
    if (!handleAuthError(cause)) error.value = '暂时无法读取内容，请确认后端服务正在运行。'
  } finally {
    loading.value = false
  }
}

function logout() {
  sessionStorage.removeItem('yubai-admin-token')
  sessionStorage.removeItem('yubai-admin-name')
  sessionStorage.removeItem('yubai-admin-expiry')
  void router.replace('/admin/login')
}

function newItem() {
  editingId.value = null
  editorKind.value = tab.value === 'posts' ? 'post' : 'project'
  if (editorKind.value === 'post') Object.assign(postForm, {
    slug: '', title: '', excerpt: '', date: new Date().toISOString().slice(0, 10), readTime: 5,
    category: '工程实践', tags: '', color: '#A6784C', number: String(posts.value.length + 1).padStart(2, '0'), featured: false,
    content: '<p class="lead">从这里开始写正文。</p>',
  })
  else Object.assign(projectForm, {
    title: '', description: '', stack: '', year: String(new Date().getFullYear()),
    status: '进行中', color: '#A6784C', displayOrder: projects.value.length + 1,
  })
  editorOpen.value = true
}

function editPost(post: AdminPost) {
  editingId.value = post.id
  editorKind.value = 'post'
  Object.assign(postForm, { ...post, tags: post.tags.join(', ') })
  editorOpen.value = true
}

function editProject(project: AdminProject) {
  editingId.value = project.id
  editorKind.value = 'project'
  Object.assign(projectForm, { ...project, stack: project.stack.join(', ') })
  editorOpen.value = true
}

function postPayload(): PostPayload {
  return { ...postForm, tags: postForm.tags.split(',').map((item) => item.trim()).filter(Boolean) }
}

function projectPayload(): ProjectPayload {
  return { ...projectForm, stack: projectForm.stack.split(',').map((item) => item.trim()).filter(Boolean) }
}

async function save() {
  saving.value = true
  error.value = ''
  try {
    if (editorKind.value === 'post') {
      if (editingId.value) await updatePost(editingId.value, postPayload())
      else await createPost(postPayload())
    } else {
      if (editingId.value) await updateProject(editingId.value, projectPayload())
      else await createProject(projectPayload())
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

async function remove(kind: 'post' | 'project', id: number, title: string) {
  if (!window.confirm(`确认删除“${title}”？此操作无法撤销。`)) return
  try {
    if (kind === 'post') await deletePost(id)
    else await deleteProject(id)
    await load()
  } catch (cause) {
    if (!handleAuthError(cause)) error.value = '删除失败，请稍后再试。'
  }
}

onMounted(load)
</script>

<template>
  <section class="admin-workspace section-wrap">
    <header class="admin-workspace-head">
      <div><p class="eyebrow"><span /> CONTENT OPERATIONS</p><h1>内容工作台</h1><p>{{ username }}，欢迎回来。今天也留下一些值得再次阅读的内容。</p></div>
      <div class="admin-head-actions"><RouterLink class="button secondary" to="/">查看博客 ↗</RouterLink><button class="button secondary" type="button" @click="logout">退出</button></div>
    </header>

    <div class="admin-metrics"><article><span>ALL CONTENT</span><strong>{{ posts.length + projects.length }}</strong><small>全部记录</small></article><article><span>POSTS</span><strong>{{ posts.length }}</strong><small>已发布文章</small></article><article><span>PROJECTS</span><strong>{{ projects.length }}</strong><small>项目档案</small></article></div>

    <div class="admin-toolbar">
      <div class="admin-tabs"><button :class="{ active: tab === 'posts' }" @click="tab = 'posts'">文章</button><button :class="{ active: tab === 'projects' }" @click="tab = 'projects'">项目</button></div>
      <span>{{ currentCount.toString().padStart(2, '0') }} RECORDS</span>
      <button class="button primary" type="button" @click="newItem">＋ 新建{{ tab === 'posts' ? '文章' : '项目' }}</button>
    </div>

    <p v-if="error" class="admin-error admin-page-error" role="alert">{{ error }}</p>
    <div v-if="loading" class="admin-empty">正在读取内容…</div>
    <div v-else-if="tab === 'posts'" class="admin-table">
      <div class="admin-table-head"><span>编号</span><span>内容</span><span>状态</span><span>操作</span></div>
      <article v-for="post in posts" :key="post.id"><span class="admin-index">{{ post.number }}</span><div><small>{{ post.category }} · {{ post.date }}</small><strong>{{ post.title }}</strong><p>{{ post.excerpt }}</p></div><span class="admin-status" :class="{ featured: post.featured }">{{ post.featured ? '精选' : '已发布' }}</span><div class="admin-row-actions"><button @click="editPost(post)">编辑</button><button class="danger" @click="remove('post', post.id, post.title)">删除</button></div></article>
    </div>
    <div v-else class="admin-table">
      <div class="admin-table-head"><span>顺序</span><span>内容</span><span>状态</span><span>操作</span></div>
      <article v-for="project in projects" :key="project.id"><span class="admin-index">{{ String(project.displayOrder).padStart(2, '0') }}</span><div><small>{{ project.year }} · {{ project.stack.join(' / ') }}</small><strong>{{ project.title }}</strong><p>{{ project.description }}</p></div><span class="admin-status">{{ project.status }}</span><div class="admin-row-actions"><button @click="editProject(project)">编辑</button><button class="danger" @click="remove('project', project.id, project.title)">删除</button></div></article>
    </div>

    <div v-if="editorOpen" class="admin-editor-backdrop" @click.self="editorOpen = false">
      <form class="admin-editor" @submit.prevent="save">
        <header><div><small>{{ editingId ? 'EDIT RECORD' : 'NEW RECORD' }}</small><h2>{{ editingId ? '编辑' : '新建' }}{{ editorKind === 'post' ? '文章' : '项目' }}</h2></div><button type="button" aria-label="关闭编辑器" @click="editorOpen = false">×</button></header>
        <template v-if="editorKind === 'post'">
          <div class="admin-form-grid"><label>标题<input v-model="postForm.title" required maxlength="200"></label><label>Slug<input v-model="postForm.slug" required pattern="[a-z0-9]+(?:-[a-z0-9]+)*"></label><label>分类<input v-model="postForm.category" required></label><label>发布日期<input v-model="postForm.date" type="date" required></label><label>阅读时间（分钟）<input v-model.number="postForm.readTime" type="number" min="1" max="180" required></label><label>编号<input v-model="postForm.number" required maxlength="10"></label><label>颜色<input v-model="postForm.color" type="color" required></label><label class="admin-check"><input v-model="postForm.featured" type="checkbox">设为精选文章</label></div>
          <label>标签（逗号分隔）<input v-model="postForm.tags" required placeholder="Vue, TypeScript"></label>
          <label>摘要<textarea v-model="postForm.excerpt" rows="3" required /></label>
          <label>HTML 正文<textarea v-model="postForm.content" class="admin-code-editor" rows="14" required spellcheck="false" /></label>
        </template>
        <template v-else>
          <div class="admin-form-grid"><label>项目名称<input v-model="projectForm.title" required maxlength="160"></label><label>年份<input v-model="projectForm.year" required pattern="\d{4}"></label><label>状态<input v-model="projectForm.status" required></label><label>展示顺序<input v-model.number="projectForm.displayOrder" type="number" min="0" required></label><label>颜色<input v-model="projectForm.color" type="color" required></label></div>
          <label>技术栈（逗号分隔）<input v-model="projectForm.stack" required placeholder="Vue 3, TypeScript"></label>
          <label>项目描述<textarea v-model="projectForm.description" rows="5" required /></label>
        </template>
        <p v-if="error" class="admin-error" role="alert">{{ error }}</p>
        <footer><button class="button secondary" type="button" @click="editorOpen = false">取消</button><button class="button primary" type="submit" :disabled="saving">{{ saving ? '正在保存…' : '保存内容 ↗' }}</button></footer>
      </form>
    </div>
  </section>
</template>
