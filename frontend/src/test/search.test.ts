import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import GlobalSearch from '../components/GlobalSearch.vue'
import { nextTick } from 'vue'

// ── API mock ─────────────────────────────────────────────────────────────────

const searchApi = vi.hoisted(() => {
  type Entry = { resolve(v: any): void; reject(e: any): void }
  const queue: Entry[] = []

  return {
    queue,
    resolve(value: any) { const e = queue.shift(); e?.resolve(value) },
    reject(reason: any) { const e = queue.shift(); e?.reject(reason) },
    clear() { queue.length = 0 },
    searchContent: vi.fn((_q: string, _limit?: number, signal?: AbortSignal) => {
      return new Promise<any>((resolve, reject) => {
        if (signal?.aborted) {
          reject(new DOMException('Aborted', 'AbortError'))
          return
        }
        const entry = { resolve, reject }
        queue.push(entry)
        signal?.addEventListener('abort', () => {
          const idx = queue.indexOf(entry)
          if (idx >= 0) queue.splice(idx, 1)
          reject(new DOMException('Aborted', 'AbortError'))
        })
      })
    }),
  }
})

vi.mock('../api/content', async (importOriginal) => {
  const actual = await importOriginal() as Record<string, any>
  return { ...actual, searchContent: searchApi.searchContent }
})

// ── Router mock ──────────────────────────────────────────────────────────────

const routerMock = vi.hoisted(() => ({ push: vi.fn() }))

vi.mock('vue-router', () => ({
  useRouter: () => routerMock,
}))

// ── Helpers ──────────────────────────────────────────────────────────────────

async function flush() {
  const p = new Promise<void>(r => setTimeout(r, 0))
  await vi.advanceTimersByTimeAsync(5)
  await p
}

/** Advance fake timers by `ms` then settle microtasks */
async function advance(ms: number) {
  await vi.advanceTimersByTimeAsync(ms)
  await flush()
}

const SAMPLE_POST = { type: 'POST', id: 1, title: 'Article 1', excerpt: '...', category: '工程实践', url: '/articles/article-1', color: '#1649d8', number: '01' }
const SAMPLE_DISH = { type: 'DISH', id: 2, title: 'Dish 1', excerpt: '...', category: '家常菜', url: '/recipes/dish-1', color: null, number: null }
const SAMPLE_NOTE = { type: 'NOTE', id: 3, title: 'Note 1', excerpt: '...', category: null, url: '/notes/note-1', color: null, number: null }

const GROUPED = {
  articles: [SAMPLE_POST],
  dishes: [SAMPLE_DISH],
  notes: [SAMPLE_NOTE],
  total: 3,
}

// ── Setup ────────────────────────────────────────────────────────────────────

beforeEach(() => {
  searchApi.clear()
  vi.clearAllMocks()
  vi.useFakeTimers({ shouldAdvanceTime: false })
})

afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

// ── Tests ────────────────────────────────────────────────────────────────────

describe('GlobalSearch — debounce', () => {

  it('waits 300ms before calling the API after typing', async () => {
    const wrapper = mount(GlobalSearch, { props: { open: true } })
    wrapper.get('#global-search').setValue('test')
    expect(searchApi.searchContent).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(299)
    expect(searchApi.searchContent).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(2)
    await flush()
    expect(searchApi.searchContent).toHaveBeenCalledTimes(1)
    expect(searchApi.searchContent).toHaveBeenCalledWith('test', 10, expect.any(AbortSignal))
    wrapper.unmount()
  })

  it('resets debounce on rapid typing — only fires once after pause', async () => {
    const wrapper = mount(GlobalSearch, { props: { open: true } })
    const input = wrapper.get('#global-search')

    input.setValue('a')
    await advance(100)
    input.setValue('ab')
    await advance(100)
    input.setValue('abc')
    await advance(100)

    expect(searchApi.searchContent).not.toHaveBeenCalled()

    await advance(305)
    expect(searchApi.searchContent).toHaveBeenCalledTimes(1)
    expect(searchApi.searchContent).toHaveBeenCalledWith('abc', 10, expect.any(AbortSignal))
    wrapper.unmount()
  })
})

describe('GlobalSearch — cancel race', () => {

  it('cancels previous in-flight request when query changes', async () => {
    const wrapper = mount(GlobalSearch, { props: { open: true } })
    const input = wrapper.get('#global-search')

    input.setValue('first')
    await advance(310)
    expect(searchApi.searchContent).toHaveBeenCalledTimes(1)
    const firstSignal = searchApi.searchContent.mock.calls[0][2] as AbortSignal
    expect(firstSignal.aborted).toBe(false)

    input.setValue('second')
    await advance(310)
    expect(searchApi.searchContent).toHaveBeenCalledTimes(2)
    expect(firstSignal.aborted).toBe(true)
    const secondSignal = searchApi.searchContent.mock.calls[1][2] as AbortSignal
    expect(secondSignal.aborted).toBe(false)
    wrapper.unmount()
  })

  it('cancels an in-flight request when the query is cleared', async () => {
    const wrapper = mount(GlobalSearch, { props: { open: true } })
    const input = wrapper.get('#global-search')

    input.setValue('first')
    await advance(310)
    const signal = searchApi.searchContent.mock.calls[0][2] as AbortSignal

    await input.setValue('')
    await nextTick()

    expect(signal.aborted).toBe(true)
    expect(searchApi.queue).toHaveLength(0)
    expect(wrapper.findAll('.search-result')).toHaveLength(0)
    wrapper.unmount()
  })

  it('ignores stale response when request sequence changes', async () => {
    const wrapper = mount(GlobalSearch, { props: { open: true } })
    const input = wrapper.get('#global-search')

    input.setValue('first')
    await advance(310)

    input.setValue('second')
    await advance(310)

    // first request was aborted; only second in queue
    expect(searchApi.queue.length).toBe(1)
    searchApi.resolve(GROUPED)
    await flush()

    const resultEls = wrapper.findAll('.search-result')
    expect(resultEls.length).toBe(GROUPED.articles.length + GROUPED.dishes.length + GROUPED.notes.length)
    wrapper.unmount()
  })
})

describe('GlobalSearch — loading / error / retry', () => {

  it('shows loading indicator while fetching', async () => {
    const wrapper = mount(GlobalSearch, { props: { open: true } })
    wrapper.get('#global-search').setValue('test')
    await advance(310)

    expect(wrapper.text()).toContain('搜索中')
    wrapper.unmount()
  })

  it('shows error message on failure and retry button works', async () => {
    const wrapper = mount(GlobalSearch, { props: { open: true } })
    wrapper.get('#global-search').setValue('fail')
    await advance(310)

    searchApi.reject(new Error('Network error'))
    await flush()

    expect(wrapper.text()).toContain('Network error')

    searchApi.clear()
    const retryBtn = wrapper.find('.search-retry')
    expect(retryBtn.exists()).toBe(true)
    retryBtn.trigger('click')
    await advance(310)

    expect(searchApi.searchContent).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('shows empty state when no results', async () => {
    const wrapper = mount(GlobalSearch, { props: { open: true } })
    wrapper.get('#global-search').setValue('empty')
    await advance(310)

    searchApi.resolve({ articles: [], notes: [], dishes: [], total: 0 })
    await flush()

    expect(wrapper.text()).toContain('没有匹配的结果')
    wrapper.unmount()
  })

  it('shows prompt when query is empty', async () => {
    const wrapper = mount(GlobalSearch, { props: { open: true } })
    expect(wrapper.text()).toContain('输入关键词搜索全站内容')
    wrapper.unmount()
  })
})

describe('GlobalSearch — keyboard navigation', () => {

  it('arrow keys move active index and Enter selects item', async () => {
    const wrapper = mount(GlobalSearch, { props: { open: true } })
    wrapper.get('#global-search').setValue('test')
    await advance(310)

    searchApi.resolve(GROUPED)
    await flush()
    await nextTick()

    const items = wrapper.findAll('.search-result')
    expect(items.length).toBe(3)

    // First result (POST) is active by default
    expect(items[0].classes()).toContain('active')

    const input = wrapper.get('#global-search')

    await input.trigger('keydown', { key: 'ArrowUp' })
    await nextTick()
    expect(items[2].classes()).toContain('active')

    await input.trigger('keydown', { key: 'ArrowDown' })
    await nextTick()
    expect(items[0].classes()).toContain('active')

    await input.trigger('keydown', { key: 'ArrowDown' })
    await nextTick()
    expect(items[1].classes()).toContain('active')
    expect(items[0].classes()).not.toContain('active')

    await input.trigger('keydown', { key: 'ArrowDown' })
    await nextTick()
    expect(items[2].classes()).toContain('active')

    await input.trigger('keydown', { key: 'ArrowUp' })
    await nextTick()
    expect(items[1].classes()).toContain('active')

    routerMock.push.mockReset()
    await input.trigger('keydown', { key: 'Enter' })
    await flush()
    expect(routerMock.push).toHaveBeenCalledWith(GROUPED.notes[0].url)
    wrapper.unmount()
  })

  it('Escape closes the dialog', async () => {
    const wrapper = mount(GlobalSearch, { props: { open: true } })
    const input = wrapper.get('#global-search')

    await input.trigger('keydown', { key: 'Escape' })
    await flush()
    expect(wrapper.emitted('close')).toBeTruthy()
    wrapper.unmount()
  })
})

describe('GlobalSearch — groups rendering', () => {

  it('renders three groups with correct labels', async () => {
    const wrapper = mount(GlobalSearch, { props: { open: true } })
    wrapper.get('#global-search').setValue('test')
    await advance(310)

    searchApi.resolve(GROUPED)
    await flush()

    const labels = wrapper.findAll('.search-group-label').map(el => el.text())
    expect(labels).toContain('文章')
    expect(labels).toContain('美食')
    expect(labels).toContain('学习笔记')

    expect(wrapper.findAll('.search-result').length).toBe(3)
    wrapper.unmount()
  })

  it('skips group labels when a type has no results', async () => {
    const wrapper = mount(GlobalSearch, { props: { open: true } })
    wrapper.get('#global-search').setValue('test')
    await advance(310)

    searchApi.resolve({ articles: [SAMPLE_POST], notes: [SAMPLE_NOTE], dishes: [], total: 2 })
    await flush()

    const labels = wrapper.findAll('.search-group-label').map(el => el.text())
    expect(labels).toContain('文章')
    expect(labels).not.toContain('美食')
    expect(labels).toContain('学习笔记')
    wrapper.unmount()
  })
})
