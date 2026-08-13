<script setup lang="ts">
import axios from 'axios';
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import {
  fetchGraphOverview,
  fetchGraphNodes,
  type GraphOverview,
  type GraphOverviewLegendItem,
  type GraphOverviewStats,
} from '../api/content';
import { useGraphSubgraph } from '../composables/useGraphSubgraph';
import { useGraphViewport } from '../composables/useGraphViewport';
import { computeGardenLayout, type VisualNode } from '../composables/useGraphLayout';

import GraphCanvas from './knowledge-graph/GraphCanvas.vue';
import GraphToolbar from './knowledge-graph/GraphToolbar.vue';
import GraphSidebar from './knowledge-graph/GraphSidebar.vue';
import GraphSearch from './knowledge-graph/GraphSearch.vue';
import GraphMiniMap from './knowledge-graph/GraphMiniMap.vue';
import GraphSelectionPanel from './knowledge-graph/GraphSelectionPanel.vue';

export interface GraphNode {
  id: string;
  label: string;
  type: 'POST' | 'NOTE' | 'DISH' | 'TAG' | 'SERIES' | 'ROOT' | string;
  url?: string | null;
  category?: string;
  summary?: string;
  kind?: 'ROOT' | 'GROUP' | 'CONTENT';
  groupId?: string | null;
  subtitle?: string | null;
  imageUrl?: string | null;
  updatedAt?: string | null;
  degree?: number;
  importance?: number;
}

export interface GraphEdge {
  source: string;
  target: string;
  kind?: 'STRUCTURE' | 'RELATION';
  strength?: number;
}

const props = withDefaults(
  defineProps<{
    initialNodes?: GraphNode[];
    initialEdges?: GraphEdge[];
    selectedRelation?: string;
  }>(),
  {
    initialNodes: () => [],
    initialEdges: () => [],
    selectedRelation: '',
  },
);

const emit = defineEmits<{
  (e: 'selectTag', tag: string): void;
  (e: 'selectNode', node: GraphNode | null): void;
}>();

const router = useRouter();
const containerRef = ref<HTMLElement | null>(null);

// Data state
const overview = ref<GraphOverview | null>(null);
const rawNodes = ref<GraphNode[]>([]);
const rawEdges = ref<GraphEdge[]>([]);
const loading = ref(false);
const loadError = ref('');

const selectedNodeId = ref<string | null>(null);
const hoveredNodeId = ref<string | null>(null);
const activeTypeFilter = ref<string>('');
const viewMode = ref<'canvas' | 'list'>('canvas');
const TREE_TYPES = new Set(['POST', 'NOTE', 'DISH']);

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
} = useGraphSubgraph();

// Viewport composable
const BASE_W = 1000;
const BASE_H = 680;
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
} = useGraphViewport(BASE_W, BASE_H, containerRef);

// Compute Garden Layout
const gardenLayout = computed(() => {
  return computeGardenLayout(rawNodes.value, rawEdges.value, BASE_W, BASE_H);
});

const visibleNodesList = computed(() => {
  let list = gardenLayout.value.nodesList;
  if (activeTypeFilter.value) {
    list = list.filter((n) => n.kind === 'ROOT' || n.kind === 'GROUP' || n.type === activeTypeFilter.value);
  }
  return list;
});

const visibleEdgesList = computed(() => {
  const visibleIds = new Set(visibleNodesList.value.map((n) => n.id));
  return gardenLayout.value.edgesList.filter((e) => visibleIds.has(e.source) && visibleIds.has(e.target));
});

const accessibleEdges = computed(() => {
  const accessibleNodes = rawNodes.value.filter(
    (node) =>
      !activeTypeFilter.value ||
      node.type === activeTypeFilter.value ||
      node.kind === 'ROOT' ||
      node.kind === 'GROUP',
  );
  const visibleIds = new Set(accessibleNodes.map((node) => node.id));
  const labels = new Map(accessibleNodes.map((node) => [node.id, node.label]));
  return rawEdges.value
    .filter((edge) => visibleIds.has(edge.source) && visibleIds.has(edge.target))
    .map((edge) => ({
      ...edge,
      sourceLabel: labels.get(edge.source) || edge.source,
      targetLabel: labels.get(edge.target) || edge.target,
    }));
});

// Neighbor highlighting calculation
const neighborNodeIds = computed(() => {
  const targetId = selectedNodeId.value || hoveredNodeId.value;
  if (!targetId) return new Set<string>();
  const set = new Set<string>([targetId]);
  rawEdges.value.forEach((e) => {
    if (e.source === targetId) set.add(e.target);
    if (e.target === targetId) set.add(e.source);
  });
  return set;
});

const selectedVisualNode = computed(() => {
  if (!selectedNodeId.value) return null;
  return gardenLayout.value.nodesMap.get(selectedNodeId.value) || null;
});

// Legend & Stats calculation
const legendItems = computed<GraphOverviewLegendItem[]>(() => {
  if (overview.value?.legend && overview.value.legend.length > 0) {
    return overview.value.legend.filter((item) => TREE_TYPES.has(item.type));
  }
  // Fallback count from current rawNodes
  const counts: Record<string, number> = {};
  rawNodes.value.forEach((n) => {
    if (n.kind !== 'ROOT' && n.kind !== 'GROUP') {
      counts[n.type] = (counts[n.type] || 0) + 1;
    }
  });
  return [
    { type: 'POST', label: '文章', color: '#3b82f6', count: counts['POST'] || 0 },
    { type: 'NOTE', label: '学习笔记', color: '#ef6c9a', count: counts['NOTE'] || 0 },
    { type: 'DISH', label: '美食菜谱', color: '#f59e0b', count: counts['DISH'] || 0 },
  ];
});

const statsData = computed<GraphOverviewStats | null>(() => {
  if (overview.value?.stats) {
    return overview.value.stats;
  }
  return {
    contentNodeCount: rawNodes.value.filter(
      (n) => n.kind !== 'ROOT' && n.kind !== 'GROUP' && TREE_TYPES.has(n.type),
    ).length,
    visualNodeCount: rawNodes.value.length,
    relationCount: rawEdges.value.length,
    lastUpdatedAt: null,
    recommendedCenterId: '',
    localModeRecommended: rawNodes.value.length > 300,
  };
});

// Load graph data
async function loadGraphData() {
  if (props.initialNodes && props.initialNodes.length > 0) {
    rawNodes.value = props.initialNodes;
    rawEdges.value = props.initialEdges || [];
    saveSnapshot(rawNodes.value as unknown as GraphNode[], rawEdges.value as unknown as GraphEdge[]);
    if (rawNodes.value.length > 300) {
      const result = await autoLocalSubgraph(
        rawNodes.value as unknown as GraphNode[],
        rawEdges.value as unknown as GraphEdge[],
      );
      if (result) {
        rawNodes.value = result.nodes as GraphNode[];
        rawEdges.value = result.edges as GraphEdge[];
      }
    }
    return;
  }

  loading.value = true;
  loadError.value = '';
  try {
    // Try V2 Overview API first
    try {
      const data = await fetchGraphOverview();
      overview.value = data;
      rawNodes.value = data.nodes as GraphNode[];
      rawEdges.value = data.edges as GraphEdge[];
      saveSnapshot(rawNodes.value as unknown as GraphNode[], rawEdges.value as unknown as GraphEdge[]);

      if (data.stats.localModeRecommended || data.nodes.length > 300) {
        const result = await autoLocalSubgraph(
          rawNodes.value as unknown as GraphNode[],
          rawEdges.value as unknown as GraphEdge[],
        );
        if (result) {
          rawNodes.value = result.nodes as GraphNode[];
          rawEdges.value = result.edges as GraphEdge[];
        }
      }
    } catch {
      // Fallback to legacy GET /api/v1/graph/nodes
      const legacy = await fetchGraphNodes();
      rawNodes.value = (legacy.nodes || []) as GraphNode[];
      rawEdges.value = (legacy.edges || []) as GraphEdge[];
      saveSnapshot(rawNodes.value as unknown as GraphNode[], rawEdges.value as unknown as GraphEdge[]);

      if (rawNodes.value.length > 300) {
        const result = await autoLocalSubgraph(
          rawNodes.value as unknown as GraphNode[],
          rawEdges.value as unknown as GraphEdge[],
        );
        if (result) {
          rawNodes.value = result.nodes as GraphNode[];
          rawEdges.value = result.edges as GraphEdge[];
        }
      }
    }
  } catch (cause) {
    loadError.value =
      axios.isAxiosError(cause) && cause.response ? '数据加载失败' : '无法连接网络，图谱数据加载失败';
  } finally {
    loading.value = false;
  }
}

// Handlers
function handleSelectNode(node: VisualNode) {
  if (selectedNodeId.value === node.id) {
    if (node.type === 'TAG') emit('selectTag', '');
    selectedNodeId.value = null;
    emit('selectNode', null);
    return;
  }
  selectedNodeId.value = node.id;
  emit('selectNode', node as unknown as GraphNode);

  if (node.type === 'TAG') {
    const tagText = node.label.replace(/^#/, '');
    emit('selectTag', tagText);
  }
}

function handleDblClickNode(node: VisualNode) {
  if (node.kind !== 'CONTENT') return;
  selectedNodeId.value = node.id;
  void doExpandRelations();
}

function clearSelection() {
  if (selectedVisualNode.value?.type === 'TAG') emit('selectTag', '');
  selectedNodeId.value = null;
  emit('selectNode', null);
}

function handleSearchSelect(node: VisualNode) {
  handleSelectNode(node);
  centerOn(node.x, node.y, 1.3);
}

function handleOpenContent() {
  if (selectedVisualNode.value?.url && selectedVisualNode.value.type !== 'TAG') {
    void router.push(selectedVisualNode.value.url);
  }
}

async function doExpandRelations() {
  if (!selectedNodeId.value) return;
  const result = await expandSubgraph(
    selectedNodeId.value,
    2,
    rawNodes.value as unknown as GraphNode[],
    rawEdges.value as unknown as GraphEdge[],
  );
  if (result) {
    rawNodes.value = result.nodes as GraphNode[];
    rawEdges.value = result.edges as GraphEdge[];
  }
}

function downloadGraphFile(content: string, type: string, extension: string) {
  const url = URL.createObjectURL(new Blob([content], { type }));
  const link = document.createElement('a');
  link.href = url;
  link.download = `knowledge-graph-${new Date().toLocaleDateString('sv-SE')}.${extension}`;
  link.click();
  URL.revokeObjectURL(url);
}

function exportGraphJson() {
  downloadGraphFile(
    JSON.stringify(
      {
        schemaVersion: '2.0',
        exportedAt: new Date().toISOString(),
        filter: activeTypeFilter.value || null,
        nodes: visibleNodesList.value.map(({ x: _x, y: _y, ...node }) => node),
        edges: visibleEdgesList.value.map(({ pathD: _path, ...edge }) => edge),
      },
      null,
      2,
    ),
    'application/json;charset=utf-8',
    'json',
  );
}

function exportGraphImage() {
  const source = containerRef.value?.querySelector('svg.graph-svg');
  if (!source) return;
  const clone = source.cloneNode(true) as SVGSVGElement;
  clone.setAttribute('xmlns', 'http://www.w3.org/2000/svg');
  clone.setAttribute('width', String(BASE_W));
  clone.setAttribute('height', String(BASE_H));
  const metadata = document.createElementNS('http://www.w3.org/2000/svg', 'metadata');
  metadata.textContent = JSON.stringify({ schemaVersion: '2.0', exportedAt: new Date().toISOString() });
  clone.insertBefore(metadata, clone.firstChild);
  downloadGraphFile(new XMLSerializer().serializeToString(clone), 'image/svg+xml;charset=utf-8', 'svg');
}

async function doReturnToOverview() {
  const snapshot = restoreOverview();
  if (snapshot) {
    rawNodes.value = snapshot.nodes as GraphNode[];
    rawEdges.value = snapshot.edges as GraphEdge[];
    resetView();
  }
}

async function handleToggleFullscreen() {
  await toggleFullscreen();
  resetView();
  requestAnimationFrame(resetView);
}

function handleKeyDown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    clearSelection();
  }
}

watch(
  () => props.initialNodes,
  (newNodes) => {
    if (newNodes && newNodes.length > 0) {
      rawNodes.value = newNodes;
      rawEdges.value = props.initialEdges || [];
      loadError.value = '';
    }
  },
  { immediate: true },
);

watch(
  [() => props.selectedRelation, rawNodes],
  ([relation]) => {
    const normalized = relation.trim().toLocaleLowerCase();
    if (!normalized) {
      if (selectedVisualNode.value?.type === 'TAG') selectedNodeId.value = null;
      return;
    }
    const matchingTag = rawNodes.value.find(
      (node) => node.type === 'TAG' && node.label.replace(/^#/, '').trim().toLocaleLowerCase() === normalized,
    );
    selectedNodeId.value = matchingTag?.id || null;
  },
  { immediate: true },
);

onMounted(() => {
  void loadGraphData();
  window.addEventListener('keydown', handleKeyDown);
});

onUnmounted(() => {
  window.removeEventListener('keydown', handleKeyDown);
});
</script>

<template>
  <div ref="containerRef" class="knowledge-graph-v2-container" :class="{ 'is-fullscreen': isFullscreen }">
    <!-- Header Toolbar Bar -->
    <header class="graph-header">
      <div class="header-title">
        <span class="graph-badge">✦ KNOWLEDGE GRAPH V2</span>
        <h2>全站知识关联图谱</h2>
      </div>

      <button
        type="button"
        class="graph-view-toggle"
        :aria-pressed="viewMode === 'list'"
        @click="viewMode = viewMode === 'canvas' ? 'list' : 'canvas'"
      >
        {{ viewMode === 'canvas' ? '切换到关系列表' : '切换到图形视图' }}
      </button>

      <GraphToolbar
        :is-fullscreen="isFullscreen"
        :local-mode="localMode"
        :subgraph-active="subgraphActive"
        @zoom-in="zoomBy(1.25)"
        @zoom-out="zoomBy(1 / 1.25)"
        @reset="resetView"
        @toggle-fullscreen="handleToggleFullscreen"
        @return-overview="doReturnToOverview"
        @export-json="exportGraphJson"
        @export-image="exportGraphImage"
      />
    </header>

    <!-- Status Notice Bar -->
    <div v-if="localMode" class="status-notice local-notice graph-local-mode" role="status">
      <span>局部图谱模式 · 聚焦「{{ localModeCenter }}」</span>
      <button type="button" class="btn-text return-overview-btn-inline" @click="doReturnToOverview">
        返回全图
      </button>
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
        <div v-if="viewMode === 'list'" class="graph-list-view" role="region" aria-label="知识图谱关系列表">
          <div v-if="loading" class="stage-state" role="status">正在加载知识图谱</div>
          <div v-else-if="loadError" class="stage-state" role="alert">
            <p>{{ loadError }}</p>
            <button class="button primary" type="button" @click="loadGraphData">重试</button>
          </div>
          <div v-else-if="visibleNodesList.length === 0" class="stage-state">
            <p>暂无符合条件的关联节点。</p>
          </div>
          <template v-else>
            <h3>节点列表</h3>
            <p class="graph-list-help">这是图形视图的无障碍等价内容，适合键盘、读屏和窄屏浏览。</p>
            <div class="graph-table-wrap">
              <table class="graph-accessible-table">
                <caption class="sr-only">
                  知识图谱节点
                </caption>
                <thead>
                  <tr>
                    <th scope="col">名称</th>
                    <th scope="col">类型</th>
                    <th scope="col">连接数</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="node in visibleNodesList" :key="node.id">
                    <th scope="row">{{ node.label }}</th>
                    <td>{{ node.type }}</td>
                    <td>{{ node.degree ?? 0 }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <h3>关系列表</h3>
            <ul class="graph-relation-list" aria-label="知识图谱关系">
              <li v-for="edge in accessibleEdges" :key="`${edge.source}:${edge.target}`">
                <span>{{ edge.sourceLabel }}</span
                ><span aria-hidden="true"> → </span><span>{{ edge.targetLabel }}</span>
              </li>
              <li v-if="accessibleEdges.length === 0">暂无关系</li>
            </ul>
          </template>
        </div>
        <!-- Skeleton Loading State -->
        <div v-if="viewMode === 'canvas' && loading" class="stage-state stage-loading" role="status">
          <div class="skeleton-flower">🌸</div>
          <span>正在生根发芽，载入全站知识图谱…</span>
        </div>

        <!-- Error State -->
        <div v-else-if="viewMode === 'canvas' && loadError" class="stage-state stage-error" role="alert">
          <p>{{ loadError }}</p>
          <button class="button primary" type="button" @click="loadGraphData">重试</button>
        </div>

        <!-- Empty State -->
        <div
          v-else-if="viewMode === 'canvas' && visibleNodesList.length === 0"
          class="stage-state stage-empty"
        >
          <p>暂无符合条件的关联节点。</p>
        </div>

        <!-- Interactive SVG Canvas -->
        <template v-else-if="viewMode === 'canvas'">
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
          <GraphSearch :nodes="gardenLayout.nodesList" class="floating-search" @select="handleSearchSelect" />

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
  font:
    700 10px ui-monospace,
    Consolas,
    monospace;
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

.graph-view-toggle {
  margin-left: auto;
  border: 1px solid var(--line, rgba(0, 0, 0, 0.12));
  border-radius: 9px;
  padding: 8px 12px;
  background: var(--surface, #fff);
  color: var(--ink, #1e293b);
  cursor: pointer;
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

.graph-list-view {
  min-height: 520px;
  padding: 24px;
  overflow: auto;
  color: var(--ink, #1e293b);
}
.graph-list-view h3 {
  margin: 0 0 8px;
  font-size: 18px;
}
.graph-list-view h3:not(:first-child) {
  margin-top: 28px;
}
.graph-list-help {
  margin: 0 0 16px;
  color: var(--muted, #64748b);
  font-size: 13px;
}
.graph-table-wrap {
  overflow-x: auto;
}
.graph-accessible-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.graph-accessible-table th,
.graph-accessible-table td {
  padding: 9px 10px;
  border-bottom: 1px solid var(--line, rgba(0, 0, 0, 0.08));
  text-align: left;
}
.graph-relation-list {
  display: grid;
  gap: 8px;
  margin: 0;
  padding-left: 20px;
  font-size: 13px;
}
.graph-relation-list li {
  line-height: 1.45;
}
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
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
