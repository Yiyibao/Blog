<script setup lang="ts">
import { onMounted, ref, watch } from 'vue';
import { RouterLink, useRoute, useRouter } from 'vue-router';
import { fetchCategories, recordSearchClick, searchByType } from '../api/content';
import PaginationNav from '../components/PaginationNav.vue';
import type { SearchHit, SearchType } from '../data';
import { splitHighlight } from '../utils/searchHighlight';

type TypeFilter = 'ALL' | SearchType;

const route = useRoute();
const router = useRouter();
const query = ref('');
const type = ref<TypeFilter>('ALL');
const category = ref('');
const tag = ref('');
const from = ref('');
const to = ref('');
const sort = ref<'relevance' | 'desc' | 'asc'>('relevance');
const page = ref(0);
const results = ref<SearchHit[]>([]);
const totalPages = ref(1);
const totalElements = ref(0);
const categories = ref<{ name: string; slug: string }[]>([]);
const loading = ref(false);
const error = ref('');
const history = ref<string[]>([]);
let requestToken = 0;

function scalar(value: unknown) {
  return Array.isArray(value) ? String(value[0] ?? '') : String(value ?? '');
}

function readUrl() {
  query.value = scalar(route.query.q);
  const routeType = scalar(route.query.type).toUpperCase();
  type.value = ['POST', 'DISH', 'NOTE'].includes(routeType) ? (routeType as SearchType) : 'ALL';
  category.value = scalar(route.query.category);
  tag.value = scalar(route.query.tag);
  from.value = scalar(route.query.from);
  to.value = scalar(route.query.to);
  const routeSort = scalar(route.query.sort);
  sort.value = routeSort === 'asc' || routeSort === 'desc' ? routeSort : 'relevance';
  page.value = Math.max(0, Number.parseInt(scalar(route.query.page), 10) - 1 || 0);
}

function writeUrl() {
  const next = {
    q: query.value.trim() || undefined,
    type: type.value === 'ALL' ? undefined : type.value.toLowerCase(),
    category: type.value === 'DISH' || type.value === 'NOTE' ? undefined : category.value || undefined,
    tag: type.value === 'DISH' || type.value === 'NOTE' ? undefined : tag.value || undefined,
    from: from.value || undefined,
    to: to.value || undefined,
    sort: sort.value === 'relevance' ? undefined : sort.value,
    page: page.value > 0 ? String(page.value + 1) : undefined,
  };
  void router.replace({ query: next });
}

function remember(value: string) {
  const trimmed = value.trim();
  if (!trimmed) return;
  history.value = [trimmed, ...history.value.filter((item) => item !== trimmed)].slice(0, 8);
  localStorage.setItem('yubai_search_history', JSON.stringify(history.value));
}

// The API is authoritative for pagination/counts; this guard keeps old cached
// responses and contract-test fixtures from reintroducing stale dated hits.
function matchesDate(hit: SearchHit) {
  if (!hit.date) return true;
  return (!from.value || hit.date >= from.value) && (!to.value || hit.date <= to.value);
}

async function search() {
  const term = query.value.trim();
  const token = ++requestToken;
  if (!term) {
    results.value = [];
    totalElements.value = 0;
    totalPages.value = 1;
    return;
  }
  loading.value = true;
  error.value = '';
  try {
    const resultPage = await searchByType(
      term,
      type.value === 'ALL' ? 0 : page.value,
      type.value === 'ALL' ? 50 : 12,
      {
        type: type.value as SearchType | 'ALL',
        categorySlug: category.value,
        tag: tag.value,
        from: from.value,
        to: to.value,
        sort: sort.value === 'relevance' ? undefined : sort.value,
      },
    );
    if (token !== requestToken) return;
    results.value = resultPage.results
      .map((hit) => ({ ...hit, telemetryId: resultPage.telemetryId }))
      .filter(matchesDate);
    totalElements.value = resultPage.totalElements;
    totalPages.value = resultPage.totalPages;
    remember(term);
  } catch {
    if (token === requestToken) error.value = '搜索服务暂时不可用，请稍后重试。';
  } finally {
    if (token === requestToken) loading.value = false;
  }
}

function trackClick(item: SearchHit, index: number) {
  void recordSearchClick(item.telemetryId, index + 1);
}

function resultTypeLabel(value: SearchType) {
  return { POST: '文章', DISH: '菜谱', NOTE: '笔记' }[value];
}

let debounce: ReturnType<typeof setTimeout> | undefined;
watch([query, type, category, tag, from, to, sort], () => {
  page.value = 0;
  writeUrl();
  clearTimeout(debounce);
  debounce = setTimeout(search, 250);
});
watch(page, () => {
  writeUrl();
  void search();
});
watch(() => route.query, readUrl);

onMounted(async () => {
  readUrl();
  try {
    history.value = JSON.parse(localStorage.getItem('yubai_search_history') || '[]');
  } catch {
    history.value = [];
  }
  categories.value = (await fetchCategories().catch(() => [])).map((item) => ({
    name: item.name,
    slug: item.slug,
  }));
  await search();
});
</script>

<template>
  <section class="page-hero section-wrap compact-hero search-center-hero">
    <p class="eyebrow"><span /> SEARCH / 搜索中心</p>
    <h1>从所有内容里，<br /><em>找到正在寻找的线索。</em></h1>
    <p>统一检索文章、菜谱与登录后可见的学习笔记，筛选条件可随 URL 分享。</p>
  </section>

  <main class="search-center section-wrap">
    <form class="search-center-controls" role="search" @submit.prevent="search">
      <label class="search-center-query">
        <span>关键词</span>
        <input v-model="query" type="search" autofocus placeholder="标题、摘要、标签或正文…" />
      </label>
      <label
        ><span>内容类型</span
        ><select v-model="type">
          <option value="ALL">全部</option>
          <option value="POST">文章</option>
          <option value="DISH">菜谱</option>
          <option value="NOTE">笔记</option>
        </select></label
      >
      <label v-if="type === 'ALL' || type === 'POST'"
        ><span>文章分类</span
        ><select v-model="category">
          <option value="">全部分类</option>
          <option v-for="item in categories" :key="item.slug" :value="item.slug">{{ item.name }}</option>
        </select></label
      >
      <label><span>开始日期</span><input v-model="from" type="date" /></label>
      <label><span>结束日期</span><input v-model="to" type="date" /></label>
      <label
        ><span>排序</span
        ><select v-model="sort">
          <option value="relevance">相关性</option>
          <option value="desc">时间从新到旧</option>
          <option value="asc">时间从旧到新</option>
        </select></label
      >
      <label v-if="type === 'ALL' || type === 'POST'"
        ><span>标签</span><input v-model="tag" type="search" placeholder="可选标签"
      /></label>
      <button class="button primary" type="submit">搜索</button>
    </form>

    <div v-if="!query.trim() && history.length" class="search-center-history">
      <span>最近搜索</span>
      <button v-for="item in history" :key="item" type="button" @click="query = item">{{ item }}</button>
    </div>
    <p v-if="error" class="content-unavailable" role="alert">{{ error }}</p>
    <p v-else-if="loading" class="search-center-empty">正在搜索…</p>
    <p v-else-if="query.trim() && !results.length" class="search-center-empty">没有符合筛选条件的结果。</p>
    <section v-else class="search-center-results" aria-live="polite">
      <header v-if="query.trim()">
        <strong>{{ totalElements }}</strong> 条结果
      </header>
      <RouterLink
        v-for="(item, index) in results"
        :key="`${item.type}-${item.id}`"
        :to="item.url"
        class="search-center-result"
        @click="trackClick(item, index)"
      >
        <span class="search-center-type">{{ resultTypeLabel(item.type) }}</span>
        <div>
          <h2>
            <template v-for="(part, index) in splitHighlight(item.title, query)" :key="index"
              ><mark v-if="part.hit">{{ part.text }}</mark
              ><template v-else>{{ part.text }}</template></template
            >
          </h2>
          <p>
            <template v-for="(part, index) in splitHighlight(item.excerpt, query)" :key="index"
              ><mark v-if="part.hit">{{ part.text }}</mark
              ><template v-else>{{ part.text }}</template></template
            >
          </p>
          <small
            >{{ item.category || '未分类' }}<template v-if="item.date"> · {{ item.date }}</template
            ><template v-if="item.tags?.length"> · {{ item.tags.join(' / ') }}</template></small
          >
        </div>
        <span aria-hidden="true">→</span>
      </RouterLink>
    </section>
    <PaginationNav
      v-if="totalPages > 1"
      :page="page"
      :total-pages="totalPages"
      aria-label="搜索结果分页"
      @change="page = $event"
    />
  </main>
</template>

<style scoped>
.search-center {
  padding-bottom: 100px;
}
.search-center-controls {
  display: grid;
  grid-template-columns: minmax(260px, 2fr) repeat(5, minmax(130px, 1fr)) auto;
  gap: 12px;
  align-items: end;
  padding: 20px;
  border: 1px solid var(--line);
  border-radius: 18px;
  background: var(--surface-solid);
}
.search-center-controls label {
  display: grid;
  gap: 6px;
  color: var(--muted);
  font-size: 12px;
}
.search-center-controls input,
.search-center-controls select {
  min-height: 42px;
  min-width: 0;
  padding: 0 11px;
  border: 1px solid var(--line);
  border-radius: 9px;
  background: var(--surface);
  color: var(--ink);
}
.search-center-history {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin: 18px 0;
  color: var(--muted);
}
.search-center-history button {
  border: 1px solid var(--line);
  border-radius: 999px;
  padding: 6px 12px;
  background: var(--surface-solid);
  color: var(--ink);
  cursor: pointer;
}
.search-center-results {
  display: grid;
  gap: 12px;
  margin-top: 24px;
}
.search-center-results > header {
  color: var(--muted);
}
.search-center-result {
  display: grid;
  grid-template-columns: auto 1fr auto;
  gap: 16px;
  align-items: center;
  padding: 20px;
  border: 1px solid var(--line);
  border-radius: 14px;
  color: var(--ink);
  text-decoration: none;
  background: var(--surface-solid);
  transition:
    transform 0.2s ease,
    border-color 0.2s ease;
}
.search-center-result:hover {
  transform: translateY(-2px);
  border-color: var(--accent);
}
.search-center-result h2 {
  margin: 0 0 7px;
  font-size: 20px;
}
.search-center-result p {
  margin: 0 0 8px;
  color: var(--muted);
}
.search-center-result mark {
  border-radius: 3px;
  background: color-mix(in srgb, var(--accent) 22%, transparent);
  color: inherit;
}
.search-center-type {
  padding: 5px 9px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--accent) 12%, var(--surface));
  color: var(--accent);
  font-size: 12px;
}
.search-center-empty {
  padding: 60px 0;
  text-align: center;
  color: var(--muted);
}
@media (max-width: 1100px) {
  .search-center-controls {
    grid-template-columns: repeat(3, 1fr);
  }
  .search-center-query {
    grid-column: 1 / -1;
  }
}
@media (max-width: 640px) {
  .search-center-controls {
    grid-template-columns: 1fr 1fr;
  }
  .search-center-query {
    grid-column: 1 / -1;
  }
  .search-center-result {
    grid-template-columns: 1fr auto;
  }
  .search-center-type {
    grid-column: 1 / -1;
    justify-self: start;
  }
}
</style>
