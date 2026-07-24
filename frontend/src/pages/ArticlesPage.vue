<script setup lang="ts">
import { onMounted } from 'vue'
import { RouterLink } from 'vue-router'
import { useContentStore } from '../stores/contentStore'
import { useUiStore } from '../stores/uiStore'

const content = useContentStore()
const ui = useUiStore()

onMounted(() => {
  if (!content.contentReady) content.loadRemoteContent()
  content.initFavorites()
})
</script>

<template>
  <section class="page-hero section-wrap compact-hero">
    <p class="eyebrow"><span /> ARCHIVE / 文章归档</p>
    <h1>所有文章，<br><em>按自己的节奏阅读。</em></h1>
    <p>共 {{ content.posts.length }} 篇长期笔记，关于工程、设计与日常。</p>
  </section>
  <section class="archive section-wrap">
    <p v-if="content.contentError" class="content-unavailable" role="alert">文章服务暂时不可用，请确认后端已启动后重试。</p>
    <div class="archive-tools">
      <label class="search-field"><span>搜索</span><input v-model="content.query" type="search" placeholder="标题、标签或关键词…"></label>
      <div class="category-tabs" aria-label="文章分类">
        <button v-for="item in content.categories" :key="item" type="button" :class="{ active: content.category === item }" @click="content.category = item">{{ item }}</button>
      </div>
      <label class="sort-field">排序<select v-model="content.sortOrder"><option value="newest">最新优先</option><option value="oldest">最早优先</option></select></label>
    </div>
    <p class="result-count">{{ content.filteredPosts.length.toString().padStart(2, '0') }} RESULTS · PAGE {{ Math.min(content.archivePage + 1, content.archiveTotalPages).toString().padStart(2, '0') }}/{{ content.archiveTotalPages.toString().padStart(2, '0') }}</p>
    <div class="archive-list">
      <article v-for="post in content.pagedPosts" :key="post.slug" class="project-card article-project-card" :style="{ '--project-color': post.color }">
        <div class="project-number">{{ post.number }}</div>
        <div class="article-project-copy">
          <div class="project-meta"><span>{{ post.date }}</span><span>{{ post.category }}</span><span>{{ post.readTime }} MIN READ</span></div>
          <h2><RouterLink :to="`/articles/${post.slug}`">{{ post.title }}</RouterLink></h2>
          <p>{{ post.excerpt }}</p>
          <div class="tag-row"><span v-for="tag in post.tags" :key="tag"># {{ tag }}</span></div>
          <div class="article-project-actions">
            <RouterLink class="text-link" :to="`/articles/${post.slug}`">阅读全文 <b>↗</b></RouterLink>
            <button class="save-button" type="button" :class="{ saved: content.favorites.includes(post.slug) }" :aria-label="content.favorites.includes(post.slug) ? '移出稍后阅读' : '加入稍后阅读'" @click="content.toggleFavorite(post.slug); ui.showToast(content.favorites.includes(post.slug) ? '已加入稍后阅读' : '已从阅读清单移除')">{{ content.favorites.includes(post.slug) ? '★' : '☆' }}</button>
          </div>
        </div>
        <RouterLink class="project-mark article-project-mark" :to="`/articles/${post.slug}`" :aria-label="`阅读${post.title}`">
          <i /><span>{{ post.category }}<br>{{ post.readTime }} MIN READ</span><b>READ ↗</b>
        </RouterLink>
      </article>
      <div v-if="!content.filteredPosts.length" class="empty-state"><b>没有找到文章</b><p>换一个关键词，或者查看全部分类。</p><button type="button" @click="content.query = ''; content.category = '全部'">清除筛选</button></div>
    </div>
    <nav v-if="content.filteredPosts.length && content.archiveTotalPages > 1" class="pagination" aria-label="文章分页">
      <button type="button" :disabled="content.archivePage <= 0" @click="content.archivePage -= 1">上一页</button>
      <span>{{ content.archivePage + 1 }} / {{ content.archiveTotalPages }}</span>
      <button type="button" :disabled="content.archivePage >= content.archiveTotalPages - 1" @click="content.archivePage += 1">下一页</button>
    </nav>
  </section>
</template>
