<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'

export interface GraphNode {
  id: string
  label: string
  type: 'POST' | 'NOTE' | 'DISH' | 'TAG'
  url: string
  x?: number
  y?: number
  vx?: number
  vy?: number
  radius?: number
}

export interface GraphEdge {
  source: string
  target: string
}

const props = withDefaults(
  defineProps<{
    initialNodes?: GraphNode[]
    initialEdges?: GraphEdge[]
  }>(),
  {
    initialNodes: () => [],
    initialEdges: () => [],
  }
)

const router = useRouter()
const canvasRef = ref<HTMLCanvasElement | null>(null)
const isFullscreen = ref(false)
const filterType = ref<'ALL' | 'POST' | 'NOTE' | 'DISH' | 'TAG'>('ALL')

// Built-in fallback demo nodes if API data is loading/unavailable
const defaultNodes: GraphNode[] = [
  { id: 'p1', label: '设计系统与透明度', type: 'POST', url: '/articles/clarity-by-design' },
  { id: 'p2', label: 'Vue 3 响应式原理深度拆解', type: 'POST', url: '/articles' },
  { id: 'p3', label: 'TypeScript 高级类型体操', type: 'POST', url: '/articles' },
  { id: 'n1', label: 'Canvas 性能优化指南', type: 'NOTE', url: '/notes' },
  { id: 'n2', label: 'Web Audio 音频合成实践', type: 'NOTE', url: '/notes' },
  { id: 'd1', label: '糖醋排骨制作心得', type: 'DISH', url: '/recipes?dish=sweet-sour-pork' },
  { id: 'd2', label: '麻婆豆腐秘制高汤', type: 'DISH', url: '/recipes' },
  { id: 't1', label: '#前端架构', type: 'TAG', url: '/categories' },
  { id: 't2', label: '#美食生活', type: 'TAG', url: '/categories' },
  { id: 't3', label: '#TypeScript', type: 'TAG', url: '/categories' },
]

const defaultEdges: GraphEdge[] = [
  { source: 'p1', target: 't1' },
  { source: 'p2', target: 't1' },
  { source: 'p3', target: 't3' },
  { source: 'n1', target: 't1' },
  { source: 'n2', target: 't1' },
  { source: 'd1', target: 't2' },
  { source: 'd2', target: 't2' },
  { source: 'p1', target: 'n1' },
  { source: 'p2', target: 'p3' },
]

const nodes = ref<GraphNode[]>([])
const edges = ref<GraphEdge[]>([])
let animFrameId: number | null = null
let hoveredNodeId: string | null = null
let draggedNode: GraphNode | null = null
let isDragging = false

// Canvas pan & zoom offset
const scale = ref(1)
const panX = ref(0)
const panY = ref(0)
let startPanX = 0
let startPanY = 0
let startMouseX = 0
let startMouseY = 0

function getNodeColor(type: GraphNode['type']): string {
  switch (type) {
    case 'POST': return '#3b82f6' // Blue
    case 'NOTE': return '#10b981' // Emerald
    case 'DISH': return '#f59e0b' // Amber
    case 'TAG': return '#8b5cf6'  // Purple
  }
}

function getNodeRadius(type: GraphNode['type']): number {
  return type === 'TAG' ? 18 : 22
}

async function fetchRemoteGraphData() {
  try {
    const res = await fetch('/api/v1/graph/nodes')
    if (res.ok) {
      const json = await res.json()
      if (json.code === 200 && json.data) {
        nodes.value = json.data.nodes || defaultNodes
        edges.value = json.data.edges || defaultEdges
        initPhysicsPositions()
        return
      }
    }
  } catch {
    // fallback
  }
  nodes.value = props.initialNodes.length ? props.initialNodes : defaultNodes
  edges.value = props.initialEdges.length ? props.initialEdges : defaultEdges
  initPhysicsPositions()
}

function initPhysicsPositions() {
  const canvas = canvasRef.value
  const width = canvas ? canvas.width : 800
  const height = canvas ? canvas.height : 450
  const cx = width / 2
  const cy = height / 2

  nodes.value.forEach((node, idx) => {
    const angle = (idx / nodes.value.length) * Math.PI * 2
    const radius = 140 + Math.random() * 80
    node.x = cx + Math.cos(angle) * radius
    node.y = cy + Math.sin(angle) * radius
    node.vx = (Math.random() - 0.5) * 0.5
    node.vy = (Math.random() - 0.5) * 0.5
    node.radius = getNodeRadius(node.type)
  })
}

function updatePhysics() {
  const canvas = canvasRef.value
  if (!canvas) return
  const width = canvas.width
  const height = canvas.height
  const center = { x: width / 2, y: height / 2 }

  // Simple Spring Physics simulation
  nodes.value.forEach((node) => {
    if (node === draggedNode) return

    // Gravity pull toward center
    const dx = center.x - (node.x || 0)
    const dy = center.y - (node.y || 0)
    node.vx = (node.vx || 0) + dx * 0.0003
    node.vy = (node.vy || 0) + dy * 0.0003

    // Node Repulsion
    nodes.value.forEach((other) => {
      if (node === other) return
      const rx = (node.x || 0) - (other.x || 0)
      const ry = (node.y || 0) - (other.y || 0)
      const distSq = rx * rx + ry * ry + 0.1
      if (distSq < 15000) {
        const force = 30 / distSq
        node.vx = (node.vx || 0) + rx * force
        node.vy = (node.vy || 0) + ry * force
      }
    })

    // Friction
    node.vx *= 0.92
    node.vy *= 0.92

    node.x = (node.x || 0) + node.vx
    node.y = (node.y || 0) + node.vy
  })
}

function drawCanvas() {
  const canvas = canvasRef.value
  if (!canvas) return
  const ctx = canvas.getContext('2d')
  if (!ctx) return

  ctx.save()
  ctx.clearRect(0, 0, canvas.width, canvas.height)
  ctx.translate(panX.value, panY.value)
  ctx.scale(scale.value, scale.value)

  // Filter visible nodes
  const activeNodes = nodes.value.filter((n) => filterType.value === 'ALL' || n.type === filterType.value)
  const activeIds = new Set(activeNodes.map((n) => n.id))

  // Draw Edges
  edges.value.forEach((edge) => {
    if (!activeIds.has(edge.source) || !activeIds.has(edge.target)) return
    const sourceNode = nodes.value.find((n) => n.id === edge.source)
    const targetNode = nodes.value.find((n) => n.id === edge.target)
    if (!sourceNode || !targetNode) return

    const isHighlighted = hoveredNodeId === edge.source || hoveredNodeId === edge.target
    ctx.beginPath()
    ctx.moveTo(sourceNode.x || 0, sourceNode.y || 0)
    ctx.lineTo(targetNode.x || 0, targetNode.y || 0)
    ctx.strokeStyle = isHighlighted ? '#f43f5e' : 'rgba(150, 150, 150, 0.25)'
    ctx.lineWidth = isHighlighted ? 2.5 : 1
    ctx.stroke()
  })

  // Draw Nodes
  activeNodes.forEach((node) => {
    const isHovered = hoveredNodeId === node.id
    const color = getNodeColor(node.type)
    const r = (node.radius || 20) * (isHovered ? 1.25 : 1)

    // Outer Glow ring on hover
    if (isHovered) {
      ctx.beginPath()
      ctx.arc(node.x || 0, node.y || 0, r + 8, 0, Math.PI * 2)
      ctx.fillStyle = color + '33'
      ctx.fill()
    }

    // Node Circle
    ctx.beginPath()
    ctx.arc(node.x || 0, node.y || 0, r, 0, Math.PI * 2)
    ctx.fillStyle = color
    ctx.shadowColor = color
    ctx.shadowBlur = isHovered ? 16 : 6
    ctx.fill()
    ctx.shadowBlur = 0

    // Label
    ctx.font = `${isHovered ? '600 13px' : '500 12px'} system-ui, -apple-system, sans-serif`
    ctx.fillStyle = isHovered ? '#ffffff' : 'var(--ink)'
    ctx.textAlign = 'center'
    ctx.fillText(node.label, node.x || 0, (node.y || 0) + r + 18)
  })

  ctx.restore()

  updatePhysics()
  animFrameId = requestAnimationFrame(drawCanvas)
}

function handleMouseDown(e: MouseEvent) {
  const canvas = canvasRef.value
  if (!canvas) return
  const rect = canvas.getBoundingClientRect()
  const mouseX = (e.clientX - rect.left - panX.value) / scale.value
  const mouseY = (e.clientY - rect.top - panY.value) / scale.value

  const clicked = nodes.value.find((n) => {
    const dx = (n.x || 0) - mouseX
    const dy = (n.y || 0) - mouseY
    return Math.sqrt(dx * dx + dy * dy) <= (n.radius || 20)
  })

  if (clicked) {
    draggedNode = clicked
    isDragging = true
  } else {
    isDragging = false
    startMouseX = e.clientX
    startMouseY = e.clientY
    startPanX = panX.value
    startPanY = panY.value
  }
}

function handleMouseMove(e: MouseEvent) {
  const canvas = canvasRef.value
  if (!canvas) return
  const rect = canvas.getBoundingClientRect()
  const mouseX = (e.clientX - rect.left - panX.value) / scale.value
  const mouseY = (e.clientY - rect.top - panY.value) / scale.value

  if (draggedNode) {
    draggedNode.x = mouseX
    draggedNode.y = mouseY
    return
  }

  if (e.buttons === 1 && !draggedNode) {
    panX.value = startPanX + (e.clientX - startMouseX)
    panY.value = startPanY + (e.clientY - startMouseY)
    return
  }

  // Hover detection
  const hovered = nodes.value.find((n) => {
    const dx = (n.x || 0) - mouseX
    const dy = (n.y || 0) - mouseY
    return Math.sqrt(dx * dx + dy * dy) <= (n.radius || 20)
  })
  hoveredNodeId = hovered ? hovered.id : null
  canvas.style.cursor = isDragging ? 'grabbing' : (hovered ? 'pointer' : 'grab')
}

function handleMouseUp(e: MouseEvent) {
  if (draggedNode) {
    const canvas = canvasRef.value
    if (canvas) {
      const rect = canvas.getBoundingClientRect()
      const mouseX = (e.clientX - rect.left - panX.value) / scale.value
      const mouseY = (e.clientY - rect.top - panY.value) / scale.value
      const dx = (draggedNode.x || 0) - mouseX
      const dy = (draggedNode.y || 0) - mouseY
      if (Math.sqrt(dx * dx + dy * dy) < 5 && draggedNode.url) {
        void router.push(draggedNode.url)
      }
    }
  }
  draggedNode = null
}

function handleWheel(e: WheelEvent) {
  e.preventDefault()
  const zoomFactor = e.deltaY < 0 ? 1.1 : 0.9
  scale.value = Math.max(0.4, Math.min(2.5, scale.value * zoomFactor))
}

function resizeCanvas() {
  const canvas = canvasRef.value
  if (!canvas) return
  const parent = canvas.parentElement
  if (parent) {
    canvas.width = parent.clientWidth
    canvas.height = isFullscreen.value ? window.innerHeight - 80 : 420
  }
}

function toggleFullscreen() {
  isFullscreen.value = !isFullscreen.value
  setTimeout(resizeCanvas, 50)
}

onMounted(() => {
  resizeCanvas()
  window.addEventListener('resize', resizeCanvas)
  void fetchRemoteGraphData()
  animFrameId = requestAnimationFrame(drawCanvas)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeCanvas)
  if (animFrameId) cancelAnimationFrame(animFrameId)
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
          @click="filterType = (t.id as any)"
        >
          {{ t.name }}
        </button>
        <button type="button" class="fullscreen-btn" :title="isFullscreen ? '退出全屏' : '全屏浏览'" @click="toggleFullscreen">
          {{ isFullscreen ? '⤢ 退出全屏' : '⤢ 全屏罗盘' }}
        </button>
      </div>
    </header>

    <div class="canvas-wrapper">
      <canvas
        ref="canvasRef"
        @mousedown="handleMouseDown"
        @mousemove="handleMouseMove"
        @mouseup="handleMouseUp"
        @wheel="handleWheel"
      />
      <div class="canvas-legend">
        <span><i style="background: #3b82f6;" /> 文章</span>
        <span><i style="background: #10b981;" /> 学习笔记</span>
        <span><i style="background: #f59e0b;" /> 美食菜谱</span>
        <span><i style="background: #8b5cf6;" /> 标签分类</span>
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
  transition: all 0.3s var(--ease);
}
.knowledge-graph-container.is-fullscreen {
  position: fixed;
  inset: 0;
  z-index: 1300;
  margin: 0;
  border-radius: 0;
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
  padding: 5px 12px;
  border-radius: 999px;
  background: var(--surface);
  border: 1px solid var(--line);
  color: var(--muted);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s;
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
  padding: 5px 14px;
  border-radius: 999px;
  background: var(--surface);
  border: 1px solid var(--line-strong);
  color: var(--ink);
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
}
.fullscreen-btn:hover {
  border-color: var(--accent);
  transform: translateY(-1px);
}

.canvas-wrapper {
  position: relative;
  width: 100%;
  border-radius: 16px;
  background: var(--surface);
  border: 1px solid var(--line);
  overflow: hidden;
}
canvas {
  display: block;
  width: 100%;
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
</style>
