<script setup lang="ts">
import { computed } from 'vue';
import type { VisualNode } from '../../composables/useGraphLayout';

const props = defineProps<{
  nodes: VisualNode[];
  zoom: number;
  panX: number;
  panY: number;
  baseWidth: number;
  baseHeight: number;
}>();

const emit = defineEmits<{
  (e: 'panTo', x: number, y: number): void;
}>();

const MAP_W = 160;
const MAP_H = 108;

// Map logical coords to minimap SVG coords
const scaleX = computed(() => MAP_W / props.baseWidth);
const scaleY = computed(() => MAP_H / props.baseHeight);

const viewRect = computed(() => {
  const currentW = props.baseWidth / props.zoom;
  const currentH = props.baseHeight / props.zoom;
  const currentX = (props.baseWidth - currentW) / 2 + props.panX;
  const currentY = (props.baseHeight - currentH) / 2 + props.panY;

  return {
    x: Math.max(0, Math.min(MAP_W, currentX * scaleX.value)),
    y: Math.max(0, Math.min(MAP_H, currentY * scaleY.value)),
    w: Math.min(MAP_W, currentW * scaleX.value),
    h: Math.min(MAP_H, currentH * scaleY.value),
  };
});

let dragging = false;

function panFromPointer(event: PointerEvent) {
  const svg = event.currentTarget as SVGSVGElement;
  const rect = svg.getBoundingClientRect();
  const clickX = event.clientX - rect.left;
  const clickY = event.clientY - rect.top;

  const logicalX = clickX / scaleX.value;
  const logicalY = clickY / scaleY.value;
  emit('panTo', logicalX, logicalY);
}

function handlePointerDown(event: PointerEvent) {
  dragging = true;
  (event.currentTarget as SVGSVGElement).setPointerCapture(event.pointerId);
  panFromPointer(event);
}

function handlePointerMove(event: PointerEvent) {
  if (dragging) panFromPointer(event);
}

function handlePointerUp(event: PointerEvent) {
  dragging = false;
  if ((event.currentTarget as SVGSVGElement).hasPointerCapture(event.pointerId)) {
    (event.currentTarget as SVGSVGElement).releasePointerCapture(event.pointerId);
  }
}
</script>

<template>
  <div class="graph-minimap glass-card">
    <svg
      :viewBox="`0 0 ${MAP_W} ${MAP_H}`"
      class="minimap-svg"
      @pointerdown="handlePointerDown"
      @pointermove="handlePointerMove"
      @pointerup="handlePointerUp"
      @pointercancel="dragging = false"
    >
      <!-- Node dots -->
      <circle
        v-for="node in nodes"
        :key="`mini-${node.id}`"
        :cx="node.x * scaleX"
        :cy="node.y * scaleY"
        :r="node.kind === 'ROOT' ? 4 : node.kind === 'GROUP' ? 3 : 1.8"
        :fill="node.color"
        :opacity="node.kind === 'ROOT' ? 1 : 0.8"
      />

      <!-- Viewport bounding rectangle -->
      <rect
        :x="viewRect.x"
        :y="viewRect.y"
        :width="viewRect.w"
        :height="viewRect.h"
        fill="rgba(59, 130, 246, 0.12)"
        stroke="#3b82f6"
        stroke-width="1.2"
        rx="2"
      />
    </svg>
    <div class="minimap-hint">点击或拖动定位视图</div>
  </div>
</template>

<style scoped>
.graph-minimap {
  position: absolute;
  bottom: 16px;
  right: 16px;
  padding: 8px;
  border-radius: 12px;
  background: var(--surface-solid, rgba(255, 255, 255, 0.85));
  border: 1px solid var(--line, rgba(0, 0, 0, 0.08));
  box-shadow: var(--shadow-sm, 0 4px 12px rgba(0, 0, 0, 0.05));
  backdrop-filter: blur(12px);
  z-index: 10;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}

.minimap-svg {
  width: 160px;
  height: 108px;
  border-radius: 6px;
  background: var(--surface, rgba(248, 250, 252, 0.5));
  cursor: pointer;
}

.minimap-hint {
  font-size: 10px;
  color: var(--muted, #94a3b8);
  user-select: none;
}

@media (max-width: 720px) {
  .graph-minimap {
    display: none;
  }
}
</style>
