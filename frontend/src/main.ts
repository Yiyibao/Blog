import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import './tokens.css'
import './base.css'
import './content.css'

router.beforeEach(async (to) => {
  if (to.name === 'recipes') {
    await import('./recipes.css')
  } else if (to.path.startsWith('/admin') && to.name !== 'admin-login') {
    await import('./admin.css')
  }
})

createApp(App).use(createPinia()).use(router).mount('#app')
