<script setup lang="ts">
import { computed, ref, onMounted, onUnmounted, watch, nextTick } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { useContentStore } from '../stores/contentStore'
import { useUiStore } from '../stores/uiStore'
import { createSiteConfig, resolveUrl } from '../config/site'
import { usePageMeta, cleanText } from '../composables/usePageMeta'
import { blogPosting, breadcrumbList, useStructuredData } from '../composables/useStructuredData'
import { sanitizeHtml } from '../utils/sanitizeHtml'

const route = useRoute()
const content = useContentStore()

// NF-2：v-html 渲染点前置 DOMPurify 消毒，防存储型 XSS。
const sanitizedContent = computed(() => sanitizeHtml(content.currentPost?.content ?? ''))
const ui = useUiStore()
const { apply } = usePageMeta()
const { apply: applyLD } = useStructuredData()

const scrollProgress = ref(0)
const activeTocId = ref('')
const lightboxImageUrl = ref<string | null>(null)
let observer: IntersectionObserver | null = null

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

watch(() => content.currentPost, (post) => {
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
  } else if (content.contentReady) {
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
        <RouterLink class="back-link" to="/articles">← 返回文章</RouterLink>
        <div class="post-meta"><span>{{ content.currentPost.category }}</span><time>{{ content.currentPost.date }}</time><span>{{ content.currentPost.readTime }} MIN READ</span></div>
        <h1>{{ content.currentPost.title }}</h1>
        <p>{{ content.currentPost.excerpt }}</p>
        <div class="article-header-actions"><div class="tag-row"><span v-for="tag in content.currentPost.tags" :key="tag"># {{ tag }}</span></div><button class="button secondary" type="button" @click="content.toggleFavorite(content.currentPost.slug); ui.showToast(content.favorites.includes(content.currentPost.slug) ? '已收藏' : '已取消收藏')">{{ content.favorites.includes(content.currentPost.slug) ? '★ 已收藏' : '☆ 收藏' }}</button></div>
      </header>
      <div class="article-cover section-wrap" :style="{ '--post-color': content.currentPost.color }"><b>{{ content.currentPost.number }}</b><span>YUBAI / FIELD NOTE</span><i /></div>
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
        <div class="article-body" v-html="sanitizedContent" />
      </div>
      <section v-if="content.relatedPosts.length" class="related section-wrap"><div class="section-heading"><p><span>+</span> 继续阅读</p></div><div class="related-grid"><RouterLink v-for="post in content.relatedPosts" :key="post.slug" :to="`/articles/${post.slug}`"><small>{{ post.category }} · {{ post.readTime }} 分钟</small><strong>{{ post.title }}</strong><span>阅读全文 ↗</span></RouterLink></div></section>
    </article>
  </template>
  <template v-else>
    <section class="page-hero section-wrap compact-hero">
      <p class="eyebrow"><span /> NOT FOUND</p>
      <h1>{{ content.contentReady ? '这篇文章不存在，' : '正在加载文章，' }}<br><em>{{ content.contentReady ? '或者已经被归档。' : '请稍候…' }}</em></h1>
      <p v-if="content.contentReady">链接可能已失效，回到归档继续浏览其他内容。</p>
      <RouterLink v-if="content.contentReady" class="button primary" to="/articles">返回文章归档 ↗</RouterLink>
    </section>
  </template>

  <!-- Image Lightbox Modal Preview -->
  <Teleport to="body">
    <div
      v-if="lightboxImageUrl"
      class="image-lightbox-overlay"
      @click="lightboxImageUrl = null"
      @keydown.esc="lightboxImageUrl = null"
    >
      <img :src="lightboxImageUrl" alt="大图预览" class="image-lightbox-img">
    </div>
  </Teleport>
</template>
