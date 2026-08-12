<script setup lang="ts">
import axios from 'axios';
import { ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { fetchCategoryDetail, type CategoryDetail } from '../api/content';
import { usePageMeta } from '../composables/usePageMeta';
import { useRequestToken } from '../composables/useRequestToken';
import PaginationNav from '../components/PaginationNav.vue';

const route = useRoute();
const router = useRouter();
const detail = ref<CategoryDetail | null>(null);
const page = ref(0);
const loading = ref(true);
const loadError = ref('');
const requestToken = useRequestToken();
const { apply: applyMeta } = usePageMeta();

function parsePage(raw: unknown): number {
  const value = Number.parseInt(String(raw ?? '1'), 10);
  return Number.isFinite(value) && value > 0 ? value - 1 : 0;
}

async function load() {
  const slug = String(route.params.slug ?? '');
  if (!slug) return;
  const token = requestToken.next();
  loading.value = true;
  loadError.value = '';
  try {
    const result = await fetchCategoryDetail(slug, page.value, 10);
    if (!requestToken.isCurrent(token)) return;
    detail.value = result;
    applyMeta({
      title: result.name,
      description: result.description || `浏览分类“${result.name}”下的公开文章。`,
      canonicalPath: `/categories/${encodeURIComponent(result.slug)}`,
    });
  } catch (cause) {
    if (!requestToken.isCurrent(token)) return;
    detail.value = null;
    loadError.value =
      axios.isAxiosError(cause) && cause.response?.status === 404
        ? '该分类不存在或暂时没有公开文章。'
        : '分类内容加载失败，请稍后重试。';
    if (axios.isAxiosError(cause) && cause.response?.status === 404) {
      applyMeta({ title: '分类不存在', robots: 'noindex, nofollow' });
    }
  } finally {
    if (requestToken.isCurrent(token)) loading.value = false;
  }
}

watch(
  () => [route.params.slug, route.query.page],
  () => {
    page.value = parsePage(route.query.page);
    void load();
  },
  { immediate: true },
);

function go(nextPage: number) {
  void router.push({
    name: 'category-detail',
    params: { slug: String(route.params.slug) },
    query: nextPage > 0 ? { page: String(nextPage + 1) } : {},
  });
}
</script>

<template>
  <section class="category-page section-wrap">
    <RouterLink class="back-link" to="/categories">← 全部分类</RouterLink>
    <header class="category-head">
      <p class="category-kicker"><span>✦</span> CATEGORY / 分类</p>
      <h1>{{ detail?.name || '文章分类' }}</h1>
      <p v-if="detail?.description" class="category-sub">{{ detail.description }}</p>
      <small v-if="detail && !loading">{{ detail.total }} 篇公开文章</small>
    </header>

    <p v-if="loading" role="status">正在加载文章…</p>
    <p v-else-if="loadError" class="category-error" role="alert">{{ loadError }}</p>
    <p v-else-if="!detail?.posts.length" class="category-empty">该分类还没有公开文章。</p>

    <template v-else>
      <div class="category-post-list">
        <RouterLink
          v-for="post in detail.posts"
          :key="post.slug"
          class="category-post-card"
          :to="`/articles/${post.slug}`"
        >
          <span class="post-badge" :style="{ background: post.color }">{{ post.number }}</span>
          <span class="post-main">
            <small>{{ post.date }} · {{ post.readTime }} 分钟</small>
            <strong>{{ post.title }}</strong>
            <span>{{ post.excerpt }}</span>
          </span>
        </RouterLink>
      </div>
      <PaginationNav :page="page" :total-pages="detail.totalPages" aria-label="分类文章分页" @change="go" />
    </template>
  </section>
</template>

<style scoped>
.category-page {
  padding: 48px 0 72px;
}
.back-link {
  color: var(--muted);
  font-size: 13px;
  text-decoration: none;
}
.back-link:hover {
  color: var(--ink);
}
.category-head {
  margin: 20px 0 28px;
}
.category-kicker {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--muted);
  font-size: 12px;
  letter-spacing: 0.2em;
}
.category-kicker span {
  color: var(--accent);
}
.category-head h1 {
  margin: 8px 0 6px;
  color: var(--ink);
  font-size: 30px;
}
.category-head small,
.category-sub,
.category-empty {
  color: var(--muted);
  font-size: 13px;
}
.category-sub {
  margin: 0 0 6px;
}
.category-error {
  color: #b4452c;
}
.category-post-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.category-post-card {
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
.category-post-card:hover,
.category-post-card:focus-visible {
  border-color: var(--accent);
}
.post-badge {
  display: flex;
  width: 44px;
  height: 44px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  border-radius: 12px;
  color: #fff;
  font-size: 13px;
  font-weight: 600;
}
.post-main {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 4px;
}
.post-main small {
  color: var(--muted);
  font-size: 12px;
}
.post-main strong {
  font-size: 16px;
}
.post-main span:last-child {
  overflow: hidden;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.6;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
