<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import GlobalSearch from './components/GlobalSearch.vue'
import { useUiStore } from './stores/uiStore'

const route = useRoute()
const ui = useUiStore()

const menuOpen = ref(false)
const readingProgress = ref(0)
const showBackToTop = ref(false)
let revealObserver: IntersectionObserver | undefined
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

function setupReveals() {
  revealObserver?.disconnect()
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  const selector = [
    'main > section',
    '.ticker',
    '.section-heading',
    '.featured-card',
    '.post-card',
    '.archive-row',
    '.project-card',
    '.related-grid > a',
    '.values article',
    '.manifesto blockquote',
    '.manifesto > div span',
    '.pagination',
  ].join(', ')

  const nodes = [...document.querySelectorAll<HTMLElement>(selector)]
  const groupCount = new Map<string, number>()

  nodes.forEach((element) => {
    if (element.classList.contains('hero')) return
    element.classList.add('reveal-item')
    const parent = element.closest('section, .post-grid, .projects, .related-grid, .values, .manifesto, .archive-list')
    const key = parent ? `${parent.className}|${parent.tagName}` : 'root'
    const index = groupCount.get(key) ?? 0
    groupCount.set(key, index + 1)
    element.style.setProperty('--reveal-delay', `${Math.min(index, 8) * 70}ms`)

    if (element.classList.contains('featured-card')) element.dataset.reveal = 'scale'
    else if (element.classList.contains('project-card') && index % 2 === 1) element.dataset.reveal = 'right'
    else if (element.classList.contains('project-card')) element.dataset.reveal = 'left'
    else if (element.matches('.related-grid > a') && index % 2 === 1) element.dataset.reveal = 'right'
    else if (element.matches('.related-grid > a')) element.dataset.reveal = 'left'

    if (reduceMotion) {
      element.classList.add('is-visible')
      return
    }
  })

  if (reduceMotion) return

  revealObserver = new IntersectionObserver((entries) => {
    entries.forEach((entry) => {
      if (!entry.isIntersecting) return
      entry.target.classList.add('is-visible')
      revealObserver?.unobserve(entry.target)
    })
  }, { threshold: 0.12, rootMargin: '0px 0px -8% 0px' })

  nodes.forEach((element) => {
    if (element.classList.contains('hero')) return
    revealObserver?.observe(element)
  })
}

function scrollToTop() {
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

function handlePointerMove(event: PointerEvent) {
  if (event.pointerType === 'touch') return
  const target = (event.target as HTMLElement).closest<HTMLElement>('.featured-card, .post-card, .project-card, .about-card, .related-grid > a, .values article')
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
  const title = ({
    home: '首页', articles: '文章', recipes: '美食', notes: '学习笔记',
    about: '关于', admin: '内容工作台', 'admin-notes': '学习笔记',
    'admin-login': '管理员登录',
  }[String(route.name)] || '余白')
  document.title = `${title} · 余白`
  void nextTick(setupReveals)
})

onMounted(() => {
  ui.initTheme()
  window.addEventListener('keydown', onKeydown)
  window.addEventListener('scroll', scheduleProgressUpdate, { passive: true })
  updateProgress()
  void nextTick(setupReveals)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('scroll', scheduleProgressUpdate)
  if (scrollFrame !== undefined) window.cancelAnimationFrame(scrollFrame)
  revealObserver?.disconnect()
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
      <RouterLink to="/recipes">美食 <span>03</span></RouterLink>
      <RouterLink to="/notes">学习笔记 <span>04</span></RouterLink>
      <RouterLink to="/about">关于 <span>05</span></RouterLink>
    </nav>

    <main>
      <router-view />
    </main>

    <footer v-if="!isAdminRoute" class="site-footer section-wrap">
      <div class="footer-brand"><span class="brand-stamp">余</span><strong>余白</strong><p>BUILD · WRITE · REFLECT</p></div>
      <div>
        <RouterLink to="/articles">文章</RouterLink>
        <RouterLink to="/recipes">美食</RouterLink>
        <RouterLink to="/notes">学习笔记</RouterLink>
        <RouterLink to="/about">关于</RouterLink>
        <RouterLink to="/admin/login">管理</RouterLink>
      </div>
      <p>© 2026 YUBAI<br>MADE WITH CURIOSITY</p>
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
    <div class="toast" :class="{ visible: !!ui.toast }" role="status" aria-live="polite">{{ ui.toast }}</div>
  </div>
</template>
