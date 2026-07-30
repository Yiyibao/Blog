<script setup lang="ts">
import type { VisualNode } from '../../composables/useGraphLayout'

defineProps<{
  node: VisualNode
  subgraphLoading?: boolean
}>()

const emit = defineEmits<{
  (e: 'expand'): void
  (e: 'open'): void
  (e: 'close'): void
}>()

function getTypeLabel(type: string): string {
  switch (type) {
    case 'ROOT': return '中心'
    case 'POST': return '文章'
    case 'NOTE': return '笔记'
    case 'DISH': return '菜谱'
    case 'SERIES': return '合集'
    case 'TAG': return '标签'
    default: return type
  }
}
</script>

<template>
  <div class="graph-selection-panel glass-card graph-interactive-element">
    <div class="panel-header">
      <span class="panel-badge" :style="{ backgroundColor: node.color }">
        {{ getTypeLabel(node.type) }}
      </span>
      <button type="button" class="close-btn" aria-label="关闭详情" @click="emit('close')">
        ✕
      </button>
    </div>

    <div class="panel-body">
      <h3 class="panel-title">{{ node.label }}</h3>
      <p v-if="node.subtitle" class="panel-sub">{{ node.subtitle }}</p>
      <span v-if="node.updatedAt" class="panel-date">更新于: {{ node.updatedAt }}</span>
    </div>

    <div class="panel-actions">
      <button
        v-if="node.kind === 'CONTENT' || node.type === 'TAG'"
        type="button"
        class="action-btn expand-btn"
        :disabled="subgraphLoading"
        aria-label="展开两层关联"
        @click="emit('expand')"
      >
        {{ subgraphLoading ? '展开中…' : '展开两层关联' }}
      </button>

      <button
        v-if="node.url && node.type !== 'TAG'"
        type="button"
        class="action-btn open-btn open-content-btn"
        @click="emit('open')"
      >
        打开内容 ↗
      </button>
    </div>
  </div>
</template>

<style scoped>
.graph-selection-panel {
  position: absolute;
  top: 16px;
  right: 16px;
  z-index: 20;
  width: 280px;
  max-width: calc(100% - 32px);
  padding: 16px;
  border-radius: 16px;
  background: var(--surface-solid, rgba(255, 255, 255, 0.9));
  border: 1px solid var(--line, rgba(0, 0, 0, 0.08));
  box-shadow: var(--shadow-md, 0 8px 24px rgba(0, 0, 0, 0.08));
  backdrop-filter: blur(16px);
  display: flex;
  flex-direction: column;
  gap: 12px;
  animation: panel-in 0.25s cubic-bezier(0.16, 1, 0.3, 1);
}

@keyframes panel-in {
  from {
    opacity: 0;
    transform: translateY(-8px) scale(0.96);
  }
  to {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.panel-badge {
  padding: 3px 8px;
  border-radius: 6px;
  color: #ffffff;
  font-size: 11px;
  font-weight: 600;
}

.close-btn {
  border: 0;
  background: transparent;
  color: var(--muted, #94a3b8);
  font-size: 14px;
  cursor: pointer;
  padding: 2px 6px;
  border-radius: 4px;
}
.close-btn:hover {
  background: rgba(0, 0, 0, 0.05);
  color: var(--ink, #1e293b);
}

.panel-body {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.panel-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--ink, #1e293b);
  line-height: 1.4;
}

.panel-sub {
  margin: 0;
  font-size: 12px;
  color: var(--muted, #64748b);
  line-height: 1.5;
}

.panel-date {
  font-size: 11px;
  color: var(--muted, #94a3b8);
}

.panel-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.action-btn {
  flex: 1;
  height: 36px;
  padding: 0 12px;
  border-radius: 8px;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  white-space: nowrap;
  transition: all 0.15s ease;
}

.expand-btn {
  background: transparent;
  border: 1px solid var(--accent, #f43f5e);
  color: var(--accent, #f43f5e);
}
.expand-btn:hover:not(:disabled) {
  background: var(--accent-soft, rgba(244, 63, 94, 0.1));
}
.expand-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.open-btn {
  background: var(--accent, #f43f5e);
  border: 0;
  color: #ffffff;
  font-weight: 600;
}
.open-btn:hover {
  filter: brightness(1.1);
}

@media (max-width: 720px) {
  .graph-selection-panel {
    position: relative;
    top: auto;
    right: auto;
    width: 100%;
    margin-top: 12px;
  }
}
</style>
