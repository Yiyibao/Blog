<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import type { Dish } from '../../data'
import type { MealSlot } from '../../api/kitchen'
import { useFoodStore } from '../../stores/foodStore'
import { useAuthStore } from '../../stores/auth'
import { Capabilities } from '../../utils/capabilities'
import { useUiStore } from '../../stores/uiStore'
import { useFocusTrap } from '../../composables/useFocusTrap'

/**
 * FD-13：菜单编辑板（容器组件，直连 foodStore）。
 * append 走乐观更新（两人同时加菜不冲突）；定档/删除即时提交；
 * 409 时 store 已拉回对方的最新版本，这里只负责提示。
 */
const props = defineProps<{ dishes: Dish[] }>()
const emit = defineEmits<{ close: [] }>()

const store = useFoodStore()
const auth = useAuthStore()
const uiStore = useUiStore()

const boardRoot = ref<HTMLElement | null>(null)
const titleInput = ref<HTMLInputElement | null>(null)

const draftTitle = ref('')
const draftSlug = ref('')
const draftSlot = ref<MealSlot>('DINNER')
const draftNote = ref('')

const SLOTS: { value: MealSlot; label: string }[] = [
  { value: 'BREAKFAST', label: '早餐' },
  { value: 'LUNCH', label: '午餐' },
  { value: 'DINNER', label: '晚餐' },
  { value: 'SNACK', label: '加餐' },
]

const confirmed = computed(() => store.menu?.status === 'CONFIRMED')
const myName = computed(() => auth.displayName ?? auth.username ?? '')

function canRemove(authorId: number, authorName: string) {
  return auth.can(Capabilities.KITCHEN_DELETE_ANY) || authorName === myName.value || authorId < 0
}

function pickDish(slug: string) {
  draftSlug.value = slug
  const dish = props.dishes.find(item => item.slug === slug)
  if (dish) draftTitle.value = dish.name
}

async function addItem() {
  const title = draftTitle.value.trim()
  const slug = draftSlug.value.trim()
  if (!title && !slug) {
    uiStore.showToast('先写个菜名，或者从菜谱里挑一道')
    titleInput.value?.focus()
    return
  }
  try {
    await store.append({
      ...(slug ? { dishSlug: slug } : {}),
      ...(title && !slug ? { title } : {}),
      mealSlot: draftSlot.value,
      ...(draftNote.value.trim() ? { note: draftNote.value.trim() } : {}),
    })
    draftTitle.value = ''
    draftSlug.value = ''
    draftNote.value = ''
    titleInput.value?.focus()
  } catch (cause) {
    const kitchenError = cause as { message?: string }
    uiStore.showToast(kitchenError.message || '没加上，稍后再试')
  }
}

async function removeItem(id: number) {
  try {
    await store.removeItem(id)
  } catch (cause) {
    uiStore.showToast((cause as { message?: string }).message || '删除失败')
  }
}

/** 定档 / 取消定档：全量 PUT 带 expectedVersion，冲突时 store 已拉回最新。 */
async function toggleConfirm() {
  const menu = store.menu
  if (!menu || menu.version === null) return
  try {
    await store.submitMenu({
      status: confirmed.value ? 'DRAFT' : 'CONFIRMED',
      note: menu.note,
      expectedVersion: menu.version,
      items: menu.items.filter(item => item.id > 0).map(item => ({
        id: item.id,
        mealSlot: item.mealSlot,
        note: item.note,
      })),
    })
    uiStore.showToast(confirmed.value ? '菜单已定，开火吧！' : '改回草稿了，继续点菜')
  } catch (cause) {
    uiStore.showToast((cause as { message?: string }).message || '提交失败，稍后再试')
  }
}

function onWindowKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') emit('close')
}

useFocusTrap(boardRoot)

onMounted(() => {
  window.addEventListener('keydown', onWindowKeydown)
  document.body.style.overflow = 'hidden'
  titleInput.value?.focus()
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onWindowKeydown)
  document.body.style.removeProperty('overflow')
})
</script>

<template>
  <Teleport to="body">
    <div class="board-backdrop" @click.self="emit('close')">
      <section ref="boardRoot" class="menu-board" role="dialog" aria-modal="true" aria-labelledby="menu-board-title">
        <button class="board-close tap-44" type="button" aria-label="关闭菜单编辑" @click="emit('close')">×</button>
        <p class="board-kicker">TODAY'S TABLE · EDIT</p>
        <h2 id="menu-board-title">一起点今天的菜</h2>

        <ul v-if="store.menu?.items.length" class="board-items">
          <li v-for="item in store.menu.items" :key="item.id" :class="{ pending: item.id < 0 }">
            <i class="board-slot" aria-hidden="true">{{ SLOTS.find(s => s.value === item.mealSlot)?.label ?? item.mealSlot }}</i>
            <div class="board-copy">
              <strong>{{ item.title }}</strong>
              <small>{{ item.authorName }} 点的<template v-if="item.note"> · {{ item.note }}</template></small>
            </div>
            <button
              v-if="canRemove(item.authorId, item.authorName) && item.id > 0"
              class="board-remove tap-44"
              type="button"
              :aria-label="`把${item.title}移出菜单`"
              :disabled="store.saving"
              @click="removeItem(item.id)"
            >−</button>
          </li>
        </ul>
        <p v-else class="board-empty" role="status">还没有菜，从下面加第一道吧。</p>

        <form class="board-form" @submit.prevent="addItem">
          <label class="board-field">想吃什么
            <input ref="titleInput" v-model="draftTitle" maxlength="120" placeholder="直接写名字，不在菜谱库也行" @input="draftSlug = ''">
          </label>
          <label class="board-field">或从菜谱里挑
            <select :value="draftSlug" @change="pickDish(($event.target as HTMLSelectElement).value)">
              <option value="">——</option>
              <option v-for="dish in dishes" :key="dish.slug" :value="dish.slug">{{ dish.name }}</option>
            </select>
          </label>
          <div class="board-row">
            <label class="board-field slot">餐次
              <select v-model="draftSlot">
                <option v-for="slot in SLOTS" :key="slot.value" :value="slot.value">{{ slot.label }}</option>
              </select>
            </label>
            <label class="board-field note">备注
              <input v-model="draftNote" maxlength="200" placeholder="少放辣 / 多做点…">
            </label>
          </div>
          <div class="board-actions">
            <button class="board-add tap-44" type="submit" :disabled="store.saving">加进菜单 ＋</button>
            <button
              v-if="store.menu?.exists"
              class="board-confirm tap-44"
              type="button"
              :disabled="store.saving"
              @click="toggleConfirm"
            >{{ confirmed ? '改回草稿' : '就这些，定了 ✓' }}</button>
          </div>
        </form>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.board-backdrop { position: fixed; z-index: 2300; inset: 0; display: grid; place-items: center; padding: 18px; background: var(--cinema-backdrop); backdrop-filter: blur(8px); }
.menu-board { position: relative; width: min(520px, 100%); max-height: min(86vh, 720px); overflow-y: auto; padding: clamp(24px, 5vw, 38px); color: var(--ink); background: var(--surface-solid); border: 1px solid color-mix(in srgb, var(--accent) 24%, var(--line)); border-radius: 26px 26px 8px 26px; box-shadow: var(--shadow-lg); animation: board-in .5s var(--ease-out) both; }
.board-close { position: absolute; top: 14px; right: 14px; display: grid; place-items: center; color: var(--muted); font-size: 1.2rem; background: transparent; border: 1px solid var(--line); border-radius: 50%; cursor: pointer; transition: color .2s, border-color .2s; }
.board-close:hover { color: var(--ink); border-color: var(--line-strong); }
.board-kicker { margin: 0; color: var(--accent); font: 650 .62rem/1 ui-monospace, "SF Mono", Consolas, monospace; letter-spacing: .16em; }
.menu-board h2 { margin: 10px 0 18px; font: 400 clamp(1.7rem, 4vw, 2.2rem)/1.1 Georgia, "Songti SC", serif; letter-spacing: -.03em; }
.board-items { display: flex; flex-direction: column; margin: 0 0 18px; padding: 0; list-style: none; }
.board-items li { display: flex; align-items: center; gap: 12px; min-height: 52px; padding: 8px 2px; border-top: 1px dashed color-mix(in srgb, var(--accent) 22%, var(--line)); }
.board-items li:first-child { border-top: 0; }
.board-items li.pending { opacity: .55; }
.board-slot { flex: 0 0 auto; display: grid; place-items: center; min-width: 42px; height: 24px; padding: 0 8px; color: var(--accent); background: color-mix(in srgb, var(--accent-soft) 62%, transparent); border-radius: 999px; font-size: .66rem; font-style: normal; font-weight: 650; }
.board-copy { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 3px; }
.board-copy strong { overflow: hidden; font: 500 1rem/1.3 Georgia, "Songti SC", serif; text-overflow: ellipsis; white-space: nowrap; }
.board-copy small { color: var(--faint); font-size: .7rem; }
.board-remove { flex: 0 0 auto; display: grid; place-items: center; color: var(--muted); font-size: 1.15rem; background: transparent; border: 1px solid var(--line); border-radius: 50%; cursor: pointer; transition: color .2s, border-color .2s; }
.board-remove:hover:not(:disabled) { color: #b84f48; border-color: #b84f48; }
.board-remove:disabled { opacity: .4; cursor: default; }
.board-empty { margin: 0 0 18px; padding: 22px; color: var(--muted); text-align: center; border: 1px dashed color-mix(in srgb, var(--accent) 30%, var(--line)); border-radius: 16px 16px 5px 16px; font-size: .88rem; }
.board-form { display: flex; flex-direction: column; gap: 13px; padding-top: 14px; border-top: 1px solid var(--line); }
.board-field { display: flex; flex-direction: column; gap: 6px; color: var(--muted); font-size: .78rem; }
.board-field input, .board-field select { min-height: 44px; padding: 9px 12px; border: 1px solid var(--line-strong); border-radius: 10px; background: transparent; color: var(--ink); font-size: .9rem; outline: none; transition: border-color .2s; }
.board-field input:focus, .board-field select:focus { border-color: var(--accent); }
.board-row { display: grid; grid-template-columns: 1fr 1.6fr; gap: 12px; }
.board-actions { display: flex; gap: 10px; margin-top: 4px; }
.board-actions button { flex: 1; padding: 12px 14px; font-size: .9rem; font-weight: 600; border-radius: 999px; cursor: pointer; transition: transform .25s var(--ease), box-shadow .25s, background .25s, color .25s; }
.board-add { color: #fff; background: var(--accent); border: 1px solid transparent; box-shadow: 0 10px 24px color-mix(in srgb, var(--accent) 34%, transparent); }
.board-add:hover:not(:disabled) { transform: translateY(-2px); }
.board-confirm { color: var(--accent); background: transparent; border: 1px solid color-mix(in srgb, var(--accent) 40%, var(--line)); }
.board-confirm:hover:not(:disabled) { color: #fff; background: var(--accent); }
.board-actions button:disabled { opacity: .55; cursor: default; }
.menu-board :focus-visible { outline: 2px solid #0071e3; outline-offset: 3px; }
@keyframes board-in { from { opacity: 0; transform: translateY(16px) scale(.98); } to { opacity: 1; transform: none; } }
@media (max-width: 640px) { .board-row { grid-template-columns: 1fr; } .board-actions { flex-direction: column; } }
@media (prefers-reduced-motion: reduce) { .menu-board { animation: none; } .board-actions button { transition-duration: .01ms; } }
</style>
