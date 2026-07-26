import { describe, it, expect, beforeEach, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import KnowledgeGraph, { type GraphNode, type GraphEdge } from '../components/KnowledgeGraph.vue'

// NF-7：组件改走统一 api 层，失败态测试 mock api 模块而非全局 fetch
const mockFetchGraphNodes = vi.fn()

vi.mock('../api/content', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/content')>()
  return {
    ...actual,
    fetchGraphNodes: (...args: unknown[]) => mockFetchGraphNodes(...args),
  }
})

const sampleNodes: GraphNode[] = [
  { id: 'p1', label: 'Vue 架构', type: 'POST', url: '/articles/vue-arch' },
  { id: 'p2', label: 'TS 类型', type: 'POST', url: '/articles/ts-type' },
  { id: 'n1', label: 'Web Audio 笔记', type: 'NOTE', url: '/notes?note=1' },
  { id: 'd1', label: '红烧肉', type: 'DISH', url: '/recipes?dish=hongshaorou' },
  { id: 't1', label: '#前端', type: 'TAG' },
  { id: 't2', label: '#TypeScript', type: 'TAG' },
]

const sampleEdges: GraphEdge[] = [
  { source: 'p1', target: 't1' },
  { source: 'p2', target: 't2' },
  { source: 'n1', target: 't1' },
]

async function mountGraph(props: Record<string, unknown> = {}) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div>Home</div>' } },
      { path: '/articles/vue-arch', name: 'article-vue', component: { template: '<div>Vue Article</div>' } },
      { path: '/archive', name: 'archive', component: { template: '<div>Archive</div>' } },
    ],
  })
  await router.push('/archive')
  await router.isReady()
  const wrapper = mount(KnowledgeGraph, {
    props: {
      initialNodes: sampleNodes,
      initialEdges: sampleEdges,
      ...props,
    },
    global: { plugins: [router] },
  })
  return { wrapper, router }
}

describe('KnowledgeGraph Component', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('renders deterministic node positions for identical input data across multiple mounts', async () => {
    const { wrapper: wrapper1 } = await mountGraph()
    await flushPromises()

    const nodes1 = wrapper1.findAll('g.graph-node').map((nodeEl) => {
      const circle = nodeEl.find('circle.node-circle')
      return {
        id: nodeEl.attributes('aria-label'),
        cx: circle.attributes('cx'),
        cy: circle.attributes('cy'),
      }
    })

    const { wrapper: wrapper2 } = await mountGraph()
    await flushPromises()

    const nodes2 = wrapper2.findAll('g.graph-node').map((nodeEl) => {
      const circle = nodeEl.find('circle.node-circle')
      return {
        id: nodeEl.attributes('aria-label'),
        cx: circle.attributes('cx'),
        cy: circle.attributes('cy'),
      }
    })

    expect(nodes1.length).toBeGreaterThan(0)
    expect(nodes1).toEqual(nodes2)
  })

  it('selects content node on first click without immediate navigation', async () => {
    const { wrapper, router } = await mountGraph()
    await flushPromises()

    const pushSpy = vi.spyOn(router, 'push')
    const postNodeEl = wrapper.findAll('g.graph-node').find((n) => n.text().includes('Vue 架构'))
    expect(postNodeEl).toBeDefined()

    // First click -> select node
    await postNodeEl!.trigger('click')
    await flushPromises()

    expect(pushSpy).not.toHaveBeenCalled()
    expect(wrapper.find('.graph-selection-panel').exists()).toBe(true)
    expect(wrapper.find('.panel-title').text()).toBe('Vue 架构')

    // Click "打开内容 ↗" button -> navigate
    const openBtn = wrapper.find('.open-content-btn')
    expect(openBtn.exists()).toBe(true)
    await openBtn.trigger('click')
    await flushPromises()

    expect(pushSpy).toHaveBeenCalledWith('/articles/vue-arch')
  })

  it('selects TAG node and does not navigate to /categories', async () => {
    const { wrapper, router } = await mountGraph()
    await flushPromises()

    const pushSpy = vi.spyOn(router, 'push')
    const tagNodeEl = wrapper.findAll('g.graph-node').find((n) => n.text().includes('#前端'))
    expect(tagNodeEl).toBeDefined()

    await tagNodeEl!.trigger('click')
    await flushPromises()

    expect(pushSpy).not.toHaveBeenCalled()
    expect(wrapper.emitted('selectTag')).toBeDefined()
    expect(wrapper.emitted('selectTag')![0]).toEqual(['前端'])
    // TAG panel should not show "打开内容" button
    expect(wrapper.find('.open-content-btn').exists()).toBe(false)
  })

  it('handles API failure by displaying error state without fake demo fallback data', async () => {
    mockFetchGraphNodes.mockRejectedValue(new Error('Network error'))

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/', component: { template: '<div>Home</div>' } }],
    })
    const wrapper = mount(KnowledgeGraph, {
      props: { initialNodes: [], initialEdges: [] },
      global: { plugins: [router] },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('数据加载失败')
    // Ensure no fallback nodes are rendered
    expect(wrapper.findAll('g.graph-node').length).toBe(0)
  })
})
