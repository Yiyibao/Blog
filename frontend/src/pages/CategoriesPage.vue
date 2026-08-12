<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { fetchCategories } from '../api/content';
import { usePageMeta } from '../composables/usePageMeta';
import type { CategorySummary } from '../data';

const categories = ref<CategorySummary[]>([]);
const loading = ref(true);
const loadError = ref('');
const { apply: applyMeta } = usePageMeta();

onMounted(async () => {
  try {
    categories.value = await fetchCategories();
    applyMeta({
      title: '文章分类',
      description: '按主题浏览已发布的文章分类。',
      canonicalPath: '/categories',
    });
  } catch {
    loadError.value = '分类加载失败，请稍后重试。';
  } finally {
    loading.value = false;
  }
});
</script>

<template>
  <section class="categories-page section-wrap">
    <header class="categories-head">
      <p class="categories-kicker"><span>✦</span> CATEGORIES / 分类</p>
      <h1>按主题阅读</h1>
      <p class="categories-sub">从一个清晰的主题入口，进入一组可以连续阅读的文章。</p>
    </header>

    <p v-if="loading" role="status">正在加载分类…</p>
    <p v-else-if="loadError" class="categories-error" role="alert">{{ loadError }}</p>
    <p v-else-if="!categories.length" class="categories-empty">还没有公开分类，敬请期待。</p>

    <div v-else class="categories-grid">
      <RouterLink
        v-for="category in categories"
        :key="category.slug"
        class="category-card"
        :to="`/categories/${encodeURIComponent(category.slug)}`"
      >
        <span class="category-mark" aria-hidden="true">✦</span>
        <span class="category-card-main">
          <strong>{{ category.name }}</strong>
          <small>{{ category.publishedPostCount }} 篇公开文章</small>
          <span v-if="category.description" class="category-description">{{ category.description }}</span>
        </span>
        <span class="category-arrow" aria-hidden="true">↗</span>
      </RouterLink>
    </div>
  </section>
</template>

<style scoped>
.categories-page {
  padding: 48px 0 72px;
}
.categories-head {
  max-width: 620px;
  margin-bottom: 32px;
}
.categories-kicker {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--muted);
  font-size: 12px;
  letter-spacing: 0.2em;
}
.categories-kicker span {
  color: var(--accent);
}
.categories-head h1 {
  margin: 8px 0 6px;
  color: var(--ink);
  font-size: 32px;
}
.categories-sub,
.categories-empty,
.categories-error {
  color: var(--muted);
  font-size: 14px;
}
.categories-error {
  color: #b4452c;
}
.categories-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
  gap: 14px;
}
.category-card {
  display: flex;
  align-items: center;
  gap: 14px;
  min-height: 112px;
  padding: 18px 20px;
  border: 1px solid var(--line-strong);
  border-radius: 16px;
  background: var(--surface-solid);
  color: var(--ink);
  text-decoration: none;
  transition:
    border-color 0.15s ease,
    transform 0.15s ease;
}
.category-card:hover,
.category-card:focus-visible {
  border-color: var(--accent);
  transform: translateY(-2px);
}
.category-mark {
  display: grid;
  width: 38px;
  height: 38px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 12px;
  background: color-mix(in srgb, var(--accent) 14%, transparent);
  color: var(--accent);
}
.category-card-main {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 5px;
}
.category-card-main strong {
  font-size: 17px;
}
.category-card-main small,
.category-description {
  overflow: hidden;
  color: var(--muted);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.category-arrow {
  color: var(--muted);
  font-size: 20px;
}
</style>
