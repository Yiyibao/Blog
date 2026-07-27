<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useSearch } from '../composables/useSearch'
import { splitHighlight } from '../utils/searchHighlight'
import type { SearchHit } from '../data'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ close: [] }>()

const router = useRouter()
const query = ref('')
const selectedTab = ref<'ALL' | 'POST' | 'NOTE' | 'DISH'>('ALL')
const searchHistory = ref<string[]>([])
const inputRef = ref<HTMLInputElement | null>(null)
const listboxRef = ref<HTMLDivElement | null>(null)
const activeIndex = ref(0)
let lastActiveElement: HTMLElement | null = null

const { results, loading, error, retry } = useSearch(query)

function loadSearchHistory() {
  try {
    const raw = localStorage.getItem('yubai_search_history')
    searchHistory.value = raw ? JSON.parse(raw) : []
  } catch {
    searchHistory.value = []
  }
}

function saveSearchHistory(term: string) {
  const trimmed = term.trim()
  if (!trimmed) return
  const set = new Set([trimmed, ...searchHistory.value])
  searchHistory.value = Array.from(set).slice(0, 6)
  try {
    localStorage.setItem('yubai_search_history', JSON.stringify(searchHistory.value))
  } catch {}
}

function clearSearchHistory() {
  searchHistory.value = []
  try {
    localStorage.removeItem('yubai_search_history')
  } catch {}
}

interface ListItem {
  type: 'group' | 'result'
  label?: string
  hit?: SearchHit
}

const flatItems = computed<ListItem[]>(() => {
  const items: ListItem[] = []
  const showAll = selectedTab.value === 'ALL'

  if ((showAll || selectedTab.value === 'POST') && results.value.articles.length) {
    items.push({ type: 'group', label: '文章' })
    results.value.articles.forEach(h => items.push({ type: 'result', hit: h }))
  }
  if ((showAll || selectedTab.value === 'NOTE') && results.value.notes.length) {
    items.push({ type: 'group', label: '学习笔记' })
    results.value.notes.forEach(h => items.push({ type: 'result', hit: h }))
  }
  if ((showAll || selectedTab.value === 'DISH') && results.value.dishes.length) {
    items.push({ type: 'group', label: '美食' })
    results.value.dishes.forEach(h => items.push({ type: 'result', hit: h }))
  }
  return items
})

function firstResultIndex(): number {
  return flatItems.value.findIndex(i => i.type === 'result')
}

watch(flatItems, () => {
  const idx = firstResultIndex()
  if (idx >= 0) activeIndex.value = idx
})

const resultCount = computed(() => flatItems.value.filter(i => i.type === 'result').length)
const hasResults = computed(() => resultCount.value > 0)

function goToHit(hit: SearchHit) {
  if (query.value) saveSearchHistory(query.value)
  const url = hit.url
  emit('close')
  query.value = ''
  if (url) router.push(url)
}

function selectHistoryTerm(term: string) {
  query.value = term
}

function onKeydown(event: KeyboardEvent) {
  const resultIndices = flatItems.value
    .map((item, index) => item.type === 'result' ? index : -1)
    .filter(index => index >= 0)
  if (!resultIndices.length && event.key !== 'Escape') return
  const currentPosition = Math.max(0, resultIndices.indexOf(activeIndex.value))

  if (event.key === 'ArrowDown') {
    event.preventDefault()
    activeIndex.value = resultIndices[(currentPosition + 1) % resultIndices.length]
    scrollActiveIntoView()
  } else if (event.key === 'ArrowUp') {
    event.preventDefault()
    activeIndex.value = resultIndices[(currentPosition - 1 + resultIndices.length) % resultIndices.length]
    scrollActiveIntoView()
  } else if (event.key === 'Enter') {
    const item = flatItems.value[activeIndex.value]
    if (item?.type === 'result' && item.hit) {
      event.preventDefault()
      goToHit(item.hit)
    }
  } else if (event.key === 'Escape') {
    event.preventDefault()
    emit('close')
  }
}

function scrollActiveIntoView() {
  nextTick(() => {
    const el = listboxRef.value?.querySelector<HTMLElement>(`[data-index="${activeIndex.value}"]`)
    if (el && 'scrollIntoView' in el) el.scrollIntoView({ block: 'nearest' })
  })
}

watch(() => props.open, (val) => {
  if (val) {
    lastActiveElement = document.activeElement as HTMLElement
    query.value = ''
    selectedTab.value = 'ALL'
    activeIndex.value = 0
    loadSearchHistory()
    nextTick(() => inputRef.value?.focus())
  } else {
    query.value = ''
    lastActiveElement?.focus()
  }
})

onMounted(() => {
  loadSearchHistory()
})

onBeforeUnmount(() => {
  lastActiveElement?.focus()
})
</script>

<template>
  <div
    v-if="open"
    class="search-overlay"
    role="dialog"
    aria-modal="true"
    aria-label="全站搜索"
    @click.self="emit('close')"
  >
    <div class="search-panel">
      <div
        class="search-input-wrap"
        role="combobox"
        aria-expanded="true"
        aria-haspopup="listbox"
        aria-controls="search-listbox"
      >
        <span>⌕</span>
        <input
          id="global-search"
          ref="inputRef"
          v-model="query"
          type="search"
          placeholder="搜索文章、美食、笔记…"
          role="searchbox"
          aria-autocomplete="list"
          aria-controls="search-listbox"
          :aria-activedescendant="hasResults && flatItems[activeIndex]?.type === 'result' ? `search-option-${activeIndex}` : undefined"
          @keydown="onKeydown"
        >
        <button type="button" @click="emit('close')">ESC</button>
      </div>

      <!-- Category Filter Tabs -->
      <div v-if="query.trim()" class="search-tabs">
        <button
          type="button"
          class="search-tab"
          :class="{ active: selectedTab === 'ALL' }"
          @click="selectedTab = 'ALL'"
        >全部</button>
        <button
          type="button"
          class="search-tab"
          :class="{ active: selectedTab === 'POST' }"
          @click="selectedTab = 'POST'"
        >文章</button>
        <button
          type="button"
          class="search-tab"
          :class="{ active: selectedTab === 'NOTE' }"
          @click="selectedTab = 'NOTE'"
        >笔记</button>
        <button
          type="button"
          class="search-tab"
          :class="{ active: selectedTab === 'DISH' }"
          @click="selectedTab = 'DISH'"
        >美食</button>
      </div>

      <p v-if="loading">搜索中…</p>

      <template v-else-if="error && query.trim()">
        <p class="search-error" role="alert">{{ error }}</p>
        <button type="button" class="search-retry" @click="retry">重试</button>
      </template>

      <div
        v-else-if="hasResults"
        id="search-listbox"
        ref="listboxRef"
        role="listbox"
        :aria-label="`共 ${resultCount} 条结果`"
      >
        <template v-for="(item, i) in flatItems" :key="i">
          <span
            v-if="item.type === 'group'"
            class="search-group-label"
            role="presentation"
          >{{ item.label }}</span>
          <button
            v-else
            :id="`search-option-${i}`"
            class="search-result"
            role="option"
            :aria-selected="activeIndex === i"
            :data-index="i"
            :class="{ active: activeIndex === i }"
            type="button"
            @click="item.hit && goToHit(item.hit)"
            @mousemove="activeIndex = i"
          >
            <span
              v-if="item.hit?.color"
              :style="{ background: item.hit.color }"
            >{{ item.hit?.number }}</span>
            <span
              v-else
              class="search-type-badge"
              :class="`type-${(item.hit?.type ?? 'NOTE').toLowerCase()}`"
            >{{ { POST: '文', DISH: '食', NOTE: '笔' }[item.hit?.type ?? 'NOTE'] }}</span>
            <div>
              <small>{{
                item.hit?.type === 'POST'
                  ? (item.hit?.category ?? '') + ' · 文章'
                  : item.hit?.type === 'DISH'
                    ? (item.hit?.category ?? '') + ' · 美食'
                    : '学习笔记'
              }}</small>
              <!-- 5A：命中词 <mark> 高亮——纯文本分段插值，无 v-html/XSS 面 -->
              <strong><template v-for="(seg, si) in splitHighlight(item.hit?.title ?? '', query)" :key="si"><mark v-if="seg.hit">{{ seg.text }}</mark><template v-else>{{ seg.text }}</template></template></strong>
            </div>
            <b>↗</b>
          </button>
        </template>
      </div>

      <div v-else-if="query.trim() && !loading" class="search-empty">
        没有匹配的结果
      </div>
      <div v-else-if="!query.trim()" class="search-empty">
        <!-- Search History Pills -->
        <div v-if="searchHistory.length" class="search-history-container">
          <div class="search-history-head">
            <span>最近搜索</span>
            <button type="button" class="search-history-clear" @click="clearSearchHistory">清空历史</button>
          </div>
          <div class="search-history-pills">
            <button
              v-for="term in searchHistory"
              :key="term"
              type="button"
              class="search-history-pill"
              @click="selectHistoryTerm(term)"
            >
              {{ term }}
            </button>
          </div>
        </div>
        <span>输入关键词搜索全站内容</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 5A：命中词高亮 */
.search-result strong mark {
  background: color-mix(in srgb, var(--accent) 24%, transparent);
  color: inherit;
  border-radius: 3px;
  padding: 0 1px;
}

.search-result.active,
.search-result:focus-visible {
  padding-inline: 14px;
  background: color-mix(in srgb, var(--accent) 6%, transparent);
  outline: none;
}
.search-result.active strong,
.search-result:focus-visible strong {
  color: var(--accent);
}
.search-error {
  color: var(--muted);
  margin: 12px 0 8px;
  font-size: 13px;
}
.search-retry {
  padding: 8px 14px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: transparent;
  color: var(--ink);
  cursor: pointer;
  font-size: 13px;
}
.search-retry:hover {
  background: color-mix(in srgb, var(--accent) 8%, transparent);
  border-color: var(--accent);
}
</style>
