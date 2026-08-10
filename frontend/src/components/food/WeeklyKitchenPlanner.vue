<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { fetchDish } from '../../api/content';
import { fetchDailyMenu, type DailyMenu } from '../../api/kitchen';

const emit = defineEmits<{ close: []; editDay: [date: string] }>();
const weekOffset = ref(0);
const menus = ref<DailyMenu[]>([]);
const loading = ref(false);
const error = ref('');
const checked = ref(new Set<string>());

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

interface ShoppingRow {
  name: string;
  count: number;
  dishes: string[];
}
const shopping = ref<ShoppingRow[]>([]);

async function loadWeek() {
  loading.value = true;
  error.value = '';
  shopping.value = [];
  try {
    menus.value = await Promise.all(weekDates.value.map((date) => fetchDailyMenu(dateKey(date))));
    const dishSlugs = [
      ...new Set(menus.value.flatMap((menu) => menu.items.map((item) => item.dishSlug).filter(Boolean))),
    ] as string[];
    const dishes = (await Promise.all(dishSlugs.map((slug) => fetchDish(slug).catch(() => null)))).filter(
      Boolean,
    );
    const rows = new Map<string, ShoppingRow>();
    for (const dish of dishes) {
      if (!dish) continue;
      for (const ingredient of dish.ingredients) {
        const key = ingredient.trim().toLocaleLowerCase('zh-CN');
        if (!key) continue;
        const row = rows.get(key) ?? { name: ingredient.trim(), count: 0, dishes: [] };
        row.count += 1;
        if (!row.dishes.includes(dish.name)) row.dishes.push(dish.name);
        rows.set(key, row);
      }
    }
    shopping.value = [...rows.values()].sort((a, b) => a.name.localeCompare(b.name, 'zh-CN'));
  } catch {
    error.value = '本周菜单读取失败，请稍后重试。';
  } finally {
    loading.value = false;
  }
}

function changeWeek(delta: number) {
  weekOffset.value += delta;
  void loadWeek();
}

function toggleIngredient(name: string) {
  const next = new Set(checked.value);
  if (next.has(name)) next.delete(name);
  else next.add(name);
  checked.value = next;
}

function exportShoppingList() {
  const lines = [`购物清单 ${weekLabel.value}`, ''];
  for (const row of shopping.value) {
    lines.push(
      `- [${checked.value.has(row.name) ? 'x' : ' '}] ${row.name}（用于：${row.dishes.join('、')}）`,
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

onMounted(loadWeek);
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
      <template v-else>
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
            ><label v-for="row in shopping" :key="row.name" :class="{ checked: checked.has(row.name) }"
              ><input
                type="checkbox"
                :checked="checked.has(row.name)"
                @change="toggleIngredient(row.name)"
              /><span
                ><strong>{{ row.name }}</strong
                ><small
                  >{{ row.dishes.join('、')
                  }}<template v-if="row.count > 1"> · {{ row.count }} 道菜需要</template></small
                ></span
              ></label
            ></template
          >
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
