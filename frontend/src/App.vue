<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import SiteFooter from './components/SiteFooter.vue'
import GlobalSearch from './components/GlobalSearch.vue'
import AmbientSound from './components/AmbientSound.vue'
import { useUiStore } from './stores/uiStore'
import { usePageMeta } from './composables/usePageMeta'
import { useStructuredData, webSite } from './composables/useStructuredData'
import { refreshReveals, disconnectReveals } from './composables/useReveals'

const route = useRoute()
const ui = useUiStore()

const menuOpen = ref(false)
const readingProgress = ref(0)
const showBackToTop = ref(false)
let scrollFrame: number | undefined

const isAdminRoute = computed(() => String(route.path).startsWith('/admin'))

function onKeydown(event: KeyboardEvent) {
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    ui.openSearch()
  }
}

function updateProgress() {
  showBackToTop.value = window.scrollY > Math.min(520, window.innerHeight * 0.6)

  const article = document.querySelector('.article-body')
  if (!article) {
    readingProgress.value = 0
    return
  }
  const rect = article.getBoundingClientRect()
  const distance = Math.max(1, (article as HTMLElement).clientHeight - window.innerHeight * 0.35)
  readingProgress.value = Math.min(100, Math.max(0, (-rect.top + 130) / distance * 100))
}

function scheduleProgressUpdate() {
  if (scrollFrame !== undefined) return
  scrollFrame = window.requestAnimationFrame(() => {
    scrollFrame = undefined
    updateProgress()
  })
}

function scrollToTop() {
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

function handlePointerMove(event: PointerEvent) {
  if (event.pointerType === 'touch') return
  const target = (event.target as HTMLElement).closest<HTMLElement>('.featured-card, .post-card, .project-card, .about-card, .related-grid > a, .values article, .dish-card')
  if (!target) return
  const rect = target.getBoundingClientRect()
  const x = (event.clientX - rect.left) / rect.width
  const y = (event.clientY - rect.top) / rect.height
  target.style.setProperty('--mx', `${x * 100}%`)
  target.style.setProperty('--my', `${y * 100}%`)
  target.style.setProperty('--rx', `${(0.5 - y) * 2.2}deg`)
  target.style.setProperty('--ry', `${(x - 0.5) * 2.2}deg`)
  target.classList.add('is-pointed')
}

function handlePointerOut(event: PointerEvent) {
  const target = (event.target as HTMLElement).closest<HTMLElement>('.is-pointed')
  if (!target || target.contains(event.relatedTarget as Node | null)) return
  target.classList.remove('is-pointed')
  target.style.removeProperty('--rx')
  target.style.removeProperty('--ry')
}

watch(() => ui.isDark, (value) => document.documentElement.classList.toggle('dark', value as boolean), { immediate: true })

watch(() => route.fullPath, () => {
  menuOpen.value = false
  ui.closeSearch()
  const name = String(route.name)
  const { apply: applyLD, clear: clearLD } = useStructuredData()
  clearLD()
  if (name === 'home') {
    applyLD(webSite())
  }
  const { apply } = usePageMeta()
  if (name === 'not-found') {
    apply({
      title: '页面不存在',
      description: '请求的页面不存在，可能链接已失效。',
      robots: 'noindex, nofollow',
    })
  } else if (name.startsWith('admin') || name.startsWith('admin-')) {
    apply({
      title: ({ 'admin-login': '管理员登录', 'admin-notes': '学习笔记' } as Record<string, string>)[name] || '内容工作台',
      robots: 'noindex, nofollow',
    })
  } else {
    const pageMeta: Record<string, { title: string; description: string; canonicalPath: string }> = {
      home: { title: '', description: '', canonicalPath: '' },
      articles: { title: '文章', description: '阅读所有技术文章与日常随笔', canonicalPath: '/articles' },
      article: { title: '文章', description: '', canonicalPath: '' },
      notes: { title: '学习笔记', description: '公开学习笔记，持续更新的认知地图', canonicalPath: '/notes' },
      recipes: { title: '美食', description: '家常菜谱与美食记录', canonicalPath: '/recipes' },
      about: { title: '关于', description: '关于作者和这个博客', canonicalPath: '/about' },
      archive: { title: '内容归档', description: '按时间浏览所有公开的文章、学习笔记和菜谱', canonicalPath: '/archive' },
    }
    const pm = pageMeta[name] || { title: '', description: '', canonicalPath: '' }
    apply({
      title: pm.title,
      description: pm.description || undefined,
      canonicalPath: pm.canonicalPath || undefined,
      openGraph: {
        title: pm.title || undefined,
        description: pm.description || undefined,
        type: name === 'article' ? 'article' : 'website',
        image: '/og.png',
        url: pm.canonicalPath || undefined,
      },
      twitter: {
        title: pm.title || undefined,
        description: pm.description || undefined,
        image: '/og.png',
      },
    })
  }
  void nextTick(refreshReveals)
})

onMounted(() => {
  ui.initTheme()
  window.addEventListener('keydown', onKeydown)
  window.addEventListener('scroll', scheduleProgressUpdate, { passive: true })
  updateProgress()
  void nextTick(refreshReveals)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('scroll', scheduleProgressUpdate)
  if (scrollFrame !== undefined) window.cancelAnimationFrame(scrollFrame)
  disconnectReveals()
})
</script>

<template>
  <div class="site-shell" :class="{ 'admin-mode': isAdminRoute }" @pointermove="handlePointerMove" @pointerout="handlePointerOut">
    <div v-if="!isAdminRoute" class="reading-progress" :style="{ width: `${readingProgress}%` }" aria-hidden="true" />
    <div v-if="!isAdminRoute" class="sakura-petals" aria-hidden="true">
      <i
        v-for="petal in 16"
        :key="petal"
        :style="{
          '--petal': petal,
          '--petal-x': `${(petal * 43 + 7) % 101}%`,
          '--petal-sway': `${28 + (petal % 5) * 13}px`,
          '--petal-sway-left': `${-(28 + (petal % 5) * 13) * 0.55}px`,
          '--petal-sway-right': `${(28 + (petal % 5) * 13) * 0.7}px`,
          '--petal-sway-end': `${-(28 + (petal % 5) * 13) * 0.4}px`,
          '--petal-opacity': `${0.2 + (petal % 4) * 0.07}`,
          '--petal-size': `${7 + (petal % 5) * 1.35}px`,
          '--petal-duration': `${14 + (petal % 7) * 1.9}s`,
          '--flower-size': `${14 + (petal % 3) * 2}px`,
        }"
      />
    </div>
    <header v-if="!isAdminRoute" class="site-header">
      <RouterLink class="brand" to="/" aria-label="余白首页">
        <span class="brand-stamp">余</span>
        <span><strong>余白手记</strong><small>YUBAI · DIGITAL GARDEN</small></span>
      </RouterLink>
      <nav class="desktop-nav" aria-label="主导航">
        <RouterLink to="/"><i>⌂</i>首页</RouterLink>
        <RouterLink to="/articles"><i>✎</i>文章</RouterLink>
        <RouterLink to="/archive"><i>☰</i>归档</RouterLink>
        <RouterLink to="/recipes"><i>♨</i>美食</RouterLink>
        <RouterLink to="/notes"><i>☘</i>学习笔记</RouterLink>
        <RouterLink to="/about"><i>○</i>关于</RouterLink>
      </nav>
      <div class="header-actions">
        <button class="icon-button search-trigger" type="button" aria-label="全站搜索" @click="ui.openSearch">⌕ <kbd>⌘K</kbd></button>
        <button class="icon-button" type="button" :aria-label="ui.isDark ? '切换浅色模式' : '切换深色模式'" @click="ui.toggleTheme">{{ ui.isDark ? '☀' : '◐' }}</button>
        <button class="menu-button" type="button" :aria-expanded="menuOpen" aria-label="打开导航" @click="menuOpen = !menuOpen">{{ menuOpen ? '关闭' : '菜单' }}</button>
      </div>
    </header>

    <nav v-if="menuOpen" class="mobile-nav" aria-label="移动端导航">
      <RouterLink to="/">首页 <span>01</span></RouterLink>
      <RouterLink to="/articles">文章 <span>02</span></RouterLink>
      <RouterLink to="/archive">归档 <span>03</span></RouterLink>
      <RouterLink to="/recipes">美食 <span>04</span></RouterLink>
      <RouterLink to="/notes">学习笔记 <span>05</span></RouterLink>
      <RouterLink to="/about">关于 <span>06</span></RouterLink>
    </nav>

    <main>
      <router-view />
    </main>

    <footer v-if="!isAdminRoute" class="site-footer section-wrap">
      <div class="footer-brand"><span class="brand-stamp">余</span><strong>余白</strong><p>BUILD · WRITE · REFLECT</p></div>
      <div>
        <RouterLink to="/articles">文章</RouterLink>
        <RouterLink to="/archive">归档</RouterLink>
        <RouterLink to="/recipes">美食</RouterLink>
        <RouterLink to="/notes">学习笔记</RouterLink>
        <RouterLink to="/about">关于</RouterLink>
        <RouterLink to="/admin/login">管理</RouterLink>
      </div>
      <SiteFooter />
      <button type="button" @click="scrollToTop">回到顶部 ↑</button>
    </footer>

    <button
      v-if="!isAdminRoute"
      class="sakura-back-top"
      :class="{ visible: showBackToTop }"
      type="button"
      aria-label="返回网页顶部"
      :tabindex="showBackToTop ? 0 : -1"
      @click="scrollToTop"
    >
      <span class="sakura-back-top-flower" aria-hidden="true">
        <i v-for="petal in 5" :key="petal" :style="{ '--flower-angle': `${(petal - 1) * 72}deg` }" />
        <b />
      </span>
      <small>回到顶部</small>
    </button>

    <GlobalSearch :open="ui.searchOpen" @close="ui.closeSearch" />
    <AmbientSound v-if="!isAdminRoute" />
    <div class="toast" :class="{ visible: !!ui.toast }" role="status" aria-live="polite">{{ ui.toast }}</div>
  </div>
</template>
