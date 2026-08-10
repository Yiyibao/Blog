<script setup lang="ts">
defineProps<{
  isFullscreen: boolean;
  localMode?: boolean;
  subgraphActive?: boolean;
}>();

const emit = defineEmits<{
  (e: 'zoomIn'): void;
  (e: 'zoomOut'): void;
  (e: 'reset'): void;
  (e: 'toggleFullscreen'): void;
  (e: 'returnOverview'): void;
  (e: 'exportJson'): void;
  (e: 'exportImage'): void;
}>();
</script>

<template>
  <div class="graph-toolbar glass-panel">
    <button
      type="button"
      class="tool-btn view-ctrl-btn"
      title="放大"
      aria-label="放大"
      @click="emit('zoomIn')"
    >
      <svg viewBox="0 0 24 24" width="16" height="16" stroke="currentColor" stroke-width="2" fill="none">
        <circle cx="11" cy="11" r="7" />
        <line x1="11" y1="8" x2="11" y2="14" />
        <line x1="8" y1="11" x2="14" y2="11" />
        <line x1="21" y1="21" x2="16.65" y2="16.65" />
      </svg>
      <span>放大</span>
    </button>

    <button
      type="button"
      class="tool-btn view-ctrl-btn"
      title="缩小"
      aria-label="缩小"
      @click="emit('zoomOut')"
    >
      <svg viewBox="0 0 24 24" width="16" height="16" stroke="currentColor" stroke-width="2" fill="none">
        <circle cx="11" cy="11" r="7" />
        <line x1="8" y1="11" x2="14" y2="11" />
        <line x1="21" y1="21" x2="16.65" y2="16.65" />
      </svg>
      <span>缩小</span>
    </button>

    <button
      type="button"
      class="tool-btn view-ctrl-btn"
      title="复位视图"
      aria-label="复位视图"
      @click="emit('reset')"
    >
      <svg viewBox="0 0 24 24" width="16" height="16" stroke="currentColor" stroke-width="2" fill="none">
        <path d="M21.5 2v6h-6M21.34 15.57a10 10 0 1 1-.57-8.38l5.67-5.67" />
      </svg>
      <span>刷新</span>
    </button>

    <button
      type="button"
      class="tool-btn fullscreen-btn"
      :title="isFullscreen ? '退出全屏' : '全屏浏览'"
      :aria-label="isFullscreen ? '退出全屏' : '全屏'"
      @click="emit('toggleFullscreen')"
    >
      <svg viewBox="0 0 24 24" width="16" height="16" stroke="currentColor" stroke-width="2" fill="none">
        <path
          v-if="!isFullscreen"
          d="M8 3H5a2 2 0 0 0-2 2v3m18 0V5a2 2 0 0 0-2-2h-3m0 18h3a2 2 0 0 0 2-2v-3M3 16v3a2 2 0 0 0 2 2h3"
        />
        <path
          v-else
          d="M8 3v3a2 2 0 0 1-2 2H3m18 0h-3a2 2 0 0 1-2-2V3m0 18v-3a2 2 0 0 1 2-2h3M3 16h3a2 2 0 0 1 2 2v3"
        />
      </svg>
      <span>{{ isFullscreen ? '退出' : '全屏' }}</span>
    </button>

    <button type="button" class="tool-btn" title="导出当前图谱 JSON" @click="emit('exportJson')">
      <span>JSON</span>
    </button>

    <button type="button" class="tool-btn" title="导出当前图谱图片" @click="emit('exportImage')">
      <span>图片</span>
    </button>

    <button
      v-if="localMode || subgraphActive"
      type="button"
      class="tool-btn return-btn return-overview-btn"
      title="返回全图概览"
      aria-label="返回全图概览"
      @click="emit('returnOverview')"
    >
      <span>← 全图</span>
    </button>
  </div>
</template>

<style scoped>
.graph-toolbar {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px;
  border-radius: 999px;
  background: var(--surface-solid, rgba(255, 255, 255, 0.85));
  border: 1px solid var(--line, rgba(0, 0, 0, 0.08));
  box-shadow: var(--shadow-sm, 0 4px 12px rgba(0, 0, 0, 0.05));
  backdrop-filter: blur(12px);
}

.tool-btn {
  display: flex;
  align-items: center;
  gap: 5px;
  height: 36px;
  padding: 0 12px;
  border-radius: 999px;
  border: 0;
  background: transparent;
  color: var(--ink, #1e293b);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition:
    background 0.2s ease,
    color 0.2s ease;
  white-space: nowrap;
}

.tool-btn:hover {
  background: var(--accent-soft, rgba(244, 63, 94, 0.1));
  color: var(--accent, #f43f5e);
}

.return-btn {
  background: var(--accent-soft, rgba(244, 63, 94, 0.12));
  color: var(--accent, #f43f5e);
  font-weight: 600;
}
.return-btn:hover {
  background: var(--accent, #f43f5e);
  color: #ffffff;
}

@media (max-width: 640px) {
  .tool-btn span {
    display: none;
  }
  .tool-btn {
    padding: 0 10px;
  }
}
</style>
