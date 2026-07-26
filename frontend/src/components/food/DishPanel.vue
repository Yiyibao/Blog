<script setup lang="ts">
import { onBeforeUnmount, nextTick, reactive, ref, watch } from 'vue'
import type { Dish } from '../../data'
import { useFocusTrap } from '../../composables/useFocusTrap'

const props = defineProps<{ dish: Dish | null; canCheckIn?: boolean }>()
const emit = defineEmits<{ close: []; favorite: [dish: Dish]; 'check-in': [dish: Dish] }>()

const panelRoot = ref<HTMLElement | null>(null)
const closeButton = ref<HTMLButtonElement | null>(null)

const servings = ref(2)
// NF-12 未落地前基准仍为硬编码 2；落地后改读 dish.baseServings
const originalServings = ref(2)
function setServings(n: number) {
  servings.value = Math.max(1, Math.min(20, n))
}
function scaledAmount(item: string): string {
  const ratio = servings.value / originalServings.value
  if (ratio === 1) return item
  return item.replace(/(\d+(?:\.\d+)?)\s*(克|毫升|ml|g|kg|个|根|片|瓣|勺|汤匙|茶匙|小匙|大匙|碗|杯|只|条|块|包)/g, (_, num: string, unit: string) => {
    const scaled = parseFloat(num) * ratio
    const rounded = scaled >= 10 ? Math.round(scaled) : Math.round(scaled * 10) / 10
    return `${rounded} ${unit}`
  })
}

// 厨房里的勾选清单：备一样划一样；按下标记录（食材文本可能重复），换菜即清空
const checkedIngredients = reactive(new Set<number>())
function toggleIngredient(index: number) {
  if (checkedIngredients.has(index)) checkedIngredients.delete(index)
  else checkedIngredients.add(index)
}

const heartBumping = ref(false)
let heartTimer: number | undefined
function onHeart() {
  if (!props.dish) return
  emit('favorite', props.dish)
  heartBumping.value = false
  window.clearTimeout(heartTimer)
  // 强制重启动画：先摘类再挂类
  requestAnimationFrame(() => { heartBumping.value = true })
  heartTimer = window.setTimeout(() => { heartBumping.value = false }, 450)
}

function onWindowKeydown(event: KeyboardEvent) {
  // Esc 挂 window：焦点落在 body 上时（如刚点完背景）也要能关闭
  if (event.key === 'Escape' && props.dish) emit('close')
}

watch(() => props.dish, async (dish, previous) => {
  if (dish) {
    if (dish.slug !== previous?.slug) {
      servings.value = 2
      checkedIngredients.clear()
    }
    document.body.style.overflow = 'hidden'
    await nextTick()
    closeButton.value?.focus()
  } else {
    document.body.style.removeProperty('overflow')
  }
}, { immediate: true })

watch(() => props.dish, (dish) => {
  if (dish) window.addEventListener('keydown', onWindowKeydown)
  else window.removeEventListener('keydown', onWindowKeydown)
}, { immediate: true })

useFocusTrap(panelRoot)

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onWindowKeydown)
  window.clearTimeout(heartTimer)
  document.body.style.removeProperty('overflow')
})
</script>

<template>
  <Teleport to="body">
    <Transition name="dish-panel" appear>
      <div v-if="dish" class="dish-backdrop" @click.self="emit('close')">
        <article ref="panelRoot" class="dish-panel" role="dialog" aria-modal="true" :aria-labelledby="`dish-title-${dish.id}`">
          <header class="dish-panel-media">
            <img :src="dish.imageUrl" :alt="dish.imageAlt" loading="lazy">
            <span />
            <button ref="closeButton" class="tap-44" type="button" aria-label="关闭菜谱详情" @click="emit('close')">关闭 ×</button>
            <button
              class="dish-heart-btn tap-44"
              :class="{ bumping: heartBumping }"
              type="button"
              :aria-label="`为${dish.name}点亮爱心，已被点亮 ${dish.favoriteCount} 次`"
              @click="onHeart"
            ><i aria-hidden="true">♥</i><b>{{ dish.favoriteCount }}</b></button>
            <button
              v-if="canCheckIn"
              class="dish-checkin-btn tap-44"
              type="button"
              :aria-label="`把${dish.name}记进今天的美食足迹`"
              @click="emit('check-in', dish)"
            >今天吃了 ✓</button>
            <div><small>{{ dish.category }} · ★ {{ dish.rating.toFixed(1) }}</small><h2 :id="`dish-title-${dish.id}`">{{ dish.name }}</h2><p>{{ dish.summary }}</p></div>
          </header>
          <div class="dish-panel-body">
            <dl><div><dt>准备时间</dt><dd>{{ dish.prepMinutes }} 分钟</dd></div><div><dt>难度</dt><dd>{{ dish.difficulty }}</dd></div><div><dt>食材</dt><dd>{{ dish.ingredients.length }} 项</dd></div></dl>
            <section><p>01 / INGREDIENTS</p><h3>准备食材</h3><div class="servings-bar"><button class="tap-44" type="button" :disabled="servings <= 1" aria-label="减少份数" @click="setServings(servings - 1)">−</button><span>{{ servings }} 人份</span><button class="tap-44" type="button" :disabled="servings >= 20" aria-label="增加份数" @click="setServings(servings + 1)">+</button></div><ul><li v-for="(item, index) in dish.ingredients" :key="index"><button type="button" class="ingredient-item" :class="{ checked: checkedIngredients.has(index) }" :aria-pressed="checkedIngredients.has(index)" @click="toggleIngredient(index)"><span class="ingredient-checkbox" aria-hidden="true">{{ checkedIngredients.has(index) ? '✓' : '' }}</span><span>{{ scaledAmount(item) }}</span></button></li></ul></section>
            <section><p>02 / METHOD</p><h3>开始制作</h3><ol><li v-for="(step, index) in dish.steps" :key="step"><span>{{ String(index + 1).padStart(2, '0') }}</span><p>{{ step }}</p></li></ol></section>
            <footer>图片：<a :href="dish.imageSourceUrl" target="_blank" rel="noreferrer">{{ dish.imageCredit }}</a></footer>
          </div>
        </article>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
/* FD-2：抽屉恒深色是刻意设计（配全屏压暗背景的"影院"语境），
   但颜色一律走 :root 的 --cinema-* 令牌，亮/暗主题各配一组；
   照片上的白字与压暗 scrim 是仅有的字面量豁免。 */
.dish-backdrop { position: fixed; z-index: 2200; inset: 0; display: flex; justify-content: flex-end; background: var(--cinema-backdrop); backdrop-filter: blur(8px); }
.dish-panel { width: min(720px, 100%); height: 100%; overflow-y: auto; color: var(--cinema-ink); background: var(--cinema-bg); box-shadow: -40px 0 100px rgba(0, 0, 0, .46); }
.dish-panel-media { position: relative; min-height: 410px; overflow: hidden; }
.dish-panel-media > img { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover; }
.dish-panel-media > span { position: absolute; inset: 0; background: linear-gradient(180deg, rgba(0, 0, 0, .12), rgba(0, 0, 0, .88)); }
.dish-panel-media > button { position: absolute; z-index: 2; top: 22px; right: 22px; padding: 9px 15px; color: #fff; background: rgba(0, 0, 0, .45); border: 1px solid rgba(255, 255, 255, .3); border-radius: 999px; cursor: pointer; backdrop-filter: blur(12px); }
.dish-panel-media > div { position: absolute; right: 36px; bottom: 34px; left: 36px; }
.dish-panel-media small { color: rgba(255, 255, 255, .7); font-size: .68rem; letter-spacing: .1em; }
.dish-panel-media h2 { margin: 9px 0; font-size: clamp(2.5rem, 7vw, 4.8rem); font-weight: 520; line-height: 1; letter-spacing: -.055em; }
.dish-panel-media p { max-width: 560px; margin: 0; color: rgba(255, 255, 255, .72); line-height: 1.6; }
.dish-panel-body { padding: clamp(28px, 6vw, 58px); }
.dish-panel-body > dl { display: grid; grid-template-columns: repeat(3, 1fr); margin: 0 0 54px; padding: 18px 0; border-top: 1px solid var(--cinema-line); border-bottom: 1px solid var(--cinema-line); }
.dish-panel-body dl div { padding: 0 16px; border-left: 1px solid var(--cinema-line); }
.dish-panel-body dl div:first-child { padding-left: 0; border-left: 0; }
.dish-panel-body dt { color: var(--cinema-muted); font-size: .68rem; }
.dish-panel-body dd { margin: 6px 0 0; font-size: .92rem; }
.dish-panel-body section { margin-top: 50px; }
.dish-panel-body section > p { color: var(--cinema-faint); font-size: .65rem; letter-spacing: .16em; }
.dish-panel-body h3 { margin: 10px 0 24px; font-size: 2rem; font-weight: 520; letter-spacing: -.04em; }
.dish-panel-body ul { display: grid; grid-template-columns: repeat(2, 1fr); gap: 0 24px; padding: 0; list-style: none; }
.dish-panel-body ul li { color: var(--cinema-text); border-top: 1px solid var(--cinema-line); }
/* FD-3：厨房勾选清单——备一样划一样（原孤儿 CSS 复活进 scoped 并换 cinema 令牌） */
.ingredient-item { display: flex; align-items: center; gap: 10px; width: 100%; min-height: 44px; padding: 10px 6px; color: inherit; text-align: left; font-size: inherit; background: transparent; border: 0; border-radius: 8px; cursor: pointer; transition: background .2s, opacity .2s; }
.ingredient-item:hover { background: rgba(255, 255, 255, .06); }
.ingredient-item.checked { text-decoration: line-through; opacity: .55; }
.ingredient-checkbox { flex: 0 0 auto; display: flex; align-items: center; justify-content: center; width: 18px; height: 18px; border: 1px solid var(--cinema-line); border-radius: 4px; color: var(--accent); font-size: 11px; }
/* FD-18：打卡按钮——与爱心同排玻璃语言 */
.dish-checkin-btn { position: absolute; z-index: 2; right: 122px; bottom: 30px; display: inline-flex; align-items: center; padding: 9px 14px; color: #fff; font-size: .78rem; font-weight: 600; background: rgba(0, 0, 0, .45); border: 1px solid rgba(255, 255, 255, .3); border-radius: 999px; cursor: pointer; backdrop-filter: blur(12px); transition: transform .25s var(--ease), border-color .25s; }
.dish-checkin-btn:hover { transform: scale(1.05); border-color: color-mix(in srgb, var(--accent) 70%, #fff); }
/* FD-3：爱心点亮——纯计数非 toggle，动画只在主动点击时播放 */
.dish-heart-btn { position: absolute; z-index: 2; right: 22px; bottom: 30px; display: inline-flex; align-items: center; gap: 7px; padding: 9px 15px; color: #fff; background: rgba(0, 0, 0, .45); border: 1px solid rgba(255, 255, 255, .3); border-radius: 999px; cursor: pointer; backdrop-filter: blur(12px); transition: transform .25s var(--ease), border-color .25s; }
.dish-heart-btn i { font-style: normal; font-size: 1rem; line-height: 1; color: #ff8fa8; }
.dish-heart-btn b { font-weight: 600; font-size: .82rem; }
.dish-heart-btn:hover { transform: scale(1.06); border-color: rgba(255, 143, 168, .6); }
.dish-heart-btn.bumping i { animation: heart-bounce .4s cubic-bezier(.175, .885, .32, 1.275); }
@keyframes heart-bounce { 0% { transform: scale(1); } 50% { transform: scale(1.35); } 100% { transform: scale(1); } }
.servings-bar { display: flex; align-items: center; gap: 12px; margin: 0 0 20px; padding: 10px 0; }
.servings-bar button { display: grid; place-items: center; border: 1px solid var(--cinema-line); border-radius: 50%; background: transparent; color: var(--cinema-text); font-size: 1.1rem; cursor: pointer; transition: border-color .2s, color .2s; }
.servings-bar button:hover:not(:disabled) { border-color: var(--accent); color: var(--accent); }
.servings-bar button:disabled { opacity: .3; cursor: default; }
.servings-bar span { min-width: 60px; color: var(--cinema-muted); font-size: .82rem; text-align: center; }
.dish-panel-body ol { padding: 0; list-style: none; }
.dish-panel-body ol li { display: grid; grid-template-columns: 42px 1fr; gap: 18px; padding: 22px 0; border-top: 1px solid var(--cinema-line); }
.dish-panel-body ol span { color: var(--cinema-faint); font-size: .72rem; }
.dish-panel-body ol p { margin: 0; color: var(--cinema-text); line-height: 1.75; }
.dish-panel-body footer { margin-top: 58px; padding-top: 22px; color: var(--cinema-faint); border-top: 1px solid var(--cinema-line); font-size: .72rem; }
.dish-panel-body footer a { color: var(--cinema-muted); }
.dish-panel-media > button:focus-visible, .servings-bar button:focus-visible, .dish-panel-body footer a:focus-visible, .dish-heart-btn:focus-visible, .ingredient-item:focus-visible { outline: 2px solid #0071e3; outline-offset: 3px; }
.dish-panel-enter-active, .dish-panel-leave-active { transition: background .38s, backdrop-filter .38s; }
.dish-panel-enter-active .dish-panel, .dish-panel-leave-active .dish-panel { transition: transform .42s var(--ease-out), opacity .3s; }
.dish-panel-enter-from, .dish-panel-leave-to { background: transparent; backdrop-filter: blur(0); }
.dish-panel-enter-from .dish-panel, .dish-panel-leave-to .dish-panel { opacity: .55; transform: translateX(100%); }
@media (max-width: 640px) { .dish-panel-media { min-height: 360px; } .dish-panel-media > div { right: 24px; bottom: 96px; left: 24px; } .dish-heart-btn { right: 20px; bottom: 24px; } .dish-panel-body ul { grid-template-columns: 1fr; } }
@media (prefers-reduced-motion: reduce) { .dish-panel-enter-active, .dish-panel-leave-active, .dish-panel-enter-active .dish-panel, .dish-panel-leave-active .dish-panel, .dish-panel, .dish-heart-btn { transition-duration: .01ms !important; } .dish-heart-btn.bumping i { animation: none; } }
</style>
