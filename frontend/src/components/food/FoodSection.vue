<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchDish, fetchDishes, fetchDishCategories, fetchDishFavorites, favoriteDish, type DishFavoriteItem } from '../../api/content'
import type { Dish } from '../../data'
import { createSiteConfig, resolveUrl } from '../../config/site'
import { usePageMeta, cleanText } from '../../composables/usePageMeta'
import { recipe, breadcrumbList, useStructuredData } from '../../composables/useStructuredData'
import { useUiStore } from '../../stores/uiStore'
import { removeLegacyKey } from '../../utils/localStore'
import { useRequestToken } from '../../composables/useRequestToken'
import { refreshReveals } from '../../composables/useReveals'
import { useAuthStore } from '../../stores/auth'
import { useFoodStore, todayISO } from '../../stores/foodStore'
import { classifyError, createMealLog, fetchDishStats, type DishCookStat, type MenuItem } from '../../api/kitchen'
import DishPanel from './DishPanel.vue'
import DishRoulette from './DishRoulette.vue'
import TodayMenuCard from './TodayMenuCard.vue'
import TodayMenuBoard from './TodayMenuBoard.vue'
import FoodTimeline from './FoodTimeline.vue'
const route = useRoute()
const router = useRouter()
interface CategoryItem { name: string; slug: string }
const dishes = ref<Dish[]>([])
const dishPage = ref(0)
const dishTotal = ref(0)
const dishTotalPages = ref(1)
const dishPageSize = 4
const selectedCategory = ref('全部')
const selectedDish = ref<Dish | null>(null)
const loading = ref(true)
const loadError = ref('')
const ready = ref(false)
let lastTrigger: HTMLElement | null = null

const dishQuery = ref('')

const categories = ref<CategoryItem[]>([{ name: '全部', slug: '' }])
// FD-3：排行榜从"当前页 12 条按后台手填评分排序"换成全站真实点亮数据（后端收藏榜端点）
const favoriteBoard = ref<DishFavoriteItem[]>([])

// FD-19：登录后主口径换"你们做过 N 次"（meal_logs 聚合），点亮数降为副口径；
// 匿名或还没做过菜时回退点亮榜。名字/图取自收藏榜条目（聚合是轻量投影）。
const cookStats = ref<DishCookStat[]>([])
interface BoardRow { slug: string; name: string; imageUrl: string; primary: number; primaryLabel: string; secondary?: string }
const boardRows = computed<BoardRow[]>(() => {
  const cookRows = cookStats.value
    .map(stat => ({ stat, fav: favoriteBoard.value.find(item => item.slug === stat.slug) }))
    .filter((pair): pair is { stat: DishCookStat; fav: DishFavoriteItem } => Boolean(pair.fav))
    .slice(0, 5)
  if (cookRows.length) {
    return cookRows.map(({ stat, fav }) => ({
      slug: stat.slug, name: fav.name, imageUrl: fav.imageUrl,
      primary: stat.cookCount, primaryLabel: `你们做过 ${stat.cookCount} 次`,
      secondary: `大家点亮 ${fav.favoriteCount} 次`,
    }))
  }
  return favoriteBoard.value.map(item => ({
    slug: item.slug, name: item.name, imageUrl: item.imageUrl,
    primary: item.favoriteCount, primaryLabel: `大家点亮 ${item.favoriteCount} 次`,
  }))
})
const cookMode = computed(() => boardRows.value[0]?.secondary !== undefined)
const showRanking = computed(() => boardRows.value.length > 0 && boardRows.value[0].primary > 0)
const championCount = computed(() => Math.max(1, boardRows.value[0]?.primary ?? 1))

async function loadCookStats() {
  try {
    cookStats.value = auth.canKitchen ? await fetchDishStats() : []
  } catch {
    cookStats.value = []
  }
}

// FD-18：一键打卡——菜单卡 ✓ / 抽屉"今天吃了"，成功后时光机就地刷新
const timelineRef = ref<InstanceType<typeof FoodTimeline> | null>(null)
async function onCheckInItem(item: MenuItem) {
  try {
    await createMealLog({
      ...(item.dishSlug ? { dishSlug: item.dishSlug } : { title: item.title }),
      mealSlot: item.mealSlot,
      logDate: foodStore.menuDate,
    })
    uiStore.showToast(`已记一笔：${item.title} ✓`)
    timelineRef.value?.reload()
    void loadCookStats()
  } catch (cause) {
    uiStore.showToast(classifyError(cause).message)
  }
}
async function onDishCheckIn(dish: Dish) {
  try {
    await createMealLog({ dishSlug: dish.slug, mealSlot: 'DINNER', logDate: todayISO() })
    uiStore.showToast(`已记一笔：${dish.name} ✓`)
    timelineRef.value?.reload()
    void loadCookStats()
  } catch (cause) {
    uiStore.showToast(classifyError(cause).message)
  }
}

const uiStore = useUiStore()
const auth = useAuthStore()
const foodStore = useFoodStore()

// FD-13：今日菜单——?view=menu 打开编辑板（可分享/可回退），?date= 指定日期
const boardOpen = computed(() => route.query.view === 'menu' && auth.canKitchen)
function openBoard() {
  foodStore.clearArrivals()
  void router.replace({ query: { ...route.query, view: 'menu' } })
}
function closeBoard() {
  const { view: _view, ...rest } = route.query
  void router.replace({ query: rest })
}
function initialMenuDate(): string | undefined {
  const raw = route.query.date
  return typeof raw === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(raw) ? raw : undefined
}
function startMenu() {
  void foodStore.loadMenu(initialMenuDate())
  foodStore.startMenuPolling()
  void loadCookStats()
}
// FD-14：匿名点"一起定菜单"→ /login?next=/recipes?view=menu&intent=addDish ——
// 登录回来编辑板已开且输入框已聚焦，这里补句欢迎语并消费掉 intent
const MENU_INVITE_NEXT = '/recipes?view=menu&intent=addDish'
watch(boardOpen, (open) => {
  if (open && route.query.intent === 'addDish') {
    uiStore.showToast('回来啦，接着点菜～')
    const { intent: _intent, ...rest } = route.query
    void router.replace({ query: rest })
  }
}, { immediate: true })
watch(() => auth.canKitchen, (can) => {
  if (can) startMenu()
  else foodStore.stopMenuPolling()
})

// FD-5：今天吃什么·抽卡（纯前端）；抽选池 = 当前筛选结果
const rouletteOpen = ref(false)
const roulettePool = computed(() => dishes.value)
function onRouletteOpen(dish: Dish) {
  rouletteOpen.value = false
  void openDish(dish)
}

// FD-5：Hero 统计只增不跳——RECIPES 本就是全局 totalElements，
// COLLECTIONS/FEATURED 原按当前页 12 条计算、翻页会缩水，改为浏览过程单调累积
const seenCategories = ref(new Set<string>())
const seenFeatured = ref(new Set<number>())

async function onFavorite(dish: Dish) {
  // 乐观 +1，服务端响应为权威值；GET 详情带 5 分钟公共缓存，禁止用其回写计数
  dish.favoriteCount += 1
  try {
    const result = await favoriteDish(dish.slug)
    dish.favoriteCount = result.favoriteCount
  } catch (error) {
    dish.favoriteCount = Math.max(0, dish.favoriteCount - 1)
    const status = (error as { response?: { status?: number } })?.response?.status
    uiStore.showToast(status === 429 ? '爱心点太快啦，休息一分钟再来～' : '爱心没送出去，稍后再试试')
  }
}

const { apply: applyMeta } = usePageMeta()
const { apply: applyLD } = useStructuredData()

watch(selectedDish, (dish) => {
  if (dish) {
    const excerpt = cleanText(dish.summary, 200)
    const image = dish.imageUrl || '/og.png'
    const authorName = createSiteConfig().authorName
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
    ])
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
    })
  }
})

let loadRevision = 0

async function load() {
  const revision = ++loadRevision
  loading.value = true
  loadError.value = ''
  try {
    const cat = categories.value.find(c => c.name === selectedCategory.value)
    const categorySlug = selectedCategory.value === '全部' ? undefined : cat?.slug
    const q = dishQuery.value.trim() || undefined
    const [result, favorites, remoteCategories] = await Promise.all([
      fetchDishes(dishPage.value, dishPageSize, categorySlug, q),
      fetchDishFavorites(0, 5).catch(() => null),
      fetchDishCategories().catch(() => null),
    ])
    if (revision !== loadRevision) return
    if (dishPage.value > 0 && dishPage.value >= result.totalPages) {
      dishPage.value = Math.max(0, result.totalPages - 1)
      return
    }
    dishes.value = result.items
    dishTotal.value = result.totalElements
    dishTotalPages.value = Math.max(1, result.totalPages)
    favoriteBoard.value = favorites?.items ?? []
    if (remoteCategories?.length) {
      categories.value = [{ name: '全部', slug: '' }, ...remoteCategories.map(c => ({ name: c.name, slug: c.slug }))]
    }
    seenCategories.value = new Set([...seenCategories.value, ...result.items.map(dish => dish.category)])
    seenFeatured.value = new Set([...seenFeatured.value, ...result.items.filter(dish => dish.featured).map(dish => dish.id)])
    await openRouteDish()
    await nextTick()
    ready.value = true
    refreshReveals()
  } catch {
    if (revision === loadRevision) {
      loadError.value = '菜谱暂时没有准备好，请稍后再来看看。'
    }
  } finally {
    if (revision === loadRevision) {
      loading.value = false
    }
  }
}

let queryTimer: ReturnType<typeof setTimeout> | undefined
watch(dishQuery, () => {
  clearTimeout(queryTimer)
  queryTimer = setTimeout(() => {
    if (dishPage.value !== 0) {
      dishPage.value = 0
    } else {
      void load()
    }
  }, 300)
})
watch(selectedCategory, () => {
  if (dishPage.value !== 0) {
    dishPage.value = 0
  } else {
    void load()
  }
})
watch(dishPage, () => { void load() })

async function openBySlug(slug: string, event?: Event) {
  let dish = dishes.value.find(item => item.slug === slug)
  if (!dish) {
    try {
      dish = await fetchDish(slug)
    } catch {
      return
    }
  }
  await openDish(dish, event)
}

// FD-4：深链详情缓存——补取的菜不再插进列表头（会顶掉 featured 大卡、污染分页语义）
const detailCache = new Map<string, Dish>()
const detailToken = useRequestToken()

async function openRouteDish() {
  // 每次路由变化先作废在途详情请求：同步命中路径也要让迟到响应失效
  const token = detailToken.next()
  const rawSlug = Array.isArray(route.query.dish) ? route.query.dish[0] : route.query.dish
  const slug = typeof rawSlug === 'string' ? rawSlug.trim() : ''
  if (!slug) {
    if (selectedDish.value) closeDish()
    return
  }
  if (selectedDish.value?.slug === slug) return
  let dish = dishes.value.find(item => item.slug === slug) ?? detailCache.get(slug)
  if (!dish) {
    try {
      const fetched = await fetchDish(slug)
      if (!detailToken.isCurrent(token)) return
      detailCache.set(slug, fetched)
      dish = fetched
    } catch {
      return
    }
  }
  await openDish(dish)
}

async function openDish(dish: Dish, event?: Event) {
  if (event) {
    lastTrigger = event.currentTarget instanceof HTMLElement ? event.currentTarget : null
  }
  selectedDish.value = dish
  if (route.query.dish !== dish.slug) {
    void router.replace({ query: { ...route.query, dish: dish.slug } })
  }
}

function closeDish() {
  selectedDish.value = null
  if (route.query.dish) {
    const { dish: _removed, ...rest } = route.query
    void router.replace({ query: rest })
  }
  nextTick(() => lastTrigger?.focus())
}

watch(() => route.query.dish, () => void openRouteDish())

onMounted(() => {
  load()
  // NF-9（提前执行）：清掉幽灵收藏死代码写下的遗留键
  removeLegacyKey('yubai_dish_favorites')
  if (auth.canKitchen) startMenu()
})
onBeforeUnmount(() => {
  foodStore.stopMenuPolling()
  clearTimeout(queryTimer)
})
</script>

<template>
  <section class="food-stage" :class="{ ready }">
    <div class="food-shell section-wrap">
      <header class="food-hero">
        <div class="food-hero-copy">
          <p class="food-kicker">YUBAI · LIVING COOKBOOK</p>
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
          <template v-else>
            <dl class="food-stats" aria-label="菜谱统计">
              <div><dt>{{ dishTotal.toString().padStart(2, '0') }}</dt><dd>RECIPES</dd></div>
              <div><dt>{{ seenCategories.size }}</dt><dd>COLLECTIONS</dd></div>
              <div><dt>{{ seenFeatured.size }}</dt><dd>FEATURED</dd></div>
            </dl>
            <RouterLink class="menu-invite tap-44" :to="{ path: '/login', query: { next: MENU_INVITE_NEXT } }">
              一起定今天的菜单 →
            </RouterLink>
          </template>
        </div>
      </header>

      <nav class="food-filter" aria-label="菜谱分类">
        <strong>{{ dishes.length }}/{{ dishTotal }} 道家常菜</strong>
        <label class="food-search">
          <input v-model="dishQuery" type="search" placeholder="搜索菜名、食材或分类…">
        </label>
        <div class="food-filter-tabs">
          <button
            v-for="cat in categories"
            :key="cat.name"
            type="button"
            :class="{ active: selectedCategory === cat.name }"
            @click="selectedCategory = cat.name"
          >{{ cat.name }}</button>
        </div>
      </nav>

      <div v-if="loading" class="food-skeleton-grid" aria-label="正在读取菜谱">
        <span v-for="index in 7" :key="index" />
      </div>
      <div v-else-if="loadError" class="food-empty" role="alert">
        <span>THE KITCHEN IS QUIET</span><h2>{{ loadError }}</h2><button type="button" @click="load">重新加载</button>
      </div>
      <template v-else>
        <header class="food-catalog-head">
          <div><span>RECIPE INDEX</span><h2>{{ selectedCategory === '全部' ? '今天，想做点什么？' : `${selectedCategory} · 精选菜谱` }}</h2></div>
          <div class="catalog-aside">
            <p>从一顿简单的饭开始，把日常过得更有滋味。</p>
            <button type="button" class="roulette-trigger tap-44" @click="rouletteOpen = true"><i aria-hidden="true">✦</i>今天吃什么？抽一道</button>
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
              :aria-label="dish.favoriteCount > 0
                ? `查看${dish.name}的食材和做法，已被点亮 ${dish.favoriteCount} 次`
                : `查看${dish.name}的食材和做法`"
              @click="openDish(dish, $event)"
            >
              <span class="dish-media">
                <img :src="dish.imageUrl" :alt="dish.imageAlt" loading="lazy">
                <span class="dish-shade" />
                <span class="dish-topline"><small>{{ dish.category }}</small><small>★ {{ dish.rating.toFixed(1) }}</small></span>
                <span v-if="dish.favoriteCount > 0" class="dish-hearts" aria-hidden="true">♥ {{ dish.favoriteCount }}</span>
                <span class="dish-index">{{ String(index + 1).padStart(2, '0') }}</span>
              </span>
              <span class="dish-copy">
                <small>{{ dish.prepMinutes }} 分钟 · {{ dish.difficulty }} · {{ dish.ingredients.length }} 种食材 · {{ dish.baseServings ?? 2 }} 人份</small>
                <strong>{{ dish.name }}</strong>
                <span>{{ dish.summary }}</span>
                <u>打开这份菜谱 <b>↗</b></u>
              </span>
            </button>
          </div>
        </Transition>
      </template>
      <nav v-if="dishTotalPages > 1" class="pagination" aria-label="公开菜谱分页"><button type="button" :disabled="dishPage <= 0" @click="dishPage -= 1">上一页</button><span>{{ dishPage + 1 }} / {{ dishTotalPages }}</span><button type="button" :disabled="dishPage >= dishTotalPages - 1" @click="dishPage += 1">下一页</button></nav>

      <section v-if="showRanking" class="food-ranking" aria-labelledby="food-ranking-title">
        <header class="ranking-head">
          <div><p>TASTE CLUB · TOP 05</p><h2 id="food-ranking-title">美食爱好榜</h2></div>
          <p>{{ cookMode ? '按你们真实做过的次数排名——最常端上桌的，才是真爱。' : '按真实点亮次数排名，记录此刻最让人惦记的家常味道。' }}</p>
        </header>
        <div class="ranking-board">
          <button
            v-if="boardRows[0]"
            class="ranking-champion"
            type="button"
            :aria-label="`查看榜首${boardRows[0].name}，${boardRows[0].primaryLabel}`"
            @click="openBySlug(boardRows[0].slug, $event)"
          >
            <span class="champion-media"><img :src="boardRows[0].imageUrl" :alt="boardRows[0].name" loading="lazy"><i /><b>NO. 01</b></span>
            <span class="champion-copy"><small>{{ cookMode ? '你们最常做的' : '本期味蕾冠军' }}</small><strong>{{ boardRows[0].name }}</strong><span>{{ boardRows[0].secondary ?? boardRows[0].primaryLabel }}</span><u>查看冠军菜谱 ↗</u></span>
            <span class="score-orbit"><b>{{ boardRows[0].primary }}</b><small>{{ cookMode ? '次' : '点亮' }}</small></span>
          </button>
          <ol class="ranking-list">
            <li
              v-for="(item, index) in boardRows.slice(1)"
              :key="item.slug"
              :style="{ '--rank-delay': `${index * 80 + 160}ms` }"
            >
              <button type="button" :aria-label="`查看${item.name}，${item.primaryLabel}`" @click="openBySlug(item.slug, $event)">
                <span class="rank-number">{{ String(index + 2).padStart(2, '0') }}</span>
                <img :src="item.imageUrl" :alt="item.name" loading="lazy">
                <span class="rank-info"><strong>{{ item.name }}</strong><small>{{ item.primaryLabel }}<template v-if="item.secondary"> · {{ item.secondary }}</template></small><span class="rank-meter"><i :style="{ width: `${Math.round(item.primary / championCount * 100)}%` }" /></span></span>
                <b class="rank-score">{{ item.primary }}</b><span class="rank-arrow">↗</span>
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
  >✦</button>

  <TodayMenuBoard v-if="boardOpen" :dishes="dishes" @close="closeBoard" />
  <DishRoulette v-if="rouletteOpen" :dishes="roulettePool" @close="rouletteOpen = false" @open="onRouletteOpen" />
  <DishPanel :dish="selectedDish" :can-check-in="auth.canKitchen" @close="closeDish" @favorite="onFavorite" @check-in="onDishCheckIn" />
</template>

<style scoped>
.food-stage { --food-bg: transparent; --food-panel: var(--surface-solid); --food-line: var(--line); --food-text: var(--ink); --food-muted: var(--muted); width: 100%; min-width: 0; min-height: 100vh; overflow: clip; color: var(--food-text); background: var(--food-bg); }
.food-stage, .food-stage * { box-sizing: border-box; }
.food-shell { padding-top: clamp(76px, 9vw, 124px); padding-bottom: 110px; }
.food-hero { position: relative; display: grid; grid-template-columns: minmax(0, 1fr) minmax(280px, 360px); gap: clamp(48px, 7vw, 96px); align-items: end; min-height: 390px; padding: 0 0 64px; }
.food-hero::before { content: "04 / FOOD NOTES"; position: absolute; top: 0; right: 0; color: var(--faint); font: 500 10px/1 ui-monospace, "SF Mono", Consolas, monospace; letter-spacing: .16em; }
.food-hero-copy { max-width: 760px; opacity: 0; transform: translateY(18px); }
.ready .food-hero-copy { animation: food-hero-in 1s cubic-bezier(.16,1,.3,1) .08s forwards; }
.food-kicker { margin: 0 0 30px; display: flex; align-items: center; gap: 12px; color: var(--faint); font: 600 .68rem/1 ui-monospace, "SF Mono", Consolas, monospace; letter-spacing: .16em; }
.food-kicker::before { content: ""; width: 24px; height: 1px; background: var(--accent); }
.food-hero h1 { margin: 0; display: flex; flex-direction: column; align-items: flex-start; color: var(--ink); line-height: 1; letter-spacing: -.045em; }
.food-hero h1 span { font: 620 clamp(2.7rem, 5.5vw, 5.4rem)/1.04 "SF Pro Display", "Segoe UI", "PingFang SC", system-ui, sans-serif; }
.food-hero h1 em { position: relative; margin-top: 10px; color: var(--accent); font: 400 clamp(3.5rem, 7vw, 6.8rem)/.96 Georgia, "Songti SC", "STSong", serif; font-style: normal; letter-spacing: -.06em; }
.food-hero h1 em::after { content: ""; position: absolute; right: -28px; bottom: 8px; width: 18px; aspect-ratio: 1; border: 1px solid var(--accent); border-radius: 50%; opacity: .65; }
.food-hero-copy > p:last-child { max-width: 590px; margin: 34px 0 0; color: var(--food-muted); font-size: clamp(.98rem, 1.25vw, 1.12rem); line-height: 1.8; }
.food-hero-side { display: flex; flex-direction: column; justify-content: flex-end; gap: 12px; min-width: 0; }
.menu-invite { display: inline-flex; align-items: center; justify-content: center; gap: 6px; padding: 10px 16px; color: var(--accent); font-size: .8rem; font-weight: 600; text-decoration: none; background: color-mix(in srgb, var(--accent-soft) 55%, transparent); border: 1px dashed color-mix(in srgb, var(--accent) 40%, var(--line)); border-radius: 999px; transition: background .25s, color .25s, border-color .25s, transform .25s var(--ease); }
.menu-invite:hover { color: #fff; background: var(--accent); border-style: solid; transform: translateY(-2px); }
.menu-invite:focus-visible { outline: 2px solid #0071e3; outline-offset: 3px; }
.food-stats { display: grid; grid-template-columns: repeat(3, 1fr); min-width: 310px; margin: 0; padding: 24px 18px; border: 1px solid var(--food-line); border-radius: 22px; background: color-mix(in srgb, var(--surface) 72%, transparent); box-shadow: var(--shadow-sm); backdrop-filter: blur(16px); opacity: 0; }
.ready .food-stats { animation: food-fade-in .9s ease .32s forwards; }
.food-stats div { padding: 0 16px; border-left: 1px solid var(--food-line); }
.food-stats div:first-child { border-left: 0; }
.food-stats dt { font-size: 1.7rem; font-weight: 600; letter-spacing: -.04em; }
.food-stats dd { margin: 7px 0 0; color: var(--faint); font: 500 .58rem/1 ui-monospace, "SF Mono", Consolas, monospace; letter-spacing: .13em; }
.food-filter { position: sticky; z-index: 20; top: 74px; display: flex; justify-content: space-between; align-items: center; gap: 28px; margin: 0 -12px 36px; padding: 14px 12px; background: color-mix(in srgb, var(--paper) 88%, transparent); border-top: 1px solid var(--food-line); border-bottom: 1px solid var(--food-line); backdrop-filter: blur(18px) saturate(130%); }
.food-filter strong { white-space: nowrap; font-size: .8rem; font-weight: 520; }
.food-filter-tabs { display: flex; gap: 4px; overflow-x: auto; scrollbar-width: none; }
.food-filter button { flex: 0 0 auto; padding: 8px 13px; color: var(--food-muted); background: transparent; border: 0; border-radius: 8px; font-size: .78rem; cursor: pointer; transition: color .15s, background .15s, transform .15s; }
.food-filter button:hover { color: var(--food-text); }
.food-filter button:active { transform: scale(.97); }
.food-filter button.active { color: var(--paper); background: var(--ink); box-shadow: 0 4px 14px rgba(40,32,22,.1); }
.food-search { position: relative; flex-shrink: 0; }
.food-search input { width: 180px; padding: 7px 12px; border: 1px solid var(--food-line); border-radius: 8px; background: transparent; color: var(--food-text); font-size: .78rem; outline: none; transition: border-color .2s; }
.food-search input:focus { border-color: var(--accent); }
.food-search input::placeholder { color: var(--food-muted); }
.food-catalog-head { display: flex; justify-content: space-between; align-items: end; gap: 36px; margin: 52px 0 26px; }
.food-catalog-head span { color: var(--accent); font: 650 .64rem/1 ui-monospace, "SF Mono", Consolas, monospace; letter-spacing: .16em; }
.food-catalog-head h2 { margin: 9px 0 0; color: var(--ink); font: 400 clamp(2rem, 4vw, 3.4rem)/1.08 Georgia, "Songti SC", serif; letter-spacing: -.045em; }
.food-catalog-head p { max-width: 290px; margin: 0 0 4px; color: var(--food-muted); font-size: .88rem; line-height: 1.7; }
.dish-grid { display: grid; width: 100%; min-width: 0; grid-template-columns: repeat(12, minmax(0, 1fr)); grid-auto-flow: dense; gap: clamp(18px, 2vw, 28px); }
.dish-card { position: relative; grid-column: span 4; display: grid; grid-template-rows: auto 1fr; width: 100%; min-width: 0; padding: 9px; overflow: hidden; appearance: none; color: var(--food-text); text-align: left; background: color-mix(in srgb, var(--food-panel) 94%, transparent); border: 1px solid color-mix(in srgb, var(--food-line) 86%, var(--accent) 14%); border-radius: 24px 24px 8px 24px; box-shadow: 0 14px 38px rgba(83, 54, 61, .075), 0 2px 8px rgba(83, 54, 61, .035); cursor: pointer; opacity: 0; transform: translateY(20px); animation: dish-card-in .65s cubic-bezier(.16,1,.3,1) var(--card-delay) forwards; transition: transform .45s cubic-bezier(.16,1,.3,1), box-shadow .45s, border-color .3s; }
.dish-card.featured { grid-column: 1 / -1; grid-row: auto; grid-template-columns: minmax(0, 1.42fr) minmax(320px, .68fr); grid-template-rows: clamp(410px, 36vw, 470px); }
.dish-media { position: relative; display: block; min-height: 244px; overflow: hidden; border-radius: 17px 17px 5px 17px; background: var(--accent-soft); }
.dish-card.featured .dish-media { min-height: 0; }
.dish-card img { position: absolute; inset: 0; display: block; width: 100%; height: 100%; object-fit: cover; object-position: center; filter: saturate(.92) contrast(1.01); transform: scale(1.001); transition: transform .75s cubic-bezier(.16,1,.3,1), filter .35s; }
.dish-shade { position: absolute; inset: 0; background: linear-gradient(180deg, rgba(15,13,14,.08), transparent 55%, rgba(15,13,14,.2)); transition: opacity .35s; }
.dish-topline { position: absolute; inset: 15px 15px auto; display: flex; justify-content: space-between; gap: 12px; }
.dish-topline small { padding: 7px 10px; color: #fff; background: rgba(30,25,27,.46); border: 1px solid rgba(255,255,255,.22); border-radius: 999px; box-shadow: 0 5px 18px rgba(0,0,0,.1); font-size: .64rem; letter-spacing: .04em; backdrop-filter: blur(14px) saturate(130%); }
.dish-index { position: absolute; right: 16px; bottom: 12px; color: rgba(255,255,255,.82); font: 400 2.45rem/1 Georgia, serif; text-shadow: 0 2px 15px rgba(0,0,0,.3); }
/* FD-3：只读点亮徽章（可点爱心只在详情抽屉里，卡片保持单一 button 语义） */
.dish-hearts { position: absolute; left: 15px; bottom: 14px; padding: 6px 10px; color: #ffd5de; background: rgba(30,25,27,.46); border: 1px solid rgba(255,255,255,.22); border-radius: 999px; font-size: .64rem; letter-spacing: .04em; backdrop-filter: blur(14px) saturate(130%); }
.dish-copy { position: relative; display: flex; min-width: 0; flex-direction: column; align-items: flex-start; padding: 22px 17px 17px; }
.dish-card.featured .dish-copy { justify-content: center; padding: clamp(36px, 5vw, 68px); background: radial-gradient(circle at 100% 0, color-mix(in srgb, var(--accent-soft) 78%, transparent), transparent 48%); }
.dish-copy::before { content: ""; position: absolute; top: 0; left: 18px; width: 34px; height: 2px; border-radius: 99px; background: var(--accent); opacity: .58; }
.dish-card.featured .dish-copy::before { top: clamp(42px, 5vw, 68px); left: clamp(36px, 5vw, 68px); width: 46px; }
.dish-copy > small { color: var(--faint); font-size: .64rem; font-weight: 650; letter-spacing: .08em; }
.dish-copy strong { margin-top: 10px; color: var(--ink); font: 520 clamp(1.35rem, 2vw, 1.8rem)/1.18 Georgia, "Songti SC", serif; letter-spacing: -.035em; }
.dish-card.featured .dish-copy strong { margin-top: 14px; font-size: clamp(2.15rem, 3.6vw, 3.8rem); }
.dish-copy > span { display: -webkit-box; max-width: 580px; margin-top: 11px; overflow: hidden; color: var(--food-muted); font-size: .86rem; line-height: 1.7; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.dish-card.featured .dish-copy > span { font-size: .95rem; -webkit-line-clamp: 4; }
.dish-copy u { display: flex; align-items: center; justify-content: space-between; width: 100%; margin-top: auto; padding-top: 22px; color: var(--accent); font-size: .72rem; font-weight: 700; text-decoration: none; letter-spacing: .04em; }
.dish-copy u b { display: grid; place-items: center; width: 29px; height: 29px; border: 1px solid color-mix(in srgb, var(--accent) 36%, var(--food-line)); border-radius: 50%; font-size: .8rem; transition: color .3s, background .3s, transform .35s; }
.dish-card:hover, .dish-card:focus-visible { border-color: color-mix(in srgb, var(--accent) 36%, var(--food-line)); box-shadow: 0 24px 60px rgba(83, 54, 61, .14), 0 5px 14px rgba(83, 54, 61, .06); transform: translateY(-7px); }
.dish-card:hover img, .dish-card:focus-visible img { filter: saturate(1.06) contrast(1.03); transform: scale(1.055); }
.dish-card:hover .dish-copy u b, .dish-card:focus-visible .dish-copy u b { color: #fff; background: var(--accent); transform: rotate(8deg); }
.dish-card:focus-visible { outline: 2px solid #0071e3; outline-offset: 4px; }
/* FD-5：指针跟随光晕（App.vue pointermove 写 --mx/--my）；不做 3D 倾斜避免与 hover 位移打架 */
.dish-card { --mx: 50%; --my: 50%; }
.dish-card::after { content: ""; position: absolute; inset: 0; z-index: 1; border-radius: inherit; opacity: 0; pointer-events: none; background: radial-gradient(420px circle at var(--mx) var(--my), color-mix(in srgb, var(--accent) 10%, transparent), transparent 58%); transition: opacity .45s; }
.dish-card.is-pointed::after { opacity: 1; }
/* FD-5：抽卡入口 */
.catalog-aside { display: flex; flex-direction: column; align-items: flex-end; gap: 12px; }
.catalog-aside p { max-width: 290px; margin: 0; color: var(--food-muted); font-size: .88rem; line-height: 1.7; text-align: right; }
.roulette-trigger { display: inline-flex; align-items: center; gap: 8px; padding: 10px 18px; color: var(--accent); font-size: .82rem; font-weight: 600; background: color-mix(in srgb, var(--accent-soft) 60%, transparent); border: 1px solid color-mix(in srgb, var(--accent) 34%, var(--food-line)); border-radius: 999px; cursor: pointer; transition: transform .25s var(--ease), background .25s, color .25s, box-shadow .25s; }
.roulette-trigger i { font-style: normal; }
.roulette-trigger:hover { color: #fff; background: var(--accent); transform: translateY(-2px); box-shadow: 0 10px 26px color-mix(in srgb, var(--accent) 32%, transparent); }
.roulette-trigger:focus-visible { outline: 2px solid #0071e3; outline-offset: 3px; }
.roulette-fab { position: fixed; z-index: 1200; left: 18px; bottom: 22px; display: none; place-items: center; width: 52px; height: 52px; color: #fff; font-size: 1.25rem; background: var(--accent); border: 0; border-radius: 50%; box-shadow: 0 14px 34px color-mix(in srgb, var(--accent) 45%, transparent); cursor: pointer; }
.roulette-fab:focus-visible { outline: 2px solid #0071e3; outline-offset: 3px; }
/* FD-5：搜索/筛选空态 */
.food-no-result { display: grid; place-items: start; gap: 8px; min-height: 200px; padding: 44px 36px; border: 1px dashed color-mix(in srgb, var(--accent) 26%, var(--food-line)); border-radius: 24px 24px 8px 24px; background: color-mix(in srgb, var(--accent-soft) 28%, transparent); }
.food-no-result span { color: var(--faint); font: 650 .64rem/1 ui-monospace, "SF Mono", Consolas, monospace; letter-spacing: .16em; }
.food-no-result p { margin: 0; color: var(--food-muted); font-size: .95rem; line-height: 1.7; }
.dish-filter-enter-active { transition: opacity .28s ease, transform .4s cubic-bezier(.16,1,.3,1); }
.dish-filter-leave-active { transition: opacity .2s ease, transform .26s ease; }
.dish-filter-enter-from { opacity: 0; transform: translateY(14px); }
.dish-filter-leave-to { opacity: 0; transform: translateY(-7px) scale(.992); }
.food-ranking { margin-top: clamp(80px, 10vw, 140px); padding: clamp(28px, 4.2vw, 58px); overflow: hidden; border: 1px solid color-mix(in srgb, var(--accent) 20%, var(--food-line)); border-radius: 34px 34px 9px 34px; background: linear-gradient(145deg, color-mix(in srgb, var(--surface-solid) 91%, #f8e6e9), color-mix(in srgb, var(--surface-solid) 97%, transparent)); box-shadow: 0 30px 80px rgba(84,51,60,.09); }
.ranking-head { display: flex; justify-content: space-between; align-items: end; gap: 36px; margin-bottom: 32px; }
.ranking-head > div > p { margin: 0; color: var(--accent); font: 650 .64rem/1 ui-monospace, "SF Mono", Consolas, monospace; letter-spacing: .17em; }
.ranking-head h2 { margin: 10px 0 0; color: var(--ink); font: 400 clamp(2.5rem, 5vw, 4.8rem)/.98 Georgia, "Songti SC", serif; letter-spacing: -.055em; }
.ranking-head > p { max-width: 340px; margin: 0 0 5px; color: var(--food-muted); font-size: .86rem; line-height: 1.75; }
.ranking-board { display: grid; grid-template-columns: minmax(340px, .9fr) minmax(0, 1.1fr); gap: clamp(18px, 2.6vw, 38px); }
.ranking-champion { position: relative; display: grid; min-width: 0; min-height: 530px; padding: 0; overflow: hidden; isolation: isolate; color: #fff; text-align: left; background: #282324; border: 0; border-radius: 25px 25px 7px 25px; box-shadow: 0 22px 55px rgba(58,32,39,.2); cursor: pointer; animation: ranking-champion-in .85s cubic-bezier(.16,1,.3,1) both; }
.champion-media { position: absolute; inset: 0; overflow: hidden; }
.champion-media img { width: 100%; height: 100%; object-fit: cover; filter: saturate(.92) contrast(1.02); transition: transform .9s cubic-bezier(.16,1,.3,1), filter .4s; }
.champion-media i { position: absolute; inset: 0; background: linear-gradient(180deg, rgba(17,13,14,.04) 18%, rgba(17,13,14,.2) 48%, rgba(17,13,14,.93)); }
.champion-media > b { position: absolute; top: 22px; left: 22px; padding: 8px 11px; border: 1px solid rgba(255,255,255,.3); border-radius: 999px; background: rgba(32,21,24,.35); font: 650 .62rem/1 ui-monospace, Consolas, monospace; letter-spacing: .13em; backdrop-filter: blur(12px); }
.champion-copy { position: relative; z-index: 2; align-self: end; display: flex; flex-direction: column; align-items: flex-start; padding: 34px; }
.champion-copy > small { color: #f3b7c5; font-size: .7rem; letter-spacing: .12em; }
.champion-copy > strong { margin-top: 9px; font: 500 clamp(2.3rem, 4vw, 4.1rem)/1.02 Georgia, "Songti SC", serif; letter-spacing: -.055em; }
.champion-copy > span { margin-top: 11px; color: rgba(255,255,255,.7); font-size: .78rem; }
.champion-copy > u { margin-top: 24px; color: #ffd5de; font-size: .72rem; font-weight: 700; text-decoration: none; }
.score-orbit { position: absolute; z-index: 3; top: 20px; right: 20px; display: grid; place-items: center; width: 78px; height: 78px; border: 1px solid rgba(255,255,255,.42); border-radius: 50%; background: rgba(30,20,22,.32); box-shadow: inset 0 0 0 6px rgba(255,255,255,.055); backdrop-filter: blur(14px); transition: transform .5s cubic-bezier(.16,1,.3,1); }
.score-orbit::after { content: ""; position: absolute; inset: -5px; border: 1px dashed rgba(255,213,222,.45); border-radius: inherit; animation: score-spin 18s linear infinite; }
.score-orbit b { font: 500 1.5rem/1 Georgia, serif; }
.score-orbit small { margin-top: -8px; color: rgba(255,255,255,.62); font-size: .48rem; letter-spacing: .14em; }
.ranking-champion:hover .champion-media img, .ranking-champion:focus-visible .champion-media img { filter: saturate(1.08) contrast(1.04); transform: scale(1.065); }
.ranking-champion:hover .score-orbit, .ranking-champion:focus-visible .score-orbit { transform: scale(1.08) rotate(-6deg); }
.ranking-list { display: grid; align-content: stretch; gap: 10px; margin: 0; padding: 0; list-style: none; counter-reset: taste-rank 1; }
.ranking-list li { min-width: 0; opacity: 0; transform: translateX(24px); animation: ranking-row-in .65s cubic-bezier(.16,1,.3,1) var(--rank-delay) forwards; }
.ranking-list button { position: relative; display: grid; width: 100%; min-height: 124px; grid-template-columns: 46px 96px minmax(0,1fr) auto 34px; align-items: center; gap: 16px; padding: 12px 16px; overflow: hidden; color: var(--food-text); text-align: left; background: color-mix(in srgb, var(--surface-solid) 82%, transparent); border: 1px solid color-mix(in srgb, var(--food-line) 82%, var(--accent) 18%); border-radius: 19px 19px 5px 19px; cursor: pointer; transition: transform .4s cubic-bezier(.16,1,.3,1), background .3s, border-color .3s, box-shadow .4s; }
.ranking-list button::before { content: ""; position: absolute; inset: 0; background: linear-gradient(100deg, transparent 25%, color-mix(in srgb, var(--accent-soft) 62%, transparent), transparent 72%); opacity: 0; transform: translateX(-70%); transition: opacity .25s, transform .65s cubic-bezier(.16,1,.3,1); }
.rank-number { position: relative; color: color-mix(in srgb, var(--accent) 68%, var(--food-muted)); font: 400 1.55rem/1 Georgia, serif; }
.ranking-list img { position: relative; width: 96px; height: 92px; object-fit: cover; border-radius: 13px 13px 3px 13px; filter: saturate(.88); transition: transform .55s cubic-bezier(.16,1,.3,1), filter .3s; }
.rank-info { position: relative; display: flex; min-width: 0; flex-direction: column; }
.rank-info strong { overflow: hidden; color: var(--ink); font: 500 1.15rem/1.25 Georgia, "Songti SC", serif; text-overflow: ellipsis; white-space: nowrap; }
.rank-info small { margin-top: 7px; color: var(--food-muted); font-size: .68rem; }
.rank-meter { width: min(160px, 90%); height: 3px; margin-top: 13px; overflow: hidden; border-radius: 99px; background: color-mix(in srgb, var(--accent) 12%, var(--food-line)); }
.rank-meter i { display: block; height: 100%; border-radius: inherit; background: linear-gradient(90deg, #df91a5, var(--accent)); transform-origin: left; animation: rank-meter-grow 1.1s cubic-bezier(.16,1,.3,1) calc(var(--rank-delay) + 180ms) both; }
.rank-score { position: relative; color: var(--ink); font: 500 1rem/1 Georgia, serif; }
.rank-arrow { position: relative; display: grid; place-items: center; width: 30px; height: 30px; border: 1px solid var(--food-line); border-radius: 50%; color: var(--accent); transition: color .3s, background .3s, transform .35s; }
.ranking-list button:hover, .ranking-list button:focus-visible { border-color: color-mix(in srgb, var(--accent) 38%, var(--food-line)); background: var(--surface-solid); box-shadow: 0 15px 36px rgba(83,54,61,.1); transform: translateX(8px); }
.ranking-list button:hover::before, .ranking-list button:focus-visible::before { opacity: 1; transform: translateX(70%); }
.ranking-list button:hover img, .ranking-list button:focus-visible img { filter: saturate(1.08); transform: scale(1.06) rotate(-1deg); }
.ranking-list button:hover .rank-arrow, .ranking-list button:focus-visible .rank-arrow { color: #fff; background: var(--accent); transform: rotate(9deg); }
.ranking-champion:focus-visible, .ranking-list button:focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; }
/* FD-5：骨架屏改为真实网格镜像（首块 featured 整行 + span-4 卡），消除加载完成时的布局跳变 */
.food-skeleton-grid { display: grid; grid-template-columns: repeat(12, minmax(0, 1fr)); gap: clamp(18px, 2vw, 28px); }
.food-skeleton-grid span { grid-column: span 4; min-height: 320px; border: 1px solid var(--food-line); border-radius: 24px 24px 8px 24px; background: linear-gradient(110deg, var(--surface-solid) 20%, color-mix(in srgb, var(--surface-solid) 82%, var(--accent-soft)) 40%, var(--surface-solid) 60%); background-size: 220% 100%; animation: skeleton 1.5s linear infinite; }
.food-skeleton-grid span:first-child { grid-column: 1 / -1; min-height: clamp(410px, 36vw, 470px); }
.food-empty { display: grid; place-items: start; min-height: 340px; padding: 60px; background: var(--food-panel); border: 1px solid var(--food-line); border-radius: 10px; }
.food-empty span { color: var(--food-muted); font-size: .68rem; letter-spacing: .15em; }
.food-empty h2 { max-width: 600px; margin: 18px 0; font-size: 2.4rem; font-weight: 520; }
.food-empty button { padding: 10px 14px; color: var(--paper); background: var(--ink); border: 0; border-radius: 8px; cursor: pointer; }
@keyframes food-hero-in { to { opacity: 1; transform: translateY(0); } }
@keyframes food-fade-in { to { opacity: 1; } }
@keyframes dish-card-in { to { opacity: 1; transform: translateY(0); } }
@keyframes ranking-champion-in { from { opacity: 0; transform: translateY(24px) scale(.985); } to { opacity: 1; transform: none; } }
@keyframes ranking-row-in { to { opacity: 1; transform: translateX(0); } }
@keyframes rank-meter-grow { from { transform: scaleX(0); } to { transform: scaleX(1); } }
@keyframes score-spin { to { transform: rotate(360deg); } }
@keyframes skeleton { to { background-position-x: -220%; } }
@media (max-width: 980px) { .food-hero { grid-template-columns: 1fr; gap: 42px; min-height: 0; } .food-hero::before { display: none; } .food-stats { max-width: 420px; } .dish-card { grid-column: span 6; } .dish-card.featured { grid-column: 1 / -1; grid-template-columns: minmax(0, 1.18fr) minmax(280px, .82fr); grid-template-rows: 400px; } .ranking-board { grid-template-columns: 1fr; } .ranking-champion { min-height: 460px; } .ranking-list { grid-template-columns: repeat(2, minmax(0,1fr)); } .ranking-list button { min-height: 112px; grid-template-columns: 38px 74px minmax(0,1fr) auto; gap: 11px; } .ranking-list img { width: 74px; height: 78px; } .rank-arrow { display: none; } }
@media (max-width: 760px) { .dish-card.featured { grid-template-columns: 1fr; grid-template-rows: 340px auto; } .dish-card.featured .dish-copy { justify-content: flex-start; padding: 34px 26px 26px; } .dish-card.featured .dish-copy::before { top: 0; left: 26px; } }
@media (max-width: 640px) { .food-hero-side { order: -1; } .food-shell { padding-top: 70px; } .food-hero { padding-bottom: 42px; } .food-hero h1 span { font-size: clamp(2.35rem, 11vw, 3.2rem); } .food-hero h1 em { margin-top: 8px; font-size: clamp(3.15rem, 15vw, 4.5rem); letter-spacing: -.07em; } .food-hero h1 em::after { right: -16px; bottom: 5px; width: 12px; } .food-stats { min-width: 0; width: 100%; padding: 20px 8px; } .food-stats div { padding: 0 10px; } .food-filter { position: static; align-items: flex-start; flex-direction: column; gap: 10px; } .roulette-fab { display: grid; } .catalog-aside { align-items: flex-start; } .catalog-aside p { text-align: left; } .food-filter > div { width: 100%; } .food-catalog-head { align-items: flex-start; flex-direction: column; gap: 14px; margin-top: 38px; } .food-catalog-head p { max-width: none; } .dish-grid { display: flex; flex-direction: column; gap: 18px; } .dish-card, .dish-card.featured { display: grid; min-height: 0; grid-template-columns: 1fr; grid-template-rows: auto 1fr; border-radius: 20px 20px 7px 20px; } .dish-media, .dish-card.featured .dish-media { min-height: 280px; } .dish-card.featured .dish-copy { justify-content: flex-start; padding: 28px 20px 20px; } .dish-card.featured .dish-copy::before { top: 0; left: 20px; } .dish-card.featured .dish-copy strong { font-size: 2rem; } .dish-copy > span, .dish-card.featured .dish-copy > span { font-size: .86rem; -webkit-line-clamp: 3; } .food-ranking { margin-top: 64px; padding: 22px 14px; border-radius: 24px 24px 7px 24px; } .ranking-head { align-items: flex-start; flex-direction: column; gap: 13px; margin-bottom: 22px; padding-inline: 5px; } .ranking-head > p { max-width: none; } .ranking-champion { min-height: 410px; } .champion-copy { padding: 25px; } .score-orbit { width: 68px; height: 68px; } .ranking-list { grid-template-columns: 1fr; } .ranking-list button { grid-template-columns: 34px 72px minmax(0,1fr) auto; padding: 10px; } .ranking-list img { width: 72px; height: 74px; } .rank-info strong { font-size: 1rem; } .rank-meter { width: 92%; } }
@media (hover: none) { .dish-card:hover { transform: none; } }
@media (prefers-reduced-motion: reduce) { .food-hero-copy, .food-stats, .dish-card, .ranking-champion, .ranking-list li, .rank-meter i { opacity: 1; transform: none; animation: none !important; } .dish-card img, .dish-copy, .dish-copy > span, .dish-copy u { transition-duration: .01ms !important; } .food-skeleton-grid span, .score-orbit::after { animation: none; } }
</style>
