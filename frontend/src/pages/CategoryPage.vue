<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { fetchCategoryDetail } from '../api/content'
import type { CategoryDetail } from '../data'
import { usePageMeta } from '../composables/usePageMeta'

const route = useRoute()
const router = useRouter()

const slug = computed(() => String(route.params.slug ?? ''))
const pageFromQuery = computed(() => {
  const raw = route.query.page
  const n = typeof raw === 'string' ? parseInt(raw, 10) : NaN
  return Number.isFinite(n) && n >= 1 ? n - 1 : 0
})

const detail = ref<CategoryDetail | null>(null)
const loading = ref(true)
const notFound = ref(false)
const loadError = ref('')

async function load() {
  loading.value = true
  notFound.value = false
  loadError.value = ''
  detail.value = null
  const { apply } = usePageMeta()
  try {
    detail.value = await fetchCategoryDetail(slug.value, pageFromQuery.value)
    const d = detail.value
    if (d) {
      apply({
        title: d.name,
        description: d.description || `${d.name}分类下的已发布文章`,
        canonicalPath: `/categories/${encodeURIComponent(d.slug)}`,
        openGraph: {
          title: d.name,
          description: d.description || `${d.name}分类下的已发布文章`,
          type: 'website',
          image: '/og.png',
          url: `/categories/${encodeURIComponent(d.slug)}`,
        },
        twitter: {
          title: d.name,
          description: d.description || `${d.name}分类下的已发布文章`,
          image: '/og.png',
        },
      })
    }
  } catch (err: any) {
    if (err?.response?.status === 404) {
      notFound.value = true
      apply({ title: '分类不存在', description: '请求的分类不存在', robots: 'noindex, nofollow' })
    } else {
      loadError.value = '分类数据暂时无法加载，请稍后重试。'
    }
  } finally {
    loading.value = false
  }
}

function retry() {
  void load()
}

function goToPage(page: number) {
  router.push(page > 0 ? { query: { page } } : { query: {} })
}

const posts = computed(() => detail.value?.posts ?? [])
const totalPages = computed(() => Math.max(1, detail.value?.totalPages ?? 1))
const currentPage = computed(() => Math.min(pageFromQuery.value + 1, totalPages.value))

watch([slug, () => route.query.page], () => {
  if (!loading.value) void load()
})

onMounted(load)
</script>

<template>
  <section v-if="notFound" class="category-not-found section-wrap">
    <h1>分类不存在</h1>
    <p>请求的分类不存在，可能链接已失效。</p>
    <RouterLink class="button secondary" to="/categories">查看全部分类</RouterLink>
  </section>
  <template v-else>
    <section class="page-hero section-wrap compact-hero">
      <p class="eyebrow"><span /> CATEGORY / 分类</p>
      <h1 v-if="detail">{{ detail.name }}<br><em>共 {{ detail.total }} 篇文章</em></h1>
      <h1 v-else>分类文章</h1>
      <p v-if="detail?.description">{{ detail.description }}</p>
      <p v-else>浏览当前分类下的所有已发布文章。</p>
    </section>
    <section class="category-page section-wrap">
      <div v-if="loading" class="category-loading" role="status">
        <span>正在加载文章…</span>
      </div>
      <div v-else-if="loadError" class="category-error" role="alert">
        <h2>加载失败</h2>
        <p>{{ loadError }}</p>
        <button class="button primary" type="button" @click="retry">重试</button>
      </div>
      <div v-else-if="detail && posts.length === 0" class="category-empty">
        <h2>暂无文章</h2>
        <p>该分类下还没有已发布的文章。</p>
      </div>
      <div v-else-if="detail" class="category-posts">
        <article v-for="post in posts" :key="post.slug" class="category-post-card">
          <div class="post-main">
            <h2><RouterLink :to="`/articles/${post.slug}`">{{ post.title }}</RouterLink></h2>
            <p>{{ post.excerpt }}</p>
            <div class="post-meta-row">
              <span class="post-date">{{ post.date }}</span>
              <div v-if="post.tags.length" class="tag-row">
                <span v-for="tag in post.tags" :key="tag"># {{ tag }}</span>
              </div>
            </div>
          </div>
          <RouterLink class="post-link-arrow" :to="`/articles/${post.slug}`" :aria-label="`阅读${post.title}`">
            <span>阅读</span><b>↗</b>
          </RouterLink>
        </article>
      </div>
      <nav v-if="detail && totalPages > 1" class="pagination" aria-label="分类分页">
        <button type="button" :disabled="pageFromQuery <= 0" @click="goToPage(pageFromQuery - 1)">上一页</button>
        <span>{{ currentPage }} / {{ totalPages }}</span>
        <button type="button" :disabled="pageFromQuery >= totalPages - 1" @click="goToPage(pageFromQuery + 2)">下一页</button>
      </nav>
    </section>
  </template>
  <RouterLink class="back-to-categories" to="/categories">← 返回全部分类</RouterLink>
</template>

<style scoped>
.back-to-categories { display: inline-block; margin: 0 auto 60px; padding: 0 24px; color: var(--muted); font-size: 13px; text-align: center; width: 100%; transition: color .3s; }
.back-to-categories:hover { color: var(--accent); }
.category-not-found { padding: 120px 24px; text-align: center; }
.category-not-found h1 { font: 400 48px Georgia, "Songti SC", serif; margin: 0 0 16px; }
.category-not-found p { color: var(--muted); margin-bottom: 28px; }
.category-page { padding-bottom: 110px; }
.category-loading, .category-error, .category-empty { display: grid; place-items: center; min-height: 300px; padding: 60px; text-align: center; }
.category-loading span { color: var(--muted); font-size: .9rem; }
.category-error h2, .category-empty h2 { font-size: 1.8rem; font-weight: 520; margin: 0 0 12px; }
.category-error p, .category-empty p { color: var(--muted); max-width: 420px; line-height: 1.7; }
.category-posts { display: grid; gap: 1px; border-radius: 16px; overflow: hidden; background: var(--line); margin-top: 40px; }
.category-post-card { display: grid; grid-template-columns: 1fr auto; gap: 24px; align-items: center; padding: 28px 32px; background: var(--surface); transition: background .2s; }
.category-post-card:hover { background: var(--accent-soft); }
.post-main h2 { margin: 0 0 8px; font: 400 24px Georgia, "Songti SC", serif; }
.post-main h2 a { background: linear-gradient(var(--accent), var(--accent)) left bottom / 0 1px no-repeat; transition: color .35s, background-size .45s cubic-bezier(.16,1,.3,1); }
.post-main h2 a:hover { color: var(--accent); background-size: 100% 1px; }
.post-main > p { color: var(--muted); font-size: 14px; line-height: 1.8; margin: 0 0 12px; }
.post-meta-row { display: flex; flex-wrap: wrap; gap: 8px 16px; align-items: center; }
.post-date { color: var(--faint); font: 600 10px ui-monospace, Consolas, monospace; letter-spacing: .08em; }
.post-link-arrow { display: flex; align-items: center; gap: 8px; color: var(--accent); font: 500 12px ui-monospace, Consolas, monospace; transition: transform .3s; white-space: nowrap; }
.category-post-card:hover .post-link-arrow { transform: translateX(4px); }
.post-link-arrow b { display: inline-grid; place-items: center; width: 32px; height: 32px; border: 1px solid var(--line); border-radius: 50%; font-size: 16px; transition: transform .3s, background .3s; }
.category-post-card:hover .post-link-arrow b { transform: translate(2px,-2px) rotate(8deg); background: color-mix(in srgb, var(--accent) 9%, transparent); }
@media (max-width: 560px) {
  .category-post-card { grid-template-columns: 1fr; padding: 20px; }
  .post-link-arrow { justify-content: flex-end; }
  .post-main h2 { font-size: 20px; }
}
</style>
