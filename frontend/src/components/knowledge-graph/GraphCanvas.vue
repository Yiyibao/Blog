<script setup lang="ts">
import { ref } from 'vue'
import type { VisualNode, VisualEdge } from '../../composables/useGraphLayout'

defineProps<{
  nodes: VisualNode[]
  edges: VisualEdge[]
  viewBox: string
  selectedNodeId: string | null
  hoveredNodeId: string | null
  neighborNodeIds: Set<string>
  baseWidth: number
  baseHeight: number
}>()

const emit = defineEmits<{
  (e: 'selectNode', node: VisualNode): void
  (e: 'dblclickNode', node: VisualNode): void
  (e: 'hoverNode', id: string | null): void
  (e: 'pointerDown', ev: PointerEvent): void
  (e: 'pointerMove', ev: PointerEvent): void
  (e: 'pointerUp', ev?: PointerEvent): void
  (e: 'wheel', ev: WheelEvent, svgEl: SVGSVGElement | null): void
}>()

const svgRef = ref<SVGSVGElement | null>(null)
const imageErrorMap = ref<Record<string, boolean>>({})

function handleImageError(id: string) {
  imageErrorMap.value[id] = true
}
</script>

<template>
  <div class="graph-canvas-container">
    <svg
      ref="svgRef"
      :viewBox="viewBox"
      preserveAspectRatio="xMidYMid meet"
      class="graph-svg"
      aria-label="知识关联图谱"
      @pointerdown="emit('pointerDown', $event)"
      @pointermove="emit('pointerMove', $event)"
      @pointerup="emit('pointerUp', $event)"
      @pointercancel="emit('pointerUp', $event)"
      @wheel="emit('wheel', $event, svgRef)"
    >
      <defs>
        <!-- ClipPath for each dish node -->
        <template v-for="node in nodes" :key="`clip-${node.id}`">
          <clipPath v-if="node.imageUrl" :id="`clip-${node.id.replace(/[^a-zA-Z0-9_-]/g, '_')}`">
            <circle :cx="0" :cy="0" :r="node.radius" />
          </clipPath>
        </template>

        <!-- Hub glow filter -->
        <filter id="hub-glow" x="-50%" y="-50%" width="200%" height="200%">
          <feGaussianBlur stdDeviation="3" result="blur" />
          <feComposite in="SourceGraphic" in2="blur" operator="over" />
        </filter>

        <!-- Root aura gradient -->
        <radialGradient id="root-aura" cx="50%" cy="50%" r="50%">
          <stop offset="0%" stop-color="#f43f5e" stop-opacity="0.3" />
          <stop offset="100%" stop-color="#f43f5e" stop-opacity="0" />
        </radialGradient>
        <radialGradient id="root-fill" cx="35%" cy="30%" r="75%">
          <stop offset="0%" stop-color="#fbc4d7" />
          <stop offset="55%" stop-color="#f58cab" />
          <stop offset="100%" stop-color="#ec6e99" />
        </radialGradient>
      </defs>

      <!-- Background Ambient Organic Floral Vine Decorations -->
      <g class="bg-decorations" pointer-events="none">
        <path
          d="M 150,120 Q 300,80 500,350 T 850,580"
          fill="none"
          stroke="var(--accent, #f43f5e)"
          stroke-opacity="0.06"
          stroke-width="3"
        />
        <path
          d="M 820,150 Q 650,200 500,350 T 180,550"
          fill="none"
          stroke="#8b5cf6"
          stroke-opacity="0.06"
          stroke-width="3"
        />
        <circle cx="500" cy="350" r="180" fill="url(#root-aura)" />
      </g>

      <!-- Edges Layer -->
      <g class="graph-edges">
        <path
          v-for="(edge, idx) in edges"
          :key="`edge-${edge.id}`"
          :d="edge.pathD"
          pathLength="1"
          class="graph-edge"
          :class="{
            'is-structure': edge.isStructure,
            'is-relation': !edge.isStructure,
            highlighted: (selectedNodeId || hoveredNodeId) && (edge.source === (selectedNodeId || hoveredNodeId) || edge.target === (selectedNodeId || hoveredNodeId)),
            faded: (selectedNodeId || hoveredNodeId) && !(edge.source === (selectedNodeId || hoveredNodeId) || edge.target === (selectedNodeId || hoveredNodeId))
          }"
          :style="{ animationDelay: `${Math.min(idx * 15, 600) + 150}ms` }"
        />
      </g>

      <!-- Nodes Layer -->
      <g class="graph-nodes">
        <g
          v-for="(node, idx) in nodes"
          :key="`node-${node.id}`"
          class="graph-node graph-interactive-element"
          :class="{
            'is-root': node.kind === 'ROOT',
            'is-group': node.kind === 'GROUP',
            selected: selectedNodeId === node.id,
            highlighted: (selectedNodeId || hoveredNodeId) && neighborNodeIds.has(node.id),
            faded: (selectedNodeId || hoveredNodeId) && !neighborNodeIds.has(node.id)
          }"
          :style="{
            transform: `translate(${node.x.toFixed(1)}px, ${node.y.toFixed(1)}px)`,
            animationDelay: `${Math.min(idx * 30, 800)}ms`
          }"
          tabindex="0"
          role="button"
          :aria-label="`${node.label} (${node.type})`"
          @click.stop="emit('selectNode', node)"
          @dblclick.stop="emit('dblclickNode', node)"
          @mouseenter="emit('hoverNode', node.id)"
          @mouseleave="emit('hoverNode', null)"
          @keydown.enter.prevent="emit('selectNode', node)"
          @keydown.space.prevent="emit('selectNode', node)"
        >
          <!-- Breathing float wrapper for ROOT and GROUP -->
          <g :class="{ 'node-float': node.kind === 'ROOT' || node.kind === 'GROUP' }" :style="{ animationDelay: `${(idx % 7) * -1.2}s` }">
            <!-- 44x44 Touch Target -->
            <circle r="24" fill="transparent" class="hit-target" />

            <!-- Selection Ring -->
            <circle
              v-if="selectedNodeId === node.id"
              :r="node.radius + 6"
              fill="none"
              stroke="var(--accent, #f43f5e)"
              stroke-width="2.5"
              class="selection-ring"
            />

            <!-- ROOT Node Special Flower Circle & Aura -->
            <template v-if="node.kind === 'ROOT'">
              <circle :r="node.radius + 8" fill="none" stroke="#f43f5e" stroke-opacity="0.3" stroke-width="1.5" stroke-dasharray="3 3" />
              <circle :r="node.radius" fill="url(#root-fill)" class="node-circle main-root" />
              <!-- Flower SVG Icon -->
              <g transform="translate(-14, -14)">
                <svg viewBox="0 0 24 24" width="28" height="28" fill="#ffffff">
                  <path d="M12 2a4 4 0 0 0-4 4c0 2.21 1.79 4 4 4s4-1.79 4-4a4 4 0 0 0-4-4zm0 8a4 4 0 0 0-4 4c0 2.21 1.79 4 4 4s4-1.79 4-4a4 4 0 0 0-4-4zm-8 2a4 4 0 0 0 4 4c2.21 0 4-1.79 4-4s-1.79-4-4-4a4 4 0 0 0-4 4zm16 0a4 4 0 0 0-4-4c-2.21 0-4 1.79-4 4s1.79 4 4 4a4 4 0 0 0 4-4z" />
                  <circle cx="12" cy="12" r="3" fill="#fef08a" />
                </svg>
              </g>
            </template>

            <!-- GROUP Hub Nodes -->
            <template v-else-if="node.kind === 'GROUP'">
              <circle :r="node.radius" :fill="node.color" class="node-circle main-group" />
              <g transform="translate(-11, -11)">
                <!-- Post Icon -->
                <svg v-if="node.type === 'POST'" viewBox="0 0 24 24" width="22" height="22" stroke="#fff" fill="none" stroke-width="2">
                  <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                  <polyline points="14 2 14 8 20 8" />
                  <line x1="16" y1="13" x2="8" y2="13" />
                  <line x1="16" y1="17" x2="8" y2="17" />
                </svg>
                <!-- Note Icon -->
                <svg v-else-if="node.type === 'NOTE'" viewBox="0 0 24 24" width="22" height="22" stroke="#fff" fill="none" stroke-width="2">
                  <path d="M12 20h9" />
                  <path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z" />
                </svg>
                <!-- Dish Icon -->
                <svg v-else-if="node.type === 'DISH'" viewBox="0 0 24 24" width="22" height="22" stroke="#fff" fill="none" stroke-width="2">
                  <path d="M6 13.87A8 8 0 0 1 12 4a8 8 0 0 1 6 9.87" />
                  <line x1="4" y1="18" x2="20" y2="18" />
                  <path d="M12 18v4" />
                </svg>
                <!-- Tag Icon -->
                <svg v-else-if="node.type === 'TAG'" viewBox="0 0 24 24" width="22" height="22" stroke="#fff" fill="none" stroke-width="2">
                  <line x1="4" y1="9" x2="20" y2="9" />
                  <line x1="4" y1="15" x2="20" y2="15" />
                  <line x1="10" y1="3" x2="8" y2="21" />
                  <line x1="16" y1="3" x2="14" y2="21" />
                </svg>
                <!-- Series Icon -->
                <svg v-else viewBox="0 0 24 24" width="22" height="22" stroke="#fff" fill="none" stroke-width="2">
                  <polygon points="12 2 2 7 12 12 22 7 12 2" />
                  <polyline points="2 17 12 22 22 17" />
                  <polyline points="2 12 12 17 22 12" />
                </svg>
              </g>
            </template>

            <!-- CONTENT Nodes -->
            <template v-else>
              <!-- Dish image node with clipPath -->
              <g v-if="node.type === 'DISH' && node.imageUrl && !imageErrorMap[node.id]">
                <circle :r="node.radius + 1.5" fill="none" :stroke="node.color" stroke-width="2" />
                <image
                  :href="node.imageUrl"
                  :x="-node.radius"
                  :y="-node.radius"
                  :width="node.radius * 2"
                  :height="node.radius * 2"
                  :clip-path="`url(#clip-${node.id.replace(/[^a-zA-Z0-9_-]/g, '_')})`"
                  preserveAspectRatio="xMidYMid slice"
                  @error="handleImageError(node.id)"
                />
              </g>

              <!-- Standard content node circle -->
              <circle
                v-else
                :r="node.radius"
                :fill="node.color"
                class="node-circle content-circle"
              />
            </template>

            <!-- Node Tooltip -->
            <title>{{ node.label }}{{ node.subtitle ? ` (${node.subtitle})` : '' }}</title>

            <!-- Label Text -->
            <text
              v-if="node.kind !== 'CONTENT'
                || node.type === 'DISH'
                || (node.type === 'POST' && idx % 2 === 0)
                || (node.type === 'TAG' && node.degree >= 3)"
              :y="node.kind === 'CONTENT' && idx % 2 === 0 ? -node.radius - 7 : node.radius + 13"
              text-anchor="middle"
              class="node-label"
              :class="{ 'is-root-label': node.kind === 'ROOT', 'is-group-label': node.kind === 'GROUP' }"
            >
              {{ node.label.length > 12 ? node.label.slice(0, 11) + '…' : node.label }}
            </text>
          </g>
        </g>
      </g>
    </svg>
  </div>
</template>

<style scoped>
.graph-canvas-container {
  width: 100%;
  height: 100%;
  position: relative;
  overflow: hidden;
}

.graph-svg {
  display: block;
  width: 100%;
  height: 100%;
  cursor: grab;
  touch-action: none;
  user-select: none;
}

.graph-svg:active {
  cursor: grabbing;
}

/* Edges Styling */
.graph-edge {
  fill: none;
  stroke-linecap: round;
  stroke-dasharray: 1;
  stroke-dashoffset: 1;
  animation: edge-draw 0.8s ease-out forwards;
  transition: stroke 0.25s, stroke-opacity 0.25s, stroke-width 0.25s;
}

.graph-edge.is-structure {
  stroke: var(--line-strong, #cbd5e1);
  stroke-opacity: 0.5;
  stroke-width: 1.8px;
}

.graph-edge.is-relation {
  stroke: var(--line, #94a3b8);
  stroke-opacity: 0.35;
  stroke-width: 1.2px;
  stroke-dasharray: 4 3;
}

@keyframes edge-draw {
  to {
    stroke-dashoffset: 0;
  }
}

.graph-edge.highlighted {
  stroke: var(--accent, #f43f5e) !important;
  stroke-opacity: 1 !important;
  stroke-width: 2.8px !important;
  stroke-dasharray: 6 4 !important;
  animation: edge-flow 1.5s linear infinite !important;
}

@keyframes edge-flow {
  to {
    stroke-dashoffset: -20;
  }
}

.graph-edge.faded {
  stroke-opacity: 0.08 !important;
}

/* Nodes Styling */
.graph-node {
  cursor: pointer;
  outline: none;
  opacity: 0;
  animation: node-enter 0.5s cubic-bezier(0.34, 1.4, 0.64, 1) forwards;
  transition: opacity 0.25s, transform 0.6s cubic-bezier(0.22, 1, 0.36, 1);
}

@keyframes node-enter {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}

.graph-node:focus-visible .node-circle {
  stroke: var(--accent, #f43f5e);
  stroke-width: 3px;
}

.graph-node.faded {
  opacity: 0.18;
  animation: none;
}

.node-float {
  animation: node-float 6s ease-in-out infinite alternate;
}

@keyframes node-float {
  from {
    transform: translateY(-2.5px);
  }
  to {
    transform: translateY(2.5px);
  }
}

.graph-node:hover .node-circle,
.graph-node.highlighted .node-circle {
  filter: brightness(1.15);
}

.node-circle {
  transition: stroke 0.2s, stroke-width 0.2s, filter 0.25s;
}

.node-label {
  font-size: 10px;
  font-weight: 500;
  fill: var(--ink, #1e293b);
  paint-order: stroke;
  stroke: var(--surface-solid, #ffffff);
  stroke-width: 3px;
  stroke-linejoin: round;
  pointer-events: none;
  user-select: none;
}

.is-root-label {
  font-size: 14px;
  font-weight: 700;
  fill: #be123c;
}

.is-group-label {
  font-size: 13px;
  font-weight: 600;
}

/* Reduced Motion Support */
@media (prefers-reduced-motion: reduce) {
  .graph-edge,
  .graph-node,
  .node-circle {
    transition: none !important;
    animation: none !important;
  }
  .graph-node {
    opacity: 1;
  }
  .graph-edge {
    stroke-dashoffset: 0;
  }
  .node-float {
    animation: none !important;
  }
}
</style>
