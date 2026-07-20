<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { fetchPosts, fetchProjects } from './api/content'
import { posts as seedPosts, projects as seedProjects, type Post, type Project } from './data'

const route = useRoute()
const router = useRouter()
const menuOpen = ref(false)
const searchOpen = ref(false)
const query = ref('')
const category = ref('全部')
const sortOrder = ref<'newest' | 'oldest'>('newest')
const email = ref('')
const toast = ref('')
const isDark = ref(false)
const readingProgress = ref(0)
const favorites = ref<string[]>([])
const posts = ref<Post[]>([...seedPosts])
const projects = ref<Project[]>([...seedProjects])
let toastTimer: number | undefined
let revealObserver: IntersectionObserver | undefined

const categories = computed(() => ['全部', ...new Set(posts.value.map((post) => post.category))])
const currentPost = computed(() => posts.value.find((post) => post.slug === route.params.slug))
const featuredPost = computed(() => posts.value.find((post) => post.featured) ?? posts.value[0])
const filteredPosts = computed(() => {
  const normalized = query.value.trim().toLowerCase()
  return posts.value
    .filter((post) => category.value === '全部' || post.category === category.value)
    .filter((post) => !normalized || [post.title, post.excerpt, post.category, ...post.tags].join(' ').toLowerCase().includes(normalized))
    .sort((a, b) => sortOrder.value === 'newest' ? b.date.localeCompare(a.date) : a.date.localeCompare(b.date))
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

async function loadRemoteContent() {
  try {
    const [remotePosts, remoteProjects] = await Promise.all([fetchPosts(), fetchProjects()])
    if (remotePosts.length) posts.value = remotePosts
    if (remoteProjects.length) projects.value = remoteProjects
  } catch (error) {
    console.info('Backend API is unavailable; using bundled content.', error)
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

function subscribe() {
  if (!/^\S+@\S+\.\S+$/.test(email.value)) {
    showToast('请输入有效的邮箱地址')
    return
  }
  showToast('订阅成功，下一封月报见！')
  email.value = ''
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
  const article = document.querySelector('.article-body')
  if (!article) {
    readingProgress.value = 0
    return
  }
  const rect = article.getBoundingClientRect()
  const distance = Math.max(1, article.clientHeight - window.innerHeight * 0.35)
  readingProgress.value = Math.min(100, Math.max(0, (-rect.top + 130) / distance * 100))
}

function setupReveals() {
  revealObserver?.disconnect()
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return
  revealObserver = new IntersectionObserver((entries) => {
    entries.forEach((entry) => {
      if (!entry.isIntersecting) return
      entry.target.classList.add('is-visible')
      revealObserver?.unobserve(entry.target)
    })
  }, { threshold: 0.08, rootMargin: '0px 0px -40px' })

  document.querySelectorAll('main section, .post-card, .archive-row, .project-card, .related-grid > a')
    .forEach((element) => {
      element.classList.add('reveal-item')
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
watch(() => route.fullPath, () => {
  menuOpen.value = false
  searchOpen.value = false
  const title = currentPost.value?.title ?? ({ home: '首页', articles: '文章', projects: '项目', about: '关于' }[String(route.name)] || '余白')
  document.title = `${title} · 余白`
  void nextTick(setupReveals)
})

onMounted(() => {
  const savedTheme = localStorage.getItem('yubai-theme')
  isDark.value = savedTheme ? savedTheme === 'dark' : window.matchMedia('(prefers-color-scheme: dark)').matches
  try { favorites.value = JSON.parse(localStorage.getItem('yubai-reading-list') ?? '[]') as string[] } catch { favorites.value = [] }
  window.addEventListener('keydown', onKeydown)
  window.addEventListener('scroll', updateProgress, { passive: true })
  void loadRemoteContent()
  void nextTick(setupReveals)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('scroll', updateProgress)
  revealObserver?.disconnect()
  window.clearTimeout(toastTimer)
})
</script>

<template>
  <div class="site-shell" @pointermove="handlePointerMove" @pointerout="handlePointerOut">
    <div class="reading-progress" :style="{ width: `${readingProgress}%` }" aria-hidden="true" />
    <header class="site-header">
      <RouterLink class="brand" to="/" aria-label="余白首页">
        <span class="brand-stamp">余</span>
        <span><strong>余白</strong><small>YUBAI / NOTES</small></span>
      </RouterLink>
      <nav class="desktop-nav" aria-label="主导航">
        <RouterLink to="/">首页</RouterLink>
        <RouterLink to="/articles">文章</RouterLink>
        <RouterLink to="/projects">项目</RouterLink>
        <RouterLink to="/about">关于</RouterLink>
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
      <RouterLink to="/projects">项目 <span>03</span></RouterLink>
      <RouterLink to="/about">关于 <span>04</span></RouterLink>
    </nav>

    <main>
      <template v-if="route.name === 'home'">
        <section class="hero section-wrap">
          <div class="hero-copy">
            <p class="eyebrow"><span /> A TINY STUDIO OF IDEAS</p>
            <h1>在代码与日常之间，<br><em>记录正在发生的思考。</em></h1>
            <p class="hero-intro">这里收藏关于设计、工程与生活方式的长期笔记。不追逐信息的喧闹，只把值得反复阅读的部分留在页面上。</p>
            <div class="hero-actions">
              <RouterLink class="button primary" to="/articles">开始阅读 <span>↗</span></RouterLink>
              <RouterLink class="button secondary" to="/about">认识我</RouterLink>
            </div>
          </div>
          <div class="hero-art" aria-label="第七期，慢一点，想清楚">
            <div class="blue-note">
              <b>07</b>
              <p>CODE × DESIGN × DAILY LIFE<br>MAKE SPACE FOR IDEAS.</p>
              <i class="orange-ring" />
            </div>
            <div class="paper-note"><small>THIS WEEK</small><strong>慢一点，<br>想清楚。</strong></div>
            <span class="lime-dot" />
            <span class="art-caption">PERSONAL STUDIO ✦</span>
          </div>
        </section>

        <div class="ticker" aria-hidden="true"><span>长期主义的个人实验　 ✦　 DESIGN FOR CLARITY　 ✦　 代码也可以有温度　 ✦　 BUILD · WRITE · REFLECT　 ✦</span></div>

        <section class="featured section-wrap">
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

        <section class="latest section-wrap">
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
      </template>

      <template v-else-if="route.name === 'articles'">
        <section class="page-hero section-wrap compact-hero">
          <p class="eyebrow"><span /> ARCHIVE / 文章归档</p>
          <h1>所有文章，<br><em>按自己的节奏阅读。</em></h1>
          <p>共 {{ posts.length }} 篇长期笔记，关于工程、设计与日常。</p>
        </section>
        <section class="archive section-wrap">
          <div class="archive-tools">
            <label class="search-field"><span>搜索</span><input v-model="query" type="search" placeholder="标题、标签或关键词…"></label>
            <div class="category-tabs" aria-label="文章分类">
              <button v-for="item in categories" :key="item" type="button" :class="{ active: category === item }" @click="category = item">{{ item }}</button>
            </div>
            <label class="sort-field">排序<select v-model="sortOrder"><option value="newest">最新优先</option><option value="oldest">最早优先</option></select></label>
          </div>
          <p class="result-count">{{ filteredPosts.length.toString().padStart(2, '0') }} RESULTS</p>
          <div class="archive-list">
            <article v-for="post in filteredPosts" :key="post.slug" class="archive-row">
              <span class="archive-number">{{ post.number }}</span>
              <div><div class="post-meta"><span>{{ post.category }}</span><time>{{ post.date }}</time><span>{{ post.readTime }} 分钟</span></div><h2><RouterLink :to="`/articles/${post.slug}`">{{ post.title }}</RouterLink></h2><p>{{ post.excerpt }}</p><div class="tag-row"><span v-for="tag in post.tags" :key="tag"># {{ tag }}</span></div></div>
              <button class="save-button" type="button" :class="{ saved: favorites.includes(post.slug) }" :aria-label="favorites.includes(post.slug) ? '移出稍后阅读' : '加入稍后阅读'" @click="toggleFavorite(post.slug)">{{ favorites.includes(post.slug) ? '★' : '☆' }}</button>
              <RouterLink class="row-arrow" :to="`/articles/${post.slug}`" :aria-label="`阅读${post.title}`">↗</RouterLink>
            </article>
            <div v-if="!filteredPosts.length" class="empty-state"><b>没有找到文章</b><p>换一个关键词，或者查看全部分类。</p><button type="button" @click="query = ''; category = '全部'">清除筛选</button></div>
          </div>
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
            <aside class="article-aside"><p>分享文章</p><button type="button" @click="copyCurrentLink">复制链接</button><RouterLink to="/about">关于作者</RouterLink></aside>
            <div class="article-body" v-html="currentPost.content" />
          </div>
          <section v-if="relatedPosts.length" class="related section-wrap"><div class="section-heading"><p><span>+</span> 继续阅读</p></div><div class="related-grid"><RouterLink v-for="post in relatedPosts" :key="post.slug" :to="`/articles/${post.slug}`"><small>{{ post.category }} · {{ post.readTime }} 分钟</small><strong>{{ post.title }}</strong><span>阅读全文 ↗</span></RouterLink></div></section>
        </article>
      </template>

      <template v-else-if="route.name === 'projects'">
        <section class="page-hero section-wrap compact-hero"><p class="eyebrow"><span /> SELECTED WORK / 项目</p><h1>把想法做成<br><em>可以被使用的东西。</em></h1><p>一些正在发生或已经完成的独立项目与实验。</p></section>
        <section class="projects section-wrap">
          <article v-for="(project, index) in projects" :key="project.title" class="project-card" :style="{ '--project-color': project.color }">
            <div class="project-number">0{{ index + 1 }}</div><div><div class="project-meta"><span>{{ project.year }}</span><span>{{ project.status }}</span></div><h2>{{ project.title }}</h2><p>{{ project.description }}</p><div class="tag-row"><span v-for="item in project.stack" :key="item">{{ item }}</span></div></div><div class="project-mark"><i /><span>CASE<br>STUDY</span></div>
          </article>
        </section>
      </template>

      <template v-else-if="route.name === 'about'">
        <section class="about-hero section-wrap">
          <div><p class="eyebrow"><span /> HELLO / 关于我</p><h1>你好，我是一个<br><em>喜欢把事情想清楚的构建者。</em></h1><p>目前生活在上海，关注前端工程、产品设计与写作。我喜欢把复杂问题拆开，也喜欢在周末带着相机漫无目的地走路。</p><p>这个博客是我的数字花园：没有固定更新频率，但每篇文章都希望经得起再次阅读。</p><a class="button primary" href="mailto:hello@yubai.dev">和我聊聊 ↗</a></div>
          <div class="about-card"><span class="about-stamp">余</span><blockquote>“保持好奇，<br>也保持一点必要的笨拙。”</blockquote><dl><div><dt>正在做</dt><dd>独立产品 / 写作</dd></div><div><dt>常用工具</dt><dd>Vue / TypeScript / Figma</dd></div><div><dt>离线时</dt><dd>摄影 / 咖啡 / 散步</dd></div></dl></div>
        </section>
        <section class="values section-wrap"><div class="section-heading"><p><span>03</span> 我在意的事</p></div><div><article><b>01</b><h3>清晰胜过聪明</h3><p>让别人容易理解，比展示技巧更重要。</p></article><article><b>02</b><h3>长期胜过热闹</h3><p>选择可以积累、可以复利的工作。</p></article><article><b>03</b><h3>作品胜过观点</h3><p>用可被使用的东西参与讨论。</p></article></div></section>
      </template>
    </main>

    <section class="newsletter section-wrap">
      <div><p class="eyebrow"><span /> MONTHLY LETTER</p><h2>每月一封，<br>把最近想清楚的事发给你。</h2></div>
      <form @submit.prevent="subscribe"><label for="email">你的邮箱</label><div><input id="email" v-model="email" type="email" placeholder="hello@example.com"><button type="submit">订阅月报 ↗</button></div><small>不追踪、不打扰，随时可以离开。</small></form>
    </section>

    <footer class="site-footer section-wrap"><div class="footer-brand"><span class="brand-stamp">余</span><strong>余白</strong><p>BUILD · WRITE · REFLECT</p></div><div><RouterLink to="/articles">文章</RouterLink><RouterLink to="/projects">项目</RouterLink><RouterLink to="/about">关于</RouterLink></div><p>© 2026 YUBAI<br>MADE WITH CURIOSITY</p><button type="button" @click="scrollToTop">回到顶部 ↑</button></footer>

    <div v-if="searchOpen" class="search-overlay" role="dialog" aria-modal="true" aria-label="搜索文章" @click.self="searchOpen = false">
      <div class="search-panel"><div class="search-input-wrap"><span>⌕</span><input id="global-search" v-model="query" type="search" placeholder="搜索文章、标签或关键词…"><button type="button" @click="searchOpen = false">ESC</button></div><p>搜索结果</p><button v-for="post in searchResults" :key="post.slug" class="search-result" type="button" @click="goToResult(post)"><span :style="{ background: post.color }">{{ post.number }}</span><div><small>{{ post.category }} · {{ post.readTime }} 分钟</small><strong>{{ post.title }}</strong></div><b>↗</b></button><div v-if="!searchResults.length" class="search-empty">没有匹配的文章</div></div>
    </div>
    <div class="toast" :class="{ visible: toast }" role="status" aria-live="polite">{{ toast }}</div>
  </div>
</template>
