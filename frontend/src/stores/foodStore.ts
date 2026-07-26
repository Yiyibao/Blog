import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import {
  appendMenuItem,
  classifyError,
  deleteMenuItem,
  fetchDailyMenu,
  putDailyMenu,
  type DailyMenu,
  type DailyMenuPut,
  type KitchenError,
  type MenuItemDraft,
} from '../api/kitchen'
import { useAuthStore } from './auth'

/** FD-12：本地日期（非 UTC）——toISOString 会在时区上翻车（东八区晚上会算成"明天"）。 */
export function todayISO(): string {
  return new Date().toLocaleDateString('sv-SE')
}

const POLL_INTERVAL_MS = 30_000

/**
 * FD-12/FD-13：今日菜单状态分片。
 * 轮询是"她刚加了一道菜你能看见"的机制：可见才跑、保存中暂停、离开即停；
 * lastSeenAt 存 sessionStorage，用于标记"上次看过之后新到的菜"。
 */
export const useFoodStore = defineStore('food', () => {
  const auth = useAuthStore()

  const menuDate = ref(todayISO())
  const menu = ref<DailyMenu | null>(null)
  const loading = ref(false)
  const saving = ref(false)
  const error = ref<KitchenError | null>(null)
  const arrivals = ref<number[]>([])

  let pollTimer: number | undefined
  let pollGeneration = 0
  let visibilityHooked = false

  const canEdit = computed(() => auth.canKitchen)

  function seenKey(date: string) {
    return `yubai:food:menu-seen:${date}`
  }

  function readLastSeen(date: string): string | null {
    try {
      return window.sessionStorage?.getItem(seenKey(date)) ?? null
    } catch {
      return null
    }
  }

  function markSeen(date: string) {
    try {
      window.sessionStorage?.setItem(seenKey(date), new Date().toISOString())
    } catch {
      // 忽略
    }
  }

  /** 对比 lastSeenAt 标出"新到的菜"（对方在你看菜单期间加的），供到达动画使用。 */
  function detectArrivals(fresh: DailyMenu) {
    const lastSeen = readLastSeen(fresh.date)
    if (!lastSeen) {
      arrivals.value = []
      markSeen(fresh.date)
      return
    }
    const seenTime = Date.parse(lastSeen)
    arrivals.value = fresh.items
      .filter(item => Date.parse(item.createdAt) > seenTime && item.authorName !== (auth.displayName ?? auth.username))
      .map(item => item.id)
    markSeen(fresh.date)
  }

  async function loadMenu(date = menuDate.value, options: { silent?: boolean } = {}) {
    menuDate.value = date
    if (!options.silent) {
      loading.value = true
      error.value = null
    }
    try {
      const fresh = await fetchDailyMenu(date)
      // 竞态守卫：期间用户切了日期则丢弃
      if (menuDate.value !== date) return
      detectArrivals(fresh)
      menu.value = fresh
    } catch (cause) {
      if (menuDate.value !== date) return
      if (!options.silent) error.value = classifyError(cause)
    } finally {
      if (!options.silent) loading.value = false
    }
  }

  /** 乐观 append：先插临时负 id 项，响应回来整单替换；失败回滚并抛分类错误。 */
  async function append(draft: MenuItemDraft): Promise<void> {
    const date = menuDate.value
    const optimistic = menu.value
    if (optimistic) {
      menu.value = {
        ...optimistic,
        exists: true,
        items: [...optimistic.items, {
          id: -Date.now(),
          dishId: null,
          dishSlug: draft.dishSlug ?? null,
          title: draft.title ?? draft.dishSlug ?? '…',
          mealSlot: draft.mealSlot,
          note: draft.note ?? '',
          sortOrder: optimistic.items.length,
          authorId: -1,
          authorName: auth.displayName ?? auth.username ?? '我',
          createdAt: new Date().toISOString(),
        }],
      }
    }
    saving.value = true
    try {
      const fresh = await appendMenuItem(date, draft)
      if (menuDate.value === date) {
        menu.value = fresh
        markSeen(date)
      }
    } catch (cause) {
      if (menuDate.value === date) menu.value = optimistic
      throw classifyError(cause)
    } finally {
      saving.value = false
    }
  }

  /** 全量提交（排序/定档）；409 时自动刷新最新菜单再抛错，让界面提示"对方刚改过"。 */
  async function submitMenu(payload: DailyMenuPut): Promise<void> {
    const date = menuDate.value
    saving.value = true
    try {
      const fresh = await putDailyMenu(date, payload)
      if (menuDate.value === date) {
        menu.value = fresh
        markSeen(date)
      }
    } catch (cause) {
      const kitchenError = classifyError(cause)
      if (kitchenError.kind === 'conflict' && menuDate.value === date) {
        await loadMenu(date, { silent: true })
      }
      throw kitchenError
    } finally {
      saving.value = false
    }
  }

  async function removeItem(itemId: number): Promise<void> {
    const date = menuDate.value
    saving.value = true
    try {
      const fresh = await deleteMenuItem(itemId)
      if (menuDate.value === date) menu.value = fresh
    } catch (cause) {
      throw classifyError(cause)
    } finally {
      saving.value = false
    }
  }

  function onVisibilityChange() {
    if (document.visibilityState !== 'visible') return
    // 跨午夜回来：menuDate 若还是"昨天"且用户没手动翻历史，就跟到新的今天
    const today = todayISO()
    if (menuDate.value !== today && wasFollowingToday.value) {
      void loadMenu(today, { silent: true })
    } else {
      void loadMenu(menuDate.value, { silent: true })
    }
  }

  const wasFollowingToday = computed(() => menuDate.value === todayISO() || menu.value === null)

  function startMenuPolling() {
    const generation = ++pollGeneration
    stopMenuPolling()
    pollGeneration = generation
    if (!visibilityHooked) {
      document.addEventListener('visibilitychange', onVisibilityChange)
      visibilityHooked = true
    }
    pollTimer = window.setInterval(() => {
      if (document.visibilityState !== 'visible') return
      if (saving.value) return
      void loadMenu(menuDate.value, { silent: true })
    }, POLL_INTERVAL_MS)
  }

  function stopMenuPolling() {
    if (pollTimer !== undefined) {
      window.clearInterval(pollTimer)
      pollTimer = undefined
    }
    if (visibilityHooked) {
      document.removeEventListener('visibilitychange', onVisibilityChange)
      visibilityHooked = false
    }
  }

  function clearArrivals() {
    arrivals.value = []
  }

  return {
    menuDate, menu, loading, saving, error, arrivals, canEdit,
    loadMenu, append, submitMenu, removeItem,
    startMenuPolling, stopMenuPolling, clearArrivals, todayISO,
  }
})
