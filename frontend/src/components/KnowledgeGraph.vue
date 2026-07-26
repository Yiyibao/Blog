<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

export interface GraphNode {
  id: string
  label: string
  type: 'POST' | 'NOTE' | 'DISH' | 'TAG'
  url?: string | null
  category?: string
  summary?: string
}

export interface GraphEdge {
  source: string
  target: string
}

type GraphFilterType = 'ALL' | 'POST' | 'NOTE' | 'DISH' | 'TAG'

const props = withDefaults(
  defineProps<{
    initialNodes?: GraphNode[]
    initialEdges?: GraphEdge[]
    selectedRelation?: string
    selectedType?: GraphFilterType
  }>(),
  {
    initialNodes: () => [],
    initialEdges: () => [],
    selectedRelation: '',
    selectedType: 'ALL',
  }
)

const emit = defineEmits<{
  (e: 'selectTag', tag: string): void
  (e: 'selectNode', node: GraphNode | null): void
  (e: 'selectType', type: GraphFilterType): void
}>()

const router = useRouter()
const isFullscreen = ref(false)
const filterType = ref<GraphFilterType>(props.selectedType)
const nodes = ref<GraphNode[]>([])
const edges = ref<GraphEdge[]>([])
const loading = ref(false)
const loadError = ref('')
const selectedNodeId = ref<string | null>(null)
const hoveredNodeId = ref<string | null>(null)

// Maximum nodes to display for clarity
const MAX_DISPLAY_NODES = 40

async function loadGraphData() {
  if (props.initialNodes && props.initialNodes.length > 0) {
    nodes.value = props.initialNodes
    edges.value = props.initialEdges
    return
  }

  loading.value = true
  loadError.value = ''
  try {
    const res = await fetch('/api/v1/graph/nodes')
    if (res.ok) {
      const json = await res.json()
      if (json.code === 200 && json.data) {
        nodes.value = json.data.nodes || []
        edges.value = json.data.edges || []
        loading.value = false
        return
      }
    }
    loadError.value = '关联图谱数据加载失败'
  } catch {
    loadError.value = '无法连接网络，图谱数据加载失败'
  } finally {
    loading.value = false
  }
}

watch(
  () => props.initialNodes,
  (newNodes) => {
    if (newNodes && newNodes.length > 0) {
      nodes.value = newNodes
      edges.value = props.initialEdges || []
      loadError.value = ''
    }
  },
  { immediate: true }
)

const nodeDegree = computed(() => {
  const degree = new Map<string, number>()
  edges.value.forEach((edge) => {
    degree.set(edge.source, (degree.get(edge.source) || 0) + 1)
    degree.set(edge.target, (degree.get(edge.target) || 0) + 1)
  })
  return degree
})

const filteredNodes = computed(() => {
  const tags = nodes.value
    .filter((node) => node.type === 'TAG')
    .sort((a, b) => (nodeDegree.value.get(b.id) || 0) - (nodeDegree.value.get(a.id) || 0) || a.id.localeCompare(b.id))
  const content = nodes.value
    .filter((node) => node.type !== 'TAG')
    .sort((a, b) => a.id.localeCompare(b.id))

  if (filterType.value === 'TAG') return tags.slice(0, MAX_DISPLAY_NODES)
  if (filterType.value === 'ALL') {
    return [...tags.slice(0, 12), ...content.slice(0, MAX_DISPLAY_NODES - 12)]
  }

  const filteredContent = content.filter((node) => node.type === filterType.value).slice(0, 30)
  const contentIds = new Set(filteredContent.map((node) => node.id))
  const relatedTagIds = new Set<string>()
  edges.value.forEach((edge) => {
    if (contentIds.has(edge.source)) relatedTagIds.add(edge.target)
    if (contentIds.has(edge.target)) relatedTagIds.add(edge.source)
  })
  return [...tags.filter((node) => relatedTagIds.has(node.id)).slice(0, 10), ...filteredContent]
})

const activeNodeIds = computed(() => new Set(filteredNodes.value.map((n) => n.id)))

const filteredEdges = computed(() => {
  return edges.value.filter((e) => activeNodeIds.value.has(e.source) && activeNodeIds.value.has(e.target))
})

// Deterministic layout algorithm: 100% stable SVG coordinates
const positionedNodes = computed(() => {
  const list = [...filteredNodes.value].sort((a, b) => {
    const typeOrder: Record<string, number> = { TAG: 1, POST: 2, NOTE: 3, DISH: 4 }
    const diff = (typeOrder[a.type] || 9) - (typeOrder[b.type] || 9)
    if (diff !== 0) return diff
    return a.id.localeCompare(b.id)
  })

  const width = 800
  const height = 480
  const cx = width / 2
  const cy = height / 2

  const tagNodes = list.filter((n) => n.type === 'TAG')
  const contentNodes = list.filter((n) => n.type !== 'TAG')

  const map = new Map<string, GraphNode & { x: number; y: number; radius: number }>()

  tagNodes.forEach((node, idx) => {
    const total = tagNodes.length
    const angle = total === 1 ? 0 : (idx / total) * Math.PI * 2 - Math.PI / 2
    map.set(node.id, {
      ...node,
      x: cx + Math.cos(angle) * 90,
      y: cy + Math.sin(angle) * 90,
      radius: 18,
    })
  })

  contentNodes.forEach((node, idx) => {
    const total = contentNodes.length
    const angle = total === 1 ? 0 : (idx / total) * Math.PI * 2 - Math.PI / 2
    const radius = 175 + (idx % 2) * 45
    map.set(node.id, {
      ...node,
      x: cx + Math.cos(angle) * radius,
      y: cy + Math.sin(angle) * radius,
      radius: 20,
    })
  })

  return map
})

const activeHighlightId = computed(() => selectedNodeId.value || hoveredNodeId.value)

const neighborNodeIds = computed(() => {
  if (!activeHighlightId.value) return new Set<string>()
  const targetId = activeHighlightId.value
  const set = new Set<string>([targetId])
  edges.value.forEach((e) => {
    if (e.source === targetId) set.add(e.target)
    if (e.target === targetId) set.add(e.source)
  })
  return set
})

const selectedNode = computed(() => {
  if (!selectedNodeId.value) return null
  return nodes.value.find((n) => n.id === selectedNodeId.value) || null
})

function getNodeColor(type: GraphNode['type']): string {
  switch (type) {
    case 'POST': return '#3b82f6'
    case 'NOTE': return '#10b981'
    case 'DISH': return '#f59e0b'
    case 'TAG': return '#8b5cf6'
  }
}

function handleNodeClick(node: GraphNode) {
  if (selectedNodeId.value === node.id) {
    if (node.type === 'TAG') emit('selectTag', '')
    selectedNodeId.value = null
    emit('selectNode', null)
    return
  }
  selectedNodeId.value = node.id
  emit('selectNode', node)

  if (node.type === 'TAG') {
    const tagText = node.label.replace(/^#/, '')
    emit('selectTag', tagText)
  }
}

function setFilterType(type: GraphFilterType) {
  filterType.value = type
  emit('selectType', type)
}

function clearSelection() {
  if (selectedNode.value?.type === 'TAG') emit('selectTag', '')
  selectedNodeId.value = null
  emit('selectNode', null)
}

function handleOpenContent() {
  if (selectedNode.value && selectedNode.value.url && selectedNode.value.type !== 'TAG') {
    void router.push(selectedNode.value.url)
  }
}

function toggleFullscreen() {
  isFullscreen.value = !isFullscreen.value
}

onMounted(() => {
  void loadGraphData()
})

watch(() => props.selectedType, (type) => {
  filterType.value = type
})

watch([() => props.selectedRelation, nodes], ([relation]) => {
  const normalized = relation.trim().toLocaleLowerCase()
  if (!normalized) {
    if (selectedNode.value?.type === 'TAG') selectedNodeId.value = null
    return
  }
  const matchingTag = nodes.value.find((node) => node.type === 'TAG'
    && node.label.replace(/^#/, '').trim().toLocaleLowerCase() === normalized)
  selectedNodeId.value = matchingTag?.id || null
}, { immediate: true })

watch(filteredNodes, (visibleNodes) => {
  if (selectedNodeId.value && !visibleNodes.some((node) => node.id === selectedNodeId.value)) {
    selectedNodeId.value = null
    emit('selectNode', null)
  }
})
</script>

<template>
  <div class="knowledge-graph-container" :class="{ 'is-fullscreen': isFullscreen }">
    <header class="graph-toolbar">
      <div class="graph-title">
        <span class="graph-badge">✦ KNOWLEDGE GRAPH</span>
        <h3>全站知识关联图谱</h3>
      </div>
      <div class="graph-filters">
        <button
          v-for="t in [
            { id: 'ALL', name: '全部' },
            { id: 'POST', name: '文章' },
            { id: 'NOTE', name: '笔记' },
            { id: 'DISH', name: '菜谱' },
            { id: 'TAG', name: '标签' },
          ]"
          :key="t.id"
          type="button"
          class="filter-pill"
          :class="{ active: filterType === t.id }"
          @click="setFilterType(t.id as GraphFilterType)"
        >
          {{ t.name }}
        </button>
        <button type="button" class="fullscreen-btn" :title="isFullscreen ? '退出全屏' : '全屏浏览'" @click="toggleFullscreen">
          {{ isFullscreen ? '⤢ 退出全屏' : '⤢ 全屏罗盘' }}
        </button>
      </div>
    </header>

    <div v-if="loading" class="graph-state graph-loading" role="status">
      <span>正在加载关联图谱…</span>
    </div>

    <div v-else-if="loadError" class="graph-state graph-error" role="alert">
      <p>{{ loadError }}</p>
      <button class="button primary" type="button" @click="loadGraphData">重试</button>
    </div>

    <div v-else-if="filteredNodes.length === 0" class="graph-state graph-empty">
      <p>暂无符合条件的关联节点。</p>
    </div>

    <div v-else class="svg-wrapper">
      <svg
        viewBox="0 0 800 480"
        preserveAspectRatio="xMidYMid meet"
        aria-label="知识关联图谱"
      >
        <!-- Edges -->
        <g class="graph-edges">
          <line
            v-for="(edge, idx) in filteredEdges"
            :key="`edge-${idx}-${edge.source}-${edge.target}`"
            :x1="positionedNodes.get(edge.source)?.x"
            :y1="positionedNodes.get(edge.source)?.y"
            :x2="positionedNodes.get(edge.target)?.x"
            :y2="positionedNodes.get(edge.target)?.y"
            class="graph-edge"
            :class="{
              highlighted: activeHighlightId && (edge.source === activeHighlightId || edge.target === activeHighlightId),
              faded: activeHighlightId && !(edge.source === activeHighlightId || edge.target === activeHighlightId)
            }"
          />
        </g>

        <!-- Nodes -->
        <g class="graph-nodes">
          <g
            v-for="[id, node] in positionedNodes"
            :key="id"
            class="graph-node"
            :class="{
              selected: selectedNodeId === id,
              highlighted: activeHighlightId && neighborNodeIds.has(id),
              faded: activeHighlightId && !neighborNodeIds.has(id)
            }"
            tabindex="0"
            role="button"
            :aria-label="`${node.label} (${node.type})`"
            @click="handleNodeClick(node)"
            @mouseenter="hoveredNodeId = id"
            @mouseleave="hoveredNodeId = null"
            @keydown.enter.prevent="handleNodeClick(node)"
            @keydown.space.prevent="handleNodeClick(node)"
          >
            <!-- 44x44 minimum touch/pointer target area -->
            <circle :cx="node.x" :cy="node.y" r="22" fill="transparent" class="hit-target" />

            <!-- Selection ring -->
            <circle
              v-if="selectedNodeId === id"
              :cx="node.x"
              :cy="node.y"
              :r="node.radius + 6"
              fill="none"
              stroke="var(--accent)"
              stroke-width="2"
              class="selection-ring"
            />

            <!-- Main Circle -->
            <circle
              :cx="node.x"
              :cy="node.y"
              :r="node.radius"
              :fill="getNodeColor(node.type)"
              class="node-circle"
            />

            <!-- Label -->
            <text
              :x="node.x"
              :y="node.y + node.radius + 14"
              text-anchor="middle"
              class="node-label"
            >
              {{ node.label }}
            </text>
          </g>
        </g>
      </svg>

      <!-- Selection Panel / Card -->
      <div v-if="selectedNode" class="graph-selection-panel">
        <div class="panel-content">
          <span class="panel-type" :style="{ background: getNodeColor(selectedNode.type) }">
            {{ selectedNode.type === 'POST' ? '文章' : selectedNode.type === 'NOTE' ? '笔记' : selectedNode.type === 'DISH' ? '菜谱' : '标签' }}
          </span>
          <strong class="panel-title">{{ selectedNode.label }}</strong>
          <span v-if="selectedNode.category" class="panel-category">{{ selectedNode.category }}</span>
        </div>
        <div class="panel-actions">
          <button
            v-if="selectedNode.type !== 'TAG' && selectedNode.url"
            type="button"
            class="open-content-btn"
            @click="handleOpenContent"
          >
            打开内容 ↗
          </button>
          <button type="button" class="close-panel-btn" @click="clearSelection">
            关闭
          </button>
        </div>
      </div>

      <div class="canvas-legend">
        <span><i style="background: #3b82f6;" /> 文章</span>
        <span><i style="background: #10b981;" /> 学习笔记</span>
        <span><i style="background: #f59e0b;" /> 美食菜谱</span>
        <span><i style="background: #8b5cf6;" /> 标签</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.knowledge-graph-container {
  margin: 32px 0;
  padding: 24px;
  border-radius: 24px;
  background: var(--surface-solid);
  border: 1px solid var(--line-strong);
  box-shadow: var(--shadow-md);
  transition: opacity 0.2s ease, border-color 0.2s ease;
}
.knowledge-graph-container.is-fullscreen {
  position: fixed;
  inset: 0;
  z-index: 1300;
  margin: 0;
  border-radius: 0;
  overflow: auto;
}

.graph-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  flex-wrap: wrap;
  gap: 12px;
}
.graph-badge {
  font: 600 10px ui-monospace, Consolas, monospace;
  color: var(--accent);
  letter-spacing: 0.15em;
  display: block;
}
.graph-title h3 {
  margin: 2px 0 0;
  font-size: 18px;
  font-weight: 600;
  color: var(--ink);
}

.graph-filters {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.filter-pill {
  min-height: 44px;
  padding: 5px 12px;
  border-radius: 999px;
  background: var(--surface);
  border: 1px solid var(--line);
  color: var(--muted);
  font-size: 12px;
  cursor: pointer;
  transition: background 0.2s, color 0.2s;
}
.filter-pill:hover {
  color: var(--ink);
  border-color: var(--accent);
}
.filter-pill.active {
  background: var(--accent);
  color: #fff;
  border-color: var(--accent);
}

.fullscreen-btn {
  min-height: 44px;
  padding: 5px 14px;
  border-radius: 999px;
  background: var(--surface);
  border: 1px solid var(--line-strong);
  color: var(--ink);
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
}

.graph-state {
  display: grid;
  place-items: center;
  min-height: 320px;
  padding: 40px;
  text-align: center;
  color: var(--muted);
}

.svg-wrapper {
  position: relative;
  width: 100%;
  border-radius: 16px;
  background: var(--surface);
  border: 1px solid var(--line);
  overflow: hidden;
}
svg {
  display: block;
  width: 100%;
  height: auto;
  max-height: 500px;
}

.graph-edge {
  stroke: var(--line-strong);
  stroke-opacity: 0.4;
  stroke-width: 1.5px;
  transition: stroke 0.2s, stroke-opacity 0.2s, stroke-width 0.2s;
}
.graph-edge.highlighted {
  stroke: var(--accent);
  stroke-opacity: 1;
  stroke-width: 2.5px;
}
.graph-edge.faded {
  stroke-opacity: 0.1;
}

.graph-node {
  cursor: pointer;
  outline: none;
  transition: opacity 0.2s;
}
.graph-node:focus-visible .node-circle {
  stroke: var(--accent);
  stroke-width: 3px;
}
.graph-node.faded {
  opacity: 0.2;
}

.node-circle {
  transition: stroke 0.2s, stroke-width 0.2s;
}

.node-label {
  font-size: 12px;
  font-weight: 500;
  fill: var(--ink);
  pointer-events: none;
  user-select: none;
}

.graph-selection-panel {
  position: absolute;
  top: 16px;
  right: 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 18px;
  border-radius: 12px;
  background: var(--surface-solid);
  border: 1px solid var(--line-strong);
  box-shadow: var(--shadow-md);
  max-width: 380px;
  z-index: 10;
}
.panel-content {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}
.panel-type {
  display: inline-block;
  align-self: flex-start;
  padding: 2px 7px;
  border-radius: 4px;
  color: #fff;
  font-size: 10px;
  font-weight: 600;
}
.panel-title {
  font-size: 14px;
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.panel-category {
  font-size: 11px;
  color: var(--muted);
}

.panel-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}
.open-content-btn {
  min-height: 44px;
  padding: 6px 12px;
  border-radius: 6px;
  background: var(--accent);
  color: #fff;
  border: 0;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
}
.close-panel-btn {
  min-height: 44px;
  padding: 6px 10px;
  border-radius: 6px;
  background: transparent;
  color: var(--muted);
  border: 1px solid var(--line);
  font-size: 12px;
  cursor: pointer;
}

.canvas-legend {
  position: absolute;
  bottom: 16px;
  left: 16px;
  display: flex;
  gap: 14px;
  padding: 8px 14px;
  border-radius: 999px;
  background: var(--surface-solid);
  border: 1px solid var(--line);
  backdrop-filter: blur(10px);
  font-size: 12px;
  color: var(--muted);
}
.canvas-legend span {
  display: flex;
  align-items: center;
  gap: 6px;
}
.canvas-legend i {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

@media (prefers-reduced-motion: reduce) {
  .knowledge-graph-container,
  .graph-edge,
  .graph-node,
  .node-circle {
    transition: none !important;
  }
}

@media (max-width: 720px) {
  .knowledge-graph-container { padding: 14px; border-radius: 16px; }
  .graph-filters { gap: 4px; }
  .filter-pill, .fullscreen-btn { flex: 1 1 auto; }
  .graph-selection-panel { position: static; max-width: none; margin: 12px; flex-direction: column; align-items: stretch; }
  .panel-actions { justify-content: flex-end; }
  .canvas-legend { position: static; margin: 12px; flex-wrap: wrap; border-radius: 10px; }
}
</style>
