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
const RecipesPage = defineAsyncComponent(() => import('../pages/RecipesPage.vue'))
const AdminDashboardPage = defineAsyncComponent(() => import('../pages/AdminDashboardPage.vue'))
const AdminNotesPage = defineAsyncComponent(() => import('../pages/AdminNotesPage.vue'))

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: HomePage },
    { path: '/articles', name: 'articles', component: ArticlesPage },
    { path: '/articles/:slug', name: 'article', component: ArticlePage },
    { path: '/about', name: 'about', component: AboutPage },
    { path: '/notes', name: 'notes', component: NotesPage },
    { path: '/admin/login', name: 'admin-login', component: AdminLoginPage },
    { path: '/admin', name: 'admin', component: AdminDashboardPage },
    { path: '/admin/notes', name: 'admin-notes', component: AdminNotesPage },
    { path: '/recipes', name: 'recipes', component: RecipesPage },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: NotFoundPage },
  ],
  scrollBehavior: (_to, _from, savedPosition) => savedPosition ?? { top: 0 },
})

router.beforeEach((to, _from, next) => {
  if (to.name === 'admin' || to.name === 'admin-notes') {
    const auth = useAuthStore()
    if (!auth.isAuthenticated) {
      next({ name: 'admin-login' })
      return
    }
  }
  next()
})

export default router
