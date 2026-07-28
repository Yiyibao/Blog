<script setup lang="ts">
import { onMounted, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { useContentStore } from '../stores/contentStore'
import { useUiStore } from '../stores/uiStore'

const content = useContentStore()
const ui = useUiStore()
const route = useRoute()
const router = useRouter()
const archivePageSize = 6

function displayNumber(index: number) {
  return String(content.archivePage * archivePageSize + index + 1).padStart(2, '0')
}

// NF-5：URL ?page=N（1 起）与 store.archivePage（0 起）双向同步，刷新/分享链接落在同一页。
watch(() => route.query.page, (raw) => {
  const value = Array.isArray(raw) ? raw[0] : raw
  const parsed = Number.parseInt(value ?? '', 10)
  const page = Number.isFinite(parsed) && parsed > 1 ? parsed - 1 : 0
  if (content.archivePage !== page) content.archivePage = page
}, { immediate: true })

watch(() => content.archivePage, (page) => {
  const current = Array.isArray(route.query.page) ? route.query.page[0] : route.query.page
  const next = page > 0 ? String(page + 1) : undefined
  if ((current ?? undefined) === next) return
  const query = { ...route.query }
  if (next) query.page = next
  else delete query.page
  void router.replace({ query })
})

onMounted(() => {
  if (!content.contentReady) content.loadRemoteContent()
  content.initFavorites()
  // URL 带 ?page=N 时，上方 immediate watch 已触发 store 内的翻页加载，避免重复请求
  if (!content.archiveLoading) void content.loadArchive()
})
</script>

<template>
  <section class="page-hero section-wrap compact-hero">
    <p class="eyebrow"><span /> ARCHIVE / 文章归档</p>
    <h1>所有文章，<br><em>按自己的节奏阅读。</em></h1>
    <p>共 {{ content.postTotal }} 篇长期笔记，关于工程、设计与日常。</p>
  </section>
  <section class="archive section-wrap">
    <p v-if="content.contentError" class="content-unavailable" role="alert">文章服务暂时不可用，请确认后端已启动后重试。</p>
    <div class="archive-tools">
      <label class="search-field"><span>搜索</span><input v-model="content.query" type="search" placeholder="标题、标签或关键词…"></label>
      <div class="archive-filter-tabs">
        <div class="category-tabs" aria-label="文章分类">
          <button v-for="item in content.categories" :key="item" type="button" :class="{ active: content.category === item && !content.showFavoritesOnly }" @click="content.showFavoritesOnly = false; content.category = item">{{ item }}</button>
        </div>
        <button class="fav-filter" type="button" :class="{ active: content.showFavoritesOnly }" @click="content.showFavoritesOnly = !content.showFavoritesOnly">★ 收藏 ({{ content.favorites.length }})</button>
      </div>
      <label class="sort-field">排序<select v-model="content.sortOrder"><option value="newest">最新优先</option><option value="oldest">最早优先</option></select></label>
    </div>
    <p class="result-count">{{ content.archiveTotal.toString().padStart(2, '0') }} RESULTS · PAGE {{ Math.min(content.archivePage + 1, content.archiveTotalPages).toString().padStart(2, '0') }}/{{ content.archiveTotalPages.toString().padStart(2, '0') }}</p>
    <div class="archive-list">
      <!-- NF-4：远端数据在途且暂无可展示内容时的骨架占位 -->
      <template v-if="content.archiveLoading && !content.archivePosts.length">
        <div v-for="i in 3" :key="`sk-${i}`" class="archive-skeleton-card" aria-hidden="true" />
      </template>
      <article v-for="(post, index) in content.archivePosts" :key="post.slug" class="project-card article-project-card" :style="{ '--project-color': post.color }">
        <div class="project-number">{{ displayNumber(index) }}</div>
        <div class="article-project-copy">
          <div class="project-meta"><span v-if="post.date">{{ post.date }}</span><span>{{ post.category }}</span><span v-if="post.readTime">{{ post.readTime }} MIN READ</span></div>
          <h2><RouterLink :to="`/articles/${post.slug}`">{{ post.title }}</RouterLink></h2>
          <p>{{ post.excerpt }}</p>
          <div v-if="post.tags.length" class="tag-row"><span v-for="tag in post.tags" :key="tag"># {{ tag }}</span></div>
          <div class="article-project-actions">
            <RouterLink class="text-link" :to="`/articles/${post.slug}`">阅读全文 <b>↗</b></RouterLink>
            <button class="save-button" type="button" :class="{ saved: content.favorites.includes(post.slug) }" :aria-label="content.favorites.includes(post.slug) ? '取消收藏' : '收藏'" @click="content.toggleFavorite(post.slug); ui.showToast(content.favorites.includes(post.slug) ? '已收藏' : '已取消收藏')">{{ content.favorites.includes(post.slug) ? '★' : '☆' }}</button>
          </div>
        </div>
        <RouterLink class="project-mark article-project-mark" :to="`/articles/${post.slug}`" :aria-label="`阅读${post.title}`">
          <i /><span>{{ post.category }}<template v-if="post.readTime"><br>{{ post.readTime }} MIN READ</template></span><b>READ ↗</b>
        </RouterLink>
      </article>
      <div v-if="!content.archiveLoading && !content.archivePosts.length" class="empty-state"><b>没有找到文章</b><p>换一个关键词，或者查看全部分类。</p><button type="button" @click="content.query = ''; content.category = '全部'">清除筛选</button></div>
    </div>
    <!-- 以总页数而非当前页条数决定是否显示分页：搜索+收藏组合下当前页可能被过滤为空，仍需可翻页 -->
    <nav v-if="content.archiveTotalPages > 1" class="pagination" aria-label="文章分页">
      <button type="button" :disabled="content.archivePage <= 0" @click="content.archivePage -= 1">上一页</button>
      <span>{{ content.archivePage + 1 }} / {{ content.archiveTotalPages }}</span>
      <button type="button" :disabled="content.archivePage >= content.archiveTotalPages - 1" @click="content.archivePage += 1">下一页</button>
    </nav>
  </section>
</template>

<style scoped>
/* NF-4：归档加载骨架 */
.archive-skeleton-card {
  height: 168px;
  border-radius: 24px;
  background: linear-gradient(100deg, var(--surface) 40%, color-mix(in srgb, var(--ink) 6%, var(--surface)) 50%, var(--surface) 60%);
  background-size: 200% 100%;
  animation: archive-skeleton-shimmer 1.4s ease-in-out infinite;
}
@keyframes archive-skeleton-shimmer {
  from { background-position: 200% 0; }
  to { background-position: -200% 0; }
}
@media (prefers-reduced-motion: reduce) {
  .archive-skeleton-card { animation: none; }
}
</style>
