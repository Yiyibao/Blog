import { createRouter, createWebHistory } from 'vue-router'
import { defineAsyncComponent } from 'vue'
import AdminLoginPage from '../pages/AdminLoginPage.vue'
import NotFoundPage from '../pages/NotFoundPage.vue'
import { useAuthStore } from '../stores/auth'

const HomePage = defineAsyncComponent(() => import('../pages/HomePage.vue'))
const ArticlesPage = defineAsyncComponent(() => import('../pages/ArticlesPage.vue'))
const ArticlePage = defineAsyncComponent(() => import('../pages/ArticlePage.vue'))
const AboutPage = defineAsyncComponent(() => import('../pages/AboutPage.vue'))
const NotesPage = defineAsyncComponent(() => import('../pages/NotesPage.vue'))
const ArchivePage = defineAsyncComponent(() => import('../pages/ArchivePage.vue'))
const RecipesPage = defineAsyncComponent(() => import('../pages/RecipesPage.vue'))
const AdminDashboardPage = defineAsyncComponent(() => import('../pages/AdminDashboardPage.vue'))
const AdminNotesPage = defineAsyncComponent(() => import('../pages/AdminNotesPage.vue'))
const AdminAiPage = defineAsyncComponent(() => import('../pages/AdminAiPage.vue'))
const AdminAiProvidersPage = defineAsyncComponent(() => import('../pages/AdminAiProvidersPage.vue'))
const AdminLibraryPage = defineAsyncComponent(() => import('../pages/AdminLibraryPage.vue'))
const AdminSeriesPage = defineAsyncComponent(() => import('../pages/AdminSeriesPage.vue'))
const AdminAttachmentsPage = defineAsyncComponent(() => import('../pages/AdminAttachmentsPage.vue'))
const SeriesPage = defineAsyncComponent(() => import('../pages/SeriesPage.vue'))
const SeriesDetailPage = defineAsyncComponent(() => import('../pages/SeriesDetailPage.vue'))
const LoginPage = defineAsyncComponent(() => import('../pages/LoginPage.vue'))
const AccountPage = defineAsyncComponent(() => import('../pages/AccountPage.vue'))

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: HomePage },
    { path: '/articles', name: 'articles', component: ArticlesPage },
    { path: '/articles/:slug', name: 'article', component: ArticlePage },
    { path: '/about', name: 'about', component: AboutPage },
    // L-16/D-17：学习笔记对游客真隐藏——需登录（任意角色），深链未登录会被送去 /login?next= 接续
    { path: '/notes', name: 'notes', component: NotesPage, meta: { requiresAuth: true } },
    { path: '/login', name: 'login', component: LoginPage },
    { path: '/account', name: 'account', component: AccountPage, meta: { requiresAuth: true } },
    { path: '/admin/login', name: 'admin-login', component: AdminLoginPage },
    { path: '/admin', name: 'admin', component: AdminDashboardPage, meta: { requiresAuth: true, requiresRole: 'ADMIN' } },
    { path: '/admin/notes', name: 'admin-notes', component: AdminNotesPage, meta: { requiresAuth: true, requiresRole: 'ADMIN' } },
    { path: '/admin/ai', name: 'admin-ai', component: AdminAiPage, meta: { requiresAuth: true, requiresRole: 'ADMIN' } },
    { path: '/admin/ai/providers', name: 'admin-ai-providers', component: AdminAiProvidersPage, meta: { requiresAuth: true, requiresRole: 'ADMIN' } },
    { path: '/admin/library', name: 'admin-library', component: AdminLibraryPage, meta: { requiresAuth: true, requiresRole: 'ADMIN' } },
    { path: '/admin/series', name: 'admin-series', component: AdminSeriesPage, meta: { requiresAuth: true, requiresRole: 'ADMIN' } },
    { path: '/admin/attachments', name: 'admin-attachments', component: AdminAttachmentsPage, meta: { requiresAuth: true, requiresRole: 'ADMIN' } },
    { path: '/series', name: 'series', component: SeriesPage },
    { path: '/series/:slug', name: 'series-detail', component: SeriesDetailPage },
    { path: '/archive', name: 'archive', component: ArchivePage },
    { path: '/recipes', name: 'recipes', component: RecipesPage },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: NotFoundPage },
  ],
  scrollBehavior: (_to, _from, savedPosition) => savedPosition ?? { top: 0 },
})

router.beforeEach((to, _from, next) => {
  // FD-8：requiresAdmin 拆为 requiresAuth + requiresRole——
  // 未登录去登录页；已登录但角色不符（如 PARTNER 访问 /admin）重定向 /recipes 而非登录页，
  // 免得"已登录还被要求登录"的死循环体验
  if (!to.meta.requiresAuth) {
    next()
    return
  }
  const auth = useAuthStore()
  if (!auth.isAuthenticated) {
    // FD-9：统一走 /login，带上来路以便登录后原地接续（FD-14 的 intent 也在 next 里）
    next({ name: 'login', query: { next: to.fullPath } })
    return
  }
  if (to.meta.requiresRole && to.meta.requiresRole !== auth.role) {
    next({ path: '/recipes' })
    return
  }
  next()
})

export default router
