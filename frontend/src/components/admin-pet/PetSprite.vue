<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { usePrefersReducedMotion } from '../../composables/usePrefersReducedMotion'
import { ATLAS, PET_ANIMATIONS, lookCell, type PetState } from './petAnimations'

/**
 * 纯渲染组件：只负责图集裁切、逐帧定时、状态切换与 reduced-motion 静态退化。
 * 不读 auth、不发网络请求；用 <img> + 固定裁切窗口显示原图局部。
 * 原始逻辑尺寸 192×208，整张 1536×2288 图集按 size（CSS 宽度 px）等比缩放。
 */
const props = withDefaults(defineProps<{
  state: PetState | 'look'
  /** 仅 state === 'look' 时使用，0° = 上，顺时针，单位度。 */
  lookDirection?: number
  /** 宠物显示的 CSS 宽度（px），高度按 208/192 等比。 */
  size?: number
}>(), {
  lookDirection: 0,
  size: 96,
})

const emit = defineEmits<{ finished: [] }>()

const reduced = usePrefersReducedMotion()

const spec = computed(() => (props.state === 'look' ? null : PET_ANIMATIONS[props.state]))
const frame = ref(0)

let timer: ReturnType<typeof setTimeout> | undefined
let pageVisible = true

const scale = computed(() => props.size / ATLAS.cellWidth)
const viewHeight = computed(() => props.size * ATLAS.cellHeight / ATLAS.cellWidth)
const imageWidth = computed(() => ATLAS.width * scale.value)
const imageHeight = computed(() => ATLAS.height * scale.value)

const cell = computed(() => {
  if (props.state === 'look') return lookCell(props.lookDirection)
  const s = spec.value!
  return { row: s.row, col: Math.min(frame.value, s.frames - 1) }
})

const imageTransform = computed(() =>
  `translate(${-cell.value.col * ATLAS.cellWidth * scale.value}px, ${-cell.value.row * ATLAS.cellHeight * scale.value}px)`)

function clearTimer() {
  if (timer !== undefined) {
    clearTimeout(timer)
    timer = undefined
  }
}

function scheduleNext(current: number) {
  const s = spec.value
  if (!s || reduced.value || !pageVisible || props.state === 'look') return
  const hold = s.durations[current]
  timer = setTimeout(() => {
    timer = undefined
    const next = current + 1
    if (next < s.frames) {
      frame.value = next
      scheduleNext(next)
    } else if (s.loop) {
      frame.value = 0
      scheduleNext(0)
    } else {
      frame.value = 0
      emit('finished')
    }
  }, hold)
}

function restart() {
  clearTimer()
  frame.value = 0
  scheduleNext(0)
}

watch(() => [props.state, props.lookDirection], restart)

watch(reduced, (value) => {
  if (value) clearTimer()
  else scheduleNext(frame.value)
})

function onVisibilityChange() {
  pageVisible = document.visibilityState === 'visible'
  if (pageVisible) scheduleNext(frame.value)
  else clearTimer()
}

onMounted(() => {
  document.addEventListener('visibilitychange', onVisibilityChange)
  scheduleNext(0)
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
  >
    <img
      :src="ATLAS.spriteUrl"
      alt=""
      :width="ATLAS.width"
      :height="ATLAS.height"
      draggable="false"
      :style="{
        width: `${imageWidth}px`,
        height: `${imageHeight}px`,
        transform: imageTransform,
      }"
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
