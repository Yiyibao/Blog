<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { fetchDish } from '../../api/content';
import {
  classifyError,
  clearCheckedShoppingList,
  fetchDailyMenu,
  fetchShoppingList,
  generateShoppingList,
  replayShoppingListMutation,
  updateShoppingList,
  type DailyMenu,
  type ShoppingList,
  type ShoppingListItem,
  type ShoppingListItemDraft,
} from '../../api/kitchen';
import { useAuthStore } from '../../stores/auth';
import {
  enqueueKitchenMutation,
  kitchenQueueSize,
  readKitchenQueue,
  readKitchenSnapshot,
  removeKitchenMutation,
  saveKitchenSnapshot,
  type KitchenQueuePayload,
} from '../../utils/kitchenOfflineQueue';

const emit = defineEmits<{ close: []; editDay: [date: string] }>();
const weekOffset = ref(0);
const menus = ref<DailyMenu[]>([]);
const loading = ref(false);
const error = ref('');
const syncing = ref(false);
const queueSize = ref(0);
const conflict = ref<{ local: ShoppingListItemDraft[]; server: ShoppingListItem[] } | null>(null);
const manualName = ref('');
const manualQuantity = ref('');
const manualUnit = ref('');
const manualCategory = ref('未分类');
const listNote = ref('');
const shoppingList = ref<ShoppingList | null>(null);
const pantrySuggestions = ['食用油', '盐', '黑胡椒'];
const authStore = useAuthStore();
const auth = {
  get canKitchen() {
    return authStore.canKitchen;
  },
  get username() {
    return authStore.username;
  },
};

function dateKey(date: Date) {
  return date.toLocaleDateString('sv-SE');
}

const weekDates = computed(() => {
  const current = new Date();
  current.setHours(12, 0, 0, 0);
  const mondayDelta = (current.getDay() + 6) % 7;
  current.setDate(current.getDate() - mondayDelta + weekOffset.value * 7);
  return Array.from({ length: 7 }, (_, index) => {
    const date = new Date(current);
    date.setDate(current.getDate() + index);
    return date;
  });
});

const weekLabel = computed(() => {
  const dates = weekDates.value;
  return `${dateKey(dates[0])} — ${dateKey(dates[6])}`;
});

const weekStart = computed(() => dateKey(weekDates.value[0]));
const shopping = ref<ShoppingListItem[]>([]);

function mutationKey() {
  try {
    return String(crypto.randomUUID());
  } catch {
    return `kitchen-${Date.now()}-${Math.random().toString(36).slice(2)}`;
  }
}

function quantityLabel(item: ShoppingListItem) {
  if (item.quantity === null || item.quantity === undefined) return item.originalQuantity || '';
  return `${item.quantity}${item.unit}`;
}

function toDraft(item: ShoppingListItem): ShoppingListItemDraft {
  const id = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(item.id)
    ? item.id
    : undefined;
  return {
    ...(id ? { id } : {}),
    displayName: item.displayName,
    normalizedName: item.normalizedName,
    quantity: item.quantity,
    unit: item.unit,
    originalQuantity: item.originalQuantity,
    sourceRecipe: item.sourceRecipe,
    category: item.category,
    checked: item.checked,
    manual: item.manual,
    note: item.note,
  };
}

function applyList(next: ShoppingList) {
  shoppingList.value = next;
  shopping.value = next.items;
  listNote.value = next.note;
  saveKitchenSnapshot(auth.username || 'unknown', next.weekStart, next);
  queueSize.value = kitchenQueueSize(auth.username || 'unknown');
}

function legacyShoppingRows(dishes: Array<{ name: string; ingredients: string[] }>) {
  const rows = new Map<string, ShoppingListItem>();
  for (const dish of dishes) {
    for (const ingredient of dish.ingredients) {
      const displayName = ingredient.trim();
      const normalizedName = displayName.replace(/\s+/g, '').toLocaleLowerCase('zh-CN');
      if (!normalizedName) continue;
      const current = rows.get(normalizedName);
      if (current) {
        current.sourceRecipe = current.sourceRecipe.includes(dish.name)
          ? current.sourceRecipe
          : `${current.sourceRecipe}、${dish.name}`;
        current.originalQuantity = current.originalQuantity
          ? `${current.originalQuantity}；${displayName}`
          : displayName;
      } else {
        rows.set(normalizedName, {
          id: `legacy-${normalizedName}`,
          displayName,
          normalizedName,
          quantity: null,
          unit: '',
          originalQuantity: displayName,
          sourceRecipe: dish.name,
          category: '未分类',
          checked: false,
          manual: false,
          note: '',
          sortOrder: rows.size,
          createdAt: new Date().toISOString(),
        });
      }
    }
  }
  shopping.value = [...rows.values()].sort((a, b) => a.displayName.localeCompare(b.displayName, 'zh-CN'));
}

async function loadPersistedList() {
  if (!auth.canKitchen) return false;
  try {
    let next = await fetchShoppingList(weekStart.value);
    if (!next.items.length && menus.value.some((menu) => menu.items.some((item) => item.dishSlug))) {
      next = await generateShoppingList(weekStart.value, mutationKey());
    }
    applyList(next);
    return true;
  } catch (cause) {
    const snapshot = readKitchenSnapshot(auth.username || 'unknown', weekStart.value);
    if (snapshot) {
      applyList(snapshot);
      error.value = '当前离线，显示最近一次保存的购物清单';
      return true;
    }
    error.value = classifyError(cause).message;
    return false;
  }
}

async function loadWeek() {
  loading.value = true;
  error.value = '';
  shopping.value = [];
  shoppingList.value = null;
  conflict.value = null;
  try {
    menus.value = await Promise.all(weekDates.value.map((date) => fetchDailyMenu(dateKey(date))));
    if (await loadPersistedList()) return;
    const dishSlugs = [
      ...new Set(menus.value.flatMap((menu) => menu.items.map((item) => item.dishSlug).filter(Boolean))),
    ] as string[];
    const dishes = (await Promise.all(dishSlugs.map((slug) => fetchDish(slug).catch(() => null)))).filter(
      Boolean,
    );
    legacyShoppingRows(dishes.filter(Boolean) as Array<{ name: string; ingredients: string[] }>);
  } catch {
    const snapshot = readKitchenSnapshot(auth.username || 'unknown', weekStart.value);
    if (snapshot) {
      applyList(snapshot);
      error.value = '当前离线，显示最近一次保存的购物清单';
    } else {
      error.value = '本周菜单读取失败，请稍后重试。';
    }
  } finally {
    loading.value = false;
  }
}

function changeWeek(delta: number) {
  weekOffset.value += delta;
  void loadWeek();
}

async function persistItems(items: ShoppingListItem[], note = listNote.value) {
  const current = shoppingList.value;
  if (!current) {
    shopping.value = items;
    return;
  }
  const update = { expectedVersion: current.version, note, items: items.map(toDraft) };
  const key = mutationKey();
  try {
    syncing.value = true;
    applyList(await updateShoppingList(current.id, update, key));
    error.value = '';
    conflict.value = null;
  } catch (cause) {
    const classified = classifyError(cause);
    if (classified.kind === 'network') {
      const payload: KitchenQueuePayload = {
        idempotencyKey: key,
        owner: auth.username || 'unknown',
        listId: current.id,
        update,
        createdAt: new Date().toISOString(),
      };
      enqueueKitchenMutation(payload);
      shopping.value = items;
      listNote.value = note;
      queueSize.value = kitchenQueueSize(payload.owner);
      saveKitchenSnapshot(payload.owner, current.weekStart, { ...current, items, note });
      error.value = `网络不可用，已加入离线队列（${queueSize.value}/${50}）`;
    } else if (classified.kind === 'conflict') {
      try {
        const server = await fetchShoppingList(current.weekStart);
        conflict.value = { local: update.items, server: server.items };
      } catch {
        conflict.value = { local: update.items, server: [] };
      }
      error.value = classified.message;
    } else {
      error.value = classified.message;
    }
  } finally {
    syncing.value = false;
  }
}

async function toggleIngredient(id: string) {
  const next = shopping.value.map((item) => (item.id === id ? { ...item, checked: !item.checked } : item));
  shopping.value = next;
  await persistItems(next);
}

function createManualItem(
  displayName: string,
  quantity: number | null,
  unit: string,
  category: string,
  sourceRecipe: string,
): ShoppingListItem {
  return {
    id: mutationKey(),
    displayName,
    normalizedName: displayName.replace(/\s+/g, '').toLocaleLowerCase('zh-CN'),
    quantity,
    unit,
    originalQuantity: quantity === null ? '' : `${quantity}${unit}`,
    sourceRecipe,
    category,
    checked: false,
    manual: true,
    note: '',
    sortOrder: shopping.value.length,
    createdAt: new Date().toISOString(),
  };
}

function hasShoppingItem(displayName: string) {
  const normalized = displayName.replace(/\s+/g, '').toLocaleLowerCase('zh-CN');
  return shopping.value.some((item) => item.normalizedName === normalized);
}

async function addManualItem() {
  const displayName = manualName.value.trim();
  if (!displayName) return;
  const quantity = manualQuantity.value.trim() ? Number(manualQuantity.value) || null : null;
  const next = createManualItem(
    displayName,
    quantity,
    manualUnit.value.trim(),
    manualCategory.value || '未分类',
    '手工添加',
  );
  await persistItems([...shopping.value, next]);
  manualName.value = '';
  manualQuantity.value = '';
  manualUnit.value = '';
}

async function addPantrySuggestion(displayName: string) {
  if (hasShoppingItem(displayName)) return;
  await persistItems([...shopping.value, createManualItem(displayName, null, '', '常备项', '常备项建议')]);
}

async function clearChecked() {
  const current = shoppingList.value;
  if (!current) {
    shopping.value = shopping.value.filter((item) => !item.checked);
    return;
  }
  try {
    syncing.value = true;
    applyList(await clearCheckedShoppingList(current.id, current.version, mutationKey()));
  } catch {
    await persistItems(shopping.value.filter((item) => !item.checked));
  } finally {
    syncing.value = false;
  }
}

async function generatePersistedList() {
  if (!auth.canKitchen) return;
  try {
    syncing.value = true;
    applyList(await generateShoppingList(weekStart.value, mutationKey()));
    error.value = '';
  } catch (cause) {
    error.value = classifyError(cause).message;
  } finally {
    syncing.value = false;
  }
}

async function flushQueue() {
  if (!auth.canKitchen || !navigator.onLine) return;
  const owner = auth.username || 'unknown';
  for (const payload of readKitchenQueue(owner)) {
    try {
      const next = await replayShoppingListMutation(payload);
      removeKitchenMutation(owner, payload.idempotencyKey);
      applyList(next);
    } catch (cause) {
      const classified = classifyError(cause);
      if (classified.kind === 'conflict') error.value = '离线修改与服务器版本冲突，请查看下方差异';
      break;
    }
  }
  queueSize.value = kitchenQueueSize(owner);
}

function onOnline() {
  void flushQueue();
}

function exportShoppingList() {
  const lines = [`购物清单 ${weekLabel.value}`, ''];
  for (const row of shopping.value) {
    lines.push(
      `- [${row.checked ? 'x' : ' '}] ${row.displayName} ${quantityLabel(row)}（${row.category}；来源：${row.sourceRecipe}）`,
    );
  }
  const url = URL.createObjectURL(new Blob([lines.join('\n')], { type: 'text/plain;charset=utf-8' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = `shopping-list-${dateKey(weekDates.value[0])}.txt`;
  link.click();
  URL.revokeObjectURL(url);
}

function printShoppingList() {
  window.print();
}

onMounted(() => {
  window.addEventListener('online', onOnline);
  void loadWeek().then(() => flushQueue());
});
onBeforeUnmount(() => window.removeEventListener('online', onOnline));
</script>

<template>
  <div class="weekly-planner-backdrop" role="presentation" @click.self="emit('close')">
    <section class="weekly-planner" role="dialog" aria-modal="true" aria-labelledby="weekly-planner-title">
      <header>
        <div>
          <small>WEEKLY KITCHEN</small>
          <h2 id="weekly-planner-title">一周菜单与购物清单</h2>
          <p>{{ weekLabel }}</p>
        </div>
        <button type="button" aria-label="关闭周菜单" @click="emit('close')">×</button>
      </header>
      <nav aria-label="切换周">
        <button type="button" @click="changeWeek(-1)">← 上一周</button
        ><button
          type="button"
          @click="
            weekOffset = 0;
            loadWeek();
          "
        >
          本周</button
        ><button type="button" @click="changeWeek(1)">下一周 →</button>
      </nav>
      <p v-if="error" class="content-unavailable" role="alert">{{ error }}</p>
      <p v-else-if="loading" class="weekly-empty">正在整理本周餐桌…</p>
      <template v-if="!loading && (menus.length || shoppingList || shopping.length)">
        <div class="weekly-days">
          <article v-for="(date, index) in weekDates" :key="dateKey(date)">
            <header>
              <strong>{{ ['周一', '周二', '周三', '周四', '周五', '周六', '周日'][index] }}</strong
              ><small>{{ dateKey(date).slice(5) }}</small>
            </header>
            <ul v-if="menus[index]?.items.length">
              <li v-for="item in menus[index].items" :key="item.id">
                <span>{{ item.title }}</span
                ><small>{{ item.mealSlot }}</small>
              </li>
            </ul>
            <p v-else>还没安排</p>
            <button type="button" @click="emit('editDay', dateKey(date))">编辑当天</button>
          </article>
        </div>
        <section class="shopping-list">
          <header>
            <div>
              <small>MERGED INGREDIENTS</small>
              <h3>合并购物清单</h3>
            </div>
            <div>
              <button type="button" @click="printShoppingList">打印</button
              ><button type="button" @click="exportShoppingList">导出 TXT</button>
            </div>
          </header>
          <p v-if="!shopping.length" class="weekly-empty">为本周菜单添加关联菜谱后，这里会自动汇总食材。</p>
          <template v-else
            ><label v-for="row in shopping" :key="row.id" :class="{ checked: row.checked }"
              ><input
                type="checkbox"
                :checked="row.checked"
                :aria-label="`勾选 ${row.displayName}`"
                :disabled="syncing"
                @change="toggleIngredient(row.id)"
              /><span
                ><strong>{{ row.displayName }}</strong
                ><small>{{ quantityLabel(row) }} · {{ row.category }} · 来源：{{ row.sourceRecipe }}</small
                ><small v-if="row.originalQuantity && row.originalQuantity !== quantityLabel(row)"
                  >原始数量：{{ row.originalQuantity }}</small
                ></span
              ></label
            ></template
          >
          <div class="shopping-controls" v-if="auth.canKitchen">
            <div class="manual-item-form">
              <input
                v-model="manualName"
                aria-label="手工食材名称"
                placeholder="添加手工项"
                maxlength="160"
              />
              <input
                v-model="manualQuantity"
                aria-label="手工食材数量"
                placeholder="数量"
                inputmode="decimal"
              />
              <input v-model="manualUnit" aria-label="手工食材单位" placeholder="单位" maxlength="32" />
              <select v-model="manualCategory" aria-label="手工食材分类">
                <option>未分类</option>
                <option>蔬菜</option>
                <option>肉蛋豆制品</option>
                <option>调味料</option>
                <option>日用品</option>
              </select>
              <button type="button" :disabled="syncing || !manualName.trim()" @click="addManualItem">
                添加手工项
              </button>
            </div>
            <div class="pantry-suggestions" aria-label="常备项建议">
              <span>常备项建议</span>
              <button
                v-for="suggestion in pantrySuggestions"
                :key="suggestion"
                type="button"
                :disabled="syncing || hasShoppingItem(suggestion)"
                @click="addPantrySuggestion(suggestion)"
              >
                {{ suggestion }}
              </button>
            </div>
            <label class="list-note"
              >清单备注<textarea
                v-model="listNote"
                maxlength="500"
                @change="persistItems(shopping, listNote)"
              />
            </label>
            <div class="shopping-actions">
              <button type="button" :disabled="syncing" @click="generatePersistedList">
                重新按周菜单生成
              </button>
              <button
                type="button"
                :disabled="syncing || !shopping.some((item) => item.checked)"
                @click="clearChecked"
              >
                清理已勾选
              </button>
              <span v-if="queueSize" role="status">待同步 {{ queueSize }}/50</span>
            </div>
          </div>
          <aside v-if="conflict" class="shopping-conflict" role="alert">
            <strong>检测到并发修改</strong>
            <p>
              服务器当前 {{ conflict.server.length }} 项，本地待提交
              {{ conflict.local.length }} 项；请刷新后手动合并。
            </p>
          </aside>
        </section>
      </template>
    </section>
  </div>
</template>

<style scoped>
.weekly-planner-backdrop {
  position: fixed;
  inset: 0;
  z-index: 80;
  display: grid;
  place-items: center;
  padding: 20px;
  background: rgb(20 17 14 / 70%);
  backdrop-filter: blur(8px);
}
.weekly-planner {
  width: min(1180px, 96vw);
  max-height: 92vh;
  overflow: auto;
  padding: 24px;
  border-radius: 20px;
  background: var(--surface-solid);
  color: var(--ink);
  box-shadow: 0 24px 80px rgb(0 0 0 / 30%);
}
.weekly-planner > header,
.shopping-list > header {
  display: flex;
  justify-content: space-between;
  gap: 18px;
  align-items: flex-start;
}
.weekly-planner h2,
.weekly-planner h3 {
  margin: 4px 0;
}
.weekly-planner > header > button {
  border: 0;
  background: transparent;
  color: var(--ink);
  font-size: 30px;
  cursor: pointer;
}
.weekly-planner > nav {
  display: flex;
  gap: 8px;
  justify-content: center;
  margin: 16px 0;
}
.weekly-planner button {
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 7px 11px;
  background: var(--surface);
  color: var(--ink);
  cursor: pointer;
}
.weekly-days {
  display: grid;
  grid-template-columns: repeat(7, minmax(130px, 1fr));
  gap: 9px;
}
.weekly-days article {
  display: flex;
  flex-direction: column;
  min-height: 190px;
  padding: 12px;
  border: 1px solid var(--line);
  border-radius: 12px;
}
.weekly-days article > header {
  display: flex;
  justify-content: space-between;
}
.weekly-days ul {
  flex: 1;
  margin: 12px 0;
  padding: 0;
  list-style: none;
}
.weekly-days li {
  display: grid;
  gap: 2px;
  margin-bottom: 8px;
}
.weekly-days p {
  flex: 1;
  color: var(--muted);
}
.shopping-list {
  margin-top: 22px;
  padding-top: 20px;
  border-top: 1px solid var(--line);
}
.shopping-list > header > div:last-child {
  display: flex;
  gap: 8px;
}
.shopping-list > label {
  display: flex;
  gap: 12px;
  padding: 10px 4px;
  border-bottom: 1px solid var(--line);
}
.shopping-list label span {
  display: grid;
  gap: 3px;
}
.shopping-list label small {
  color: var(--muted);
}
.shopping-list label.checked {
  opacity: 0.55;
  text-decoration: line-through;
}
.shopping-controls {
  display: grid;
  gap: 12px;
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px dashed var(--line);
}
.manual-item-form,
.shopping-actions,
.pantry-suggestions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}
.pantry-suggestions {
  color: var(--muted);
  font-size: 13px;
}
.manual-item-form input,
.manual-item-form select,
.list-note textarea {
  min-width: 120px;
  border: 1px solid var(--line);
  border-radius: 7px;
  padding: 8px;
  background: var(--surface);
  color: var(--ink);
}
.list-note {
  display: grid;
  gap: 5px;
  color: var(--muted);
  font-size: 13px;
}
.list-note textarea {
  width: 100%;
  box-sizing: border-box;
  resize: vertical;
}
.shopping-actions span {
  color: var(--muted);
  font-size: 12px;
}
.shopping-conflict {
  margin-top: 12px;
  padding: 12px;
  border: 1px solid color-mix(in srgb, #c5792e 50%, var(--line));
  border-radius: 8px;
  background: color-mix(in srgb, #c5792e 9%, var(--surface));
}
.shopping-conflict p {
  margin: 5px 0 0;
  color: var(--muted);
}
.weekly-empty {
  padding: 40px;
  text-align: center;
  color: var(--muted);
}
@media (max-width: 900px) {
  .weekly-days {
    grid-template-columns: repeat(2, 1fr);
  }
}
@media (max-width: 520px) {
  .weekly-days {
    grid-template-columns: 1fr;
  }
}
@media print {
  .weekly-planner-backdrop {
    position: static;
    padding: 0;
    background: white;
  }
  .weekly-planner {
    max-height: none;
    box-shadow: none;
  }
  .weekly-planner > header,
  .weekly-planner > nav,
  .weekly-days {
    display: none;
  }
}
</style>
