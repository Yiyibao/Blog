<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import axios from 'axios'
import { fetchTagPosts } from '../api/content'
import type { PostSummary } from '../data'
import { useRequestToken } from '../composables/useRequestToken'
import { usePageMeta } from '../composables/usePageMeta'
import PaginationNav from '../components/PaginationNav.vue'

/** 5B：标签页——该标签下已发布文章（服务端分页）。 */
const route = useRoute()
const tag = ref('')
const posts = ref<PostSummary[]>([])
const page = ref(0)
const totalPages = ref(1)
const totalElements = ref(0)
const loading = ref(true)
const loadError = ref('')
const detailToken = useRequestToken()
const { apply: applyMeta } = usePageMeta()

async function load() {
  const token = detailToken.next()
  loading.value = true
  loadError.value = ''
  try {
    const result = await fetchTagPosts(tag.value, page.value, 10)
    if (!detailToken.isCurrent(token)) return
    posts.value = result.items
    totalPages.value = result.totalPages
    totalElements.value = result.totalElements
    applyMeta({ title: `#${tag.value}`, description: result.totalElements > 0 ? `浏览标签 #${tag.value} 下的 ${result.totalElements} 篇文章` : undefined, canonicalPath: `/tags/${encodeURIComponent(tag.value)}` })
  } catch (cause) {
    if (!detailToken.isCurrent(token)) return
    posts.value = []
    const is404 = axios.isAxiosError(cause) && cause.response?.status === 404
    loadError.value = is404 ? '该标签下暂无已发布文章。' : '标签内容加载失败，请稍后重试。'
    if (is404) applyMeta({ title: '标签不存在', robots: 'noindex, nofollow' })
  } finally {
    if (detailToken.isCurrent(token)) loading.value = false
  }
}

watch(() => route.params.tag, (raw) => {
  const next = String(raw ?? '')
  if (!next) return
  tag.value = next
  page.value = 0
  void load()
}, { immediate: true })

function go(pageIndex: number) {
  page.value = pageIndex
  void load()
}
</script>

<template>
  <section class="tag-page section-wrap">
    <RouterLink class="back-link" to="/articles">← 全部文章</RouterLink>
    <header class="tag-head">
      <p class="tag-kicker"><span>#</span> TAG</p>
      <h1># {{ tag }}</h1>
      <small v-if="!loading && !loadError">{{ totalElements }} 篇文章</small>
    </header>

    <p v-if="loading" role="status">正在加载…</p>
    <p v-else-if="loadError" class="tag-error" role="alert">{{ loadError }}</p>

    <template v-else>
      <div class="tag-post-list">
        <RouterLink
          v-for="post in posts"
          :key="post.slug"
          class="tag-post-card"
          :to="`/articles/${post.slug}`"
        >
          <span class="post-badge" :style="{ background: post.color }">{{ post.number }}</span>
          <span class="post-main">
            <small>{{ post.category }} · {{ post.date }} · {{ post.readTime }} 分钟</small>
            <strong>{{ post.title }}</strong>
            <p>{{ post.excerpt }}</p>
          </span>
        </RouterLink>
      </div>
      <PaginationNav :page="page" :total-pages="totalPages" aria-label="标签文章分页" @change="go" />
    </template>
  </section>
</template>

<style scoped>
.tag-page { padding: 48px 0 72px; }
.back-link { color: var(--muted); font-size: 13px; text-decoration: none; }
.back-link:hover { color: var(--ink); }
.tag-head { margin: 20px 0 28px; }
.tag-kicker {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--muted);
  font-size: 12px;
  letter-spacing: 0.2em;
}
.tag-kicker span { color: var(--accent); font-size: 15px; }
.tag-head h1 { margin: 8px 0 6px; font-size: 28px; color: var(--ink); }
.tag-head small { color: var(--muted); font-size: 13px; }
.tag-error { color: #b4452c; }
.tag-post-list { display: flex; flex-direction: column; gap: 12px; }
.tag-post-card {
  display: flex;
  gap: 16px;
  padding: 16px 18px;
  border: 1px solid var(--line-strong);
  border-radius: 14px;
  background: var(--surface-solid);
  color: var(--ink);
  text-decoration: none;
  transition: border-color 0.15s ease;
}
.tag-post-card:hover { border-color: var(--accent); }
.post-badge {
  display: flex;
  align-items: center;
  justify-content: center;
  min-width: 44px;
  height: 44px;
  border-radius: 12px;
  color: #fff;
  font-size: 13px;
  font-weight: 600;
}
.post-main { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
.post-main small { color: var(--muted); font-size: 12px; }
.post-main strong { font-size: 16px; }
.post-main p {
  margin: 0;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.6;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
</style>
