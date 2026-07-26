<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { fetchAiProviders, type AiProvider } from '../api/admin'
import AdminAiChat from './AdminAiChat.vue'

/**
 * 4A-4：AI 助手停靠侧边栏——挂 App 布局层，全 /admin 路由可用（/admin/ai 全屏页除外）。
 * 可折叠、宽度可拖（320–640px，localStorage 记忆）、移动端全屏抽屉；
 * 顶部供应商/模型切换注入对话核心；会话经 sessionStorage 与全屏页共享；Ctrl+Shift+A 开合。
 */
const OPEN_KEY = 'yubai-ai-sidebar-open'
const WIDTH_KEY = 'yubai-ai-sidebar-width'
const MIN_WIDTH = 320
const MAX_WIDTH = 640

const route = useRoute()

function readStorage(key: string): string | null {
  try {
    return window.localStorage?.getItem(key) ?? null
  } catch {
    return null
  }
}

function writeStorage(key: string, value: string) {
  try {
    window.localStorage?.setItem(key, value)
  } catch {
    // 隐私模式：不记忆
  }
}

const open = ref(readStorage(OPEN_KEY) === '1')
const width = ref(Math.min(MAX_WIDTH, Math.max(MIN_WIDTH, Number(readStorage(WIDTH_KEY)) || 380)))

const providers = ref<AiProvider[]>([])
const selectedProviderId = ref<number | null>(null)
const selectedModel = ref<string | null>(null)

/** /admin/ai 全屏页自带完整对话视图，停靠栏在该路由隐藏避免双实例同写会话。 */
const visible = computed(() => route.path.startsWith('/admin') && route.path !== '/admin/ai')

const selectedProvider = computed(() =>
  providers.value.find((p) => p.id === selectedProviderId.value) ?? null)

const modelOptions = computed(() => {
  const p = selectedProvider.value
  if (!p) return []
  const models = p.models?.length ? p.models : []
  return models.length ? models : (p.defaultModel ? [p.defaultModel] : [])
})

function toggle() {
  open.value = !open.value
  writeStorage(OPEN_KEY, open.value ? '1' : '0')
}

function onProviderChange(raw: string) {
  selectedProviderId.value = raw ? Number(raw) : null
  selectedModel.value = selectedProvider.value?.defaultModel ?? null
}

async function loadProviders() {
  try {
    providers.value = (await fetchAiProviders()).filter((p) => p.enabled)
    const preferred = providers.value.find((p) => p.isDefault) ?? providers.value[0] ?? null
    selectedProviderId.value = preferred?.id ?? null
    selectedModel.value = preferred?.defaultModel ?? null
  } catch {
    // 注册表不可用时走 env 默认供应商，切换器留空
    providers.value = []
  }
}

// 宽度拖拽（把手在左缘）
let dragState: { startX: number; startWidth: number } | null = null

function onDragStart(event: PointerEvent) {
  dragState = { startX: event.clientX, startWidth: width.value }
  ;(event.target as Element).setPointerCapture(event.pointerId)
}

function onDragMove(event: PointerEvent) {
  if (!dragState) return
  const next = dragState.startWidth + (dragState.startX - event.clientX)
  width.value = Math.min(MAX_WIDTH, Math.max(MIN_WIDTH, next))
}

function onDragEnd() {
  if (!dragState) return
  dragState = null
  writeStorage(WIDTH_KEY, String(width.value))
}

function onHotkey(event: KeyboardEvent) {
  if ((event.ctrlKey || event.metaKey) && event.shiftKey && event.key.toLowerCase() === 'a') {
    event.preventDefault()
    toggle()
  }
}

onMounted(() => {
  window.addEventListener('keydown', onHotkey)
  void loadProviders()
})
onBeforeUnmount(() => window.removeEventListener('keydown', onHotkey))
</script>

<template>
  <div v-if="visible" class="ai-dock">
    <button
      v-if="!open"
      type="button"
      class="ai-dock-trigger"
      title="AI 助手（Ctrl+Shift+A）"
      aria-label="打开 AI 助手侧边栏"
      @click="toggle"
    >
      ✦ AI
    </button>

    <aside
      v-else
      class="ai-dock-panel"
      :style="{ width: `${width}px` }"
      role="complementary"
      aria-label="AI 助手侧边栏"
    >
      <span
        class="ai-dock-resizer"
        role="separator"
        aria-orientation="vertical"
        aria-label="拖动调整侧边栏宽度"
        @pointerdown="onDragStart"
        @pointermove="onDragMove"
        @pointerup="onDragEnd"
        @pointercancel="onDragEnd"
      />
      <header class="ai-dock-header">
        <strong>✦ AI 助手</strong>
        <div class="ai-dock-switchers">
          <select
            v-if="providers.length"
            class="dock-select"
            :value="selectedProviderId ?? ''"
            aria-label="选择供应商"
            @change="onProviderChange(($event.target as HTMLSelectElement).value)"
          >
            <option v-for="p in providers" :key="p.id" :value="p.id">{{ p.name }}</option>
          </select>
          <select
            v-if="modelOptions.length > 1"
            v-model="selectedModel"
            class="dock-select"
            aria-label="选择模型"
          >
            <option v-for="m in modelOptions" :key="m" :value="m">{{ m }}</option>
          </select>
        </div>
        <div class="ai-dock-actions">
          <RouterLink class="dock-expand" to="/admin/ai" title="全屏视图">⤢</RouterLink>
          <button type="button" class="dock-close" aria-label="收起 AI 助手" @click="toggle">×</button>
        </div>
      </header>
      <div class="ai-dock-body">
        <AdminAiChat compact :provider-id="selectedProviderId" :model="selectedModel" />
      </div>
    </aside>
  </div>
</template>

<style scoped>
.ai-dock-trigger {
  position: fixed;
  right: 18px;
  bottom: 18px;
  z-index: 1250;
  padding: 10px 16px;
  border-radius: 999px;
  border: 1px solid var(--line-strong);
  background: var(--surface-solid);
  color: var(--ink);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  box-shadow: var(--shadow-md);
}
.ai-dock-trigger:hover {
  border-color: var(--accent);
}

.ai-dock-panel {
  position: fixed;
  top: 0;
  right: 0;
  bottom: 0;
  z-index: 1250;
  display: flex;
  flex-direction: column;
  background: var(--surface-solid);
  border-left: 1px solid var(--line-strong);
  box-shadow: -12px 0 40px rgba(0, 0, 0, 0.14);
}
.ai-dock-resizer {
  position: absolute;
  top: 0;
  left: -4px;
  bottom: 0;
  width: 8px;
  cursor: col-resize;
  touch-action: none;
}
.ai-dock-header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  border-bottom: 1px solid var(--line);
}
.ai-dock-header strong {
  font-size: 14px;
  color: var(--ink);
  white-space: nowrap;
}
.ai-dock-switchers {
  display: flex;
  gap: 6px;
  flex: 1;
  min-width: 0;
}
.dock-select {
  min-width: 0;
  flex: 1;
  padding: 5px 8px;
  border-radius: 8px;
  border: 1px solid var(--line-strong);
  background: var(--surface);
  color: var(--ink);
  font-size: 12px;
}
.ai-dock-actions {
  display: flex;
  gap: 4px;
  align-items: center;
}
.dock-expand,
.dock-close {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border-radius: 8px;
  border: none;
  background: transparent;
  color: var(--muted);
  font-size: 16px;
  cursor: pointer;
  text-decoration: none;
}
.dock-expand:hover,
.dock-close:hover {
  color: var(--accent);
  background: var(--surface);
}
.ai-dock-body {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

/* 移动端：全屏抽屉，宽度拖拽失效 */
@media (max-width: 720px) {
  .ai-dock-panel {
    width: 100vw !important;
    left: 0;
  }
  .ai-dock-resizer {
    display: none;
  }
}
</style>
