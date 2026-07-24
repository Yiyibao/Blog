<script setup lang="ts">
import { onMounted } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { useContentStore } from '../stores/contentStore'
import { useUiStore } from '../stores/uiStore'

const route = useRoute()
const content = useContentStore()
const ui = useUiStore()

async function copyCurrentLink() {
  await navigator.clipboard.writeText(window.location.href)
  ui.showToast('链接已复制')
}

onMounted(() => {
  if (!content.contentReady) content.loadRemoteContent()
  content.initFavorites()
  const slug = String(route.params.slug || '')
  content.ensureArticleDetail(slug)
})
</script>

<template>
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
          <div v-if="content.articleOutline.length" class="article-toc"><p>文章目录</p><a v-for="item in content.articleOutline" :key="item.id" :href="`#${item.id}`">{{ item.title }}</a></div>
          <div class="article-share"><p>分享文章</p><button type="button" @click="copyCurrentLink">复制链接</button><RouterLink to="/about">关于作者</RouterLink></div>
        </aside>
        <div class="article-body" v-html="content.currentPost.content" />
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
</template>
