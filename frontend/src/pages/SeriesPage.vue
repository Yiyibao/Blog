<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { fetchSeriesList, type SeriesSummary } from '../api/content';

/** 4B：合集列表——文章按主题成串的公开入口。 */
const seriesList = ref<SeriesSummary[]>([]);
const loading = ref(true);
const loadError = ref('');

onMounted(async () => {
  try {
    seriesList.value = await fetchSeriesList();
  } catch {
    loadError.value = '合集加载失败，请稍后重试。';
  } finally {
    loading.value = false;
  }
});

function formatDate(iso: string | null): string {
  if (!iso) return '';
  return iso.slice(0, 10);
}
</script>

<template>
  <section class="series-page section-wrap">
    <header class="series-head">
      <p class="series-kicker"><span>≣</span> SERIES / 合集</p>
      <h1>文章合集</h1>
      <p class="series-sub">按主题成串的系列文章，按序阅读效果更佳。</p>
    </header>

    <p v-if="loading" role="status">正在加载…</p>
    <p v-else-if="loadError" class="series-error" role="alert">{{ loadError }}</p>
    <p v-else-if="!seriesList.length" class="series-empty">还没有公开的合集，敬请期待。</p>

    <div v-else class="series-grid">
      <RouterLink
        v-for="series in seriesList"
        :key="series.slug"
        class="series-card"
        :to="`/series/${series.slug}`"
      >
        <div
          v-if="series.coverImage"
          class="series-cover"
          :style="{ backgroundImage: `url(${series.coverImage})` }"
        />
        <div v-else class="series-cover placeholder"><span>≣</span></div>
        <div class="series-card-body">
          <strong>{{ series.name }}</strong>
          <p>{{ series.description || '——' }}</p>
          <footer>
            <span>{{ series.entryCount }} 篇</span>
            <time v-if="series.publishedAt">{{ formatDate(series.publishedAt) }}</time>
          </footer>
        </div>
      </RouterLink>
    </div>
  </section>
</template>

<style scoped>
.series-page {
  padding: 48px 0 72px;
}
.series-head {
  margin-bottom: 32px;
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
.series-head h1 {
  margin: 8px 0 6px;
  font-size: 32px;
  color: var(--ink);
}
.series-sub {
  color: var(--muted);
  font-size: 14px;
}
.series-error {
  color: #b4452c;
}
.series-empty {
  color: var(--muted);
}
.series-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 20px;
}
.series-card {
  display: flex;
  flex-direction: column;
  border: 1px solid var(--line-strong);
  border-radius: 18px;
  overflow: hidden;
  background: var(--surface-solid);
  color: var(--ink);
  text-decoration: none;
  transition:
    transform 0.15s ease,
    border-color 0.15s ease;
}
.series-card:hover {
  transform: translateY(-3px);
  border-color: #ec4899;
}
.series-cover {
  height: 120px;
  background-size: cover;
  background-position: center;
}
.series-cover.placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, #ec4899 10%, var(--surface));
}
.series-cover.placeholder span {
  font-size: 40px;
  color: #ec4899;
  opacity: 0.6;
}
.series-card-body {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 16px 18px 14px;
}
.series-card-body strong {
  font-size: 17px;
}
.series-card-body p {
  color: var(--muted);
  font-size: 13px;
  line-height: 1.6;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.series-card-body footer {
  display: flex;
  justify-content: space-between;
  color: var(--muted);
  font-size: 12px;
  margin-top: 4px;
}
</style>
