import { createRouter, createWebHistory } from 'vue-router';
import { useAuthStore } from '../stores/auth';
import { refreshSession } from '../api/admin';
import { Capabilities, type Capability } from '../utils/capabilities';

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      name: 'home',
      component: () => import('../pages/HomePage.vue'),
      meta: { visibility: 'public' },
    },
    {
      path: '/articles',
      name: 'articles',
      component: () => import('../pages/ArticlesPage.vue'),
      meta: { visibility: 'public' },
    },
    {
      path: '/articles/:slug',
      name: 'article',
      component: () => import('../pages/ArticlePage.vue'),
      meta: { visibility: 'public' },
    },
    {
      path: '/preview/posts/:postId',
      name: 'post-preview',
      component: () => import('../pages/PostPreviewPage.vue'),
      meta: { visibility: 'public' },
    },
    {
      path: '/search',
      name: 'search',
      component: () => import('../pages/SearchPage.vue'),
      meta: { visibility: 'public' },
    },
    // L-16/D-17：学习笔记对游客真隐藏——需登录（任意角色），深链未登录会被送去 /login?next= 接续
    {
      path: '/notes',
      name: 'notes',
      component: () => import('../pages/NotesPage.vue'),
      meta: { requiresAuth: true, capability: Capabilities.ACCOUNT_ACCESS },
    },
    { path: '/login', name: 'login', component: () => import('../pages/LoginPage.vue') },
    {
      path: '/account',
      name: 'account',
      component: () => import('../pages/AccountPage.vue'),
      meta: { requiresAuth: true, capability: Capabilities.ACCOUNT_ACCESS },
    },
    { path: '/admin/login', name: 'admin-login', component: () => import('../pages/AdminLoginPage.vue') },
    {
      path: '/admin',
      name: 'admin',
      component: () => import('../pages/AdminDashboardPage.vue'),
      meta: { requiresAuth: true, capability: Capabilities.CONTENT_MANAGE },
    },
    {
      path: '/admin/notes',
      name: 'admin-notes',
      component: () => import('../pages/AdminNotesPage.vue'),
      meta: { requiresAuth: true, capability: Capabilities.CONTENT_MANAGE },
    },
    {
      path: '/admin/ai',
      name: 'admin-ai',
      component: () => import('../pages/AdminAiPage.vue'),
      meta: { requiresAuth: true, capability: Capabilities.AI_USAGE },
    },
    ...(import.meta.env.DEV
      ? [
          {
            path: '/admin/ai-preview',
            name: 'ai-workspace-preview',
            component: () => import('../pages/AdminAiPage.vue'),
            meta: { visibility: 'public' },
          },
        ]
      : []),
    {
      path: '/admin/ai/images',
      name: 'admin-ai-images',
      component: () => import('../pages/AdminAiImagesPage.vue'),
      meta: { requiresAuth: true, capability: Capabilities.AI_USAGE },
    },
    {
      path: '/admin/ai/providers',
      name: 'admin-ai-providers',
      component: () => import('../pages/AdminAiProvidersPage.vue'),
      meta: { requiresAuth: true, capability: Capabilities.AI_MANAGE },
    },
    {
      path: '/admin/library',
      name: 'admin-library',
      component: () => import('../pages/AdminLibraryPage.vue'),
      meta: { requiresAuth: true, capability: Capabilities.LIBRARY_MANAGE },
    },
    {
      path: '/admin/series',
      name: 'admin-series',
      component: () => import('../pages/AdminSeriesPage.vue'),
      meta: { requiresAuth: true, capability: Capabilities.CONTENT_MANAGE },
    },
    {
      path: '/admin/attachments',
      name: 'admin-attachments',
      component: () => import('../pages/AdminAttachmentsPage.vue'),
      meta: { requiresAuth: true, capability: Capabilities.ATTACHMENTS_MANAGE },
    },
    {
      path: '/admin/media',
      name: 'admin-media',
      component: () => import('../pages/AdminMediaPage.vue'),
      meta: { requiresAuth: true, capability: Capabilities.LIBRARY_MANAGE },
    },
    {
      path: '/series',
      name: 'series',
      component: () => import('../pages/SeriesPage.vue'),
      meta: { visibility: 'public' },
    },
    {
      path: '/series/:slug',
      name: 'series-detail',
      component: () => import('../pages/SeriesDetailPage.vue'),
      meta: { visibility: 'public' },
    },
    {
      path: '/tags/:tag',
      name: 'tag',
      component: () => import('../pages/TagPage.vue'),
      meta: { visibility: 'public' },
    },
    {
      path: '/archive',
      name: 'archive',
      component: () => import('../pages/ArchivePage.vue'),
      meta: { visibility: 'public' },
    },
    {
      path: '/categories',
      name: 'categories',
      component: () => import('../pages/CategoriesPage.vue'),
      meta: { visibility: 'public' },
    },
    {
      path: '/categories/:slug',
      name: 'category-detail',
      component: () => import('../pages/CategoryPage.vue'),
      meta: { visibility: 'public' },
    },
    {
      path: '/about',
      name: 'about',
      component: () => import('../pages/AboutPage.vue'),
      meta: { visibility: 'public' },
    },
    {
      path: '/recipes',
      name: 'recipes',
      component: () => import('../pages/RecipesPage.vue'),
      meta: { visibility: 'public' },
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: () => import('../pages/NotFoundPage.vue'),
      meta: { visibility: 'public' },
    },
  ],
  scrollBehavior: (_to, _from, savedPosition) => savedPosition ?? { top: 0 },
});

router.beforeEach(async (to, _from, next) => {
  const auth = useAuthStore();
  const routeName = String(to.name ?? '');
  const authEntryRoutes = new Set(['login', 'admin-login']);
  // M4：公开路由只由 route.meta.visibility 控制；未知路径保留 404，不能静默改成首页。
  // 受保护路由只由 requiresAuth + capability 决定，不再用角色名单重复裁剪公开内容。
  if (!to.meta.requiresAuth) {
    if (to.meta.visibility === 'public' || authEntryRoutes.has(routeName)) next();
    else next({ name: 'home' });
    return;
  }
  // 6C-1：本地 access 无效时先尝试 cookie 恢复，再决定跳登录
  if (!auth.isAuthenticated) {
    const ok = await refreshSession();
    if (!ok) {
      next({ name: 'login', query: { next: to.fullPath } });
      return;
    }
  }
  const required = to.meta.capability as Capability | undefined;
  if (required && !auth.can(required)) {
    next({ path: '/recipes' });
    return;
  }
  next();
});

export default router;
