import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { AxiosError } from 'axios'
import TagPage from '../pages/TagPage.vue'

const mockTagPosts = vi.fn()

vi.mock('../api/content', () => ({
  fetchTagPosts: (...args: unknown[]) => mockTagPosts(...args),
}))

beforeEach(() => {
  mockTagPosts.mockReset()
})

async function mountAt(path: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/articles', component: { template: '<div>Articles</div>' } },
      { path: '/articles/:slug', component: { template: '<div>Article</div>' } },
      { path: '/tags/:tag', component: TagPage },
    ],
  })
  await router.push(path)
  await router.isReady()
  return mount(TagPage, { global: { plugins: [router] } })
}

describe('TagPage', () => {
  it('loads posts for the route tag and renders cards', async () => {
    mockTagPosts.mockResolvedValue({
      items: [{
        slug: 'vue-post', title: 'Vue 文章', excerpt: '摘要', date: '2026-07-01', readTime: 5,
        category: '工程实践', tags: ['Vue'], color: '#123456', number: '01', featured: false, status: 'PUBLISHED',
      }],
      page: 0, size: 10, totalElements: 1, totalPages: 1,
    })
    const wrapper = await mountAt('/tags/Vue')
    await flushPromises()

    expect(mockTagPosts).toHaveBeenCalledWith('Vue', 0, 10)
    expect(wrapper.text()).toContain('# Vue')
    expect(wrapper.text()).toContain('1 篇文章')
    expect(wrapper.find('a.tag-post-card').attributes('href')).toBe('/articles/vue-post')
  })

  it('shows empty message on 404', async () => {
    mockTagPosts.mockImplementation(() => Promise.reject(new AxiosError(
      'nf', undefined, undefined, undefined,
      { status: 404, data: {}, statusText: 'Not Found', headers: {}, config: {} as never })))
    const wrapper = await mountAt('/tags/ghost')
    await flushPromises()

    expect(wrapper.text()).toContain('该标签下暂无已发布文章')
  })

  it('discards stale out-of-order tag response when route changes', async () => {
    let resolveFirst!: (v: unknown) => void
    let resolveSecond!: (v: unknown) => void
    mockTagPosts
      .mockReturnValueOnce(new Promise((r) => { resolveFirst = r }))
      .mockReturnValueOnce(new Promise((r) => { resolveSecond = r }))
    const wrapper = await mountAt('/tags/vue')
    await flushPromises()
    await wrapper.vm.$router.push('/tags/react')
    await flushPromises()
    resolveFirst({ items: [{ slug: 'vue-post', title: 'Vue Article', excerpt: '', date: '2026-07-01', readTime: 1, category: '', tags: [], color: '#000', number: '01', featured: false, status: 'PUBLISHED' }], page: 0, size: 10, totalElements: 1, totalPages: 1 })
    resolveSecond({ items: [{ slug: 'react-post', title: 'React Article', excerpt: '', date: '2026-07-02', readTime: 2, category: '', tags: [], color: '#000', number: '02', featured: false, status: 'PUBLISHED' }], page: 0, size: 10, totalElements: 1, totalPages: 1 })
    await flushPromises()
    expect(wrapper.text()).toContain('React Article')
    expect(wrapper.text()).not.toContain('Vue Article')
  })
})
