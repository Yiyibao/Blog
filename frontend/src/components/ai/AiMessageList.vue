<script setup lang="ts">
import type { AiTask } from '../../api/ai';

defineProps<{ task: AiTask | null }>();
</script>

<template>
  <section class="ai-panel ai-messages" aria-live="polite" aria-labelledby="ai-messages-title">
    <div class="ai-panel__heading">
      <div>
        <p class="ai-eyebrow">持久任务</p>
        <h2 id="ai-messages-title">对话结果</h2>
      </div>
      <span v-if="task" class="ai-status" :data-status="task.status">{{ task.status }}</span>
    </div>
    <div v-if="task" class="ai-message-list">
      <article
        v-for="part in task.parts.filter((item) => item.kind === 'TEXT')"
        :key="`${task.id}-${part.sequence}`"
        class="ai-message"
        :class="`ai-message--${part.role.toLowerCase()}`"
      >
        <strong>{{ part.role === 'ASSISTANT' ? 'AI' : '你' }}</strong>
        <p>{{ part.text }}</p>
      </article>
      <p v-if="task.errorMessage" class="ai-error" role="alert">
        {{ task.errorCode }}：{{ task.errorMessage }}
      </p>
    </div>
    <p v-else class="ai-empty">选择历史任务，或创建一个新的多模态任务。</p>
  </section>
</template>
