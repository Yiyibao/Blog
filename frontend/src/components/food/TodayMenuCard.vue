<script setup lang="ts">
import { computed } from 'vue'
import type { DailyMenu, MealSlot } from '../../api/kitchen'

/**
 * FD-13：今日菜单卡（哑组件）——给女朋友看的门面。
 * 信笺+餐牌形态：衬线菜名、餐次小签、点菜人署名、封蜡"膳"印记；
 * arrivals 里的项播放到达动画（对方刚点的菜）。
 */
const props = defineProps<{
  menu: DailyMenu | null
  loading: boolean
  canEdit: boolean
  arrivals: number[]
}>()
const emit = defineEmits<{ open: [] }>()

const SLOT_LABEL: Record<MealSlot, string> = {
  BREAKFAST: '早',
  LUNCH: '午',
  DINNER: '晚',
  SNACK: '加餐',
}

const hasItems = computed(() => (props.menu?.items.length ?? 0) > 0)
const confirmed = computed(() => props.menu?.status === 'CONFIRMED')
const dateLabel = computed(() => {
  const raw = props.menu?.date
  if (!raw) return ''
  const [, month, day] = raw.split('-')
  return `${Number(month)} 月 ${Number(day)} 日`
})

function slotLabel(slot: MealSlot) {
  return SLOT_LABEL[slot] ?? slot
}
</script>

<template>
  <article class="menu-card" :class="{ confirmed }" aria-label="今日菜单">
    <header class="menu-card-head">
      <p class="menu-kicker">TODAY'S TABLE · {{ dateLabel }}</p>
      <span class="menu-seal" aria-hidden="true">膳</span>
    </header>
    <h3>今日菜单</h3>
    <p v-if="menu?.note" class="menu-note">「{{ menu.note }}」</p>

    <div v-if="loading && !menu" class="menu-loading" aria-label="正在读取菜单"><i /><i /><i /></div>

    <template v-else-if="hasItems">
      <ul class="menu-lines">
        <li
          v-for="item in menu!.items"
          :key="item.id"
          :class="{ arrived: arrivals.includes(item.id), pending: item.id < 0 }"
        >
          <i class="menu-slot" aria-hidden="true">{{ slotLabel(item.mealSlot) }}</i>
          <span class="menu-title">{{ item.title }}</span>
          <small class="menu-author">{{ item.authorName }} 点的</small>
        </li>
      </ul>
      <footer class="menu-card-foot">
        <span class="menu-status" role="status">{{ confirmed ? '菜单已定 ✓' : '还在点菜中…' }}</span>
        <button v-if="canEdit" class="menu-open tap-44" type="button" @click="emit('open')">
          {{ confirmed ? '再改改' : '去点菜' }} ↗
        </button>
      </footer>
    </template>

    <div v-else class="menu-empty">
      <div class="menu-plates" aria-hidden="true"><i /><i /><i /></div>
      <p>今天还没定吃什么</p>
      <button v-if="canEdit" class="menu-open tap-44" type="button" @click="emit('open')">我来点第一道 ✦</button>
      <small v-else>登录后就能一起点菜啦</small>
    </div>
  </article>
</template>

<style scoped>
/* 信笺 + 餐牌：暖纸底、非对称圆角（美食区签名）、封蜡印记 */
.menu-card { position: relative; display: flex; flex-direction: column; gap: 10px; min-width: 310px; padding: 24px 22px 20px; border: 1px solid color-mix(in srgb, var(--accent) 26%, var(--line)); border-radius: 22px 22px 7px 22px; background: linear-gradient(160deg, color-mix(in srgb, var(--surface-solid) 90%, var(--accent-soft)), var(--surface-solid) 70%); box-shadow: var(--shadow-sm); }
.menu-card.confirmed { border-color: color-mix(in srgb, var(--accent) 45%, var(--line)); }
.menu-card-head { display: flex; align-items: flex-start; justify-content: space-between; }
.menu-kicker { margin: 0; color: var(--accent); font: 650 .6rem/1.4 ui-monospace, "SF Mono", Consolas, monospace; letter-spacing: .15em; }
.menu-seal { display: grid; place-items: center; width: 40px; height: 40px; color: #fff; background: radial-gradient(circle at 32% 30%, color-mix(in srgb, var(--accent) 82%, #fff), var(--accent) 62%, color-mix(in srgb, var(--accent) 72%, #7a2f42)); border-radius: 50%; font: 500 1.05rem/1 Georgia, "Songti SC", serif; box-shadow: 0 4px 12px color-mix(in srgb, var(--accent) 45%, transparent), inset 0 0 0 2px rgba(255, 255, 255, .28); transform: rotate(-8deg); }
.menu-card h3 { margin: 0; font: 500 1.5rem/1.15 Georgia, "Songti SC", serif; letter-spacing: -.02em; color: var(--ink); }
.menu-note { margin: 0; color: var(--muted); font-size: .8rem; line-height: 1.6; }
.menu-lines { display: flex; flex-direction: column; margin: 4px 0 0; padding: 0; list-style: none; }
.menu-lines li { display: flex; align-items: center; gap: 10px; min-height: 44px; padding: 6px 2px; border-top: 1px dashed color-mix(in srgb, var(--accent) 22%, var(--line)); }
.menu-lines li:first-child { border-top: 0; }
.menu-lines li.pending { opacity: .55; }
.menu-lines li.arrived { animation: menu-chip-arrive .8s var(--ease-out); }
.menu-slot { flex: 0 0 auto; display: grid; place-items: center; min-width: 34px; height: 22px; padding: 0 7px; color: var(--accent); background: color-mix(in srgb, var(--accent-soft) 62%, transparent); border-radius: 999px; font-size: .62rem; font-style: normal; font-weight: 650; letter-spacing: .05em; }
.menu-title { flex: 1; min-width: 0; overflow: hidden; color: var(--ink); font: 500 .98rem/1.35 Georgia, "Songti SC", serif; text-overflow: ellipsis; white-space: nowrap; }
.menu-author { flex: 0 0 auto; color: var(--faint); font-size: .68rem; }
.menu-card-foot { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 4px; }
.menu-status { color: var(--muted); font-size: .74rem; }
.menu-card.confirmed .menu-status { color: var(--accent); font-weight: 600; }
.menu-open { display: inline-flex; align-items: center; gap: 6px; padding: 9px 16px; color: var(--accent); font-size: .8rem; font-weight: 600; background: transparent; border: 1px solid color-mix(in srgb, var(--accent) 36%, var(--line)); border-radius: 999px; cursor: pointer; transition: transform .25s var(--ease), background .25s, color .25s; }
.menu-open:hover { color: #fff; background: var(--accent); transform: translateY(-2px); }
.menu-open:focus-visible { outline: 2px solid #0071e3; outline-offset: 3px; }
.menu-empty { display: flex; flex-direction: column; align-items: center; gap: 12px; padding: 18px 0 8px; text-align: center; }
.menu-empty p { margin: 0; color: var(--muted); font-size: .88rem; }
.menu-empty small { color: var(--faint); font-size: .72rem; }
/* 空态三只虚线餐盘：中间那只轻轻呼吸（循环动画白名单第 3 个，仅空态存在时运行） */
.menu-plates { display: flex; gap: 12px; }
.menu-plates i { width: 44px; height: 44px; border: 1.5px dashed color-mix(in srgb, var(--accent) 42%, var(--line)); border-radius: 50%; }
.menu-plates i:nth-child(2) { animation: menu-plate-breathe 6s ease-in-out infinite; }
.menu-loading { display: flex; flex-direction: column; gap: 10px; padding: 8px 0; }
.menu-loading i { height: 34px; border-radius: 10px; background: linear-gradient(110deg, var(--surface-solid) 20%, color-mix(in srgb, var(--surface-solid) 82%, var(--accent-soft)) 40%, var(--surface-solid) 60%); background-size: 220% 100%; animation: skeleton 1.5s linear infinite; }
@keyframes menu-chip-arrive { 0% { background: color-mix(in srgb, var(--accent-soft) 85%, transparent); transform: translateX(14px); opacity: .2; } 60% { background: color-mix(in srgb, var(--accent-soft) 45%, transparent); } 100% { background: transparent; transform: none; opacity: 1; } }
@keyframes menu-plate-breathe { 0%, 100% { transform: scale(1); opacity: .75; } 50% { transform: scale(1.08); opacity: 1; } }
@keyframes skeleton { to { background-position-x: -220%; } }
@media (prefers-reduced-motion: reduce) { .menu-lines li.arrived { animation: none; } .menu-plates i:nth-child(2) { animation: none; } .menu-loading i { animation: none; } }
</style>
