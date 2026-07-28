import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import SeriesPage from '../pages/SeriesPage.vue'
import SeriesDetailPage from '../pages/SeriesDetailPage.vue'

const mockList = vi.fn()
const mockDetail = vi.fn()

vi.mock('../api/content', () => ({
  fetchSeriesList: (...args: unknown[]) => mockList(...args),
  fetchSeriesDetail: (...args: unknown[]) => mockDetail(...args),
}))

function buildRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div>Home</div>' } },
      { path: '/series', component: SeriesPage },
      { path: '/series/:slug', component: SeriesDetailPage },
      { path: '/articles/:slug', component: { template: '<div>Article</div>' } },
    ],
  })
}

beforeEach(() => {
  mockList.mockReset()
  mockDetail.mockReset()
})

describe('SeriesPage', () => {
  it('renders published series cards with entry count', async () => {
    mockList.mockResolvedValue([
      { slug: 'vue-deep-dive', name: 'Vue 深入浅出', description: '从响应式到编译器', coverImage: null, entryCount: 5, publishedAt: '2026-07-01T00:00:00Z' },
    ])
    const router = buildRouter()
    await router.push('/series')
    await router.isReady()
    const wrapper = mount(SeriesPage, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.text()).toContain('Vue 深入浅出')
    expect(wrapper.text()).toContain('5 篇')
    expect(wrapper.find('a.series-card').attributes('href')).toBe('/series/vue-deep-dive')
  })

  it('shows empty state when no series published', async () => {
    mockList.mockResolvedValue([])
    const router = buildRouter()
    await router.push('/series')
    await router.isReady()
    const wrapper = mount(SeriesPage, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.text()).toContain('还没有公开的合集')
  })
})

describe('SeriesDetailPage', () => {
  it('renders ordered entries linking to articles', async () => {
    mockDetail.mockResolvedValue({
      slug: 'vue-deep-dive', name: 'Vue 深入浅出', description: '', coverImage: null,
      publishedAt: '2026-07-01T00:00:00Z',
      entries: [
        { postId: 1, slug: 'reactivity', title: '响应式原理', date: '2026-06-01', chapterTitle: '第一章', position: 1 },
        { postId: 2, slug: 'compiler', title: '编译器', date: '2026-06-08', chapterTitle: null, position: 2 },
      ],
    })
    const router = buildRouter()
    await router.push('/series/vue-deep-dive')
    await router.isReady()
    const wrapper = mount(SeriesDetailPage, { global: { plugins: [router] } })
    await flushPromises()

    const links = wrapper.findAll('a.entry-link')
    expect(links).toHaveLength(2)
    expect(links[0].attributes('href')).toBe('/articles/reactivity')
    expect(links[0].text()).toContain('01')
    expect(links[0].text()).toContain('第一章')
    expect(links[1].attributes('href')).toBe('/articles/compiler')
  })

  it('discards stale out-of-order response from earlier slug', async () => {
    let resolveFirst!: (v: unknown) => void
    let resolveSecond!: (v: unknown) => void
    mockDetail
      .mockReturnValueOnce(new Promise((r) => { resolveFirst = r }))
      .mockReturnValueOnce(new Promise((r) => { resolveSecond = r }))
    const router = buildRouter()
    await router.push('/series/first')
    await router.isReady()
    const wrapper = mount(SeriesDetailPage, { global: { plugins: [router] } })
    await flushPromises()
    await router.push('/series/second')
    await flushPromises()
    resolveFirst({ slug: 'first', name: 'First', description: '', coverImage: null, publishedAt: null, entries: [] })
    resolveSecond({ slug: 'second', name: 'Second', description: '', coverImage: null, publishedAt: null, entries: [] })
    await flushPromises()
    expect(wrapper.text()).toContain('Second')
    expect(wrapper.text()).not.toContain('First')
  })

  it('shows not-found message on 404', async () => {
    const axios = await import('axios')
    mockDetail.mockRejectedValue(new axios.AxiosError('nf', undefined, undefined, undefined, {
      status: 404, data: {}, statusText: 'Not Found', headers: {}, config: {} as never,
    }))
    const router = buildRouter()
    await router.push('/series/ghost')
    await router.isReady()
    const wrapper = mount(SeriesDetailPage, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.text()).toContain('合集不存在或尚未发布')
  })
})
