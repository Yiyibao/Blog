import { beforeEach, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import ArticlesPage from '../pages/ArticlesPage.vue'
import { useContentStore } from '../stores/contentStore'

beforeEach(() => {
  localStorage.clear()
  setActivePinia(createPinia())
})

describe('ArticlesPage', () => {
  it('numbers posts by their rendered position including the page offset', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/articles', component: ArticlesPage },
        { path: '/articles/:slug', component: { template: '<div />' } },
      ],
    })
    await router.push('/articles?page=2')
    await router.isReady()

    const content = useContentStore()
    content.contentReady = true
    content.archiveLoading = true
    content.archivePage = 1
    content.archiveTotal = 8
    content.archiveTotalPages = 2
    content.archivePosts = [
      {
        slug: 'seventh', title: '第七篇', excerpt: '', date: '2026-07-02', readTime: 1,
        category: '工程实践', tags: [], color: '#111111', number: '99',
      },
      {
        slug: 'eighth', title: '第八篇', excerpt: '', date: '2026-07-01', readTime: 1,
        category: '工程实践', tags: [], color: '#222222', number: '42',
      },
    ]

    const wrapper = mount(ArticlesPage, { global: { plugins: [router] } })

    expect(wrapper.findAll('.project-number').map((node) => node.text())).toEqual(['07', '08'])
  })
})
