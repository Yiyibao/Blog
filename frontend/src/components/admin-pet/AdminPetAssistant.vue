<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '../../stores/auth'
import { useUiStore } from '../../stores/uiStore'
import { useAiStore } from '../../stores/aiStore'
import { usePrefersReducedMotion } from '../../composables/usePrefersReducedMotion'
import type { AiReasoningSelection } from '../../api/admin'
import AdminAiChat from '../AdminAiChat.vue'
import PetSprite from './PetSprite.vue'
import { ATLAS, HD_SOURCE } from './petAnimations'
import type { PetState } from './petAnimations'
import { createIdleScheduler } from './petIdleScheduler'
import type { IdleActionId } from './petIdleScheduler'

/**
 * 全局宠物/紧凑聊天宿主（全站唯一实例，App.vue 仅在 auth.isStaff 且非登录页时挂载）。
 * - 聊天面板只在打开时挂载 compact AdminAiChat，关闭即销毁；会话由既有 sessionStorage 恢复。
 * - /admin/ai 全屏页不挂载 compact 聊天；点击宠物只聚焦完整页输入框，绝不出现第二个实例。
 * - 隐藏仅存 sessionStorage；Ctrl/Cmd+Shift+A 恢复并打开；logout 清理隐藏状态。
 * - 宠物动画状态由 AdminAiChat 的类型化 emits 驱动，不复制聊天请求逻辑。
 * - 供应商/模型选择由 aiStore 统一维护，与全屏聊天页、供应商页实时同步。
 * - 无互动时默认原版 idle（微晃呼吸）；待机调度器以随机间隔（默认 12-36 秒）
 *   触发一次待机动作；hover 即清零；点击打开聊天面板时播放一次 chat-open
 *   （面板立即挂载，动画不阻塞交互，播完落回 waiting，不循环）。
 */
const HIDDEN_KEY = 'yubai-admin-pet-hidden'
const PET_POS_KEY = 'yubai-admin-pet-pos'
const GAZE_RADIUS = 420
/** P5：宠物尺寸——桌面 307、移动 243（当前 384/304 的 0.8 倍）。 */
const DESKTOP_SIZE = 307
const MOBILE_SIZE = 243
/** 小于该位移视为点击而非拖动 */
const DRAG_CLICK_THRESHOLD = 6
/** 面板尺寸用于「贴顶时向下翻转」「贴左时向右翻转」的几何判断 */
const PANEL_MAX_HEIGHT = 620
const PANEL_MIN_WIDTH = 380
/** 宠物栈额外部件：隐藏按钮高 ≈30px + 间距 6px */
const STACK_EXTRA = 36

const auth = useAuthStore()
const ui = useUiStore()
const ai = useAiStore()
const route = useRoute()
const reduced = usePrefersReducedMotion()

const panelOpen = ref(false)
const petHidden = ref(readHidden())
const petButtonRef = ref<HTMLElement | null>(null)
/** 一次性动画：waving / failed / review；播放完毕由 PetSprite finished 清空。 */
const oneShot = ref<'waving' | 'failed' | 'review' | null>(null)
const streaming = ref(false)
const petSize = ref(DESKTOP_SIZE)
/** P5：宠物左上角相对视口的位置（position: fixed 的 left/top，拖动实时更新）。 */
const petPos = ref({ x: 0, y: 0 })
const dragging = ref(false)
let dragSession: {
  startX: number
  startY: number
  originX: number
  originY: number
  moved: boolean
} | null = null
let suppressNextClick = false

const petHeight = computed(() => petSize.value * ATLAS.cellHeight / ATLAS.cellWidth)
const petStackHeight = computed(() => petHeight.value + STACK_EXTRA)

const gazeDirection = ref(0)
const gazeNear = ref(false)
let gazeFrame: number | undefined
let lastGazeEvent = 0

const isLoginRoute = computed(() => route.name === 'login' || route.name === 'admin-login')
const isAdminAiRoute = computed(() => route.name === 'admin-ai')

const reasoningOptions: Array<{ value: AiReasoningSelection; label: string }> = [
  { value: 'auto', label: '自动' },
  { value: 'none', label: '关闭' },
  { value: 'minimal', label: '极低' },
  { value: 'low', label: '低' },
  { value: 'medium', label: '中' },
  { value: 'high', label: '高' },
  { value: 'xhigh', label: '极高' },
]

const petVisible = computed(() => auth.isStaff && !petHidden.value && !isLoginRoute.value)

/** 正在播放的待机动作（随机间隔触发一次，一轮后回 idle）。 */
const idleAction = ref<IdleActionId | null>(null)
/** 点击打开聊天面板时播放一次的 chat-open 动作。 */
const chatOpen = ref(false)
/** 指针是否悬浮在宠物按钮命中区域内（悬浮期间不启动待机计时）。 */
const hovered = ref(false)
const pageVisible = ref(typeof document === 'undefined' || document.visibilityState === 'visible')

/** 待机计时必要条件：可见/未悬浮/未拖动/面板关/未流式/无一次性动作/未禁用动画。 */
const idleEligible = computed(() =>
  petVisible.value
  && !panelOpen.value
  && !streaming.value
  && !dragging.value
  && !hovered.value
  && !reduced.value
  && pageVisible.value
  && oneShot.value === null
  && !chatOpen.value)

const scheduler = createIdleScheduler({
  onStart: (action) => {
    idleAction.value = action
  },
  onFinish: () => {
    // 计时已由调度器内部从零重启；父级无需额外处理
  },
  onCancel: (action) => {
    if (idleAction.value === action) idleAction.value = null
  },
})

/** 待机资格翻转：恢复后从零重新计时；条件不满足则停止并取消正在播放的动作。 */
watch(idleEligible, (eligible) => {
  if (eligible) scheduler.restart()
  else scheduler.stop()
}, { immediate: true })

/** 状态优先级：dragging > failed > running > chat-open > waving/review > idle-action > look > idle。
 *  面板打开时回归常态 idle（微晃呼吸）——点击动作播完即停，不循环 waiting。 */
const displayState = computed<PetState | 'look'>(() => {
  if (dragging.value) return 'idle'
  // reduced-motion：跳过所有动画状态，稳定显示 idle 首帧（业务交互不受影响）
  if (reduced.value) return 'idle'
  if (oneShot.value === 'failed') return 'failed'
  if (streaming.value) return 'running'
  if (chatOpen.value) return 'chat-open'
  if (oneShot.value === 'waving' || oneShot.value === 'review') return oneShot.value
  if (idleAction.value) return idleAction.value
  if (!petVisible.value) return 'idle'
  if (gazeNear.value && !panelOpen.value && !reduced.value) return 'look'
  return 'idle'
})

/** 面板贴近顶部空间不足时向下翻转，贴近左缘时向右翻转。 */
const panelFlippedDown = computed(() => {
  if (!panelOpen.value || mobileQuery?.matches) return false
  const vh = window.innerHeight || 0
  const panelHeight = Math.min(PANEL_MAX_HEIGHT, vh * 0.72)
  const roomAbove = petPos.value.y
  const roomBelow = Math.max(0, vh - (petPos.value.y + petStackHeight.value) - 12)
  // 上方放不下才向下翻；若下方同样放不下（宠物在屏幕底部），保持默认向上展开
  return roomAbove < panelHeight + 12 && roomBelow > roomAbove
})

const panelFlippedLeft = computed(() => {
  if (!panelOpen.value || mobileQuery?.matches) return false
  return petPos.value.x < PANEL_MIN_WIDTH
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

// ---- P5：位置持久化 / 视口夹紧 / 拖动 ----
function readSavedPos(): { x: number; y: number } | null {
  try {
    const raw = window.localStorage?.getItem(PET_POS_KEY)
    if (!raw) return null
    const parsed: unknown = JSON.parse(raw)
    if (parsed && typeof parsed === 'object'
      && typeof (parsed as { x?: unknown }).x === 'number'
      && typeof (parsed as { y?: unknown }).y === 'number') {
      return parsed as { x: number; y: number }
    }
  } catch {
    // 脏数据：忽略
  }
  return null
}

function savePos() {
  try {
    window.localStorage?.setItem(PET_POS_KEY, JSON.stringify(petPos.value))
  } catch {
    // 隐私模式：仅本次内存状态生效
  }
}

function clampPos(pos: { x: number; y: number }): { x: number; y: number } {
  const margin = 4
  const maxX = Math.max(margin, window.innerWidth - petSize.value - margin)
  const maxY = Math.max(margin, window.innerHeight - petStackHeight.value - margin)
  return {
    x: Math.min(Math.max(pos.x, margin), maxX),
    y: Math.min(Math.max(pos.y, margin), maxY),
  }
}

function defaultPos(): { x: number; y: number } {
  const mobile = mobileQuery?.matches === true
  return clampPos({
    x: window.innerWidth - petSize.value - (mobile ? 8 : 20),
    y: window.innerHeight - petStackHeight.value - (mobile ? 8 : 18),
  })
}

function onPetPointerDown(event: PointerEvent) {
  suppressNextClick = false
  // 用户主动接触：中断启动 waving / 流式 review / failed 等一次性动画残留
  oneShot.value = null
  dragSession = {
    startX: event.clientX,
    startY: event.clientY,
    originX: petPos.value.x,
    originY: petPos.value.y,
    moved: false,
  }
  dragging.value = true
  cancelGaze()
  window.addEventListener('pointermove', onDragPointerMove)
  window.addEventListener('pointerup', onDragPointerUp)
  window.addEventListener('pointercancel', onDragPointerUp)
}

function onDragPointerMove(event: PointerEvent) {
  if (!dragSession) return
  const dx = event.clientX - dragSession.startX
  const dy = event.clientY - dragSession.startY
  if (Math.abs(dx) + Math.abs(dy) > DRAG_CLICK_THRESHOLD) dragSession.moved = true
  petPos.value = clampPos({ x: dragSession.originX + dx, y: dragSession.originY + dy })
}

function onDragPointerUp() {
  if (!dragSession) return
  if (dragSession.moved) suppressNextClick = true
  dragSession = null
  dragging.value = false
  window.removeEventListener('pointermove', onDragPointerMove)
  window.removeEventListener('pointerup', onDragPointerUp)
  window.removeEventListener('pointercancel', onDragPointerUp)
  savePos()
}

/** 拖动结束时抑制紧随其后的 click；键盘激活（Enter/Space）不受影响。 */
function onPetButtonClick() {
  if (suppressNextClick) {
    suppressNextClick = false
    return
  }
  const willOpen = !panelOpen.value
  // 用户主动点击：中断启动 waving / review / failed 等一次性动画残留，
  // 保证 chat-open 播完后直接衔接 waiting，不出现"动作重复播放"观感
  oneShot.value = null
  togglePanel()
  // 打开时播放一次 chat-open（动画不阻塞面板挂载与输入框聚焦）；关闭不反向播放
  if (willOpen) playChatOpen()
}

/** chat-open：点击瞬间的欢迎动作，恰好播放一次后由 finished 清除；reduced-motion 下不播放。 */
function playChatOpen() {
  if (reduced.value) return
  chatOpen.value = true
}

function onPetPointerEnter() {
  hovered.value = true
  gazeNear.value = false
}

function onPetPointerLeave() {
  hovered.value = false
}

function onViewportResize() {
  petPos.value = clampPos(petPos.value)
}

function focusChatInput() {
  const input = document.querySelector<HTMLElement>('[data-testid="ai-chat-input"]')
  if (input) input.focus()
}

function closePanel() {
  panelOpen.value = false
  gazeNear.value = false
  streaming.value = false
  chatOpen.value = false
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
  // Clear stale selections synchronously, then refresh without delaying panel opening.
  // Until the registry arrives, AdminAiChat omits provider/model so the backend resolves
  // the current default instead of submitting an outdated explicit model.
  void ai.ensureProviders()
  panelOpen.value = true
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

/** PetSprite finished(id)：只清理仍匹配的动作；idle 动作结束同时推进调度器回到计时。 */
function onPetFinished(id: string) {
  if (id === 'chat-open') {
    chatOpen.value = false
    return
  }
  if (id === 'idle-curious' || id === 'idle-sleeve' || id === 'idle-sway') {
    if (idleAction.value === id) {
      idleAction.value = null
      scheduler.handleActionFinished(id)
    }
    return
  }
  if (oneShot.value === id) oneShot.value = null
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

// ---- 指针视线（16 方向，仅非触屏/非 reduced-motion/未聊天/未拖动/可见时启用，rAF 节流）----
function onPointerMove(event: PointerEvent) {
  if (event.pointerType === 'touch' || dragging.value) return
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
  pageVisible.value = document.visibilityState === 'visible'
  if (!pageVisible.value) cancelGaze()
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
  petPos.value = clampPos(petPos.value)
}

onMounted(() => {
  ai.subscribe()
  window.addEventListener('keydown', onKeydown)
  window.addEventListener('pointermove', onPointerMove, { passive: true })
  window.addEventListener('resize', onViewportResize)
  document.addEventListener('mouseleave', onMouseLeave)
  document.addEventListener('visibilitychange', onVisibilityChange)
  if (typeof window.matchMedia === 'function') {
    mobileQuery = window.matchMedia('(max-width: 720px)')
    petSize.value = mobileQuery.matches ? MOBILE_SIZE : DESKTOP_SIZE
    mobileQuery.addEventListener?.('change', syncPetSize)
  }
  // P5：恢复上次拖动位置（夹紧到视口内），无保存值时落在右下角默认位
  petPos.value = clampPos(readSavedPos() ?? defaultPos())
  if (!petHidden.value) oneShot.value = 'waving'
  // 流畅优化：预热关键行图（chat-open/waiting），点击开窗动作与面板状态切换不闪烁
  for (const rowId of ['chat-open', 'waiting'] as const) {
    const img = new Image()
    img.src = HD_SOURCE(rowId).url
  }
})

onBeforeUnmount(() => {
  scheduler.dispose()
  ai.unsubscribe()
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('pointermove', onPointerMove)
  window.removeEventListener('resize', onViewportResize)
  document.removeEventListener('mouseleave', onMouseLeave)
  document.removeEventListener('visibilitychange', onVisibilityChange)
  window.removeEventListener('pointermove', onDragPointerMove)
  window.removeEventListener('pointerup', onDragPointerUp)
  window.removeEventListener('pointercancel', onDragPointerUp)
  mobileQuery?.removeEventListener?.('change', syncPetSize)
  mobileQuery = null
  cancelGaze()
  closePanel()
})
</script>

<template>
  <div
    v-if="petVisible"
    class="pet-assistant"
    data-testid="admin-pet-assistant"
    :style="{ left: `${petPos.x}px`, top: `${petPos.y}px` }"
  >
    <aside
      v-if="panelOpen && !isAdminAiRoute"
      id="pet-chat-panel"
      class="pet-chat-panel"
      :class="{ 'panel-below': panelFlippedDown, 'panel-left': panelFlippedLeft }"
      role="complementary"
      aria-label="AI 宠物助手对话"
      data-testid="pet-chat-panel"
    >
      <header class="pet-chat-header">
        <strong>✦ Xinn 宠物助手</strong>
        <div v-if="ai.providers.length > 1 || ai.modelOptions.length > 1 || (ai.providers.length && ai.reasoningSupported)" class="pet-chat-switchers">
          <select
            v-if="ai.providers.length > 1"
            class="pet-chat-select"
            :value="ai.selectedProviderId ?? ''"
            aria-label="选择供应商"
            data-testid="pet-provider-select"
            @change="ai.selectProvider(($event.target as HTMLSelectElement).value)"
          >
            <option v-for="p in ai.providers" :key="p.id" :value="p.id">{{ p.name }}</option>
          </select>
          <select
            v-if="ai.modelOptions.length > 1"
            class="pet-chat-select"
            :value="ai.selectedModel ?? ''"
            aria-label="选择模型"
            data-testid="pet-model-select"
            @change="ai.selectModel(($event.target as HTMLSelectElement).value)"
          >
            <option v-for="m in ai.modelOptions" :key="m" :value="m">{{ m }}</option>
          </select>
          <select
            v-if="ai.reasoningSupported"
            class="pet-chat-select"
            :value="ai.selectedReasoningEffort"
            aria-label="选择推理强度"
            data-testid="pet-reasoning-select"
            @change="ai.selectReasoningEffort(($event.target as HTMLSelectElement).value as AiReasoningSelection)"
          >
            <option v-for="option in reasoningOptions" :key="option.value" :value="option.value">
              推理：{{ option.label }}
            </option>
          </select>
        </div>
        <button type="button" class="pet-chat-close" aria-label="收起聊天面板" @click="closePanel">×</button>
      </header>
      <div class="pet-chat-body">
        <AdminAiChat
          compact
          :provider-id="ai.selectedProviderId"
          :model="ai.selectedModel"
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
        :class="{ dragging }"
        data-testid="pet-button"
        :aria-label="isAdminAiRoute ? '宠物助手：聚焦全屏聊天输入框' : (panelOpen ? '收起 AI 宠物助手' : '打开 AI 宠物助手')"
        :aria-expanded="!isAdminAiRoute ? panelOpen : undefined"
        :aria-controls="!isAdminAiRoute ? 'pet-chat-panel' : undefined"
        @pointerdown="onPetPointerDown"
        @pointerenter="onPetPointerEnter"
        @pointerleave="onPetPointerLeave"
        @click="onPetButtonClick"
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
  /* P5：left/top 由拖动逻辑以内联样式写入，此处仅作 JS 失效时的兜底 */
  left: 0;
  top: 0;
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
  cursor: grab;
  border-radius: 12px;
  line-height: 0;
  /* P5：触屏拖动手势不触发页面滚动/缩放 */
  touch-action: none;
  -webkit-user-select: none;
  user-select: none;
}
.pet-button.dragging {
  cursor: grabbing;
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

/* P5：宠物被拖到贴近顶部时面板向下展开；贴近左缘时面板向右展开 */
.pet-chat-panel.panel-below {
  bottom: auto;
  top: calc(100% + 12px);
}
.pet-chat-panel.panel-left {
  right: auto;
  left: 0;
}

@media (max-width: 720px) {
  /* P1：移动端面板直接以 viewport 为定位基准（position: fixed + 双侧 inset），
     不再从右下角宠物容器向外延伸；bottom 偏移 = 宠物(≈263px) + 隐藏按钮(≈30px) + 间距，
     保证面板与宠物、输入框同屏可见。宠物容器位置由拖动逻辑控制，不再用 CSS 定位。 */
  .pet-chat-panel {
    position: fixed;
    left: 8px;
    right: 8px;
    width: auto;
    max-width: none;
    top: auto;
    bottom: calc(8px + env(safe-area-inset-bottom) + 330px);
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
