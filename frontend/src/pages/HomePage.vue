<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { useContentStore } from '../stores/contentStore'
import heroBackgroundUrl from '../assets/hero-sakura-lake.jpg'

const content = useContentStore()
const heroProgress = ref(0)
let scrollFrame: number | undefined

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

function updateHeroProgress() {
  const heroStage = document.querySelector<HTMLElement>('.hero-stage')
  if (heroStage) {
    const heroRect = heroStage.getBoundingClientRect()
    const headerHeight = document.querySelector<HTMLElement>('.site-header')?.offsetHeight ?? 0
    const heroDistance = Math.max(1, window.innerHeight * 1.15)
    heroProgress.value = Math.min(1, Math.max(0, (headerHeight - heroRect.top) / heroDistance))
  } else {
    heroProgress.value = 0
  }
}

function scheduleProgress() {
  if (scrollFrame !== undefined) return
  scrollFrame = window.requestAnimationFrame(() => {
    scrollFrame = undefined
    updateHeroProgress()
  })
}

onMounted(() => {
  void content.loadRemoteContent()
  updateHeroProgress()
  window.addEventListener('scroll', scheduleProgress, { passive: true })
  void nextTick(updateHeroProgress)
})

onBeforeUnmount(() => {
  window.removeEventListener('scroll', scheduleProgress)
  if (scrollFrame !== undefined) window.cancelAnimationFrame(scrollFrame)
})
</script>

<template>
  <section class="hero-stage">
    <div class="hero" :style="heroStyle">
      <img class="hero-background" :src="heroBackgroundUrl" alt="" fetchpriority="high" decoding="async">
      <div class="hero-veil" aria-hidden="true" />
      <div class="hero-copy">
        <p class="hero-kana hero-enter" style="--enter-delay: 0ms">拾取日常里一闪而过的光。</p>
        <p class="eyebrow hero-enter" style="--enter-delay: 40ms"><span /> HXNF'S MEMOIR</p>
        <h1 class="hero-enter" style="--enter-delay: 80ms">把日常散落的微光<span class="hero-punctuation">，</span><br><em>写成可以重读的记忆<span class="hero-punctuation">。</span></em></h1>
        <p class="hero-intro hero-enter" style="--enter-delay: 160ms">拾起代码、阅读、料理与日常生活里的微光，记录思考，也珍藏时间。</p>
        <div class="hero-actions hero-enter" style="--enter-delay: 220ms">
          <RouterLink class="button primary" to="/articles">开始阅读 <span>→</span></RouterLink>
        </div>
      </div>
      <a class="hero-scroll" href="#home-content" aria-label="向下浏览"><span>SCROLL TO DISCOVER</span><i /></a>
    </div>
  </section>

  <div id="home-content" class="home-content">
    <p v-if="content.contentError && !content.posts.length" class="content-unavailable section-wrap" role="alert">内容服务暂时不可用，请稍后刷新重试。</p>
    <p v-else-if="content.contentReady && !content.posts.length" class="content-unavailable section-wrap">这里还没有发布内容，敬请期待。</p>

    <!-- NF-4：生产环境无内置种子——首个响应到达前用骨架占位，避免布局跳动 -->
    <section v-if="!content.contentReady && !content.posts.length" class="featured section-wrap" aria-hidden="true">
      <div class="skeleton-block skeleton-hero" />
      <div class="skeleton-row">
        <div v-for="i in 3" :key="i" class="skeleton-block skeleton-card" />
      </div>
    </section>

    <section v-if="content.featuredPost" id="featured-story" class="featured section-wrap band-featured">
      <div class="section-heading"><p><span>01</span> 本期精选</p><RouterLink to="/articles">浏览全部 ↗</RouterLink></div>
      <article class="featured-card">
        <div class="featured-visual" :style="{ '--post-color': content.featuredPost.color }">
          <span>{{ content.featuredPost.number }}</span><i /><small>FEATURED<br>STORY</small>
        </div>
        <div class="featured-content">
          <div class="post-meta"><span>{{ content.featuredPost.category }}</span><time>{{ content.featuredPost.date }}</time><span>{{ content.featuredPost.readTime }} MIN READ</span></div>
          <h2><RouterLink :to="`/articles/${content.featuredPost.slug}`">{{ content.featuredPost.title }}</RouterLink></h2>
          <p>{{ content.featuredPost.excerpt }}</p>
          <div class="tag-row"><span v-for="tag in content.featuredPost.tags" :key="tag"># {{ tag }}</span></div>
          <RouterLink class="text-link" :to="`/articles/${content.featuredPost.slug}`">阅读全文 <b>↗</b></RouterLink>
        </div>
      </article>
    </section>

    <section class="latest section-wrap band-latest">
      <div class="section-heading"><p><span>02</span> 最近更新</p><span class="heading-note">NOTES FROM THE PROCESS</span></div>
      <div class="post-grid">
        <article v-for="post in content.posts.slice(1, 4)" :key="post.slug" class="post-card">
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
      <blockquote>"写作不是为了显得知道很多，<br>而是为了把一件事<strong>想得更清楚</strong>。"</blockquote>
      <div><span>保持好奇</span><span>持续构建</span><span>诚实记录</span></div>
    </section>
  </div>
</template>

<style scoped>
/* NF-4：加载骨架（仅生产空态可见，样式随组件走） */
.skeleton-block {
  border-radius: 24px;
  background: linear-gradient(100deg, var(--surface) 40%, color-mix(in srgb, var(--ink) 6%, var(--surface)) 50%, var(--surface) 60%);
  background-size: 200% 100%;
  animation: skeleton-shimmer 1.4s ease-in-out infinite;
}
.skeleton-hero { height: 320px; margin-bottom: 28px; }
.skeleton-row { display: grid; grid-template-columns: repeat(3, 1fr); gap: 24px; }
.skeleton-card { height: 180px; }
@keyframes skeleton-shimmer {
  from { background-position: 200% 0; }
  to { background-position: -200% 0; }
}
@media (max-width: 760px) {
  .skeleton-row { grid-template-columns: 1fr; }
}
@media (prefers-reduced-motion: reduce) {
  .skeleton-block { animation: none; }
}
</style>
