<script setup lang="ts">
import { ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import axios from 'axios';
import { fetchSeriesDetail, type SeriesDetail } from '../api/content';
import { useRequestToken } from '../composables/useRequestToken';
import { usePageMeta } from '../composables/usePageMeta';

/** 4B：合集详情——按序成员列表，逐篇阅读。 */
const route = useRoute();
const detail = ref<SeriesDetail | null>(null);
const loading = ref(true);
const loadError = ref('');
const detailToken = useRequestToken();
const { apply: applyMeta } = usePageMeta();

watch(
  () => route.params.slug,
  async (raw) => {
    const slug = String(raw ?? '');
    if (!slug) return;
    const token = detailToken.next();
    loading.value = true;
    loadError.value = '';
    try {
      const result = await fetchSeriesDetail(slug);
      if (!detailToken.isCurrent(token)) return;
      detail.value = result;
      applyMeta({
        title: result.name,
        description: result.description || undefined,
        canonicalPath: `/series/${slug}`,
      });
    } catch (cause) {
      if (!detailToken.isCurrent(token)) return;
      detail.value = null;
      const is404 = axios.isAxiosError(cause) && cause.response?.status === 404;
      loadError.value = is404 ? '合集不存在或尚未发布。' : '合集加载失败，请稍后重试。';
      if (is404) applyMeta({ title: '合集不存在', robots: 'noindex, nofollow' });
    } finally {
      if (detailToken.isCurrent(token)) loading.value = false;
    }
  },
  { immediate: true },
);
</script>

<template>
  <section class="series-detail section-wrap">
    <RouterLink class="back-link" to="/series">← 全部合集</RouterLink>

    <p v-if="loading" role="status">正在加载…</p>
    <p v-else-if="loadError" class="series-error" role="alert">{{ loadError }}</p>

    <template v-else-if="detail">
      <header class="detail-head">
        <p class="series-kicker"><span>≣</span> SERIES / 合集</p>
        <h1>{{ detail.name }}</h1>
        <p v-if="detail.description" class="detail-desc">{{ detail.description }}</p>
        <small>{{ detail.entries.length }} 篇 · 按序阅读</small>
      </header>

      <p v-if="!detail.entries.length" class="series-empty">合集内文章尚未发布。</p>
      <ol v-else class="entry-list">
        <li v-for="entry in detail.entries" :key="entry.postId">
          <RouterLink class="entry-link" :to="`/articles/${entry.slug}`">
            <span class="entry-pos">{{ String(entry.position).padStart(2, '0') }}</span>
            <span class="entry-main">
              <small v-if="entry.chapterTitle">{{ entry.chapterTitle }}</small>
              <strong>{{ entry.title }}</strong>
            </span>
            <time v-if="entry.date">{{ entry.date }}</time>
          </RouterLink>
        </li>
      </ol>
    </template>
  </section>
</template>

<style scoped>
.series-detail {
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
.series-error {
  color: #b4452c;
}
.series-empty {
  color: var(--muted);
}
.series-kicker {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--muted);
  font-size: 12px;
  letter-spacing: 0.2em;
}
.series-kicker span {
  color: #ec4899;
  font-size: 15px;
}
.detail-head {
  margin: 20px 0 28px;
}
.detail-head h1 {
  margin: 8px 0 8px;
  font-size: 30px;
  color: var(--ink);
}
.detail-desc {
  color: var(--muted);
  font-size: 14px;
  line-height: 1.7;
  max-width: 640px;
}
.detail-head small {
  display: block;
  margin-top: 8px;
  color: var(--muted);
  font-size: 12px;
}
.entry-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.entry-link {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 14px 18px;
  border: 1px solid var(--line-strong);
  border-radius: 14px;
  background: var(--surface-solid);
  color: var(--ink);
  text-decoration: none;
  transition: border-color 0.15s ease;
}
.entry-link:hover {
  border-color: #ec4899;
}
.entry-pos {
  font-family: ui-monospace, monospace;
  font-size: 15px;
  color: #ec4899;
  min-width: 32px;
}
.entry-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex: 1;
}
.entry-main small {
  color: var(--muted);
  font-size: 12px;
}
.entry-main strong {
  font-size: 15px;
}
.entry-link time {
  color: var(--muted);
  font-size: 12px;
}
</style>
