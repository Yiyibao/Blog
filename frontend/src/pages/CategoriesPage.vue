<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { fetchCategories } from '../api/content'
import type { CategorySummary } from '../data'

const categories = ref<CategorySummary[]>([])
const loading = ref(true)
const loadError = ref('')

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    categories.value = await fetchCategories()
  } catch {
    loadError.value = '分类数据暂时无法加载，请稍后重试。'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="page-hero section-wrap compact-hero">
    <p class="eyebrow"><span /> CATEGORIES / 文章分类</p>
    <h1>按分类浏览，<br><em>找到你感兴趣的方向。</em></h1>
    <p>所有文章按主题分类整理，方便快速定位相关内容。</p>
  </section>
  <section class="categories-page section-wrap">
    <div v-if="loading" class="categories-loading" role="status">
      <span>正在加载分类数据…</span>
    </div>
    <div v-else-if="loadError" class="categories-error" role="alert">
      <h2>加载失败</h2>
      <p>{{ loadError }}</p>
      <button class="button primary" type="button" @click="load">重试</button>
    </div>
    <div v-else-if="categories.length === 0" class="categories-empty">
      <h2>暂无分类</h2>
      <p>目前还没有已发布的文章分类。</p>
    </div>
    <div v-else class="categories-grid">
      <RouterLink
        v-for="cat in categories"
        :key="cat.slug"
        :to="`/categories/${encodeURIComponent(cat.slug)}`"
        class="category-card"
      >
        <div class="category-count">{{ cat.publishedPostCount }}</div>
        <div class="category-body">
          <h2>{{ cat.name }}</h2>
          <p v-if="cat.description">{{ cat.description }}</p>
          <p class="category-meta">{{ cat.publishedPostCount }} 篇已发布文章</p>
        </div>
        <span class="category-arrow">→</span>
      </RouterLink>
    </div>
  </section>
</template>

<style scoped>
.categories-page { padding-bottom: 110px; }
.categories-loading, .categories-error, .categories-empty { display: grid; place-items: center; min-height: 300px; padding: 60px; text-align: center; }
.categories-loading span { color: var(--muted); font-size: .9rem; }
.categories-error h2, .categories-empty h2 { font-size: 1.8rem; font-weight: 520; margin: 0 0 12px; }
.categories-error p, .categories-empty p { color: var(--muted); max-width: 420px; line-height: 1.7; }
.categories-grid { display: grid; gap: 16px; margin-top: 40px; }
.category-card { display: grid; grid-template-columns: 70px 1fr auto; gap: 24px; align-items: center; padding: 28px 32px; border: 1px solid var(--line); border-radius: 20px; background: var(--surface); transition: border-color .3s, box-shadow .3s, transform .3s; }
.category-card:hover { border-color: var(--line-strong); box-shadow: var(--shadow-sm); transform: translateY(-3px); }
.category-count { width: 70px; height: 70px; display: grid; place-items: center; border-radius: 50%; color: var(--accent); font: 400 32px Georgia, serif; background: color-mix(in srgb, var(--accent) 12%, transparent); }
.category-body h2 { margin: 0 0 6px; font: 400 26px Georgia, "Songti SC", serif; }
.category-body p { margin: 0; color: var(--muted); font-size: 14px; line-height: 1.7; }
.category-meta { margin-top: 8px !important; color: var(--faint) !important; font: 600 10px ui-monospace, Consolas, monospace; letter-spacing: .08em; }
.category-arrow { color: var(--accent); font-size: 20px; transition: transform .3s; }
.category-card:hover .category-arrow { transform: translateX(4px); }
@media (max-width: 560px) {
  .category-card { grid-template-columns: 56px 1fr auto; gap: 16px; padding: 20px; }
  .category-count { width: 56px; height: 56px; font-size: 26px; }
  .category-body h2 { font-size: 22px; }
}
</style>
