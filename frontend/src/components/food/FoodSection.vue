<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import {
  fetchDish,
  fetchDishes,
  fetchDishCategories,
  fetchDishFavorites,
  favoriteDish,
  type DishFavoriteItem,
} from '../../api/content';
import type { Dish } from '../../data';
import { createSiteConfig, resolveUrl } from '../../config/site';
import { usePageMeta, cleanText } from '../../composables/usePageMeta';
import { recipe, breadcrumbList, useStructuredData } from '../../composables/useStructuredData';
import { useUiStore } from '../../stores/uiStore';
import { removeLegacyKey } from '../../utils/localStore';
import { useRequestToken } from '../../composables/useRequestToken';
import { refreshReveals } from '../../composables/useReveals';
import { useAuthStore } from '../../stores/auth';
import { useFoodStore, todayISO } from '../../stores/foodStore';
import {
  classifyError,
  createMealLog,
  fetchDishStats,
  type DishCookStat,
  type MenuItem,
} from '../../api/kitchen';
import DishPanel from './DishPanel.vue';
import DishRoulette from './DishRoulette.vue';
import TodayMenuCard from './TodayMenuCard.vue';
import TodayMenuBoard from './TodayMenuBoard.vue';
import WeeklyKitchenPlanner from './WeeklyKitchenPlanner.vue';
import FoodTimeline from './FoodTimeline.vue';
import PaginationNav from '../PaginationNav.vue';
const route = useRoute();
const router = useRouter();
interface CategoryItem {
  name: string;
  slug: string;
}
const dishes = ref<Dish[]>([]);
const dishPage = ref(0);
const dishTotal = ref(0);
const dishTotalPages = ref(1);
const dishPageSize = 4;
const selectedCategory = ref('全部');
const selectedDish = ref<Dish | null>(null);
const loading = ref(true);
const loadError = ref('');
const ready = ref(false);
let lastTrigger: HTMLElement | null = null;

const dishQuery = ref('');

const categories = ref<CategoryItem[]>([{ name: '全部', slug: '' }]);
// FD-3：排行榜从"当前页 12 条按后台手填评分排序"换成全站真实点亮数据（后端收藏榜端点）
const favoriteBoard = ref<DishFavoriteItem[]>([]);

// FD-19：登录后主口径换"你们做过 N 次"（meal_logs 聚合），点亮数降为副口径；
// 匿名或还没做过菜时回退点亮榜。名字/图取自收藏榜条目（聚合是轻量投影）。
const cookStats = ref<DishCookStat[]>([]);
interface BoardRow {
  slug: string;
  name: string;
  imageUrl: string;
  primary: number;
  primaryLabel: string;
  secondary?: string;
}
const boardRows = computed<BoardRow[]>(() => {
  const cookRows = cookStats.value
    .map((stat) => ({ stat, fav: favoriteBoard.value.find((item) => item.slug === stat.slug) }))
    .filter((pair): pair is { stat: DishCookStat; fav: DishFavoriteItem } => Boolean(pair.fav))
    .slice(0, 5);
  if (cookRows.length) {
    return cookRows.map(({ stat, fav }) => ({
      slug: stat.slug,
      name: fav.name,
      imageUrl: fav.imageUrl,
      primary: stat.cookCount,
      primaryLabel: `你们做过 ${stat.cookCount} 次`,
      secondary: `大家点亮 ${fav.favoriteCount} 次`,
    }));
  }
  return favoriteBoard.value.map((item) => ({
    slug: item.slug,
    name: item.name,
    imageUrl: item.imageUrl,
    primary: item.favoriteCount,
    primaryLabel: `大家点亮 ${item.favoriteCount} 次`,
  }));
});
const cookMode = computed(() => boardRows.value[0]?.secondary !== undefined);
const showRanking = computed(() => boardRows.value.length > 0 && boardRows.value[0].primary > 0);
const championCount = computed(() => Math.max(1, boardRows.value[0]?.primary ?? 1));

async function loadCookStats() {
  try {
    cookStats.value = auth.canKitchen ? await fetchDishStats() : [];
  } catch {
    cookStats.value = [];
  }
}

// FD-18：一键打卡——菜单卡 ✓ / 抽屉"今天吃了"，成功后时光机就地刷新
const timelineRef = ref<InstanceType<typeof FoodTimeline> | null>(null);
async function onCheckInItem(item: MenuItem) {
  try {
    await createMealLog({
      ...(item.dishSlug ? { dishSlug: item.dishSlug } : { title: item.title }),
      mealSlot: item.mealSlot,
      logDate: foodStore.menuDate,
    });
    uiStore.showToast(`已记一笔：${item.title} ✓`);
    timelineRef.value?.reload();
    void loadCookStats();
  } catch (cause) {
    uiStore.showToast(classifyError(cause).message);
  }
}
async function onDishCheckIn(dish: Dish) {
  try {
    await createMealLog({ dishSlug: dish.slug, mealSlot: 'DINNER', logDate: todayISO() });
    uiStore.showToast(`已记一笔：${dish.name} ✓`);
    timelineRef.value?.reload();
    void loadCookStats();
  } catch (cause) {
    uiStore.showToast(classifyError(cause).message);
  }
}

const uiStore = useUiStore();
const auth = useAuthStore();
const foodStore = useFoodStore();

// FD-13：今日菜单——?view=menu 打开编辑板（可分享/可回退），?date= 指定日期
const boardOpen = computed(() => route.query.view === 'menu' && auth.canKitchen);
const weekPlannerOpen = computed(() => route.query.view === 'week' && auth.canKitchen);
function openBoard() {
  foodStore.clearArrivals();
  void router.replace({ query: { ...route.query, view: 'menu' } });
}
function closeBoard() {
  const { view: _view, ...rest } = route.query;
  void router.replace({ query: rest });
}
function openWeekPlanner() {
  void router.replace({ query: { ...route.query, view: 'week' } });
}
function closeWeekPlanner() {
  const { view: _view, ...rest } = route.query;
  void router.replace({ query: rest });
}
function editWeekDay(date: string) {
  void router.replace({ query: { ...route.query, view: 'menu', date } });
  void foodStore.loadMenu(date);
}
function initialMenuDate(): string | undefined {
  const raw = route.query.date;
  return typeof raw === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(raw) ? raw : undefined;
}
function startMenu() {
  void foodStore.loadMenu(initialMenuDate());
  foodStore.startMenuPolling();
  void loadCookStats();
}
// FD-14：匿名点"一起定菜单"→ /login?next=/recipes?view=menu&intent=addDish ——
// 登录回来编辑板已开且输入框已聚焦，这里补句欢迎语并消费掉 intent
const MENU_INVITE_NEXT = '/recipes?view=menu&intent=addDish';
watch(
  boardOpen,
  (open) => {
    if (open && route.query.intent === 'addDish') {
      uiStore.showToast('回来啦，接着点菜～');
      const { intent: _intent, ...rest } = route.query;
      void router.replace({ query: rest });
    }
  },
  { immediate: true },
);
watch(
  () => auth.canKitchen,
  (can) => {
    if (can) startMenu();
    else foodStore.stopMenuPolling();
  },
);

// FD-5：今天吃什么·抽卡（纯前端）；抽选池 = 当前筛选结果
const rouletteOpen = ref(false);
const roulettePool = computed(() => dishes.value);
function onRouletteOpen(dish: Dish) {
  rouletteOpen.value = false;
  void openDish(dish);
}

// FD-5：Hero 统计只增不跳——RECIPES 本就是全局 totalElements，
// COLLECTIONS/FEATURED 原按当前页 12 条计算、翻页会缩水，改为浏览过程单调累积
const seenCategories = ref(new Set<string>());
const seenFeatured = ref(new Set<number>());

async function onFavorite(dish: Dish) {
  // 乐观 +1，服务端响应为权威值；GET 详情带 5 分钟公共缓存，禁止用其回写计数
  dish.favoriteCount += 1;
  try {
    const result = await favoriteDish(dish.slug);
    dish.favoriteCount = result.favoriteCount;
  } catch (error) {
    dish.favoriteCount = Math.max(0, dish.favoriteCount - 1);
    const status = (error as { response?: { status?: number } })?.response?.status;
    uiStore.showToast(status === 429 ? '爱心点太快啦，休息一分钟再来～' : '爱心没送出去，稍后再试试');
  }
}

const { apply: applyMeta } = usePageMeta();
const { apply: applyLD } = useStructuredData();

watch(selectedDish, (dish) => {
  if (dish) {
    const excerpt = cleanText(dish.summary, 200);
    const image = dish.imageUrl || '/og.png';
    const authorName = createSiteConfig().authorName;
    applyLD([
      recipe({
        name: dish.name,
        description: excerpt,
        url: resolveUrl(`/recipes?dish=${dish.slug}`),
        image: dish.imageUrl,
        recipeIngredient: dish.ingredients,
        recipeInstructions: dish.steps,
        recipeCategory: dish.category,
        authorName: authorName || 'Yubai',
        datePublished: dish.createdAt,
        dateModified: dish.updatedAt,
      }),
      breadcrumbList([
        { name: '首页', path: '/' },
        { name: '美食', path: '/recipes' },
        { name: dish.name, path: `/recipes?dish=${dish.slug}` },
      ]),
    ]);
    applyMeta({
      title: dish.name,
      description: excerpt,
      canonicalPath: `/recipes?dish=${dish.slug}`,
      openGraph: {
        title: dish.name,
        description: excerpt,
        type: 'article',
        image: image,
        url: `/recipes?dish=${dish.slug}`,
      },
      twitter: {
        title: dish.name,
        description: excerpt,
        card: 'summary_large_image',
        image: image,
      },
    });
  }
});

let loadRevision = 0;

async function load() {
  const revision = ++loadRevision;
  loading.value = true;
  loadError.value = '';
  try {
    const cat = categories.value.find((c) => c.name === selectedCategory.value);
    const categorySlug = selectedCategory.value === '全部' ? undefined : cat?.slug;
    const q = dishQuery.value.trim() || undefined;
    const [result, favorites, remoteCategories] = await Promise.all([
      fetchDishes(dishPage.value, dishPageSize, categorySlug, q),
      fetchDishFavorites(0, 5).catch(() => null),
      fetchDishCategories().catch(() => null),
    ]);
    if (revision !== loadRevision) return;
    if (dishPage.value > 0 && dishPage.value >= result.totalPages) {
      dishPage.value = Math.max(0, result.totalPages - 1);
      return;
    }
    dishes.value = result.items;
    dishTotal.value = result.totalElements;
    dishTotalPages.value = Math.max(1, result.totalPages);
    favoriteBoard.value = favorites?.items ?? [];
    if (remoteCategories?.length) {
      categories.value = [
        { name: '全部', slug: '' },
        ...remoteCategories.map((c) => ({ name: c.name, slug: c.slug })),
      ];
    }
    seenCategories.value = new Set([...seenCategories.value, ...result.items.map((dish) => dish.category)]);
    seenFeatured.value = new Set([
      ...seenFeatured.value,
      ...result.items.filter((dish) => dish.featured).map((dish) => dish.id),
    ]);
    await openRouteDish();
    await nextTick();
    ready.value = true;
    refreshReveals();
  } catch {
    if (revision === loadRevision) {
      loadError.value = '菜谱暂时没有准备好，请稍后再来看看。';
    }
  } finally {
    if (revision === loadRevision) {
      loading.value = false;
    }
  }
}

let queryTimer: ReturnType<typeof setTimeout> | undefined;
watch(dishQuery, () => {
  clearTimeout(queryTimer);
  queryTimer = setTimeout(() => {
    if (dishPage.value !== 0) {
      dishPage.value = 0;
    } else {
      void load();
    }
  }, 300);
});
watch(selectedCategory, () => {
  if (dishPage.value !== 0) {
    dishPage.value = 0;
  } else {
    void load();
  }
});
watch(dishPage, () => {
  void load();
});

async function openBySlug(slug: string, event?: Event) {
  let dish = dishes.value.find((item) => item.slug === slug);
  if (!dish) {
    try {
      dish = await fetchDish(slug);
    } catch {
      return;
    }
  }
  await openDish(dish, event);
}

// FD-4：深链详情缓存——补取的菜不再插进列表头（会顶掉 featured 大卡、污染分页语义）
const detailCache = new Map<string, Dish>();
const detailToken = useRequestToken();

async function openRouteDish() {
  // 每次路由变化先作废在途详情请求：同步命中路径也要让迟到响应失效
  const token = detailToken.next();
  const rawSlug = Array.isArray(route.query.dish) ? route.query.dish[0] : route.query.dish;
  const slug = typeof rawSlug === 'string' ? rawSlug.trim() : '';
  if (!slug) {
    if (selectedDish.value) closeDish();
    return;
  }
  if (selectedDish.value?.slug === slug) return;
  let dish = dishes.value.find((item) => item.slug === slug) ?? detailCache.get(slug);
  if (!dish) {
    try {
      const fetched = await fetchDish(slug);
      if (!detailToken.isCurrent(token)) return;
      detailCache.set(slug, fetched);
      dish = fetched;
    } catch {
      return;
    }
  }
  await openDish(dish);
}

async function openDish(dish: Dish, event?: Event) {
  if (event) {
    lastTrigger = event.currentTarget instanceof HTMLElement ? event.currentTarget : null;
  }
  selectedDish.value = dish;
  if (route.query.dish !== dish.slug) {
    void router.replace({ query: { ...route.query, dish: dish.slug } });
  }
}

function closeDish() {
  selectedDish.value = null;
  if (route.query.dish) {
    const { dish: _removed, ...rest } = route.query;
    void router.replace({ query: rest });
  }
  nextTick(() => lastTrigger?.focus());
}

watch(
  () => route.query.dish,
  () => void openRouteDish(),
);

onMounted(() => {
  load();
  // NF-9（提前执行）：清掉幽灵收藏死代码写下的遗留键
  removeLegacyKey('yubai_dish_favorites');
  if (auth.canKitchen) startMenu();
});
onBeforeUnmount(() => {
  foodStore.stopMenuPolling();
  clearTimeout(queryTimer);
});
</script>

<template>
  <section class="food-stage" :class="{ ready }">
    <div class="food-shell section-wrap">
      <header class="food-hero">
        <div class="food-hero-copy">
          <p class="food-kicker">HXNF · LIVING COOKBOOK</p>
          <h1><span>一座持续生长的</span><em>家常菜谱库</em></h1>
          <p>真实食材、清楚步骤，以及值得反复端上餐桌的味道。每一道菜都来自数据库，也保留照片作者与来源。</p>
        </div>
        <div class="food-hero-side">
          <TodayMenuCard
            v-if="auth.canKitchen"
            :menu="foodStore.menu"
            :loading="foodStore.loading"
            :can-edit="foodStore.canEdit"
            :arrivals="foodStore.arrivals"
            @open="openBoard"
            @check-in-item="onCheckInItem"
          />
          <button
            v-if="auth.canKitchen"
            class="weekly-planner-trigger"
            type="button"
            @click="openWeekPlanner"
          >
            一周菜单与购物清单 →
          </button>
          <template v-else>
            <dl class="food-stats" aria-label="菜谱统计">
              <div>
                <dt>{{ dishTotal.toString().padStart(2, '0') }}</dt>
                <dd>RECIPES</dd>
              </div>
              <div>
                <dt>{{ seenCategories.size }}</dt>
                <dd>COLLECTIONS</dd>
              </div>
              <div>
                <dt>{{ seenFeatured.size }}</dt>
                <dd>FEATURED</dd>
              </div>
            </dl>
            <RouterLink
              class="menu-invite tap-44"
              :to="{ path: '/login', query: { next: MENU_INVITE_NEXT } }"
            >
              一起定今天的菜单 →
            </RouterLink>
          </template>
        </div>
      </header>

      <nav class="food-filter" aria-label="菜谱分类">
        <strong>{{ dishes.length }}/{{ dishTotal }} 道家常菜</strong>
        <label class="food-search">
          <input v-model="dishQuery" type="search" placeholder="搜索菜名、食材或分类…" />
        </label>
        <div class="food-filter-tabs">
          <button
            v-for="cat in categories"
            :key="cat.name"
            type="button"
            :class="{ active: selectedCategory === cat.name }"
            @click="selectedCategory = cat.name"
          >
            {{ cat.name }}
          </button>
        </div>
      </nav>

      <div v-if="loading" class="food-skeleton-grid" aria-label="正在读取菜谱">
        <span v-for="index in 7" :key="index" />
      </div>
      <div v-else-if="loadError" class="food-empty" role="alert">
        <span>THE KITCHEN IS QUIET</span>
        <h2>{{ loadError }}</h2>
        <button type="button" @click="load">重新加载</button>
      </div>
      <template v-else>
        <header class="food-catalog-head">
          <div>
            <span>RECIPE INDEX</span>
            <h2>
              {{ selectedCategory === '全部' ? '今天，想做点什么？' : `${selectedCategory} · 精选菜谱` }}
            </h2>
          </div>
          <div class="catalog-aside">
            <p>从一顿简单的饭开始，把日常过得更有滋味。</p>
            <button type="button" class="roulette-trigger tap-44" @click="rouletteOpen = true">
              <i aria-hidden="true">✦</i>今天吃什么？抽一道
            </button>
          </div>
        </header>
        <div v-if="!dishes.length" class="food-no-result" role="status">
          <span>NO MATCH</span>
          <p>没有找到{{ dishQuery ? `「${dishQuery}」` : '' }}相关的菜，换个关键词或分类试试？</p>
        </div>
        <Transition v-else name="dish-filter" mode="out-in">
          <div :key="selectedCategory" class="dish-grid">
            <button
              v-for="(dish, index) in dishes"
              :key="dish.id"
              class="dish-card"
              :class="{ featured: index === 0 && selectedCategory === '全部' }"
              :style="{ '--card-delay': `${Math.min(index, 7) * 55}ms` }"
              type="button"
              :aria-label="
                dish.favoriteCount > 0
                  ? `查看${dish.name}的食材和做法，已被点亮 ${dish.favoriteCount} 次`
                  : `查看${dish.name}的食材和做法`
              "
              @click="openDish(dish, $event)"
            >
              <span class="dish-media">
                <img :src="dish.imageUrl" :alt="dish.imageAlt" loading="lazy" />
                <span class="dish-shade" />
                <span class="dish-topline"
                  ><small>{{ dish.category }}</small
                  ><small>★ {{ dish.rating.toFixed(1) }}</small></span
                >
                <span v-if="dish.favoriteCount > 0" class="dish-hearts" aria-hidden="true"
                  >♥ {{ dish.favoriteCount }}</span
                >
                <span class="dish-index">{{ String(index + 1).padStart(2, '0') }}</span>
              </span>
              <span class="dish-copy">
                <small
                  >{{ dish.prepMinutes }} 分钟 · {{ dish.difficulty }} · {{ dish.ingredients.length }} 种食材
                  · {{ dish.baseServings ?? 2 }} 人份</small
                >
                <strong>{{ dish.name }}</strong>
                <span>{{ dish.summary }}</span>
                <u>打开这份菜谱 <b>↗</b></u>
              </span>
            </button>
          </div>
        </Transition>
      </template>
      <PaginationNav
        :page="dishPage"
        :total-pages="dishTotalPages"
        aria-label="公开菜谱分页"
        @change="dishPage = $event"
      />

      <section v-if="showRanking" class="food-ranking" aria-labelledby="food-ranking-title">
        <header class="ranking-head">
          <div>
            <p>TASTE CLUB · TOP 05</p>
            <h2 id="food-ranking-title">美食爱好榜</h2>
          </div>
          <p>
            {{
              cookMode
                ? '按你们真实做过的次数排名——最常端上桌的，才是真爱。'
                : '按真实点亮次数排名，记录此刻最让人惦记的家常味道。'
            }}
          </p>
        </header>
        <div class="ranking-board">
          <button
            v-if="boardRows[0]"
            class="ranking-champion"
            type="button"
            :aria-label="`查看榜首${boardRows[0].name}，${boardRows[0].primaryLabel}`"
            @click="openBySlug(boardRows[0].slug, $event)"
          >
            <span class="champion-media"
              ><img :src="boardRows[0].imageUrl" :alt="boardRows[0].name" loading="lazy" /><i /><b
                >NO. 01</b
              ></span
            >
            <span class="champion-copy"
              ><small>{{ cookMode ? '你们最常做的' : '本期味蕾冠军' }}</small
              ><strong>{{ boardRows[0].name }}</strong
              ><span>{{ boardRows[0].secondary ?? boardRows[0].primaryLabel }}</span
              ><u>查看冠军菜谱 ↗</u></span
            >
            <span class="score-orbit"
              ><b>{{ boardRows[0].primary }}</b
              ><small>{{ cookMode ? '次' : '点亮' }}</small></span
            >
          </button>
          <ol class="ranking-list">
            <li
              v-for="(item, index) in boardRows.slice(1)"
              :key="item.slug"
              :style="{ '--rank-delay': `${index * 80 + 160}ms` }"
            >
              <button
                type="button"
                :aria-label="`查看${item.name}，${item.primaryLabel}`"
                @click="openBySlug(item.slug, $event)"
              >
                <span class="rank-number">{{ String(index + 2).padStart(2, '0') }}</span>
                <img :src="item.imageUrl" :alt="item.name" loading="lazy" />
                <span class="rank-info"
                  ><strong>{{ item.name }}</strong
                  ><small
                    >{{ item.primaryLabel
                    }}<template v-if="item.secondary"> · {{ item.secondary }}</template></small
                  ><span class="rank-meter"
                    ><i :style="{ width: `${Math.round((item.primary / championCount) * 100)}%` }" /></span
                ></span>
                <b class="rank-score">{{ item.primary }}</b
                ><span class="rank-arrow">↗</span>
              </button>
            </li>
          </ol>
        </div>
      </section>

      <FoodTimeline v-if="auth.canKitchen" ref="timelineRef" @open="openBySlug($event)" />
    </div>
  </section>

  <button
    v-if="!loading && !loadError"
    class="roulette-fab tap-44"
    type="button"
    aria-label="今天吃什么？随机抽一道"
    @click="rouletteOpen = true"
  >
    ✦
  </button>

  <TodayMenuBoard v-if="boardOpen" :dishes="dishes" @close="closeBoard" />
  <WeeklyKitchenPlanner v-if="weekPlannerOpen" @close="closeWeekPlanner" @edit-day="editWeekDay" />
  <DishRoulette
    v-if="rouletteOpen"
    :dishes="roulettePool"
    @close="rouletteOpen = false"
    @open="onRouletteOpen"
  />
  <DishPanel
    :dish="selectedDish"
    :can-check-in="auth.canKitchen"
    @close="closeDish"
    @favorite="onFavorite"
    @check-in="onDishCheckIn"
  />
</template>

<style scoped src="./FoodSection.css"></style>
