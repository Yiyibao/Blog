<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { fetchPost, fetchPosts } from './api/content'
import AdminDashboard from './components/AdminDashboard.vue'
import AdminLogin from './components/AdminLogin.vue'
const NotesWorkspace = defineAsyncComponent(() => import('./components/NotesWorkspace.vue'))
const PublicNotes = defineAsyncComponent(() => import('./components/PublicNotes.vue'))
const FoodSection = defineAsyncComponent(() => import('./components/FoodSection.vue'))
import { posts as seedPosts, type Post } from './data'

const route = useRoute()
const router = useRouter()
const menuOpen = ref(false)
const searchOpen = ref(false)
const query = ref('')
const category = ref('全部')
const sortOrder = ref<'newest' | 'oldest'>('newest')
const toast = ref('')
const isDark = ref(false)
const readingProgress = ref(0)
const heroProgress = ref(0)
const showBackToTop = ref(false)
const favorites = ref<string[]>([])
const posts = ref<Post[]>([...seedPosts])
const contentReady = ref(false)
const contentError = ref(false)
const archivePage = ref(0)
const archivePageSize = 6
const articleDetail = ref<Post | null>(null)
let toastTimer: number | undefined
let revealObserver: IntersectionObserver | undefined
let scrollFrame: number | undefined

const categories = computed(() => ['全部', ...new Set(posts.value.map((post) => post.category))])
const currentPost = computed(() => {
  const slug = String(route.params.slug || '')
  return posts.value.find((post) => post.slug === slug) ?? (articleDetail.value?.slug === slug ? articleDetail.value : null)
})
const featuredPost = computed(() => posts.value.find((post) => post.featured) ?? posts.value[0] ?? null)
const filteredPosts = computed(() => {
  const normalized = query.value.trim().toLowerCase()
  return posts.value
    .filter((post) => category.value === '全部' || post.category === category.value)
    .filter((post) => !normalized || [post.title, post.excerpt, post.category, ...post.tags].join(' ').toLowerCase().includes(normalized))
    .sort((a, b) => sortOrder.value === 'newest' ? b.date.localeCompare(a.date) : a.date.localeCompare(b.date))
})
const archiveTotalPages = computed(() => Math.max(1, Math.ceil(filteredPosts.value.length / archivePageSize)))
const pagedPosts = computed(() => {
  const page = Math.min(archivePage.value, archiveTotalPages.value - 1)
  const start = page * archivePageSize
  return filteredPosts.value.slice(start, start + archivePageSize)
})
const searchResults = computed(() => {
  const normalized = query.value.trim().toLowerCase()
  if (!normalized) return posts.value.slice(0, 3)
  return posts.value.filter((post) => [post.title, post.excerpt, ...post.tags].join(' ').toLowerCase().includes(normalized)).slice(0, 5)
})
const relatedPosts = computed(() => {
  if (!currentPost.value) return []
  return posts.value.filter((post) => post.slug !== currentPost.value?.slug && post.tags.some((tag) => currentPost.value?.tags.includes(tag))).slice(0, 2)
})
const articleOutline = computed(() => {
  if (!currentPost.value?.content) return []
  return [...currentPost.value.content.matchAll(/<h2\s+id=["']([^"']+)["'][^>]*>(.*?)<\/h2>/gi)]
    .map((match) => ({ id: match[1], title: match[2].replace(/<[^>]+>/g, '') }))
})

function smoothstep(start: number, end: number, value: number) {
  const normalized = Math.min(1, Math.max(0, (value - start) / (end - start)))
  return normalized * normalized * (3 - 2 * normalized)
}

const heroStyle = computed(() => {
  const progress = heroProgress.value
  const copyExit = smoothstep(0.03, 0.38, progress)
  const backdropExit = smoothstep(0.08, 1, progress)
  return {
    '--hero-scale': `${1 + backdropExit * 0.032}`,
    '--hero-backdrop-opacity': `${1 - backdropExit * 0.5}`,
    '--hero-copy-opacity': `${1 - copyExit}`,
    '--hero-copy-y': `${copyExit * -42}px`,
    '--hero-copy-scale': `${1 - copyExit * 0.022}`,
    '--hero-paper-opacity': `${backdropExit * 0.58}`,
  }
})
const isAdminRoute = computed(() => String(route.name).startsWith('admin'))

async function loadRemoteContent() {
  const allowBundledFallback = import.meta.env.DEV || import.meta.env.VITE_ALLOW_BUNDLED_CONTENT === 'true'
  try {
    contentError.value = false
    const remotePage = await fetchPosts(0, 50)
    if (remotePage?.items?.length) posts.value = remotePage.items
  } catch (error) {
    contentError.value = true
    if (!allowBundledFallback) {
      posts.value = []
      console.error('Backend API is unavailable; bundled content fallback is disabled.', error)
    } else {
      console.info('Backend API is unavailable; using bundled content in development.', error)
    }
  } finally {
    contentReady.value = true
    void ensureArticleDetail()
    void nextTick(setupReveals)
  }
}

async function ensureArticleDetail() {
  if (route.name !== 'article') return
  const slug = String(route.params.slug || '')
  if (!slug || posts.value.some((post) => post.slug === slug)) {
    articleDetail.value = null
    return
  }
  try {
    articleDetail.value = await fetchPost(slug)
  } catch {
    articleDetail.value = null
  }
}

function showToast(message: string) {
  toast.value = message
  window.clearTimeout(toastTimer)
  toastTimer = window.setTimeout(() => { toast.value = '' }, 2600)
}

function toggleTheme() {
  isDark.value = !isDark.value
  localStorage.setItem('yubai-theme', isDark.value ? 'dark' : 'light')
}

function toggleFavorite(slug: string) {
  favorites.value = favorites.value.includes(slug)
    ? favorites.value.filter((item) => item !== slug)
    : [...favorites.value, slug]
  localStorage.setItem('yubai-reading-list', JSON.stringify(favorites.value))
  showToast(favorites.value.includes(slug) ? '已加入稍后阅读' : '已从阅读清单移除')
}

async function copyCurrentLink() {
  await navigator.clipboard.writeText(window.location.href)
  showToast('链接已复制')
}

function scrollToTop() {
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

function openSearch() {
  searchOpen.value = true
  menuOpen.value = false
  requestAnimationFrame(() => document.querySelector<HTMLInputElement>('#global-search')?.focus())
}

function goToResult(post: Post) {
  searchOpen.value = false
  query.value = ''
  void router.push(`/articles/${post.slug}`)
}

function onKeydown(event: KeyboardEvent) {
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    openSearch()
  }
  if (event.key === 'Escape') searchOpen.value = false
}

function updateProgress() {
  showBackToTop.value = window.scrollY > Math.min(520, window.innerHeight * 0.6)

  const heroStage = document.querySelector<HTMLElement>('.hero-stage')
  if (heroStage) {
    const heroRect = heroStage.getBoundingClientRect()
    const headerHeight = document.querySelector<HTMLElement>('.site-header')?.offsetHeight ?? 0
    const heroDistance = Math.max(1, window.innerHeight * 1.15)
    heroProgress.value = Math.min(1, Math.max(0, (headerHeight - heroRect.top) / heroDistance))
  } else {
    heroProgress.value = 0
  }

  const article = document.querySelector('.article-body')
  if (!article) {
    readingProgress.value = 0
    return
  }
  const rect = article.getBoundingClientRect()
  const distance = Math.max(1, article.clientHeight - window.innerHeight * 0.35)
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

watch(isDark, (value) => document.documentElement.classList.toggle('dark', value), { immediate: true })
watch([query, category, sortOrder], () => { archivePage.value = 0 })
watch(() => route.fullPath, () => {
  menuOpen.value = false
  searchOpen.value = false
  if (route.name === 'articles') archivePage.value = 0
  const title = currentPost.value?.title ?? ({ home: '首页', articles: '文章', recipes: '美食', notes: '学习笔记', about: '关于', admin: '内容工作台', 'admin-notes': '学习笔记', 'admin-login': '管理员登录' }[String(route.name)] || '余白')
  document.title = `${title} · 余白`
  void ensureArticleDetail()
  void nextTick(setupReveals)
})

onMounted(() => {
  const savedTheme = localStorage.getItem('yubai-theme')
  isDark.value = savedTheme ? savedTheme === 'dark' : window.matchMedia('(prefers-color-scheme: dark)').matches
  try { favorites.value = JSON.parse(localStorage.getItem('yubai-reading-list') ?? '[]') as string[] } catch { favorites.value = [] }
  window.addEventListener('keydown', onKeydown)
  window.addEventListener('scroll', scheduleProgressUpdate, { passive: true })
  updateProgress()
  void loadRemoteContent()
  void nextTick(setupReveals)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('scroll', scheduleProgressUpdate)
  if (scrollFrame !== undefined) window.cancelAnimationFrame(scrollFrame)
  revealObserver?.disconnect()
  window.clearTimeout(toastTimer)
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
        <button class="icon-button search-trigger" type="button" aria-label="搜索文章" @click="openSearch">⌕ <kbd>⌘K</kbd></button>
        <button class="icon-button" type="button" :aria-label="isDark ? '切换浅色模式' : '切换深色模式'" @click="toggleTheme">{{ isDark ? '☀' : '◐' }}</button>
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
      <template v-if="route.name === 'admin-login'">
        <AdminLogin />
      </template>

      <template v-else-if="route.name === 'admin'">
        <AdminDashboard />
      </template>

      <template v-else-if="route.name === 'admin-notes'">
        <NotesWorkspace />
      </template>

      <template v-else-if="route.name === 'home'">
        <section class="hero-stage">
          <div class="hero" :style="heroStyle">
            <div class="hero-background" aria-hidden="true" />
            <div class="hero-veil" aria-hidden="true" />
            <div class="hero-copy">
              <p class="hero-kana hero-enter" style="--enter-delay: 0ms">一页一念，安静生长。</p>
              <p class="eyebrow hero-enter" style="--enter-delay: 40ms"><span /> A QUIET DIGITAL GARDEN</p>
              <h1 class="hero-enter" style="--enter-delay: 80ms">把生活写成一座<br><em>安静生长的花园</em>。</h1>
              <p class="hero-intro hero-enter" style="--enter-delay: 160ms">关于设计、工程与日常的长期手记。向下走，让故事从风景里慢慢浮现。</p>
              <div class="hero-actions hero-enter" style="--enter-delay: 220ms">
                <RouterLink class="button primary" to="/articles">开始阅读 <span>→</span></RouterLink>
                <RouterLink class="button secondary" to="/about">关于这里</RouterLink>
              </div>
            </div>
            <a class="hero-scroll" href="#home-content" aria-label="向下浏览"><span>SCROLL TO DISCOVER</span><i /></a>
          </div>
        </section>

        <div id="home-content" class="home-content">
        <p v-if="contentError && !posts.length" class="content-unavailable section-wrap" role="alert">内容服务暂时不可用，请稍后刷新重试。</p>
        <section v-if="featuredPost" id="featured-story" class="featured section-wrap band-featured">
          <div class="section-heading"><p><span>01</span> 本期精选</p><RouterLink to="/articles">浏览全部 ↗</RouterLink></div>
          <article class="featured-card">
            <div class="featured-visual" :style="{ '--post-color': featuredPost.color }">
              <span>{{ featuredPost.number }}</span><i /><small>FEATURED<br>STORY</small>
            </div>
            <div class="featured-content">
              <div class="post-meta"><span>{{ featuredPost.category }}</span><time>{{ featuredPost.date }}</time><span>{{ featuredPost.readTime }} MIN READ</span></div>
              <h2><RouterLink :to="`/articles/${featuredPost.slug}`">{{ featuredPost.title }}</RouterLink></h2>
              <p>{{ featuredPost.excerpt }}</p>
              <div class="tag-row"><span v-for="tag in featuredPost.tags" :key="tag"># {{ tag }}</span></div>
              <RouterLink class="text-link" :to="`/articles/${featuredPost.slug}`">阅读全文 <b>↗</b></RouterLink>
            </div>
          </article>
        </section>

        <section class="latest section-wrap band-latest">
          <div class="section-heading"><p><span>02</span> 最近更新</p><span class="heading-note">NOTES FROM THE PROCESS</span></div>
          <div class="post-grid">
            <article v-for="post in posts.slice(1, 4)" :key="post.slug" class="post-card">
              <div class="mini-cover" :style="{ '--post-color': post.color }"><b>{{ post.number }}</b><i /></div>
              <div class="post-meta"><span>{{ post.category }}</span><time>{{ post.date.slice(5).replace('-', '.') }}</time></div>
              <h3><RouterLink :to="`/articles/${post.slug}`">{{ post.title }}</RouterLink></h3>
              <p>{{ post.excerpt }}</p>
              <RouterLink :to="`/articles/${post.slug}`" :aria-label="`阅读${post.title}`">阅读 ↗</RouterLink>
            </article>
          </div>
        </section>

        <section class="manifesto section-wrap">
          <p class="eyebrow"><span /> PERSONAL MANIFESTO</p>
          <blockquote>“写作不是为了显得知道很多，<br>而是为了把一件事<strong>想得更清楚</strong>。”</blockquote>
          <div><span>保持好奇</span><span>持续构建</span><span>诚实记录</span></div>
        </section>
        </div>
      </template>

      <template v-else-if="route.name === 'articles'">
        <section class="page-hero section-wrap compact-hero">
          <p class="eyebrow"><span /> ARCHIVE / 文章归档</p>
          <h1>所有文章，<br><em>按自己的节奏阅读。</em></h1>
          <p>共 {{ posts.length }} 篇长期笔记，关于工程、设计与日常。</p>
        </section>
        <section class="archive section-wrap">
          <p v-if="contentError" class="content-unavailable" role="alert">文章服务暂时不可用，请确认后端已启动后重试。</p>
          <div class="archive-tools">
            <label class="search-field"><span>搜索</span><input v-model="query" type="search" placeholder="标题、标签或关键词…"></label>
            <div class="category-tabs" aria-label="文章分类">
              <button v-for="item in categories" :key="item" type="button" :class="{ active: category === item }" @click="category = item">{{ item }}</button>
            </div>
            <label class="sort-field">排序<select v-model="sortOrder"><option value="newest">最新优先</option><option value="oldest">最早优先</option></select></label>
          </div>
          <p class="result-count">{{ filteredPosts.length.toString().padStart(2, '0') }} RESULTS · PAGE {{ Math.min(archivePage + 1, archiveTotalPages).toString().padStart(2, '0') }}/{{ archiveTotalPages.toString().padStart(2, '0') }}</p>
          <div class="archive-list">
            <article v-for="post in pagedPosts" :key="post.slug" class="project-card article-project-card" :style="{ '--project-color': post.color }">
              <div class="project-number">{{ post.number }}</div>
              <div class="article-project-copy">
                <div class="project-meta"><span>{{ post.date }}</span><span>{{ post.category }}</span><span>{{ post.readTime }} MIN READ</span></div>
                <h2><RouterLink :to="`/articles/${post.slug}`">{{ post.title }}</RouterLink></h2>
                <p>{{ post.excerpt }}</p>
                <div class="tag-row"><span v-for="tag in post.tags" :key="tag"># {{ tag }}</span></div>
                <div class="article-project-actions">
                  <RouterLink class="text-link" :to="`/articles/${post.slug}`">阅读全文 <b>↗</b></RouterLink>
                  <button class="save-button" type="button" :class="{ saved: favorites.includes(post.slug) }" :aria-label="favorites.includes(post.slug) ? '移出稍后阅读' : '加入稍后阅读'" @click="toggleFavorite(post.slug)">{{ favorites.includes(post.slug) ? '★' : '☆' }}</button>
                </div>
              </div>
              <RouterLink class="project-mark article-project-mark" :to="`/articles/${post.slug}`" :aria-label="`阅读${post.title}`">
                <i /><span>{{ post.category }}<br>{{ post.readTime }} MIN READ</span><b>READ ↗</b>
              </RouterLink>
            </article>
            <div v-if="!filteredPosts.length" class="empty-state"><b>没有找到文章</b><p>换一个关键词，或者查看全部分类。</p><button type="button" @click="query = ''; category = '全部'">清除筛选</button></div>
          </div>
          <nav v-if="filteredPosts.length && archiveTotalPages > 1" class="pagination" aria-label="文章分页">
            <button type="button" :disabled="archivePage <= 0" @click="archivePage -= 1">上一页</button>
            <span>{{ archivePage + 1 }} / {{ archiveTotalPages }}</span>
            <button type="button" :disabled="archivePage >= archiveTotalPages - 1" @click="archivePage += 1">下一页</button>
          </nav>
        </section>
      </template>

      <template v-else-if="route.name === 'article' && currentPost">
        <article class="article-page">
          <header class="article-header section-wrap">
            <RouterLink class="back-link" to="/articles">← 返回文章</RouterLink>
            <div class="post-meta"><span>{{ currentPost.category }}</span><time>{{ currentPost.date }}</time><span>{{ currentPost.readTime }} MIN READ</span></div>
            <h1>{{ currentPost.title }}</h1>
            <p>{{ currentPost.excerpt }}</p>
            <div class="article-header-actions"><div class="tag-row"><span v-for="tag in currentPost.tags" :key="tag"># {{ tag }}</span></div><button class="button secondary" type="button" @click="toggleFavorite(currentPost.slug)">{{ favorites.includes(currentPost.slug) ? '★ 已收藏' : '☆ 稍后阅读' }}</button></div>
          </header>
          <div class="article-cover section-wrap" :style="{ '--post-color': currentPost.color }"><b>{{ currentPost.number }}</b><span>YUBAI / FIELD NOTE</span><i /></div>
          <div class="article-layout section-wrap">
            <aside class="article-aside">
              <div v-if="articleOutline.length" class="article-toc"><p>文章目录</p><a v-for="item in articleOutline" :key="item.id" :href="`#${item.id}`">{{ item.title }}</a></div>
              <div class="article-share"><p>分享文章</p><button type="button" @click="copyCurrentLink">复制链接</button><RouterLink to="/about">关于作者</RouterLink></div>
            </aside>
            <div class="article-body" v-html="currentPost.content" />
          </div>
          <section v-if="relatedPosts.length" class="related section-wrap"><div class="section-heading"><p><span>+</span> 继续阅读</p></div><div class="related-grid"><RouterLink v-for="post in relatedPosts" :key="post.slug" :to="`/articles/${post.slug}`"><small>{{ post.category }} · {{ post.readTime }} 分钟</small><strong>{{ post.title }}</strong><span>阅读全文 ↗</span></RouterLink></div></section>
        </article>
      </template>

      <template v-else-if="route.name === 'article'">
        <section class="page-hero section-wrap compact-hero">
          <p class="eyebrow"><span /> NOT FOUND</p>
          <h1>{{ contentReady ? '这篇文章不存在，' : '正在加载文章，' }}<br><em>{{ contentReady ? '或者已经被归档。' : '请稍候…' }}</em></h1>
          <p v-if="contentReady">链接可能已失效，回到归档继续浏览其他内容。</p>
          <RouterLink v-if="contentReady" class="button primary" to="/articles">返回文章归档 ↗</RouterLink>
        </section>
      </template>

      <template v-else-if="route.name === 'notes'">
        <PublicNotes />
      </template>

      <template v-else-if="route.name === 'recipes'">
        <FoodSection />
      </template>

      <template v-else-if="route.name === 'about'">
        <section class="about-hero section-wrap">
          <div><p class="eyebrow"><span /> HELLO / 关于我</p><h1>你好，我是一个<br><em>喜欢把事情想清楚的构建者。</em></h1><p>目前生活在上海，关注前端工程、产品设计与写作。我喜欢把复杂问题拆开，也喜欢在周末带着相机漫无目的地走路。</p><p>这个博客是我的数字花园：没有固定更新频率，但每篇文章都希望经得起再次阅读。</p><a class="button primary" href="mailto:hello@yubai.dev">和我聊聊 ↗</a></div>
          <div class="about-card"><span class="about-stamp">余</span><blockquote>“保持好奇，<br>也保持一点必要的笨拙。”</blockquote><dl><div><dt>正在做</dt><dd>独立产品 / 写作</dd></div><div><dt>常用工具</dt><dd>Vue / TypeScript / Figma</dd></div><div><dt>离线时</dt><dd>摄影 / 咖啡 / 散步</dd></div></dl></div>
        </section>
        <section class="values section-wrap"><div class="section-heading"><p><span>03</span> 我在意的事</p></div><div><article><b>01</b><h3>清晰胜过聪明</h3><p>让别人容易理解，比展示技巧更重要。</p></article><article><b>02</b><h3>长期胜过热闹</h3><p>选择可以积累、可以复利的工作。</p></article><article><b>03</b><h3>作品胜过观点</h3><p>用可被使用的东西参与讨论。</p></article></div></section>
      </template>
    </main>

    <footer v-if="!String(route.name).startsWith('admin')" class="site-footer section-wrap"><div class="footer-brand"><span class="brand-stamp">余</span><strong>余白</strong><p>BUILD · WRITE · REFLECT</p></div><div><RouterLink to="/articles">文章</RouterLink><RouterLink to="/recipes">美食</RouterLink><RouterLink to="/notes">学习笔记</RouterLink><RouterLink to="/about">关于</RouterLink><RouterLink to="/admin/login">管理</RouterLink></div><p>© 2026 YUBAI<br>MADE WITH CURIOSITY</p><button type="button" @click="scrollToTop">回到顶部 ↑</button></footer>

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

    <div v-if="searchOpen" class="search-overlay" role="dialog" aria-modal="true" aria-label="搜索文章" @click.self="searchOpen = false">
      <div class="search-panel"><div class="search-input-wrap"><span>⌕</span><input id="global-search" v-model="query" type="search" placeholder="搜索文章、标签或关键词…"><button type="button" @click="searchOpen = false">ESC</button></div><p>搜索结果</p><button v-for="post in searchResults" :key="post.slug" class="search-result" type="button" @click="goToResult(post)"><span :style="{ background: post.color }">{{ post.number }}</span><div><small>{{ post.category }} · {{ post.readTime }} 分钟</small><strong>{{ post.title }}</strong></div><b>↗</b></button><div v-if="!searchResults.length" class="search-empty">没有匹配的文章</div></div>
    </div>
    <div class="toast" :class="{ visible: toast }" role="status" aria-live="polite">{{ toast }}</div>
  </div>
</template>
