<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { logout as apiLogout, getAdminSessionName } from '../api/admin'

defineProps<{
  postTotal?: number
  dishTotal?: number
  noteTotal?: number
}>()

const route = useRoute()
const router = useRouter()
const username = computed(() => getAdminSessionName() || 'Admin')

const activeItem = computed(() => {
  const path = route.path
  if (path === '/admin' || path === '/admin/') {
    const section = Array.isArray(route.query.section) ? route.query.section[0] : route.query.section
    if (section === 'posts') return 'posts'
    if (section === 'dishes') return 'dishes'
    return 'overview'
  }
  if (path.startsWith('/admin/notes')) return 'notes'
  if (path.startsWith('/admin/ai/providers')) return 'ai-providers'
  if (path.startsWith('/admin/ai')) return 'ai'
  if (path.startsWith('/admin/library')) return 'library'
  if (path.startsWith('/admin/series')) return 'series'
  if (path.startsWith('/admin/attachments')) return 'attachments'
  return ''
})

function logout() {
  apiLogout()
  void router.replace('/admin/login')
}
</script>

<template>
  <aside class="admin-sidebar">
    <RouterLink class="admin-brand" to="/admin">
      <span>余</span>
      <div><strong>拾光录后台</strong><small>ADMIN CONSOLE</small></div>
    </RouterLink>
    <nav aria-label="后台导航">
      <p>工作空间</p>
      <RouterLink :class="{ active: activeItem === 'overview' }" to="/admin">
        <i>⌂</i><span>总览</span>
      </RouterLink>
      <RouterLink :class="{ active: activeItem === 'posts' }" :to="{ path: '/admin', query: { section: 'posts' } }">
        <i>▤</i><span>文章管理</span><b v-if="postTotal !== undefined">{{ postTotal }}</b>
      </RouterLink>
      <RouterLink :class="{ active: activeItem === 'dishes' }" :to="{ path: '/admin', query: { section: 'dishes' } }">
        <i>◉</i><span>菜品管理</span><b v-if="dishTotal !== undefined">{{ dishTotal }}</b>
      </RouterLink>

      <RouterLink :class="{ active: activeItem === 'notes' }" to="/admin/notes">
        <i>✎</i><span>学习笔记</span><b v-if="noteTotal !== undefined">{{ noteTotal }}</b>
      </RouterLink>
      <RouterLink class="ai-nav" :class="{ active: activeItem === 'ai' }" to="/admin/ai">
        <i>🤖</i><span>AI 助手</span>
      </RouterLink>
      <RouterLink :class="{ active: activeItem === 'ai-providers' }" to="/admin/ai/providers">
        <i>⚙</i><span>AI 供应商</span>
      </RouterLink>
      <RouterLink :class="{ active: activeItem === 'library' }" to="/admin/library">
        <i>♪</i><span>曲目与语录</span>
      </RouterLink>
      <RouterLink :class="{ active: activeItem === 'series' }" to="/admin/series">
        <i>≣</i><span>文章合集</span>
      </RouterLink>
      <RouterLink :class="{ active: activeItem === 'attachments' }" to="/admin/attachments">
        <i>⎘</i><span>附件管理</span>
      </RouterLink>
    </nav>
    <footer>
      <div class="admin-avatar">{{ username.slice(0, 1).toUpperCase() }}</div>
      <div><strong>{{ username }}</strong><small>Administrator</small></div>
      <button type="button" title="退出登录" @click="logout">↪</button>
    </footer>
  </aside>
</template>
