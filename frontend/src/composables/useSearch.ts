import { ref, watch, type Ref, onScopeDispose } from 'vue'
import { searchContent, type SearchGroup } from '../api/content'

export function useSearch(query: Ref<string>, debounceMs = 300) {
  const results = ref<SearchGroup>({ articles: [], notes: [], dishes: [], total: 0 })
  const loading = ref(false)
  const error = ref<string | null>(null)

  let currentController: AbortController | null = null
  let currentSeq = 0
  let debounceTimer: number | undefined

  async function executeSearch(q: string) {
    currentController?.abort()
    const seq = ++currentSeq
    const controller = new AbortController()
    currentController = controller

    loading.value = true
    error.value = null

    try {
      const data = await searchContent(q, 10, controller.signal)
      if (seq !== currentSeq) return
      results.value = data
    } catch (err: unknown) {
      if ((err as any)?.name === 'AbortError' || (err as any)?.code === 'ERR_CANCELED') return
      if (seq !== currentSeq) return
      const message = (err as any)?.response?.data?.message || (err as Error)?.message || '搜索失败'
      error.value = message
      results.value = { articles: [], notes: [], dishes: [], total: 0 }
    } finally {
      if (seq === currentSeq) loading.value = false
    }
  }

  watch(query, (val) => {
    clearTimeout(debounceTimer)
    const trimmed = val.trim()
    if (!trimmed) {
      currentController?.abort()
      currentController = null
      currentSeq++
      results.value = { articles: [], notes: [], dishes: [], total: 0 }
      error.value = null
      loading.value = false
      return
    }
    debounceTimer = window.setTimeout(() => executeSearch(trimmed), debounceMs)
  })

  function retry() {
    const trimmed = query.value.trim()
    if (trimmed) executeSearch(trimmed)
  }

  function cleanup() {
    clearTimeout(debounceTimer)
    currentController?.abort()
  }

  onScopeDispose(cleanup)

  return { results, loading, error, retry }
}
