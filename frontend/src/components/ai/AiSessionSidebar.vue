<script setup lang="ts">
import type { AiSession, AiTask } from '../../api/ai';

defineProps<{
  sessions: AiSession[];
  tasks: AiTask[];
  currentTaskId?: string | null;
}>();
const emit = defineEmits<{ selectTask: [task: AiTask] }>();
</script>

<template>
  <aside class="ai-panel ai-history" aria-labelledby="ai-history-title">
    <div class="ai-panel__heading">
      <div>
        <p class="ai-eyebrow">数据库事实源</p>
        <h2 id="ai-history-title">任务历史</h2>
      </div>
    </div>
    <ul v-if="tasks.length" class="ai-history-list">
      <li v-for="task in tasks" :key="task.id">
        <button
          type="button"
          :aria-current="task.id === currentTaskId ? 'true' : undefined"
          @click="emit('selectTask', task)"
        >
          <strong>{{
            sessions.find((session) => session.id === task.sessionId)?.title || '未命名任务'
          }}</strong>
          <small>{{ task.status }} · {{ new Date(task.createdAt).toLocaleString() }}</small>
        </button>
      </li>
    </ul>
    <p v-else class="ai-empty">暂无任务。</p>
  </aside>
</template>
