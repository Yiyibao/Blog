import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { refreshSession } from '../api/admin'
import { Capabilities, type Capability } from '../utils/capabilities'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: () => import('../pages/HomePage.vue') },
    { path: '/articles', name: 'articles', component: () => import('../pages/ArticlesPage.vue') },
    { path: '/articles/:slug', name: 'article', component: () => import('../pages/ArticlePage.vue') },
    { path: '/about', name: 'about', component: () => import('../pages/AboutPage.vue') },
    // L-16/D-17：学习笔记对游客真隐藏——需登录（任意角色），深链未登录会被送去 /login?next= 接续
    { path: '/notes', name: 'notes', component: () => import('../pages/NotesPage.vue'), meta: { requiresAuth: true, capability: Capabilities.ACCOUNT_ACCESS } },
    { path: '/login', name: 'login', component: () => import('../pages/LoginPage.vue') },
    { path: '/account', name: 'account', component: () => import('../pages/AccountPage.vue'), meta: { requiresAuth: true, capability: Capabilities.ACCOUNT_ACCESS } },
    { path: '/admin/login', name: 'admin-login', component: () => import('../pages/AdminLoginPage.vue') },
    { path: '/admin', name: 'admin', component: () => import('../pages/AdminDashboardPage.vue'), meta: { requiresAuth: true, capability: Capabilities.CONTENT_MANAGE } },
    { path: '/admin/notes', name: 'admin-notes', component: () => import('../pages/AdminNotesPage.vue'), meta: { requiresAuth: true, capability: Capabilities.CONTENT_MANAGE } },
    { path: '/admin/ai', name: 'admin-ai', component: () => import('../pages/AdminAiPage.vue'), meta: { requiresAuth: true, capability: Capabilities.AI_USAGE } },
    { path: '/admin/ai/providers', name: 'admin-ai-providers', component: () => import('../pages/AdminAiProvidersPage.vue'), meta: { requiresAuth: true, capability: Capabilities.AI_MANAGE } },
    { path: '/admin/library', name: 'admin-library', component: () => import('../pages/AdminLibraryPage.vue'), meta: { requiresAuth: true, capability: Capabilities.LIBRARY_MANAGE } },
    { path: '/admin/series', name: 'admin-series', component: () => import('../pages/AdminSeriesPage.vue'), meta: { requiresAuth: true, capability: Capabilities.CONTENT_MANAGE } },
    { path: '/admin/attachments', name: 'admin-attachments', component: () => import('../pages/AdminAttachmentsPage.vue'), meta: { requiresAuth: true, capability: Capabilities.ATTACHMENTS_MANAGE } },
    { path: '/series', name: 'series', component: () => import('../pages/SeriesPage.vue') },
    { path: '/series/:slug', name: 'series-detail', component: () => import('../pages/SeriesDetailPage.vue') },
    { path: '/tags/:tag', name: 'tag', component: () => import('../pages/TagPage.vue') },
    { path: '/archive', name: 'archive', component: () => import('../pages/ArchivePage.vue') },
    { path: '/recipes', name: 'recipes', component: () => import('../pages/RecipesPage.vue') },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('../pages/NotFoundPage.vue') },
  ],
  scrollBehavior: (_to, _from, savedPosition) => savedPosition ?? { top: 0 },
})

router.beforeEach(async (to, _from, next) => {
  const auth = useAuthStore()
  const routeName = String(to.name ?? '')
  const memberVisibleRoutes = new Set(['articles', 'article', 'recipes'])
  // FD-8：requiresAuth + capability——
  // 未登录去登录页；已登录但缺少所需 capability（如 PARTNER 访问 /admin）重定向 /recipes 而非登录页，
  // 免得"已登录还被要求登录"的死循环体验
  if (!to.meta.requiresAuth) {
    if (auth.isAuthenticated && !auth.isAdmin && !memberVisibleRoutes.has(routeName)) {
      next({ name: 'articles' })
      return
    }
    next()
    return
  }
  // 6C-1：本地 access 无效时先尝试 cookie 恢复，再决定跳登录
  if (!auth.isAuthenticated) {
    const ok = await refreshSession()
    if (!ok) {
      next({ name: 'login', query: { next: to.fullPath } })
      return
    }
  }
  if (auth.isAuthenticated && !auth.isAdmin && !memberVisibleRoutes.has(routeName)) {
    next({ name: 'articles' })
    return
  }
  const required = to.meta.capability as Capability | undefined
  if (required && !auth.can(required)) {
    next({ path: '/recipes' })
    return
  }
  next()
})

export default router
