<script setup lang="ts">
import type { GraphOverviewLegendItem, GraphOverviewStats } from '../../api/content';

defineProps<{
  legend: GraphOverviewLegendItem[];
  stats: GraphOverviewStats | null;
  activeTypeFilter?: string;
}>();

const emit = defineEmits<{
  (e: 'filterType', type: string): void;
}>();

function formatUpdatedAt(value: string | null | undefined): string {
  if (!value) return '暂无';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(date);
}
</script>

<template>
  <aside class="graph-sidebar">
    <!-- Card 1: 图例 · 分类 -->
    <div class="sidebar-card glass-card">
      <h4 class="card-title">图例 · 分类</h4>
      <ul class="legend-list">
        <li
          v-for="item in legend"
          :key="item.type"
          class="legend-item"
          :class="{ active: activeTypeFilter === item.type }"
          role="button"
          tabindex="0"
          @click="emit('filterType', item.type)"
          @keydown.enter.prevent="emit('filterType', item.type)"
          @keydown.space.prevent="emit('filterType', item.type)"
        >
          <span class="legend-badge" :style="{ backgroundColor: item.color }">
            <svg
              v-if="item.type === 'POST'"
              viewBox="0 0 24 24"
              width="12"
              height="12"
              stroke="#fff"
              fill="none"
              stroke-width="2"
            >
              <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
              <polyline points="14 2 14 8 20 8" />
            </svg>
            <svg
              v-else-if="item.type === 'NOTE'"
              viewBox="0 0 24 24"
              width="12"
              height="12"
              stroke="#fff"
              fill="none"
              stroke-width="2"
            >
              <path d="M12 20h9" />
              <path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z" />
            </svg>
            <svg
              v-else-if="item.type === 'DISH'"
              viewBox="0 0 24 24"
              width="12"
              height="12"
              stroke="#fff"
              fill="none"
              stroke-width="2"
            >
              <path d="M6 13.87A8 8 0 0 1 12 4a8 8 0 0 1 6 9.87" />
              <line x1="4" y1="18" x2="20" y2="18" />
            </svg>
            <svg
              v-else-if="item.type === 'SERIES'"
              viewBox="0 0 24 24"
              width="12"
              height="12"
              stroke="#fff"
              fill="none"
              stroke-width="2"
            >
              <polygon points="12 2 2 7 12 12 22 7 12 2" />
              <polyline points="2 17 12 22 22 17" />
              <polyline points="2 12 12 17 22 12" />
            </svg>
            <svg v-else viewBox="0 0 24 24" width="12" height="12" stroke="#fff" fill="none" stroke-width="2">
              <path d="M20.59 13.41l-7.17 7.17a2 2 0 0 1-2.83 0L2 12V2h10l8.59 8.59a2 2 0 0 1 0 2.82z" />
              <line x1="7" y1="7" x2="7.01" y2="7" />
            </svg>
          </span>
          <span class="legend-label">{{ item.label }}</span>
          <span class="legend-count">{{ item.count }}</span>
        </li>
      </ul>
    </div>

    <!-- Card 2: 图谱洞察 -->
    <div class="sidebar-card glass-card">
      <h4 class="card-title">图谱洞察</h4>
      <div class="stats-grid">
        <div class="stat-item">
          <span class="stat-label">节点总数</span>
          <strong class="stat-value">{{ stats?.contentNodeCount ?? '-' }}</strong>
        </div>
        <div class="stat-item">
          <span class="stat-label">关联关系</span>
          <strong class="stat-value">{{ stats?.relationCount ?? '-' }}</strong>
        </div>
        <div class="stat-item full-width">
          <span class="stat-label">最近更新</span>
          <span class="stat-date">{{ formatUpdatedAt(stats?.lastUpdatedAt) }}</span>
        </div>
      </div>
    </div>

    <!-- Card 3: 灵感引言 -->
    <div class="sidebar-card glass-card quote-card">
      <div class="quote-icon">“</div>
      <p class="quote-text">知识如花，在连接中生长，在分享中绽放。</p>
      <div class="flower-deco">
        <svg viewBox="0 0 24 24" width="20" height="20" fill="#f43f5e" opacity="0.4">
          <path
            d="M12 2a4 4 0 0 0-4 4c0 2 2 4 4 4s4-2 4-4a4 4 0 0 0-4-4zm0 8a4 4 0 0 0-4 4c0 2 2 4 4 4s4-2 4-4a4 4 0 0 0-4-4zm-8 2a4 4 0 0 0 4 4c2 0 4-2 4-4s-2-4-4-4a4 4 0 0 0-4 4zm16 0a4 4 0 0 0-4-4c-2 0-4 2-4 4s2 4 4 4a4 4 0 0 0 4-4z"
          />
        </svg>
      </div>
    </div>
  </aside>
</template>

<style scoped>
.graph-sidebar {
  display: flex;
  flex-direction: column;
  gap: 16px;
  width: 220px;
  flex-shrink: 0;
}

.sidebar-card {
  padding: 16px;
  border-radius: 16px;
  background: var(--surface-solid, rgba(255, 255, 255, 0.8));
  border: 1px solid var(--line, rgba(0, 0, 0, 0.08));
  box-shadow: var(--shadow-sm, 0 4px 12px rgba(0, 0, 0, 0.04));
  backdrop-filter: blur(12px);
}

.card-title {
  margin: 0 0 12px 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--muted, #64748b);
  letter-spacing: 0.05em;
}

.legend-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.legend-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 8px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s ease;
}

.legend-item:hover,
.legend-item.active {
  background: var(--accent-soft, rgba(244, 63, 94, 0.08));
}

.legend-badge {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.legend-label {
  flex: 1;
  font-size: 13px;
  color: var(--ink, #1e293b);
  font-weight: 500;
}

.legend-count {
  font-size: 12px;
  font-weight: 600;
  color: var(--muted, #94a3b8);
}

.stats-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.stat-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.stat-item.full-width {
  grid-column: span 2;
}

.stat-label {
  font-size: 11px;
  color: var(--muted, #94a3b8);
}

.stat-value {
  font-size: 18px;
  font-weight: 700;
  color: var(--ink, #1e293b);
}

.stat-date {
  font-size: 12px;
  font-weight: 500;
  color: var(--ink, #334155);
}

.quote-card {
  position: relative;
  overflow: hidden;
  background: linear-gradient(135deg, rgba(254, 242, 242, 0.9), rgba(253, 230, 230, 0.6));
}

.quote-icon {
  font-size: 28px;
  line-height: 1;
  color: #f43f5e;
  opacity: 0.6;
  margin-bottom: 4px;
}

.quote-text {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: #881337;
  font-style: italic;
}

.flower-deco {
  position: absolute;
  bottom: -4px;
  right: -4px;
  pointer-events: none;
}

@media (max-width: 860px) {
  .graph-sidebar {
    width: 100%;
    flex-direction: row;
    flex-wrap: wrap;
  }
  .sidebar-card {
    flex: 1 1 200px;
  }
}
</style>
