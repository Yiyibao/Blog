<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue';
import type { VisualNode } from '../../composables/useGraphLayout';

const props = defineProps<{
  nodes: VisualNode[];
}>();

const emit = defineEmits<{
  (e: 'select', node: VisualNode): void;
}>();

const query = ref('');
const isOpen = ref(false);

const results = computed(() => {
  const q = query.value.trim().toLowerCase();
  if (!q) return [];
  return props.nodes
    .filter(
      (n) =>
        n.kind !== 'ROOT' &&
        (n.label.toLowerCase().includes(q) ||
          (n.subtitle && n.subtitle.toLowerCase().includes(q)) ||
          n.type.toLowerCase().includes(q)),
    )
    .sort((a, b) => {
      const aPrefix = a.label.toLowerCase().startsWith(q) ? 1 : 0;
      const bPrefix = b.label.toLowerCase().startsWith(q) ? 1 : 0;
      if (aPrefix !== bPrefix) return bPrefix - aPrefix;
      if (a.importance !== b.importance) return b.importance - a.importance;
      return (b.updatedAt || '').localeCompare(a.updatedAt || '') || a.id.localeCompare(b.id);
    })
    .slice(0, 8);
});

function handleSelect(node: VisualNode) {
  emit('select', node);
  query.value = node.label;
  isOpen.value = false;
}

let blurTimer: ReturnType<typeof setTimeout> | null = null;

function handleBlur() {
  blurTimer = setTimeout(() => {
    isOpen.value = false;
    blurTimer = null;
  }, 200);
}

onUnmounted(() => {
  if (blurTimer) clearTimeout(blurTimer);
});
</script>

<template>
  <div class="graph-search glass-panel">
    <div class="search-input-wrapper">
      <svg
        class="search-icon"
        viewBox="0 0 24 24"
        width="16"
        height="16"
        stroke="currentColor"
        fill="none"
        stroke-width="2"
      >
        <circle cx="11" cy="11" r="8" />
        <line x1="21" y1="21" x2="16.65" y2="16.65" />
      </svg>
      <input
        v-model="query"
        type="text"
        class="search-input"
        placeholder="搜索节点、标签或内容..."
        aria-label="搜索节点、标签或内容"
        @focus="isOpen = true"
        @input="isOpen = true"
        @blur="handleBlur"
        @keydown.enter="results.length > 0 && handleSelect(results[0])"
      />
      <button
        v-if="query"
        type="button"
        class="clear-btn"
        aria-label="清除搜索"
        @click="
          query = '';
          isOpen = false;
        "
      >
        ✕
      </button>
    </div>

    <!-- Dropdown results -->
    <ul v-if="isOpen && results.length > 0" class="search-results-dropdown glass-card">
      <li v-for="node in results" :key="node.id" class="result-item" @mousedown.prevent="handleSelect(node)">
        <span class="result-badge" :style="{ backgroundColor: node.color }">
          {{
            node.type === 'POST'
              ? '文章'
              : node.type === 'NOTE'
                ? '笔记'
                : node.type === 'DISH'
                  ? '菜谱'
                  : node.type === 'SERIES'
                    ? '合集'
                    : '标签'
          }}
        </span>
        <div class="result-text">
          <span class="result-label">{{ node.label }}</span>
          <span v-if="node.subtitle" class="result-sub">{{ node.subtitle }}</span>
        </div>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.graph-search {
  position: relative;
  width: 280px;
  max-width: 100%;
}

.search-input-wrapper {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 38px;
  padding: 0 12px;
  border-radius: 999px;
  background: var(--surface-solid, rgba(255, 255, 255, 0.85));
  border: 1px solid var(--line, rgba(0, 0, 0, 0.08));
  box-shadow: var(--shadow-sm, 0 4px 12px rgba(0, 0, 0, 0.05));
  backdrop-filter: blur(12px);
  transition:
    border-color 0.2s ease,
    box-shadow 0.2s ease;
}

.search-input-wrapper:focus-within {
  border-color: var(--accent, #f43f5e);
  box-shadow: 0 0 0 3px rgba(244, 63, 94, 0.15);
}

.search-icon {
  color: var(--muted, #94a3b8);
  flex-shrink: 0;
}

.search-input {
  flex: 1;
  border: 0;
  background: transparent;
  outline: none;
  font-size: 13px;
  color: var(--ink, #1e293b);
}
.search-input::placeholder {
  color: var(--muted, #94a3b8);
}

.clear-btn {
  border: 0;
  background: transparent;
  color: var(--muted, #94a3b8);
  font-size: 12px;
  cursor: pointer;
  padding: 0 4px;
}

.search-results-dropdown {
  position: absolute;
  top: 44px;
  left: 0;
  right: 0;
  z-index: 100;
  max-height: 260px;
  overflow-y: auto;
  padding: 6px;
  border-radius: 12px;
  background: var(--surface-solid, rgba(255, 255, 255, 0.95));
  border: 1px solid var(--line, rgba(0, 0, 0, 0.08));
  box-shadow: var(--shadow-md, 0 8px 24px rgba(0, 0, 0, 0.1));
  list-style: none;
  margin: 0;
}

.result-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s ease;
}

.result-item:hover {
  background: var(--accent-soft, rgba(244, 63, 94, 0.08));
}

.result-badge {
  padding: 2px 6px;
  border-radius: 4px;
  color: #fff;
  font-size: 10px;
  font-weight: 600;
  white-space: nowrap;
}

.result-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.result-label {
  font-size: 13px;
  font-weight: 500;
  color: var(--ink, #1e293b);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.result-sub {
  font-size: 11px;
  color: var(--muted, #94a3b8);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
