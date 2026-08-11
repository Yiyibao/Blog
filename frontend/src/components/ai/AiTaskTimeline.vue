<script setup lang="ts">
import type { AiTaskEvent } from '../../api/ai';

defineProps<{ events: AiTaskEvent[] }>();

function eventLabel(eventType: string) {
  return (
    {
      'task.queued': '任务已排队',
      'task.started': '开始调用模型',
      'context.truncated': '上下文已压缩',
      'message.delta': '收到模型回答',
      'message.completed': '回答已保存',
      'artifact.created': '文件已生成',
      'task.completed': '任务完成',
      'task.failed': '任务失败',
      'task.cancelled': '任务取消',
    }[eventType] ?? eventType
  );
}

function eventDetail(event: AiTaskEvent) {
  const payload = event.payload;
  if (typeof payload.name === 'string') return payload.name;
  if (typeof payload.model === 'string') return payload.model;
  if (typeof payload.content === 'string') return `${payload.content.length} 字符`;
  return '';
}
</script>

<template>
  <section class="ai-panel ai-task-timeline" aria-labelledby="ai-timeline-title">
    <div class="ai-panel__heading">
      <div>
        <p class="ai-eyebrow">可恢复事件</p>
        <h2 id="ai-timeline-title">任务时间线</h2>
      </div>
      <span class="ai-count-badge">{{ events.length }} 条</span>
    </div>
    <ol v-if="events.length" class="ai-timeline">
      <li v-for="event in events" :key="event.sequence">
        <span class="ai-timeline__dot" aria-hidden="true" />
        <div>
          <strong>{{ eventLabel(event.eventType) }}</strong>
          <small v-if="eventDetail(event)">{{ eventDetail(event) }}</small>
        </div>
        <time :datetime="event.createdAt">{{ new Date(event.createdAt).toLocaleTimeString() }}</time>
      </li>
    </ol>
    <p v-else class="ai-empty">任务运行后会在这里保留可恢复记录。</p>
  </section>
</template>

<style scoped>
.ai-panel__heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.7rem;
}

.ai-count-badge {
  padding: 0.28rem 0.55rem;
  border-radius: 999px;
  color: #2f4bbf;
  background: #edf1ff;
  font-size: 0.7rem;
  font-weight: 800;
}

.ai-timeline {
  margin-top: 0.85rem !important;
}

.ai-timeline li {
  display: grid;
  grid-template-columns: 0.55rem minmax(0, 1fr) auto;
  gap: 0.55rem;
  align-items: start;
  font-size: 0.76rem;
}

.ai-timeline__dot {
  width: 0.45rem;
  height: 0.45rem;
  margin-top: 0.28rem;
  border-radius: 50%;
  background: #7184e4;
  box-shadow: 0 0 0 3px #edf1ff;
}

.ai-timeline li div {
  display: grid;
  gap: 0.12rem;
}

.ai-timeline small,
.ai-timeline time {
  color: var(--ai-muted, #687189);
  font-size: 0.68rem;
}

.ai-timeline time {
  white-space: nowrap;
}
</style>
