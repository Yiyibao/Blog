<script setup lang="ts">
import axios from 'axios'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  fetchGraphOverview,
  fetchGraphNodes,
  type GraphOverview,
  type GraphOverviewLegendItem,
  type GraphOverviewStats,
} from '../api/content'
import { useGraphSubgraph } from '../composables/useGraphSubgraph'
import { useGraphViewport } from '../composables/useGraphViewport'
import { computeGardenLayout, type VisualNode } from '../composables/useGraphLayout'

import GraphCanvas from './knowledge-graph/GraphCanvas.vue'
import GraphToolbar from './knowledge-graph/GraphToolbar.vue'
import GraphSidebar from './knowledge-graph/GraphSidebar.vue'
import GraphSearch from './knowledge-graph/GraphSearch.vue'
import GraphMiniMap from './knowledge-graph/GraphMiniMap.vue'
import GraphSelectionPanel from './knowledge-graph/GraphSelectionPanel.vue'

export interface GraphNode {
  id: string
  label: string
  type: 'POST' | 'NOTE' | 'DISH' | 'TAG' | 'SERIES' | 'ROOT' | string
  url?: string | null
  category?: string
  summary?: string
  kind?: 'ROOT' | 'GROUP' | 'CONTENT'
  groupId?: string | null
  subtitle?: string | null
  imageUrl?: string | null
  updatedAt?: string | null
  degree?: number
  importance?: number
}

export interface GraphEdge {
  source: string
  target: string
  kind?: 'STRUCTURE' | 'RELATION'
  strength?: number
}

const props = withDefaults(
  defineProps<{
    initialNodes?: GraphNode[]
    initialEdges?: GraphEdge[]
    selectedRelation?: string
  }>(),
  {
    initialNodes: () => [],
    initialEdges: () => [],
    selectedRelation: '',
  }
)

const emit = defineEmits<{
  (e: 'selectTag', tag: string): void
  (e: 'selectNode', node: GraphNode | null): void
}>()

const router = useRouter()
const containerRef = ref<HTMLElement | null>(null)

// Data state
const overview = ref<GraphOverview | null>(null)
const rawNodes = ref<GraphNode[]>([])
const rawEdges = ref<GraphEdge[]>([])
const loading = ref(false)
const loadError = ref('')

const selectedNodeId = ref<string | null>(null)
const hoveredNodeId = ref<string | null>(null)
const activeTypeFilter = ref<string>('')

// Subgraph & Local Mode composable
const {
  subgraphLoading,
  subgraphError,
  subgraphActive,
  localMode,
  localModeCenter,
  saveSnapshot,
  restoreOverview,
  expandSubgraph,
  autoLocalSubgraph,
} = useGraphSubgraph()

// Viewport composable
const BASE_W = 1000
const BASE_H = 680
const {
  zoom,
  panX,
  panY,
  isFullscreen,
  viewBox,
  zoomBy,
  resetView,
  centerOn,
  onPointerDown,
  onPointerMove,
  onPointerUp,
  onWheel,
  toggleFullscreen,
} = useGraphViewport(BASE_W, BASE_H, containerRef)

// Compute Garden Layout
const gardenLayout = computed(() => {
  return computeGardenLayout(
    rawNodes.value,
    rawEdges.value,
    BASE_W,
    BASE_H
  )
})

const visibleNodesList = computed(() => {
  let list = gardenLayout.value.nodesList
  if (activeTypeFilter.value) {
    list = list.filter((n) => n.kind === 'ROOT' || n.kind === 'GROUP' || n.type === activeTypeFilter.value)
  }
  return list
})

const visibleEdgesList = computed(() => {
  const visibleIds = new Set(visibleNodesList.value.map((n) => n.id))
  return gardenLayout.value.edgesList.filter(
    (e) => visibleIds.has(e.source) && visibleIds.has(e.target)
  )
})

// Neighbor highlighting calculation
const neighborNodeIds = computed(() => {
  const targetId = selectedNodeId.value || hoveredNodeId.value
  if (!targetId) return new Set<string>()
  const set = new Set<string>([targetId])
  rawEdges.value.forEach((e) => {
    if (e.source === targetId) set.add(e.target)
    if (e.target === targetId) set.add(e.source)
  })
  return set
})

const selectedVisualNode = computed(() => {
  if (!selectedNodeId.value) return null
  return gardenLayout.value.nodesMap.get(selectedNodeId.value) || null
})

// Legend & Stats calculation
const legendItems = computed<GraphOverviewLegendItem[]>(() => {
  if (overview.value?.legend && overview.value.legend.length > 0) {
    return overview.value.legend
  }
  // Fallback count from current rawNodes
  const counts: Record<string, number> = {}
  rawNodes.value.forEach((n) => {
    if (n.kind !== 'ROOT' && n.kind !== 'GROUP') {
      counts[n.type] = (counts[n.type] || 0) + 1
    }
  })
  return [
    { type: 'POST', label: '文章', color: '#3b82f6', count: counts['POST'] || 0 },
    { type: 'NOTE', label: '学习笔记', color: '#10b981', count: counts['NOTE'] || 0 },
    { type: 'DISH', label: '美食菜谱', color: '#f59e0b', count: counts['DISH'] || 0 },
    { type: 'SERIES', label: '合集', color: '#ec4899', count: counts['SERIES'] || 0 },
    { type: 'TAG', label: '标签', color: '#8b5cf6', count: counts['TAG'] || 0 },
  ]
})

const statsData = computed<GraphOverviewStats | null>(() => {
  if (overview.value?.stats) {
    return overview.value.stats
  }
  return {
    contentNodeCount: rawNodes.value.filter((n) => n.kind !== 'ROOT' && n.kind !== 'GROUP').length,
    visualNodeCount: rawNodes.value.length,
    relationCount: rawEdges.value.length,
    lastUpdatedAt: null,
    recommendedCenterId: '',
    localModeRecommended: rawNodes.value.length > 300,
  }
})

// Load graph data
async function loadGraphData() {
  if (props.initialNodes && props.initialNodes.length > 0) {
    rawNodes.value = props.initialNodes
    rawEdges.value = props.initialEdges || []
    saveSnapshot(rawNodes.value as unknown as GraphNode[], rawEdges.value as unknown as GraphEdge[])
    if (rawNodes.value.length > 300) {
      const result = await autoLocalSubgraph(rawNodes.value as unknown as GraphNode[], rawEdges.value as unknown as GraphEdge[])
      if (result) {
        rawNodes.value = result.nodes as GraphNode[]
        rawEdges.value = result.edges as GraphEdge[]
      }
    }
    return
  }

  loading.value = true
  loadError.value = ''
  try {
    // Try V2 Overview API first
    try {
      const data = await fetchGraphOverview()
      overview.value = data
      rawNodes.value = data.nodes as GraphNode[]
      rawEdges.value = data.edges as GraphEdge[]
      saveSnapshot(rawNodes.value as unknown as GraphNode[], rawEdges.value as unknown as GraphEdge[])

      if (data.stats.localModeRecommended || data.nodes.length > 300) {
        const result = await autoLocalSubgraph(rawNodes.value as unknown as GraphNode[], rawEdges.value as unknown as GraphEdge[])
        if (result) {
          rawNodes.value = result.nodes as GraphNode[]
          rawEdges.value = result.edges as GraphEdge[]
        }
      }
    } catch {
      // Fallback to legacy GET /api/v1/graph/nodes
      const legacy = await fetchGraphNodes()
      rawNodes.value = (legacy.nodes || []) as GraphNode[]
      rawEdges.value = (legacy.edges || []) as GraphEdge[]
      saveSnapshot(rawNodes.value as unknown as GraphNode[], rawEdges.value as unknown as GraphEdge[])

      if (rawNodes.value.length > 300) {
        const result = await autoLocalSubgraph(rawNodes.value as unknown as GraphNode[], rawEdges.value as unknown as GraphEdge[])
        if (result) {
          rawNodes.value = result.nodes as GraphNode[]
          rawEdges.value = result.edges as GraphEdge[]
        }
      }
    }
  } catch (cause) {
    loadError.value = axios.isAxiosError(cause) && cause.response
      ? '数据加载失败'
      : '无法连接网络，图谱数据加载失败'
  } finally {
    loading.value = false
  }
}

// Handlers
function handleSelectNode(node: VisualNode) {
  if (selectedNodeId.value === node.id) {
    if (node.type === 'TAG') emit('selectTag', '')
    selectedNodeId.value = null
    emit('selectNode', null)
    return
  }
  selectedNodeId.value = node.id
  emit('selectNode', node as unknown as GraphNode)

  if (node.type === 'TAG') {
    const tagText = node.label.replace(/^#/, '')
    emit('selectTag', tagText)
  }
}

function handleDblClickNode(node: VisualNode) {
  if (node.kind !== 'CONTENT') return
  selectedNodeId.value = node.id
  void doExpandRelations()
}

function clearSelection() {
  if (selectedVisualNode.value?.type === 'TAG') emit('selectTag', '')
  selectedNodeId.value = null
  emit('selectNode', null)
}

function handleSearchSelect(node: VisualNode) {
  handleSelectNode(node)
  centerOn(node.x, node.y, 1.3)
}

function handleOpenContent() {
  if (selectedVisualNode.value?.url && selectedVisualNode.value.type !== 'TAG') {
    void router.push(selectedVisualNode.value.url)
  }
}

async function doExpandRelations() {
  if (!selectedNodeId.value) return
  const result = await expandSubgraph(
    selectedNodeId.value,
    2,
    rawNodes.value as unknown as GraphNode[],
    rawEdges.value as unknown as GraphEdge[]
  )
  if (result) {
    rawNodes.value = result.nodes as GraphNode[]
    rawEdges.value = result.edges as GraphEdge[]
  }
}

async function doReturnToOverview() {
  const snapshot = restoreOverview()
  if (snapshot) {
    rawNodes.value = snapshot.nodes as GraphNode[]
    rawEdges.value = snapshot.edges as GraphEdge[]
    resetView()
  }
}

async function handleToggleFullscreen() {
  await toggleFullscreen()
  resetView()
  requestAnimationFrame(resetView)
}

function handleKeyDown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    clearSelection()
  }
}

watch(
  () => props.initialNodes,
  (newNodes) => {
    if (newNodes && newNodes.length > 0) {
      rawNodes.value = newNodes
      rawEdges.value = props.initialEdges || []
      loadError.value = ''
    }
  },
  { immediate: true }
)

watch([() => props.selectedRelation, rawNodes], ([relation]) => {
  const normalized = relation.trim().toLocaleLowerCase()
  if (!normalized) {
    if (selectedVisualNode.value?.type === 'TAG') selectedNodeId.value = null
    return
  }
  const matchingTag = rawNodes.value.find((node) => node.type === 'TAG'
    && node.label.replace(/^#/, '').trim().toLocaleLowerCase() === normalized)
  selectedNodeId.value = matchingTag?.id || null
}, { immediate: true })

onMounted(() => {
  void loadGraphData()
  window.addEventListener('keydown', handleKeyDown)
})

onUnmounted(() => {
  window.removeEventListener('keydown', handleKeyDown)
})
</script>

<template>
  <div
    ref="containerRef"
    class="knowledge-graph-v2-container"
    :class="{ 'is-fullscreen': isFullscreen }"
  >
    <!-- Header Toolbar Bar -->
    <header class="graph-header">
      <div class="header-title">
        <span class="graph-badge">✦ KNOWLEDGE GRAPH V2</span>
        <h2>全站知识关联图谱</h2>
      </div>

      <GraphToolbar
        :is-fullscreen="isFullscreen"
        :local-mode="localMode"
        :subgraph-active="subgraphActive"
        @zoom-in="zoomBy(1.25)"
        @zoom-out="zoomBy(1 / 1.25)"
        @reset="resetView"
        @toggle-fullscreen="handleToggleFullscreen"
        @return-overview="doReturnToOverview"
      />
    </header>

    <!-- Status Notice Bar -->
    <div v-if="localMode" class="status-notice local-notice graph-local-mode" role="status">
      <span>局部图谱模式 · 聚焦「{{ localModeCenter }}」</span>
      <button type="button" class="btn-text return-overview-btn-inline" @click="doReturnToOverview">返回全图</button>
    </div>

    <div v-if="subgraphLoading && !loading" class="status-notice info-notice" role="status">
      <span>正在展开关联节点…</span>
    </div>

    <div v-if="subgraphError" class="status-notice error-notice" role="alert">
      <span>{{ subgraphError }}</span>
      <button type="button" class="btn-text" @click="doExpandRelations">重试</button>
    </div>

    <!-- Main Workspace Layout -->
    <div class="graph-body">
      <!-- Left Sidebar -->
      <GraphSidebar
        :legend="legendItems"
        :stats="statsData"
        :active-type-filter="activeTypeFilter"
        @filter-type="activeTypeFilter = activeTypeFilter === $event ? '' : $event"
      />

      <!-- Center Main Canvas Stage -->
      <div class="canvas-stage glass-card">
        <!-- Skeleton Loading State -->
        <div v-if="loading" class="stage-state stage-loading" role="status">
          <div class="skeleton-flower">🌸</div>
          <span>正在生根发芽，载入全站知识图谱…</span>
        </div>

        <!-- Error State -->
        <div v-else-if="loadError" class="stage-state stage-error" role="alert">
          <p>{{ loadError }}</p>
          <button class="button primary" type="button" @click="loadGraphData">重试</button>
        </div>

        <!-- Empty State -->
        <div v-else-if="visibleNodesList.length === 0" class="stage-state stage-empty">
          <p>暂无符合条件的关联节点。</p>
        </div>

        <!-- Interactive SVG Canvas -->
        <template v-else>
          <GraphCanvas
            :nodes="visibleNodesList"
            :edges="visibleEdgesList"
            :view-box="viewBox"
            :selected-node-id="selectedNodeId"
            :hovered-node-id="hoveredNodeId"
            :neighbor-node-ids="neighborNodeIds"
            :base-width="BASE_W"
            :base-height="BASE_H"
            @select-node="handleSelectNode"
            @dblclick-node="handleDblClickNode"
            @hover-node="hoveredNodeId = $event"
            @pointer-down="onPointerDown"
            @pointer-move="onPointerMove"
            @pointer-up="onPointerUp"
            @wheel="onWheel"
          />

          <!-- Floating Search Box -->
          <GraphSearch
            :nodes="gardenLayout.nodesList"
            class="floating-search"
            @select="handleSearchSelect"
          />

          <!-- Floating MiniMap -->
          <GraphMiniMap
            :nodes="gardenLayout.nodesList"
            :zoom="zoom"
            :pan-x="panX"
            :pan-y="panY"
            :base-width="BASE_W"
            :base-height="BASE_H"
            @pan-to="(x, y) => centerOn(x, y)"
          />

          <!-- Floating Selected Node Details Panel -->
          <GraphSelectionPanel
            v-if="selectedVisualNode"
            :node="selectedVisualNode"
            :subgraph-loading="subgraphLoading"
            @expand="doExpandRelations"
            @open="handleOpenContent"
            @close="clearSelection"
          />
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.knowledge-graph-v2-container {
  margin: 24px 0 40px 0;
  padding: 24px;
  border-radius: 28px;
  background: linear-gradient(135deg, rgba(255, 251, 245, 0.95), rgba(254, 242, 242, 0.85));
  border: 1px solid var(--line, rgba(244, 63, 94, 0.12));
  box-shadow: var(--shadow-md, 0 12px 32px rgba(244, 63, 94, 0.06));
  display: flex;
  flex-direction: column;
  gap: 16px;
  transition: all 0.3s ease;
}

:deep(.dark),
[data-theme='dark'] .knowledge-graph-v2-container {
  background: var(--surface-solid, #1e293b);
  border-color: var(--line-strong, #334155);
}

.knowledge-graph-v2-container.is-fullscreen {
  position: fixed;
  inset: 0;
  z-index: 1300;
  margin: 0;
  border-radius: 0;
  overflow: auto;
}

.graph-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
}

.graph-badge {
  font: 700 10px ui-monospace, Consolas, monospace;
  color: var(--accent, #f43f5e);
  letter-spacing: 0.15em;
  display: block;
}

.header-title h2 {
  margin: 2px 0 0 0;
  font-size: 22px;
  font-weight: 700;
  color: var(--ink, #1e293b);
}

.status-notice {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 16px;
  border-radius: 12px;
  font-size: 13px;
  font-weight: 500;
}

.local-notice {
  background: rgba(244, 63, 94, 0.1);
  color: var(--accent, #f43f5e);
}
.info-notice {
  background: rgba(59, 130, 246, 0.1);
  color: #2563eb;
}
.error-notice {
  background: rgba(239, 68, 68, 0.1);
  color: #dc2626;
}

.btn-text {
  border: 0;
  background: transparent;
  color: currentColor;
  font-weight: 700;
  cursor: pointer;
  text-decoration: underline;
}

.graph-body {
  display: flex;
  gap: 20px;
  align-items: stretch;
}

.canvas-stage {
  flex: 1;
  position: relative;
  min-height: 580px;
  border-radius: 20px;
  background: var(--surface, rgba(255, 255, 255, 0.7));
  border: 1px solid var(--line, rgba(0, 0, 0, 0.06));
  overflow: hidden;
  box-shadow: inset 0 2px 8px rgba(0, 0, 0, 0.02);
}

.stage-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  height: 100%;
  min-height: 520px;
  color: var(--muted, #64748b);
  font-size: 14px;
}

.skeleton-flower {
  font-size: 42px;
  animation: pulse-flower 1.8s ease-in-out infinite alternate;
}

@keyframes pulse-flower {
  from {
    transform: scale(0.9) rotate(-5deg);
  }
  to {
    transform: scale(1.1) rotate(5deg);
  }
}

.floating-search {
  position: absolute;
  bottom: 16px;
  left: 16px;
  z-index: 10;
}

@media (max-width: 860px) {
  .graph-body {
    flex-direction: column;
  }
  .canvas-stage {
    min-height: 480px;
  }
}
</style>
