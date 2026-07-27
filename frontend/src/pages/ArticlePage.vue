<script setup lang="ts">
import { computed, ref, onMounted, onUnmounted, watch, nextTick } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { useContentStore } from '../stores/contentStore'
import { useUiStore } from '../stores/uiStore'
import { createSiteConfig, resolveUrl } from '../config/site'
import { usePageMeta, cleanText } from '../composables/usePageMeta'
import { blogPosting, breadcrumbList, useStructuredData } from '../composables/useStructuredData'
import { sanitizeHtml } from '../utils/sanitizeHtml'
import ControlledMarkdown from '../components/ControlledMarkdown.vue'

const route = useRoute()
const content = useContentStore()

// NF-2：v-html 渲染点前置 DOMPurify 消毒，防存储型 XSS。
// P1-2：正文只来自详情接口（store.currentContent），列表摘要不再携带 content。
const sanitizedContent = computed(() => sanitizeHtml(content.currentContent))
const ui = useUiStore()
const { apply } = usePageMeta()
const { apply: applyLD } = useStructuredData()

// 3D：相邻文章导航（来自公开详情响应，按 (date,id) 序）
// 4B：「本文属于合集 X（n/N）」
const seriesRef = computed(() => content.articleDetail?.series ?? null)

const neighbors = computed(() => ({
  previous: content.articleDetail?.previous ?? null,
  next: content.articleDetail?.next ?? null,
}))

// L-11：返回链接还原来路页码——归档页码存活在 store 中，跨路由仍在
const backToArchive = computed(() =>
  content.archivePage > 0
    ? { path: '/articles', query: { page: String(content.archivePage + 1) } }
    : { path: '/articles' })

const scrollProgress = ref(0)
const activeTocId = ref('')
const lightboxImageUrl = ref<string | null>(null)
let observer: IntersectionObserver | null = null

// NF-8：灯箱可访问性——Esc 关闭、焦点移入关闭钮并困在对话框内、关闭后还原焦点
const lightboxCloseBtn = ref<HTMLButtonElement | null>(null)
let lightboxLastFocused: HTMLElement | null = null

function closeLightbox() {
  lightboxImageUrl.value = null
}

function onLightboxKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') closeLightbox()
  // 对话框内唯一可聚焦元素是关闭钮，Tab 一律拉回，防焦点逃逸到底层页面
  if (event.key === 'Tab') {
    event.preventDefault()
    lightboxCloseBtn.value?.focus()
  }
}

watch(lightboxImageUrl, (url) => {
  if (url) {
    lightboxLastFocused = document.activeElement instanceof HTMLElement ? document.activeElement : null
    window.addEventListener('keydown', onLightboxKeydown)
    void nextTick(() => lightboxCloseBtn.value?.focus())
  } else {
    window.removeEventListener('keydown', onLightboxKeydown)
    lightboxLastFocused?.focus()
    lightboxLastFocused = null
  }
})

function updateScrollProgress() {
  const totalHeight = document.documentElement.scrollHeight - window.innerHeight
  if (totalHeight > 0) {
    scrollProgress.value = Math.min(100, Math.max(0, Math.round((window.scrollY / totalHeight) * 100)))
  } else {
    scrollProgress.value = 0
  }
}

async function copyCurrentLink() {
  await navigator.clipboard.writeText(window.location.href)
  ui.showToast('链接已复制')
}

function initArticleEnhancements() {
  nextTick(() => {
    // 1. ScrollSpy IntersectionObserver
    const headings = document.querySelectorAll('.article-body h1, .article-body h2, .article-body h3')
    if (observer) observer.disconnect()
    observer = new IntersectionObserver((entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) {
          activeTocId.value = entry.target.id
        }
      })
    }, { rootMargin: '-80px 0px -70% 0px' })
    headings.forEach((h) => observer?.observe(h))

    // 2. Image Click Lightbox
    const images = document.querySelectorAll('.article-body img')
    images.forEach((img) => {
      const htmlImg = img as HTMLImageElement
      htmlImg.style.cursor = 'zoom-in'
      htmlImg.onclick = () => {
        lightboxImageUrl.value = htmlImg.src
      }
    })

    // 3. Code Block Copy Buttons
    const codeBlocks = document.querySelectorAll('.article-body pre')
    codeBlocks.forEach((pre) => {
      if (pre.querySelector('.code-copy-btn')) return
      const wrapper = document.createElement('div')
      wrapper.className = 'code-block-wrapper'
      pre.parentNode?.insertBefore(wrapper, pre)
      wrapper.appendChild(pre)

      const btn = document.createElement('button')
      btn.className = 'code-copy-btn'
      btn.type = 'button'
      btn.innerText = '复制'
      btn.onclick = async () => {
        const text = pre.textContent || ''
        await navigator.clipboard.writeText(text)
        btn.innerText = '已复制!'
        ui.showToast('代码已复制到剪贴板')
        setTimeout(() => { btn.innerText = '复制' }, 2000)
      }
      wrapper.appendChild(btn)
    })
  })
}

// 加入 articleDetailLoading 维度：详情在途时不写 404 meta；
// 加载结束仍无文章（currentPost 保持 null 不触发自身变化）时由 loading 翻转驱动本 watcher 补写。
watch([() => content.currentPost, () => content.articleDetailLoading], ([post]) => {
  if (post) {
    const authorName = createSiteConfig().authorName
    applyLD([
      blogPosting({
        headline: post.title,
        description: post.excerpt,
        url: resolveUrl(`/articles/${post.slug}`),
        datePublished: post.date,
        dateModified: post.date,
        authorName: authorName || 'Yubai',
      }),
      breadcrumbList([
        { name: '首页', path: '/' },
        { name: '文章', path: '/articles' },
        { name: post.title, path: `/articles/${post.slug}` },
      ]),
    ])
    apply({
      title: post.title,
      description: cleanText(post.excerpt, 200),
      canonicalPath: `/articles/${post.slug}`,
      openGraph: {
        title: post.title,
        description: cleanText(post.excerpt, 200),
        type: 'article',
        image: post.color ? undefined : '/og.png',
        url: `/articles/${post.slug}`,
      },
      twitter: {
        title: post.title,
        description: cleanText(post.excerpt, 200),
        image: post.color ? undefined : '/og.png',
      },
    })
    initArticleEnhancements()
  } else if (content.contentReady && !content.articleDetailLoading) {
    apply({
      title: '页面不存在',
      description: '文章不存在或已被归档',
      robots: 'noindex, nofollow',
    })
  }
}, { immediate: true })

// NF-3：以 route.params.slug 为响应式输入。文章间跳转（如相关文章）复用本组件，
// onMounted 不会重跑，必须 watch slug 同步 store 并重拉详情。
watch(() => route.params.slug, (raw) => {
  const slug = String(raw ?? '')
  if (!slug) return // 离开本路由时 slug 变空，交由 onUnmounted 清理
  content.setCurrentSlug(slug)
  void content.ensureArticleDetail(slug)
}, { immediate: true })

onMounted(() => {
  if (!content.contentReady) content.loadRemoteContent()
  content.initFavorites()
  window.addEventListener('scroll', updateScrollProgress, { passive: true })
})

onUnmounted(() => {
  content.setCurrentSlug('')
  window.removeEventListener('scroll', updateScrollProgress)
  window.removeEventListener('keydown', onLightboxKeydown)
  if (observer) observer.disconnect()
})
</script>

<template>
  <!-- Reading Progress Bar -->
  <div v-if="content.currentPost" class="reading-progress-bar">
    <div class="reading-progress-fill" :style="{ width: `${scrollProgress}%` }" />
  </div>

  <template v-if="content.currentPost">
    <article class="article-page">
      <header class="article-header section-wrap">
        <RouterLink class="back-link" :to="backToArchive">← 返回文章</RouterLink>
        <!-- 搜索命中映射的摘要缺 date/readTime，空值不渲染对应元信息 -->
        <div class="post-meta"><span>{{ content.currentPost.category }}</span><time v-if="content.currentPost.date">{{ content.currentPost.date }}</time><span v-if="content.currentPost.readTime">{{ content.currentPost.readTime }} MIN READ</span></div>
        <h1>{{ content.currentPost.title }}</h1>
        <p>{{ content.currentPost.excerpt }}</p>
        <div class="article-header-actions"><div class="tag-row"><RouterLink v-for="tag in content.currentPost.tags" :key="tag" class="tag-link" :to="`/tags/${encodeURIComponent(tag)}`"># {{ tag }}</RouterLink></div><button class="button secondary" type="button" @click="content.toggleFavorite(content.currentPost.slug); ui.showToast(content.favorites.includes(content.currentPost.slug) ? '已收藏' : '已取消收藏')">{{ content.favorites.includes(content.currentPost.slug) ? '★ 已收藏' : '☆ 收藏' }}</button></div>
      </header>
      <div class="article-cover section-wrap" :style="{ '--post-color': content.currentPost.color }"><b>{{ content.currentPost.number }}</b><span>YUBAI / FIELD NOTE</span><i /></div>
      <!-- 4B：合集归属条 -->
      <RouterLink v-if="seriesRef" class="article-series-bar section-wrap" :to="`/series/${seriesRef.slug}`">
        <span class="series-icon" aria-hidden="true">≣</span>
        <span>本文属于合集 <strong>{{ seriesRef.name }}</strong>（{{ seriesRef.position }}/{{ seriesRef.total }}）</span>
        <span class="series-go">查看全部 ↗</span>
      </RouterLink>
      <div class="article-layout section-wrap">
        <aside class="article-aside">
          <div v-if="content.articleOutline.length" class="article-toc">
            <p>文章目录</p>
            <a
              v-for="item in content.articleOutline"
              :key="item.id"
              :href="`#${item.id}`"
              :class="{ 'is-active': item.id === activeTocId }"
            >
              {{ item.title }}
            </a>
          </div>
          <div class="article-share"><p>分享文章</p><button type="button" @click="copyCurrentLink">复制链接</button><RouterLink to="/about">关于作者</RouterLink></div>
        </aside>
        <!-- 3A-4：MARKDOWN 篇走受控渲染（Tiptap 只读 + lowlight，与笔记同管线）；HTML 存量篇维持消毒 v-html -->
        <ControlledMarkdown
          v-if="content.currentIsMarkdown"
          class="article-body article-markdown-body"
          :markdown="content.currentMarkdown"
          @rendered="initArticleEnhancements"
        />
        <div v-else class="article-body" v-html="sanitizedContent" />
      </div>
      <!-- 3D：相邻文章导航 -->
      <nav v-if="neighbors.previous || neighbors.next" class="article-neighbors section-wrap" aria-label="相邻文章">
        <RouterLink v-if="neighbors.previous" class="neighbor-link prev" :to="`/articles/${neighbors.previous.slug}`">
          <small>← 上一篇</small><strong>{{ neighbors.previous.title }}</strong>
        </RouterLink>
        <span v-else class="neighbor-spacer" aria-hidden="true" />
        <RouterLink v-if="neighbors.next" class="neighbor-link next" :to="`/articles/${neighbors.next.slug}`">
          <small>下一篇 →</small><strong>{{ neighbors.next.title }}</strong>
        </RouterLink>
      </nav>

      <section v-if="content.relatedPosts.length" class="related section-wrap"><div class="section-heading"><p><span>+</span> 继续阅读</p></div><div class="related-grid"><RouterLink v-for="post in content.relatedPosts" :key="post.slug" :to="`/articles/${post.slug}`"><small>{{ post.category }} · {{ post.readTime }} 分钟</small><strong>{{ post.title }}</strong><span>阅读全文 ↗</span></RouterLink></div></section>
    </article>
  </template>
  <template v-else>
    <!-- 详情请求在途时视为加载中，只有确认拉取结束仍无文章才呈现 404 -->
    <section class="page-hero section-wrap compact-hero">
      <p class="eyebrow"><span /> {{ content.contentReady && !content.articleDetailLoading ? 'NOT FOUND' : 'LOADING' }}</p>
      <h1>{{ content.contentReady && !content.articleDetailLoading ? '这篇文章不存在，' : '正在加载文章，' }}<br><em>{{ content.contentReady && !content.articleDetailLoading ? '或者已经被归档。' : '请稍候…' }}</em></h1>
      <p v-if="content.contentReady && !content.articleDetailLoading">链接可能已失效，回到归档继续浏览其他内容。</p>
      <RouterLink v-if="content.contentReady && !content.articleDetailLoading" class="button primary" :to="backToArchive">返回文章归档 ↗</RouterLink>
    </section>
  </template>

  <!-- Image Lightbox Modal Preview -->
  <Teleport to="body">
    <div
      v-if="lightboxImageUrl"
      class="image-lightbox-overlay"
      role="dialog"
      aria-modal="true"
      aria-label="图片预览"
      @click="closeLightbox"
    >
      <button
        ref="lightboxCloseBtn"
        type="button"
        class="image-lightbox-close"
        aria-label="关闭图片预览"
        @click.stop="closeLightbox"
      >×</button>
      <img :src="lightboxImageUrl" alt="大图预览" class="image-lightbox-img">
    </div>
  </Teleport>
</template>

<style scoped>
/* 3D：相邻文章导航 */
.article-series-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 18px;
  padding: 12px 18px;
  border: 1px solid var(--line-strong);
  border-radius: 14px;
  background: color-mix(in srgb, #ec4899 6%, var(--surface-solid));
  color: var(--ink);
  font-size: 13px;
  text-decoration: none;
}
.article-series-bar:hover { border-color: #ec4899; }
.article-series-bar .series-icon { color: #ec4899; font-size: 16px; }
.article-series-bar strong { color: var(--ink); }
.article-series-bar .series-go { margin-left: auto; color: var(--muted); font-size: 12px; }

.article-neighbors {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  margin-top: 40px;
}
.neighbor-link {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 18px 22px;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: var(--surface);
  color: var(--ink);
  text-decoration: none;
  transition: border-color 0.2s, transform 0.3s;
}
.neighbor-link:hover {
  border-color: var(--accent);
  transform: translateY(-2px);
}
.neighbor-link small { color: var(--muted); font-size: 12px; }
.neighbor-link strong { font-size: 15px; line-height: 1.5; }
.neighbor-link.next { text-align: right; align-items: flex-end; }
@media (max-width: 640px) {
  .article-neighbors { grid-template-columns: 1fr; }
  .neighbor-spacer { display: none; }
}

/* NF-8：灯箱关闭钮（新元素，样式随组件走，避免与并行改动的全局样式表交叉） */
.image-lightbox-close {
  position: fixed;
  top: 24px;
  right: 28px;
  width: 44px;
  height: 44px;
  border: 1px solid rgba(255, 255, 255, 0.4);
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.45);
  color: #fff;
  font-size: 24px;
  line-height: 1;
  cursor: pointer;
  z-index: 2;
}
.image-lightbox-close:hover,
.image-lightbox-close:focus-visible {
  border-color: #fff;
  background: rgba(0, 0, 0, 0.7);
}
</style>
