import { describe, it, expect, beforeEach, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '../stores/auth'
import KnowledgeGraph, { type GraphNode, type GraphEdge } from '../components/KnowledgeGraph.vue'

const mockFetchGraphNodes = vi.fn()
const mockFetchGraphSubgraph = vi.fn()

vi.mock('../api/content', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/content')>()
  return {
    ...actual,
    fetchGraphNodes: (...args: unknown[]) => mockFetchGraphNodes(...args),
    fetchGraphSubgraph: (...args: unknown[]) => mockFetchGraphSubgraph(...args),
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
    // L-13：节点改 transform 定位（布局过渡动画载体）——确定性断言改读 style.transform
    const readPositions = (wrapper: Awaited<ReturnType<typeof mountGraph>>['wrapper']) =>
      wrapper.findAll('g.graph-node').map((nodeEl) => ({
        id: nodeEl.attributes('aria-label'),
        transform: (nodeEl.attributes('style') || '').match(/translate\([^)]*\)/)?.[0],
      }))

    const { wrapper: wrapper1 } = await mountGraph()
    await flushPromises()
    const nodes1 = readPositions(wrapper1)

    const { wrapper: wrapper2 } = await mountGraph()
    await flushPromises()
    const nodes2 = readPositions(wrapper2)

    expect(nodes1.length).toBeGreaterThan(0)
    expect(nodes1.every((n) => n.transform)).toBe(true)
    expect(nodes1).toEqual(nodes2)
  })

  it('L-13: category filter pills are gone while pan/zoom controls exist', async () => {
    const { wrapper } = await mountGraph()
    await flushPromises()

    expect(wrapper.find('.filter-pill').exists()).toBe(false)
    const ctrls = wrapper.findAll('.view-ctrl-btn')
    expect(ctrls.length).toBe(3)

    // 缩放按钮驱动 viewBox 变化（零依赖视窗导航）
    const svg = wrapper.find('svg.graph-svg')
    const before = svg.attributes('viewBox')
    await ctrls[0].trigger('click')
    expect(svg.attributes('viewBox')).not.toBe(before)
    // 复位后还原
    await ctrls[2].trigger('click')
    expect(svg.attributes('viewBox')).toBe(before)
  })

  it('L-13: nodes carry staggered entry delays and breathing float wrapper', async () => {
    const { wrapper } = await mountGraph()
    await flushPromises()

    const nodes = wrapper.findAll('g.graph-node')
    expect(nodes.length).toBeGreaterThan(1)
    // 错峰入场：不同 order 的节点 animation-delay 不同
    const delays = nodes.map((n) => (n.attributes('style') || '').match(/animation-delay: ([^;]+)/)?.[1])
    expect(new Set(delays).size).toBeGreaterThan(1)
    // 呼吸漂浮载体存在
    expect(wrapper.find('.node-float').exists()).toBe(true)
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

describe('KnowledgeGraph 5C Subgraph', () => {
  beforeEach(() => {
    mockFetchGraphNodes.mockReset()
    mockFetchGraphSubgraph.mockReset()
  })

  const baseNodes: GraphNode[] = [
    { id: 't1', label: '#前端', type: 'TAG' },
    { id: 't2', label: '#TypeScript', type: 'TAG' },
    { id: 'p1', label: 'Vue 架构', type: 'POST', url: '/articles/vue-arch' },
  ]
  const baseEdges: GraphEdge[] = [
    { source: 'p1', target: 't1' },
  ]

  const subgraphNodes: GraphNode[] = [
    { id: 'p2', label: 'React 入门', type: 'POST', url: '/articles/react' },
    { id: 'n1', label: '学习笔记', type: 'NOTE', url: '/notes?note=1' },
  ]
  const subgraphEdges: GraphEdge[] = [
    { source: 'p2', target: 't1' },
    { source: 'n1', target: 't1' },
  ]

  async function mountGraph(props: Record<string, unknown> = {}) {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'home', component: { template: '<div>Home</div>' } },
        { path: '/archive', name: 'archive', component: { template: '<div>Archive</div>' } },
      ],
    })
    await router.push('/archive')
    await router.isReady()
    return mount(KnowledgeGraph, {
      props: {
        initialNodes: baseNodes,
        initialEdges: baseEdges,
        ...props,
      },
      global: { plugins: [router] },
    })
  }

  it('双击节点触发子图请求 depth=2', async () => {
    mockFetchGraphSubgraph.mockResolvedValue({ nodes: subgraphNodes, edges: subgraphEdges })
    const wrapper = await mountGraph()
    await flushPromises()

    const node = wrapper.findAll('g.graph-node').find((n) => n.text().includes('Vue 架构'))
    expect(node).toBeDefined()
    await node!.trigger('dblclick')
    await flushPromises()

    expect(mockFetchGraphSubgraph).toHaveBeenCalledWith('p1', 2)
  })

  it('展开两层关联按钮触发子图', async () => {
    mockFetchGraphSubgraph.mockResolvedValue({ nodes: subgraphNodes, edges: subgraphEdges })
    const wrapper = await mountGraph()
    await flushPromises()

    const node = wrapper.findAll('g.graph-node').find((n) => n.text().includes('Vue 架构'))
    await node!.trigger('click')
    await flushPromises()

    const expandBtn = wrapper.find('.expand-btn')
    expect(expandBtn.exists()).toBe(true)
    await expandBtn.trigger('click')
    await flushPromises()

    expect(mockFetchGraphSubgraph).toHaveBeenCalledWith('p1', 2)
  })

  it('子图节点去重合并：重复 id 不新增', async () => {
    const duplicateNodes = [
      ...subgraphNodes,
      { id: 'p1', label: 'Vue 架构', type: 'POST', url: '/articles/vue-arch' },
    ]
    mockFetchGraphSubgraph.mockResolvedValue({ nodes: duplicateNodes, edges: subgraphEdges })
    const wrapper = await mountGraph()
    await flushPromises()

    const node = wrapper.findAll('g.graph-node').find((n) => n.text().includes('Vue 架构'))
    await node!.trigger('click')
    await flushPromises()

    const expandBtn = wrapper.find('.expand-btn')
    await expandBtn.trigger('click')
    await flushPromises()

    // Only unique ids
    const renderedIds = wrapper.findAll('g.graph-node').map((n) => n.attributes('aria-label'))
    const vueMatches = renderedIds.filter((l) => l?.includes('Vue 架构'))
    expect(vueMatches.length).toBe(1)
  })

  it('子图失败保留原图', async () => {
    mockFetchGraphSubgraph.mockRejectedValue(new Error('Subgraph fail'))
    const wrapper = await mountGraph()
    await flushPromises()

    expect(wrapper.findAll('g.graph-node').length).toBeGreaterThan(0)

    const node = wrapper.findAll('g.graph-node').find((n) => n.text().includes('Vue 架构'))
    await node!.trigger('click')
    await flushPromises()

    const expandBtn = wrapper.find('.expand-btn')
    await expandBtn.trigger('click')
    await flushPromises()

    // Error shown
    expect(wrapper.text()).toContain('展开失败')
    // Original graph still rendered
    const graphNodes = wrapper.findAll('g.graph-node')
    expect(graphNodes.length).toBeGreaterThanOrEqual(baseNodes.length - 2)
  })

  it('返回全图概览恢复首次快照', async () => {
    mockFetchGraphSubgraph.mockResolvedValue({ nodes: subgraphNodes, edges: subgraphEdges })
    const wrapper = await mountGraph()
    await flushPromises()

    const node = wrapper.findAll('g.graph-node').find((n) => n.text().includes('Vue 架构'))
    await node!.trigger('click')
    await flushPromises()
    const expandBtn = wrapper.find('.expand-btn')
    await expandBtn.trigger('click')
    await flushPromises()

    expect(mockFetchGraphSubgraph).toHaveBeenCalled()
    // Now has merged nodes
    expect(wrapper.findAll('g.graph-node').length).toBeGreaterThan(baseNodes.length - 2)

    // Click "← 全图概览"
    const returnBtn = wrapper.find('.return-overview-btn')
    expect(returnBtn.exists()).toBe(true)
    await returnBtn.trigger('click')
    await flushPromises()

    // Back to original node count
    expect(wrapper.findAll('g.graph-node').length).toBe(baseNodes.length)
  })

  it('useRequestToken 内置的竞态守卫：序列号机制丢弃迟到响应', async () => {
    const { useRequestToken } = await import('../composables/useRequestToken')
    const { next, isCurrent } = useRequestToken()

    const seq1 = next()
    const seq2 = next()

    expect(seq1).toBe(1)
    expect(seq2).toBe(2)
    // seq1 is stale
    expect(isCurrent(seq1)).toBe(false)
    expect(isCurrent(seq2)).toBe(true)
  })

  it('expandSubgraph 逆序丢弃迟到响应：第一请求先发后完成被第二后发先完成覆盖', async () => {
    const { useGraphSubgraph } = await import('../composables/useGraphSubgraph')
    const subgraph = useGraphSubgraph()

    let resolveFirst!: (v: { nodes: GraphNode[]; edges: GraphEdge[] }) => void
    let resolveSecond!: (v: { nodes: GraphNode[]; edges: GraphEdge[] }) => void
    const firstPromise = new Promise<{ nodes: GraphNode[]; edges: GraphEdge[] }>(resolve => { resolveFirst = resolve })
    const secondPromise = new Promise<{ nodes: GraphNode[]; edges: GraphEdge[] }>(resolve => { resolveSecond = resolve })

    mockFetchGraphSubgraph
      .mockImplementationOnce(() => firstPromise)
      .mockImplementationOnce(() => secondPromise)

    const raceNodes: GraphNode[] = [
      { id: 't1', label: '#TAG1', type: 'TAG' },
      { id: 't2', label: '#TAG2', type: 'TAG' },
    ]
    const raceEdges: GraphEdge[] = []

    const result1Promise = subgraph.expandSubgraph('t1', 2, raceNodes, raceEdges)
    const result2Promise = subgraph.expandSubgraph('t2', 2, raceNodes, raceEdges)

    resolveSecond({ nodes: [{ id: 'n2', label: 'Post2', type: 'POST' }], edges: [] })
    const result2 = await result2Promise
    expect(result2).not.toBeNull()
    expect(result2!.nodes.some(n => n.id === 'n2')).toBe(true)

    resolveFirst({ nodes: [{ id: 'n1', label: 'Post1', type: 'POST' }], edges: [] })
    const result1 = await result1Promise
    expect(result1).toBeNull()
  })

  it('findStableCenter 平局规则：最高度优先，同度按 id 升序 localeCompare < 0', async () => {
    const { useGraphSubgraph } = await import('../composables/useGraphSubgraph')
    const { findStableCenter } = useGraphSubgraph()

    const centerNodes: GraphNode[] = [
      { id: 'z-tag', label: '#Z', type: 'TAG' },
      { id: 'a-tag', label: '#A', type: 'TAG' },
      { id: 'm-tag', label: '#M', type: 'TAG' },
      { id: 'p1', label: 'Post1', type: 'POST' },
      { id: 'p2', label: 'Post2', type: 'POST' },
      { id: 'p3', label: 'Post3', type: 'POST' },
    ]

    const tieEdges: GraphEdge[] = [
      { source: 'z-tag', target: 'p1' },
      { source: 'a-tag', target: 'p2' },
    ]
    expect(findStableCenter(centerNodes, tieEdges)).toBe('a-tag')

    const degreeEdges: GraphEdge[] = [
      { source: 'z-tag', target: 'p1' },
      { source: 'z-tag', target: 'p2' },
      { source: 'a-tag', target: 'p3' },
    ]
    expect(findStableCenter(centerNodes, degreeEdges)).toBe('z-tag')
  })

  it('>300 自动局部模式：选最高度 TAG，子图不超预算，显示局部提示，返回全图按 MAX_DISPLAY_NODES 渲染', async () => {
    const tagNodes: GraphNode[] = [
      { id: 't-high', label: '#高频', type: 'TAG' },
      { id: 't-mid', label: '#中频', type: 'TAG' },
      { id: 't-low', label: '#低频', type: 'TAG' },
    ]
    const contentNodes: GraphNode[] = Array.from({ length: 307 }, (_, i) => ({
      id: `n${i}`,
      label: `Node${i}`,
      type: 'POST' as const,
    }))
    const allNodes = [...tagNodes, ...contentNodes]
    const edges: GraphEdge[] = [
      ...Array.from({ length: 20 }, (_, i) => ({ source: 't-high', target: `n${i}` })),
      ...Array.from({ length: 10 }, (_, i) => ({ source: 't-mid', target: `n${20 + i}` })),
      ...Array.from({ length: 5 }, (_, i) => ({ source: 't-low', target: `n${30 + i}` })),
    ]
    expect(allNodes.length).toBeGreaterThan(300)

    const subgraphReturnNodes: GraphNode[] = [
      { id: 't-high', label: '#高频', type: 'TAG' },
      { id: 'n0', label: 'Node0', type: 'POST' },
      { id: 'n1', label: 'Node1', type: 'POST' },
    ]
    const subgraphReturnEdges: GraphEdge[] = [
      { source: 'n0', target: 't-high' },
    ]
    mockFetchGraphSubgraph.mockResolvedValue({ nodes: subgraphReturnNodes, edges: subgraphReturnEdges })

    const wrapper = await mountGraph({ initialNodes: allNodes, initialEdges: edges })
    await flushPromises()

    expect(mockFetchGraphSubgraph).toHaveBeenCalledWith('t-high', 2)
    expect(wrapper.findAll('g.graph-node').length).toBeLessThanOrEqual(40)
    expect(wrapper.findAll('g.graph-node').length).toBeLessThan(300)
    expect(wrapper.find('.graph-local-mode').exists()).toBe(true)
    expect(wrapper.text()).toContain('局部图谱模式')
    expect(wrapper.text()).toContain('t-high')

    const returnBtn = wrapper.find('.return-overview-btn-inline')
    await returnBtn.trigger('click')
    await flushPromises()

    const afterNodes = wrapper.findAll('g.graph-node')
    expect(afterNodes.length).toBeLessThanOrEqual(40)
    expect(afterNodes.length).toBeLessThan(300)
  })
})

describe('Graph API Auth and Params', () => {
  beforeEach(() => {
    mockFetchGraphSubgraph.mockReset()
    mockFetchGraphSubgraph.mockResolvedValue({ nodes: [], edges: [] })
  })

  it('API encoding: real fetchGraphSubgraph 对需要编码的 center 做 encodeURIComponent，params.depth=2', async () => {
    setActivePinia(createPinia())
    useAuthStore().clearSession()

    const { fetchGraphSubgraph: realFetch } = await vi.importActual<typeof import('../api/content')>('../api/content')

    let caught = false
    try {
      await realFetch('t 前端/空格', 2)
    } catch (err: unknown) {
      caught = true
      const axiosErr = err as { config?: { url?: string; params?: Record<string, unknown> } }
      expect(axiosErr.config?.url).toBe(`/graph/nodes/${encodeURIComponent('t 前端/空格')}`)
      expect(axiosErr.config?.params).toEqual({ depth: 2 })
    }
    expect(caught).toBe(true)
  })

  it('API: depth 参数默认 2', async () => {
    setActivePinia(createPinia())
    useAuthStore().clearSession()
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/', component: { template: '<div>Home</div>' } }],
    })
    const wrapper = mount(KnowledgeGraph, {
      props: {
        initialNodes: [
          { id: 't1', label: '#前端', type: 'TAG' },
          { id: 'p1', label: 'Vue 架构', type: 'POST', url: '/articles/vue-arch' },
        ],
        initialEdges: [{ source: 'p1', target: 't1' }],
      },
      global: { plugins: [router] },
    })
    await flushPromises()

    const node = wrapper.findAll('g.graph-node').find((n) => n.text().includes('Vue 架构'))
    await node!.trigger('click')
    await flushPromises()
    const expandBtn = wrapper.find('.expand-btn')
    await expandBtn.trigger('click')
    await flushPromises()

    expect(mockFetchGraphSubgraph).toHaveBeenCalledWith('p1', 2)
  })
})
