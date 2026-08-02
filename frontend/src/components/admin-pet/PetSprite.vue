<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { usePrefersReducedMotion } from '../../composables/usePrefersReducedMotion'
import {
  HD_ROWS, HD_SOURCE, LEGACY_SOURCE, lookCell, lookHdRow,
  PET_HD_ANIMATIONS, PET_LEGACY_ROWS,
  type PetState,
} from './petAnimations'

/**
 * 纯渲染组件：只负责按动画行切换高清资源、逐帧定时、状态切换与 reduced-motion 静态退化。
 * - 每个状态对应一张独立高清行图（3072×416，单格 384×416），裁切与缩放完全依据
 *   sourceCellWidth/sourceCellHeight 计算；对外 size（307/243）保持不变。
 * - 资源加载：状态切换递增播放令牌并回到第 0 帧；当前图片已缓存则立即调度，
 *   否则等待匹配令牌的 load；旧资源的迟到 load 不得启动旧状态。
 * - 高清加载失败：标准状态回退旧 8×11 图集对应行；无可用回退时静态首帧，
 *   但 one-shot 仍按名义时长 emit finished，避免父级状态机卡死。
 * - finished 事件携带实际完成的动画 id，父组件只清理仍匹配的动作。
 */
const props = withDefaults(defineProps<{
  state: PetState | 'look'
  /** 仅 state === 'look' 时使用，0° = 上，顺时针，单位度。 */
  lookDirection?: number
  /** 宠物显示的 CSS 宽度（px），高度按 sourceCellHeight/sourceCellWidth 等比。 */
  size?: number
}>(), {
  lookDirection: 0,
  size: 96,
})

const emit = defineEmits<{ finished: [id: string] }>()

const reduced = usePrefersReducedMotion()

const spec = computed(() => (props.state === 'look' ? null : PET_HD_ANIMATIONS[props.state]))
const frame = ref(0)
const fallbackLegacy = ref(false)

let timer: ReturnType<typeof setTimeout> | undefined
let pageVisible = true
/** 播放令牌：状态/方向/回退切换时递增，过期令牌的 timer 与 load 回调一律作废。 */
let token = 0
let scheduled = false
let pendingToken = -1
let pendingSrc = ''

const hdSource = computed(() =>
  props.state === 'look'
    ? HD_SOURCE(lookHdRow(props.lookDirection))
    : PET_HD_ANIMATIONS[props.state].source)

/** 高清失败时回退旧 8×11 图集（新动作行回退到旧 idle 行）。 */
const source = computed(() => (fallbackLegacy.value ? LEGACY_SOURCE : hdSource.value))

/** 回退行的行号与有效帧数。 */
const fallbackMeta = computed(() => {
  if (props.state === 'look') return { row: lookCell(props.lookDirection).row, frames: 8 }
  const legacy = PET_LEGACY_ROWS[props.state]
  return { row: legacy?.row ?? 0, frames: legacy?.frames ?? 6 }
})

const scale = computed(() => props.size / source.value.cellWidth)
const viewHeight = computed(() => props.size * source.value.cellHeight / source.value.cellWidth)
const imageWidth = computed(() => source.value.cellWidth * source.value.columns * scale.value)
const imageHeight = computed(() => source.value.cellHeight * source.value.rows * scale.value)

const cell = computed(() => {
  if (props.state === 'look') {
    const pos = lookCell(props.lookDirection)
    // data-row 保留旧图集身份行号（9/10）；HD 行图内部行恒为 0
    return { row: pos.row, col: pos.col }
  }
  const s = spec.value!
  const maxCol = fallbackLegacy.value ? fallbackMeta.value.frames - 1 : s.frames - 1
  return {
    row: HD_ROWS[props.state].legacyRow,
    col: Math.min(frame.value, maxCol),
  }
})

/** 图内实际行：HD 行图为单行（0）；回退旧图集时用 legacyRow。 */
const transformRow = computed(() =>
  fallbackLegacy.value ? fallbackMeta.value.row : 0)

const imageTransform = computed(() =>
  `translate(${-cell.value.col * source.value.cellWidth * scale.value}px, ${-transformRow.value * source.value.cellHeight * scale.value}px)`)

/** 当前高清行 id（用于 data-src 调试标识与测试断言）。 */
const hdRowId = computed(() => {
  if (props.state === 'look') return lookHdRow(props.lookDirection)
  return props.state
})

function clearTimer() {
  if (timer !== undefined) {
    clearTimeout(timer)
    timer = undefined
  }
}

function scheduleNext(current: number, t: number) {
  const s = spec.value
  if (!s || reduced.value || !pageVisible || props.state === 'look') return
  if (t !== token) return
  const hold = s.durations[current]
  timer = setTimeout(() => {
    timer = undefined
    if (t !== token) return
    const next = current + 1
    if (next < s.frames) {
      frame.value = next
      scheduleNext(next, t)
    } else if (s.loop) {
      frame.value = 0
      scheduleNext(0, t)
    } else {
      frame.value = 0
      emit('finished', props.state as string)
    }
  }, hold)
}

/** 当前资源就绪后从第 0 帧开始调度（每个令牌只允许一次）。 */
function begin(t: number) {
  if (scheduled || t !== token) return
  scheduled = true
  scheduleNext(0, t)
}

const imgEl = ref<HTMLImageElement | null>(null)

function restart() {
  clearTimer()
  frame.value = 0
  scheduled = false
  fallbackLegacy.value = false
  pendingToken = ++token
  pendingSrc = source.value.url
  const el = imgEl.value
  // Vue may not have patched :src yet when this watcher runs. An already-loaded
  // previous row must never be mistaken for the new row.
  if (el && el.src.endsWith(pendingSrc) && el.complete && el.naturalWidth > 0) {
    begin(pendingToken)
  }
}

function onImgLoad(event: Event) {
  const el = event.currentTarget as HTMLImageElement | null
  if (!el || !pendingSrc) return
  // 只接受与当前待加载资源匹配的 load：旧资源的迟到 load 不得启动旧状态
  if (!el.src.endsWith(pendingSrc)) return
  begin(pendingToken)
}

function onImgError(event: Event) {
  const failedEl = event.currentTarget as HTMLImageElement | null
  if (!failedEl || !failedEl.src.endsWith(pendingSrc)) return
  if (!fallbackLegacy.value) {
    // 高清失败 → 回退旧 8×11 图集对应行
    fallbackLegacy.value = true
    pendingSrc = source.value.url
    return
  }
  // 回退同样失败：静态首帧，one-shot 按名义时长走完并 emit finished
  const s = spec.value
  if (!s || s.loop || reduced.value || props.state === 'look') return
  const t = token
  timer = setTimeout(() => {
    timer = undefined
    if (t !== token) return
    emit('finished', props.state as string)
  }, s.durations.reduce((sum, value) => sum + value, 0))
}

watch(() => [props.state, props.lookDirection], restart)

watch(reduced, (value) => {
  if (value) {
    clearTimer()
  } else if (pageVisible && props.state !== 'look' && scheduled) {
    scheduleNext(frame.value, token)
  }
})

function onVisibilityChange() {
  pageVisible = document.visibilityState === 'visible'
  if (pageVisible) {
    if (reduced.value || props.state === 'look' || !scheduled) return
    scheduleNext(frame.value, token)
  } else {
    clearTimer()
  }
}

onMounted(() => {
  document.addEventListener('visibilitychange', onVisibilityChange)
  // 初始状态：imgEl 已绑定后重启（已缓存立即调度，否则等待 load）
  restart()
})

onBeforeUnmount(() => {
  clearTimer()
  document.removeEventListener('visibilitychange', onVisibilityChange)
})
</script>

<template>
  <div
    class="pet-sprite"
    role="img"
    aria-hidden="true"
    :style="{ width: `${props.size}px`, height: `${viewHeight}px` }"
    :data-state="props.state"
    :data-row="cell.row"
    :data-col="cell.col"
    :data-frame="props.state === 'look' ? -1 : frame"
    :data-src="hdRowId"
  >
    <img
      :key="source.url"
      ref="imgEl"
      :src="source.url"
      alt=""
      :width="source.cellWidth * source.columns"
      :height="source.cellHeight * source.rows"
      draggable="false"
      :style="{
        width: `${imageWidth}px`,
        height: `${imageHeight}px`,
        transform: imageTransform,
      }"
      @load="onImgLoad"
      @error="onImgError"
    >
  </div>
</template>

<style scoped>
.pet-sprite {
  position: relative;
  overflow: hidden;
  line-height: 0;
  -webkit-user-select: none;
  user-select: none;
}
.pet-sprite img {
  position: absolute;
  top: 0;
  left: 0;
  max-width: none;
  transform-origin: top left;
  pointer-events: none;
}
</style>
