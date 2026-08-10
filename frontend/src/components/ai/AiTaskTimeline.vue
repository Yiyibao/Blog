<script setup lang="ts">
import type { AiTaskEvent } from '../../api/ai';

defineProps<{ events: AiTaskEvent[] }>();
</script>

<template>
  <section class="ai-panel" aria-labelledby="ai-timeline-title">
    <div class="ai-panel__heading">
      <div>
        <p class="ai-eyebrow">可恢复事件</p>
        <h2 id="ai-timeline-title">任务时间线</h2>
      </div>
    </div>
    <ol v-if="events.length" class="ai-timeline">
      <li v-for="event in events" :key="event.sequence">
        <span>#{{ event.sequence }}</span>
        <strong>{{ event.eventType }}</strong>
        <time :datetime="event.createdAt">{{ new Date(event.createdAt).toLocaleTimeString() }}</time>
      </li>
    </ol>
    <p v-else class="ai-empty">暂无持久事件。</p>
  </section>
</template>
