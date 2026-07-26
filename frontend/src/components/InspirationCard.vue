<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { fetchDailyQuotes } from '../api/content'
import { useUiStore } from '../stores/uiStore'

interface Quote {
  id: string | number
  content: string
  author: string
  category: string
}

const ui = useUiStore()

const fallbackQuotes: Quote[] = [
  { id: '1', content: '代码是写给人看的，只是顺便让机器能够运行。', author: '《SICP》', category: '极客哲学' },
  { id: '2', content: '留白，不是空无，而是给灵感与思考呼吸的空间。', author: '余白手记', category: '设计美学' },
  { id: '3', content: '人间烟火气，最抚凡人心。一道好菜是时间的艺术。', author: '美食随笔', category: '生活哲学' },
  { id: '4', content: '保持简单，保持专注。复杂是设计的死敌。', author: 'Dieter Rams', category: '设计原则' },
  { id: '5', content: '终身学习的意义，在于不断重构自己的认知地图。', author: '学习笔记', category: '认知跃迁' },
]

const quotes = ref<Quote[]>(fallbackQuotes)
const currentIndex = ref(0)
const isTearing = ref(false)

const currentQuote = computed(() => quotes.value[currentIndex.value] || fallbackQuotes[0])

const todayDate = computed(() => {
  const d = new Date()
  const months = ['一月', '二月', '三月', '四月', '五月', '六月', '七月', '八月', '九月', '十月', '十一月', '十二月']
  const days = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六']
  return {
    year: d.getFullYear(),
    monthName: months[d.getMonth()],
    dayNumber: String(d.getDate()).padStart(2, '0'),
    weekday: days[d.getDay()],
  }
})

// NF-7：改走统一 api 层，不再组件内裸 fetch
async function fetchDailyQuote() {
  try {
    const data = await fetchDailyQuotes()
    if (Array.isArray(data) && data.length > 0) {
      quotes.value = data
    } else if (data && !Array.isArray(data) && data.content) {
      quotes.value = [data, ...fallbackQuotes]
    }
  } catch {
    // fallback
  }
}

function nextQuote() {
  if (isTearing.value) return
  isTearing.value = true
  setTimeout(() => {
    currentIndex.value = (currentIndex.value + 1) % quotes.value.length
    isTearing.value = false
  }, 450)
}

function copyQuote() {
  const text = `“${currentQuote.value.content}” —— ${currentQuote.value.author}`
  void navigator.clipboard.writeText(text)
  ui.showToast('金句已复制到剪贴板 ✨')
}

onMounted(() => {
  void fetchDailyQuote()
})
</script>

<template>
  <div class="inspiration-calendar-widget">
    <div class="calendar-card" :class="{ 'is-tearing': isTearing }">
      <!-- Binder Rings Top Header -->
      <header class="calendar-rings">
        <span class="ring" /><span class="ring" /><span class="ring" /><span class="ring" />
      </header>

      <div class="calendar-header-strip">
        <span class="calendar-year">{{ todayDate.year }} · {{ todayDate.monthName }}</span>
        <span class="calendar-category">✦ {{ currentQuote.category }}</span>
      </div>

      <!-- Main Calendar Date Body -->
      <div class="calendar-body">
        <div class="date-large-number">{{ todayDate.dayNumber }}</div>
        <div class="date-weekday">{{ todayDate.weekday }}</div>

        <!-- Quote Content Box -->
        <blockquote class="quote-content">
          <p>“{{ currentQuote.content }}”</p>
          <cite>—— {{ currentQuote.author }}</cite>
        </blockquote>
      </div>

      <!-- Action Footer -->
      <footer class="calendar-actions">
        <button type="button" class="calendar-btn tear-btn" :disabled="isTearing" @click="nextQuote">
          <i>✂</i> 撕下一页 · 换灵感
        </button>
        <button type="button" class="calendar-btn copy-btn" @click="copyQuote">
          <i>📋</i> 复制
        </button>
      </footer>
    </div>
  </div>
</template>

<style scoped>
.inspiration-calendar-widget {
  position: relative;
  perspective: 1000px;
}

.calendar-card {
  position: relative;
  width: 100%;
  max-width: 340px;
  margin: 0 auto;
  border-radius: 20px;
  background: var(--surface-solid);
  border: 1px solid var(--line-strong);
  box-shadow: 0 16px 40px rgba(0, 0, 0, 0.08);
  overflow: hidden;
  transition: transform 0.45s cubic-bezier(0.16, 1, 0.3, 1), opacity 0.45s;
  transform-origin: top center;
}
.calendar-card.is-tearing {
  animation: tear-off-animation 0.45s ease-in forwards;
}
@keyframes tear-off-animation {
  0% { transform: rotate(0deg) translateY(0); opacity: 1; }
  50% { transform: rotate(-8deg) translateY(20px) scale(0.96); opacity: 0.6; }
  100% { transform: rotate(-15deg) translateY(80px) scale(0.9); opacity: 0; }
}

.calendar-rings {
  display: flex;
  justify-content: space-around;
  padding: 10px 24px 0;
  background: var(--surface);
}
.ring {
  width: 12px;
  height: 18px;
  border-radius: 6px;
  background: linear-gradient(180deg, #d1d5db 0%, #9ca3af 100%);
  box-shadow: inset 0 2px 4px rgba(0,0,0,0.3);
}

.calendar-header-strip {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 20px;
  background: var(--surface);
  border-bottom: 1px dashed var(--line);
  font-size: 12px;
  color: var(--muted);
}
.calendar-category {
  color: var(--accent);
  font-weight: 600;
  font-size: 11px;
}

.calendar-body {
  padding: 24px 20px 16px;
  text-align: center;
}
.date-large-number {
  font: 800 64px/1 Georgia, 'Times New Roman', serif;
  color: var(--accent);
  letter-spacing: -0.04em;
}
.date-weekday {
  font-size: 13px;
  color: var(--muted);
  margin-top: 4px;
  margin-bottom: 18px;
  letter-spacing: 0.1em;
}

.quote-content {
  margin: 0;
  padding: 14px 16px;
  border-radius: 14px;
  background: var(--surface);
  border: 1px solid var(--line);
  text-align: left;
}
.quote-content p {
  margin: 0 0 8px;
  font-size: 14px;
  line-height: 1.6;
  color: var(--ink);
  font-style: italic;
}
.quote-content cite {
  display: block;
  text-align: right;
  font-size: 12px;
  color: var(--muted);
  font-style: normal;
}

.calendar-actions {
  display: flex;
  gap: 8px;
  padding: 14px 20px 18px;
}
.calendar-btn {
  flex: 1;
  padding: 8px 12px;
  border-radius: 12px;
  font-size: 12px;
  font-weight: 600;
  border: 1px solid var(--line);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  transition: all 0.2s;
}
.tear-btn {
  background: var(--accent);
  color: #fff;
  border-color: var(--accent);
}
.tear-btn:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 12px color-mix(in srgb, var(--accent) 35%, transparent);
}
.copy-btn {
  background: var(--surface);
  color: var(--ink);
}
.copy-btn:hover {
  border-color: var(--accent);
  color: var(--accent);
}
</style>
