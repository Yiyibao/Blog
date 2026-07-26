<script setup lang="ts">
import { computed } from 'vue'
import type { AdminStats } from '../api/admin'

/** 4D：仪表盘趋势区——纯 SVG 折线（零依赖）、TOP5 热文、状态/附件/AI 用量卡片。 */
const props = defineProps<{ stats: AdminStats }>()

const CHART_W = 600
const CHART_H = 140
const PAD = 8

const maxViews = computed(() => Math.max(1, ...props.stats.viewTrend.map((d) => d.views)))

const points = computed(() => {
  const trend = props.stats.viewTrend
  if (!trend.length) return ''
  const stepX = (CHART_W - PAD * 2) / Math.max(1, trend.length - 1)
  return trend
    .map((d, i) => {
      const x = PAD + i * stepX
      const y = CHART_H - PAD - (d.views / maxViews.value) * (CHART_H - PAD * 2)
      return `${x.toFixed(1)},${y.toFixed(1)}`
    })
    .join(' ')
})

const areaPoints = computed(() => {
  if (!points.value) return ''
  return `${PAD},${CHART_H - PAD} ${points.value} ${CHART_W - PAD},${CHART_H - PAD}`
})

const totalViews30d = computed(() => props.stats.viewTrend.reduce((sum, d) => sum + d.views, 0))
const firstDay = computed(() => props.stats.viewTrend[0]?.day ?? '')
const lastDay = computed(() => props.stats.viewTrend[props.stats.viewTrend.length - 1]?.day ?? '')

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}
</script>

<template>
  <section class="dashboard-trends">
    <div class="trend-chart-card">
      <header>
        <strong>近 30 天浏览趋势</strong>
        <small>{{ firstDay }} ~ {{ lastDay }} · 共 {{ totalViews30d }} 次</small>
      </header>
      <svg
        :viewBox="`0 0 ${CHART_W} ${CHART_H}`"
        preserveAspectRatio="none"
        role="img"
        aria-label="近 30 天浏览趋势折线图"
      >
        <polygon v-if="areaPoints" :points="areaPoints" class="trend-area" />
        <polyline v-if="points" :points="points" class="trend-line" fill="none" />
      </svg>
    </div>

    <div class="trend-side">
      <div class="stat-cards">
        <div class="stat-card">
          <small>文章状态</small>
          <strong>{{ stats.publishedPosts }} 发布 / {{ stats.draftPosts }} 草稿</strong>
        </div>
        <div class="stat-card">
          <small>附件容量</small>
          <strong>{{ stats.attachmentCount }} 个 · {{ formatBytes(stats.attachmentBytes) }}</strong>
        </div>
        <div class="stat-card">
          <small>AI 用量（30 天）</small>
          <strong>{{ stats.aiUsage.requests }} 次 · {{ stats.aiUsage.tokens }} tokens</strong>
        </div>
      </div>
      <div class="top-posts">
        <p>热门文章 TOP {{ stats.topPosts.length }}</p>
        <ol>
          <li v-for="post in stats.topPosts" :key="post.slug">
            <span class="top-title">{{ post.title }}</span>
            <span class="top-meta">{{ post.viewsCount }} 阅 · {{ post.likeCount }} 赞</span>
          </li>
        </ol>
        <p v-if="!stats.topPosts.length" class="top-empty">暂无已发布文章。</p>
      </div>
    </div>
  </section>
</template>

<style scoped>
.dashboard-trends {
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) minmax(0, 1fr);
  gap: 16px;
  margin-bottom: 28px;
}
.trend-chart-card {
  border: 1px solid var(--line);
  border-radius: 16px;
  background: var(--surface);
  padding: 16px 18px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.trend-chart-card header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
}
.trend-chart-card strong { font-size: 14px; color: var(--ink); }
.trend-chart-card small { color: var(--muted); font-size: 11px; }
.trend-chart-card svg { width: 100%; height: 140px; }
.trend-line { stroke: var(--accent); stroke-width: 2; }
.trend-area { fill: color-mix(in srgb, var(--accent) 14%, transparent); }
.trend-side { display: flex; flex-direction: column; gap: 12px; min-width: 0; }
.stat-cards { display: grid; grid-template-columns: 1fr; gap: 8px; }
.stat-card {
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--surface);
  padding: 10px 14px;
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 8px;
}
.stat-card small { color: var(--muted); font-size: 12px; white-space: nowrap; }
.stat-card strong { color: var(--ink); font-size: 13px; text-align: right; }
.top-posts {
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--surface);
  padding: 12px 14px;
}
.top-posts > p { margin: 0 0 8px; color: var(--muted); font-size: 12px; }
.top-posts ol { margin: 0; padding-left: 18px; display: flex; flex-direction: column; gap: 6px; }
.top-posts li { display: flex; justify-content: space-between; gap: 10px; font-size: 13px; }
.top-title {
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.top-meta { color: var(--muted); font-size: 12px; white-space: nowrap; }
.top-empty { color: var(--muted); font-size: 12px; }
@media (max-width: 900px) {
  .dashboard-trends { grid-template-columns: 1fr; }
}
</style>
