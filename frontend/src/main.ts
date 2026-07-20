import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import './styles.css'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: App },
    { path: '/articles', name: 'articles', component: App },
    { path: '/articles/:slug', name: 'article', component: App },
    { path: '/projects', name: 'projects', component: App },
    { path: '/about', name: 'about', component: App },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
  scrollBehavior: (_to, _from, savedPosition) => savedPosition ?? { top: 0 },
})

createApp(App).use(createPinia()).use(router).mount('#app')
