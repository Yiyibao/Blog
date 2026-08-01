<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '../../stores/auth'
import { useUiStore } from '../../stores/uiStore'
import { usePrefersReducedMotion } from '../../composables/usePrefersReducedMotion'
import { fetchAiProviders, type AiProvider } from '../../api/admin'
import AdminAiChat from '../AdminAiChat.vue'
import PetSprite from './PetSprite.vue'
import type { PetState } from './petAnimations'

/**
 * 全局宠物/紧凑聊天宿主（全站唯一实例，App.vue 仅在 auth.isStaff 且非登录页时挂载）。
 * - 聊天面板只在打开时挂载 compact AdminAiChat，关闭即销毁；会话由既有 sessionStorage 恢复。
 * - /admin/ai 全屏页不挂载 compact 聊天；点击宠物只聚焦完整页输入框，绝不出现第二个实例。
 * - 隐藏仅存 sessionStorage；Ctrl/Cmd+Shift+A 恢复并打开；logout 清理隐藏状态。
 * - 宠物动画状态由 AdminAiChat 的类型化 emits 驱动，不复制聊天请求逻辑。
 */
const HIDDEN_KEY = 'yubai-admin-pet-hidden'
const GAZE_RADIUS = 240
const DESKTOP_SIZE = 96
const MOBILE_SIZE = 76

const auth = useAuthStore()
const ui = useUiStore()
const route = useRoute()
const reduced = usePrefersReducedMotion()

const panelOpen = ref(false)
const petHidden = ref(readHidden())
const petButtonRef = ref<HTMLElement | null>(null)
/** 一次性动画：waving / failed / review；播放完毕由 PetSprite finished 清空。 */
const oneShot = ref<'waving' | 'failed' | 'review' | null>(null)
const streaming = ref(false)
const petSize = ref(DESKTOP_SIZE)

const providers = ref<AiProvider[]>([])
const selectedProviderId = ref<number | null>(null)
const selectedModel = ref<string | null>(null)
let providersLoaded = false

const gazeDirection = ref(0)
const gazeNear = ref(false)
let gazeFrame: number | undefined
let lastGazeEvent = 0

const isLoginRoute = computed(() => route.name === 'login' || route.name === 'admin-login')
const isAdminAiRoute = computed(() => route.name === 'admin-ai')

const petVisible = computed(() => auth.isStaff && !petHidden.value && !isLoginRoute.value)

/** 优先级：failed > running > waiting > waving > review > idle/look（生成中无 gaze）。 */
const displayState = computed<PetState | 'look'>(() => {
  if (oneShot.value === 'failed') return 'failed'
  if (streaming.value) return 'running'
  if (oneShot.value === 'waving') return 'waving'
  if (oneShot.value === 'review') return 'review'
  if (!petVisible.value) return 'idle'
  if (gazeNear.value && !panelOpen.value && !reduced.value) return 'look'
  return panelOpen.value ? 'waiting' : 'idle'
})

function readHidden(): boolean {
  try {
    return window.sessionStorage?.getItem(HIDDEN_KEY) === '1'
  } catch {
    return false
  }
}

function writeHidden(hidden: boolean) {
  try {
    if (hidden) window.sessionStorage?.setItem(HIDDEN_KEY, '1')
    else window.sessionStorage?.removeItem(HIDDEN_KEY)
  } catch {
    // 隐私模式：仅本次内存状态生效
  }
}

async function ensureProviders() {
  if (providersLoaded) return
  providersLoaded = true
  try {
    providers.value = (await fetchAiProviders()).filter((provider) => provider.enabled)
    const preferred = providers.value.find((provider) => provider.isDefault)
      ?? providers.value[0]
      ?? null
    selectedProviderId.value = preferred?.id ?? null
    selectedModel.value = preferred?.defaultModel || preferred?.models?.[0] || null
  } catch {
    // 注册表不可用时走 env 默认供应商，切换器留空
    providers.value = []
  }
}

const selectedProvider = computed(() =>
  providers.value.find((provider) => provider.id === selectedProviderId.value) ?? null)

/** model 选项始终包含 defaultModel，即使它未出现在 models 数组中。 */
const modelOptions = computed(() => {
  const provider = selectedProvider.value
  if (!provider) return []
  const models = [...(provider.models ?? [])]
  if (provider.defaultModel && !models.includes(provider.defaultModel)) {
    models.unshift(provider.defaultModel)
  }
  return models
})

function onProviderChange(raw: string) {
  selectedProviderId.value = raw ? Number(raw) : null
  const provider = selectedProvider.value
  selectedModel.value = provider?.defaultModel || provider?.models?.[0] || null
}

function focusChatInput() {
  const input = document.querySelector<HTMLElement>('[data-testid="ai-chat-input"]')
  if (input) input.focus()
}

function closePanel() {
  panelOpen.value = false
  gazeNear.value = false
  streaming.value = false
}

async function openPanel() {
  if (!petVisible.value) return
  gazeNear.value = false
  if (isAdminAiRoute.value) {
    focusChatInput()
    if (!document.activeElement || document.activeElement.tagName !== 'TEXTAREA') {
      ui.showToast('请在下方全屏聊天输入框直接开始对话')
    }
    return
  }
  panelOpen.value = true
  void ensureProviders()
  await nextTick()
  focusChatInput()
}

function togglePanel() {
  if (panelOpen.value) closePanel()
  else void openPanel()
}

function hidePet() {
  closePanel()
  petHidden.value = true
  writeHidden(true)
  oneShot.value = null
}

function restoreAndOpen() {
  petHidden.value = false
  writeHidden(false)
  void openPanel()
}

function onPetFinished() {
  oneShot.value = null
}

// ---- AdminAiChat 事件 → 宠物动画 ----
function onStreamStart() {
  streaming.value = true
  oneShot.value = null
}

function onStreamFirstDelta() {
  streaming.value = true
  oneShot.value = null
}

function onStreamComplete() {
  streaming.value = false
  oneShot.value = 'review'
}

function onStreamError() {
  streaming.value = false
  oneShot.value = 'failed'
}

function onStreamAbort() {
  streaming.value = false
  oneShot.value = null
}

// ---- 指针视线（16 方向，仅非触屏/非 reduced-motion/未聊天/可见时启用，rAF 节流）----
function onPointerMove(event: PointerEvent) {
  if (event.pointerType === 'touch') return
  if (!petVisible.value || panelOpen.value || streaming.value || reduced.value) {
    gazeNear.value = false
    return
  }
  const now = performance.now()
  if (now - lastGazeEvent < 50) return
  lastGazeEvent = now
  if (gazeFrame !== undefined) return
  gazeFrame = requestAnimationFrame(() => {
    gazeFrame = undefined
    const el = petButtonRef.value
    if (!el) {
      gazeNear.value = false
      return
    }
    const rect = el.getBoundingClientRect()
    const cx = rect.left + rect.width / 2
    const cy = rect.top + rect.height / 2
    const dx = event.clientX - cx
    const dy = event.clientY - cy
    gazeNear.value = Math.hypot(dx, dy) <= GAZE_RADIUS
    if (gazeNear.value) {
      gazeDirection.value = (Math.atan2(dx, -dy) * 180 / Math.PI + 360) % 360
    }
  })
}

function onMouseLeave() {
  gazeNear.value = false
}

function cancelGaze() {
  if (gazeFrame !== undefined) {
    cancelAnimationFrame(gazeFrame)
    gazeFrame = undefined
  }
  gazeNear.value = false
}

// ---- 键盘：Escape 收起并还焦点；Ctrl/Cmd+Shift+A 恢复并打开 / 切换 ----
function onKeydown(event: KeyboardEvent) {
  if ((event.ctrlKey || event.metaKey) && event.shiftKey && event.key.toLowerCase() === 'a') {
    event.preventDefault()
    if (!petVisible.value || petHidden.value) {
      restoreAndOpen()
    } else if (panelOpen.value) {
      closePanel()
      petButtonRef.value?.focus()
    } else {
      void openPanel()
    }
    return
  }
  if (event.key === 'Escape' && panelOpen.value) {
    closePanel()
    petButtonRef.value?.focus()
  }
}

function onVisibilityChange() {
  if (document.visibilityState !== 'visible') cancelGaze()
}

// ---- 登录态 / 路由清理 ----
watch(() => auth.isStaff, (staff) => {
  if (!staff) {
    closePanel()
    cancelGaze()
    oneShot.value = null
    petHidden.value = false
    writeHidden(false)
  }
})

watch(isLoginRoute, (login) => {
  if (login) {
    closePanel()
    cancelGaze()
  }
})

// P3：进入 /admin/ai 时立即收起紧凑面板并清理状态，
// 保证第一次点击宠物直接聚焦完整页输入框；离开后不自动弹开面板。
watch(isAdminAiRoute, (adminAi) => {
  if (adminAi) closePanel()
})

let mobileQuery: MediaQueryList | null = null

/** P4：回调引用提升到组件作用域，卸载时用同一引用移除监听。 */
function syncPetSize() {
  petSize.value = mobileQuery?.matches ? MOBILE_SIZE : DESKTOP_SIZE
}

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
  window.addEventListener('pointermove', onPointerMove, { passive: true })
  document.addEventListener('mouseleave', onMouseLeave)
  document.addEventListener('visibilitychange', onVisibilityChange)
  if (typeof window.matchMedia === 'function') {
    mobileQuery = window.matchMedia('(max-width: 720px)')
    syncPetSize()
    mobileQuery.addEventListener?.('change', syncPetSize)
  }
  if (!petHidden.value) oneShot.value = 'waving'
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('pointermove', onPointerMove)
  document.removeEventListener('mouseleave', onMouseLeave)
  document.removeEventListener('visibilitychange', onVisibilityChange)
  mobileQuery?.removeEventListener?.('change', syncPetSize)
  mobileQuery = null
  cancelGaze()
  closePanel()
})
</script>

<template>
  <div v-if="petVisible" class="pet-assistant" data-testid="admin-pet-assistant">
    <aside
      v-if="panelOpen && !isAdminAiRoute"
      id="pet-chat-panel"
      class="pet-chat-panel"
      role="complementary"
      aria-label="AI 宠物助手对话"
      data-testid="pet-chat-panel"
    >
      <header class="pet-chat-header">
        <strong>✦ Xinn 宠物助手</strong>
        <div v-if="providers.length > 1 || modelOptions.length > 1" class="pet-chat-switchers">
          <select
            v-if="providers.length > 1"
            class="pet-chat-select"
            :value="selectedProviderId ?? ''"
            aria-label="选择供应商"
            data-testid="pet-provider-select"
            @change="onProviderChange(($event.target as HTMLSelectElement).value)"
          >
            <option v-for="p in providers" :key="p.id" :value="p.id">{{ p.name }}</option>
          </select>
          <select
            v-if="modelOptions.length > 1"
            v-model="selectedModel"
            class="pet-chat-select"
            aria-label="选择模型"
            data-testid="pet-model-select"
          >
            <option v-for="m in modelOptions" :key="m" :value="m">{{ m }}</option>
          </select>
        </div>
        <button type="button" class="pet-chat-close" aria-label="收起聊天面板" @click="closePanel">×</button>
      </header>
      <div class="pet-chat-body">
        <AdminAiChat
          compact
          :provider-id="selectedProviderId"
          :model="selectedModel"
          @stream-start="onStreamStart"
          @stream-first-delta="onStreamFirstDelta"
          @stream-complete="onStreamComplete"
          @stream-error="onStreamError"
          @stream-abort="onStreamAbort"
        />
      </div>
    </aside>

    <div class="pet-stack">
      <button
        type="button"
        class="pet-hide-button"
        aria-label="隐藏宠物"
        data-testid="pet-hide-button"
        @click="hidePet"
      >隐藏宠物</button>
      <button
        ref="petButtonRef"
        type="button"
        class="pet-button"
        data-testid="pet-button"
        :aria-label="isAdminAiRoute ? '宠物助手：聚焦全屏聊天输入框' : (panelOpen ? '收起 AI 宠物助手' : '打开 AI 宠物助手')"
        :aria-expanded="!isAdminAiRoute ? panelOpen : undefined"
        :aria-controls="!isAdminAiRoute ? 'pet-chat-panel' : undefined"
        @click="togglePanel"
      >
        <PetSprite
          :state="displayState"
          :look-direction="gazeDirection"
          :size="petSize"
          @finished="onPetFinished"
        />
      </button>
    </div>
  </div>
</template>

<style scoped>
.pet-assistant {
  position: fixed;
  right: 20px;
  bottom: calc(18px + env(safe-area-inset-bottom));
  z-index: 320;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

.pet-stack {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}

.pet-button {
  display: block;
  padding: 0;
  border: 0;
  background: transparent;
  cursor: pointer;
  border-radius: 12px;
  line-height: 0;
}
.pet-button:focus-visible {
  outline: 3px solid var(--accent, #d5b18a);
  outline-offset: 3px;
}
.pet-button:hover .pet-sprite,
.pet-button:focus-visible .pet-sprite {
  filter: drop-shadow(0 6px 14px rgba(0, 0, 0, 0.18));
}

.pet-hide-button {
  padding: 5px 12px;
  border: 1px solid var(--line-strong, #d9d6cf);
  border-radius: 999px;
  background: var(--surface-solid, #ffffff);
  color: var(--muted, #7f7e77);
  font-size: 12px;
  cursor: pointer;
  opacity: 0;
  transform: translateY(4px);
  pointer-events: none;
  transition: opacity 0.2s, transform 0.2s;
}
.pet-stack:hover .pet-hide-button,
.pet-hide-button:focus-visible {
  opacity: 1;
  transform: translateY(0);
  pointer-events: auto;
}
.pet-hide-button:focus-visible {
  outline: 3px solid var(--accent, #d5b18a);
  outline-offset: 2px;
}

.pet-chat-panel {
  position: absolute;
  right: 0;
  bottom: calc(100% + 12px);
  width: 380px;
  max-width: calc(100vw - 24px);
  height: min(72dvh, 620px);
  display: flex;
  flex-direction: column;
  border: 1px solid var(--line-strong, #d9d6cf);
  border-radius: 16px;
  background: var(--surface-solid, #ffffff);
  box-shadow: 0 18px 50px rgba(34, 32, 27, 0.22);
  overflow: hidden;
}
.pet-chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 10px 14px;
  border-bottom: 1px solid var(--line, #e5e1d8);
}
.pet-chat-header strong {
  color: var(--ink, #20211e);
  font-size: 14px;
  white-space: nowrap;
}
.pet-chat-switchers {
  display: flex;
  gap: 6px;
  flex: 1;
  min-width: 0;
}
.pet-chat-select {
  min-width: 0;
  flex: 1;
  padding: 5px 8px;
  border-radius: 8px;
  border: 1px solid var(--line-strong, #d9d6cf);
  background: var(--surface, #faf8f5);
  color: var(--ink, #20211e);
  font-size: 12px;
  outline: none;
}
.pet-chat-select:focus-visible {
  border-color: var(--accent, #d5b18a);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--accent, #d5b18a) 18%, transparent);
}
.pet-chat-close {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border-radius: 8px;
  border: none;
  background: transparent;
  color: var(--muted, #7f7e77);
  font-size: 18px;
  cursor: pointer;
  flex-shrink: 0;
}
.pet-chat-close:hover,
.pet-chat-close:focus-visible {
  color: var(--accent, #d5b18a);
  background: var(--surface, #faf8f5);
  outline: 2px solid var(--accent, #d5b18a);
  outline-offset: 1px;
}
.pet-chat-body {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

@media (max-width: 720px) {
  /* P1：移动端面板直接以 viewport 为定位基准（position: fixed + 双侧 inset），
     不再从右下角宠物容器向外延伸；bottom 偏移 = 宠物(≈82px) + 隐藏按钮(≈28px) + 间距，
     保证面板与宠物、输入框同屏可见。 */
  .pet-assistant {
    right: 8px;
    bottom: calc(8px + env(safe-area-inset-bottom));
  }
  .pet-chat-panel {
    position: fixed;
    left: 8px;
    right: 8px;
    width: auto;
    max-width: none;
    bottom: calc(8px + env(safe-area-inset-bottom) + 120px);
    height: min(72dvh, 620px);
    border-radius: 16px 16px 0 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  .pet-hide-button {
    transition: none;
  }
}
</style>
