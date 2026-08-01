<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  classifyError,
  deleteMealLog,
  fetchMealLogs,
  type MealLog,
  type MealSlot,
} from '../../api/kitchen'
import { useAuthStore } from '../../stores/auth'
import { useUiStore } from '../../stores/uiStore'
import { useRequestToken } from '../../composables/useRequestToken'
import { Capabilities } from '../../utils/capabilities'

/**
 * FD-17：美食时光机——"我们吃过的"垂直时间线（容器组件，仅登录可见）。
 * 胶片语言：左缘齿孔轴线、日期为帧头；"加载更早"按钮而非无限滚动（可控、可回溯）；
 * 零数据整块换邀请文案不摆空架子。
 */
const emit = defineEmits<{ open: [slug: string] }>()

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const uiStore = useUiStore()
const token = useRequestToken()

const logs = ref<MealLog[]>([])
const page = ref(0)
const totalPages = ref(1)
const totalElements = ref(0)
const loading = ref(false)
const loadingMore = ref(false)

const SLOT_LABEL: Record<MealSlot, string> = { BREAKFAST: '早', LUNCH: '午', DINNER: '晚', SNACK: '加餐' }
const SLOTS: (MealSlot | '')[] = ['', 'BREAKFAST', 'LUNCH', 'DINNER', 'SNACK']

// FD-17：餐次筛选进 URL（?slot=），可分享可回退；客户端过滤（服务端暂无 slot 参数，记于 checkpoint）
const slotFilter = computed<MealSlot | ''>(() => {
  const raw = route.query.slot
  return typeof raw === 'string' && ['BREAKFAST', 'LUNCH', 'DINNER', 'SNACK'].includes(raw)
    ? raw as MealSlot : ''
})

const visibleLogs = computed(() =>
  slotFilter.value ? logs.value.filter(log => log.mealSlot === slotFilter.value) : logs.value)

const grouped = computed(() => {
  const map = new Map<string, MealLog[]>()
  for (const log of visibleLogs.value) {
    const list = map.get(log.logDate) ?? []
    list.push(log)
    map.set(log.logDate, list)
  }
  return [...map.entries()].map(([date, entries]) => ({ date, entries }))
})

const hasMore = computed(() => page.value + 1 < totalPages.value)
const myName = computed(() => auth.displayName ?? auth.username ?? '')
// FD-29：删除任意打卡以 capability 为准（PARTNER 与 ADMIN 同权）
const canDeleteAny = computed(() => auth.can(Capabilities.KITCHEN_DELETE_ANY))

function setSlot(slot: MealSlot | '') {
  const { slot: _slot, ...rest } = route.query
  void router.replace({ query: slot ? { ...rest, slot } : rest })
}

function dateLabel(raw: string) {
  const [year, month, day] = raw.split('-')
  const today = new Date().toLocaleDateString('sv-SE')
  if (raw === today) return '今天'
  return `${Number(month)} 月 ${Number(day)} 日 · ${year}`
}

async function load(reset = true) {
  const current = token.next()
  if (reset) {
    loading.value = true
    page.value = 0
  } else {
    loadingMore.value = true
  }
  try {
    const result = await fetchMealLogs(reset ? 0 : page.value + 1, 20)
    if (!token.isCurrent(current)) return
    logs.value = reset ? result.items : [...logs.value, ...result.items]
    page.value = result.page
    totalPages.value = result.totalPages
    totalElements.value = result.totalElements
  } catch {
    // 静默：时光机是锦上添花区块，失败不打扰主页面
  } finally {
    loading.value = false
    loadingMore.value = false
  }
}

async function remove(log: MealLog) {
  try {
    await deleteMealLog(log.id)
    logs.value = logs.value.filter(item => item.id !== log.id)
    totalElements.value = Math.max(0, totalElements.value - 1)
    uiStore.showToast('已删掉这条记录')
  } catch (cause) {
    uiStore.showToast(classifyError(cause).message)
  }
}

defineExpose({ reload: () => load(true) })

onMounted(() => void load(true))
</script>

<template>
  <section v-if="logs.length || loading" class="food-timeline" aria-labelledby="food-timeline-title">
    <header class="timeline-head">
      <div>
        <p>MEMORY REEL · {{ totalElements }} 顿</p>
        <h2 id="food-timeline-title">我们的美食足迹</h2>
      </div>
      <div class="timeline-filter" role="group" aria-label="按餐次筛选">
        <button
          v-for="slot in SLOTS"
          :key="slot || 'all'"
          type="button"
          class="tap-44"
          :class="{ active: slotFilter === slot }"
          @click="setSlot(slot)"
        >{{ slot ? SLOT_LABEL[slot] : '全部' }}</button>
      </div>
    </header>

    <ol class="timeline-reel">
      <li v-for="group in grouped" :key="group.date" class="timeline-day" :class="{ today: dateLabel(group.date) === '今天' }">
        <h3><time :datetime="group.date">{{ dateLabel(group.date) }}</time><i v-if="dateLabel(group.date) === '今天'" class="today-pulse" aria-hidden="true" /></h3>
        <ul>
          <li v-for="log in group.entries" :key="log.id" class="timeline-entry">
            <i class="entry-slot" aria-hidden="true">{{ SLOT_LABEL[log.mealSlot] }}</i>
            <div class="entry-copy">
              <component
                :is="log.dishSlug ? 'button' : 'span'"
                v-bind="log.dishSlug ? { type: 'button', class: 'entry-link' } : {}"
                @click="log.dishSlug && emit('open', log.dishSlug)"
              >{{ log.title }}</component>
              <small>
                {{ log.authorName }} 记的
                <template v-if="log.rating"> · {{ '★'.repeat(log.rating) }}</template>
                <template v-if="log.note"> · {{ log.note }}</template>
              </small>
            </div>
            <button
              v-if="canDeleteAny || log.authorName === myName"
              class="entry-remove tap-44"
              type="button"
              :aria-label="`删除${log.title}的打卡记录`"
              @click="remove(log)"
            >−</button>
          </li>
        </ul>
      </li>
    </ol>

    <button v-if="hasMore" class="timeline-more tap-44" type="button" :disabled="loadingMore" @click="load(false)">
      {{ loadingMore ? '翻着相册呢…' : '加载更早的 ↓' }}
    </button>
  </section>

  <section v-else class="food-timeline-empty" aria-label="美食足迹邀请">
    <p class="empty-kicker">MEMORY REEL</p>
    <p>还没有记录——今晚做完饭，点一下菜单上的 ✓，就是第一帧回忆。</p>
  </section>
</template>

<style scoped>
.food-timeline { margin-top: clamp(64px, 8vw, 110px); }
.timeline-head { display: flex; justify-content: space-between; align-items: end; gap: 20px; margin-bottom: 26px; flex-wrap: wrap; }
.timeline-head p { margin: 0; color: var(--accent); font: 650 .64rem/1 ui-monospace, "SF Mono", Consolas, monospace; letter-spacing: .17em; }
.timeline-head h2 { margin: 10px 0 0; color: var(--ink); font: 400 clamp(2rem, 4vw, 3.2rem)/1.05 Georgia, "Songti SC", serif; letter-spacing: -.045em; }
.timeline-filter { display: flex; gap: 4px; }
.timeline-filter button { padding: 8px 13px; color: var(--muted); background: transparent; border: 0; border-radius: 8px; font-size: .78rem; cursor: pointer; transition: color .15s, background .15s; }
.timeline-filter button:hover { color: var(--ink); }
.timeline-filter button.active { color: var(--paper); background: var(--ink); }
.timeline-filter button:focus-visible { outline: 2px solid #0071e3; outline-offset: 2px; }
/* 胶片：左缘齿孔轴线（重复渐变打孔），日期是帧头 */
.timeline-reel { position: relative; margin: 0; padding: 0 0 0 34px; list-style: none; }
.timeline-reel::before { content: ""; position: absolute; top: 6px; bottom: 6px; left: 10px; width: 10px; border-radius: 6px; background: repeating-linear-gradient(180deg, color-mix(in srgb, var(--accent) 34%, var(--line)) 0 6px, transparent 6px 16px); mask-image: linear-gradient(180deg, #000 82%, transparent); }
.timeline-day { margin-bottom: 26px; }
.timeline-day h3 { display: flex; align-items: center; gap: 9px; margin: 0 0 8px; color: var(--ink); font: 600 .84rem/1 ui-monospace, "SF Mono", Consolas, monospace; letter-spacing: .06em; }
.today-pulse { width: 8px; height: 8px; border-radius: 50%; background: var(--accent); animation: today-pulse 2.4s ease-in-out infinite; }
.timeline-day ul { margin: 0; padding: 0; list-style: none; }
.timeline-entry { display: flex; align-items: center; gap: 12px; min-height: 48px; padding: 7px 4px; border-top: 1px dashed color-mix(in srgb, var(--accent) 18%, var(--line)); }
.timeline-entry:first-child { border-top: 0; }
.entry-slot { flex: 0 0 auto; display: grid; place-items: center; min-width: 34px; height: 22px; padding: 0 7px; color: var(--accent); background: color-mix(in srgb, var(--accent-soft) 62%, transparent); border-radius: 999px; font-size: .62rem; font-style: normal; font-weight: 650; }
.entry-copy { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 3px; }
.entry-copy span, .entry-link { color: var(--ink); font: 500 .96rem/1.3 Georgia, "Songti SC", serif; text-align: left; }
.entry-link { padding: 0; background: transparent; border: 0; cursor: pointer; text-decoration: underline dotted color-mix(in srgb, var(--accent) 55%, transparent); text-underline-offset: 4px; }
.entry-link:hover { color: var(--accent); }
.entry-link:focus-visible { outline: 2px solid #0071e3; outline-offset: 2px; }
.entry-copy small { overflow: hidden; color: var(--faint); font-size: .7rem; text-overflow: ellipsis; white-space: nowrap; }
.entry-remove { flex: 0 0 auto; display: grid; place-items: center; color: var(--faint); font-size: 1.05rem; background: transparent; border: 1px solid transparent; border-radius: 50%; cursor: pointer; opacity: 0; transition: opacity .2s, color .2s, border-color .2s; }
.timeline-entry:hover .entry-remove, .entry-remove:focus-visible { opacity: 1; }
.entry-remove:hover { color: #b84f48; border-color: #b84f48; }
.entry-remove:focus-visible { outline: 2px solid #0071e3; outline-offset: 2px; }
.timeline-more { display: block; margin: 6px 0 0 34px; padding: 10px 18px; color: var(--muted); font-size: .8rem; background: transparent; border: 1px dashed var(--line-strong); border-radius: 999px; cursor: pointer; transition: color .2s, border-color .2s; }
.timeline-more:hover:not(:disabled) { color: var(--accent); border-color: var(--accent); }
.timeline-more:focus-visible { outline: 2px solid #0071e3; outline-offset: 3px; }
.food-timeline-empty { margin-top: clamp(64px, 8vw, 110px); padding: 30px 26px; border: 1px dashed color-mix(in srgb, var(--accent) 30%, var(--line)); border-radius: 22px 22px 7px 22px; background: color-mix(in srgb, var(--accent-soft) 26%, transparent); }
.empty-kicker { margin: 0 0 8px; color: var(--accent); font: 650 .62rem/1 ui-monospace, "SF Mono", Consolas, monospace; letter-spacing: .16em; }
.food-timeline-empty p:last-child { margin: 0; color: var(--muted); font-size: .9rem; line-height: 1.7; }
@keyframes today-pulse { 0%, 100% { transform: scale(1); opacity: 1; } 50% { transform: scale(1.5); opacity: .5; } }
@media (max-width: 640px) { .timeline-head { align-items: flex-start; flex-direction: column; } }
@media (prefers-reduced-motion: reduce) { .today-pulse { animation: none; } }
</style>
