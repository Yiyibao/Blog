<script setup lang="ts">
import { ref } from 'vue';

defineProps<{ running: boolean; selectedCount: number }>();
const emit = defineEmits<{ submit: [prompt: string]; cancel: [] }>();
const prompt = ref('');

function submit() {
  const value = prompt.value.trim();
  emit('submit', value);
  if (value) prompt.value = '';
}
</script>

<template>
  <form class="ai-composer" @submit.prevent="submit">
    <label for="ai-task-prompt">告诉 AI 需要理解或生成什么</label>
    <textarea
      id="ai-task-prompt"
      v-model="prompt"
      rows="4"
      maxlength="32000"
      :disabled="running"
      placeholder="例如：概括附件中的关键结论，并列出需要核实的数据。"
      @keydown.ctrl.enter.prevent="submit"
      @keydown.meta.enter.prevent="submit"
    />
    <div class="ai-composer__footer">
      <span>{{ selectedCount }} 个附件已加入 · Ctrl/⌘ + Enter 发送</span>
      <button v-if="running" type="button" class="ai-button ai-button--danger" @click="emit('cancel')">
        取消任务
      </button>
      <button v-else type="submit" class="ai-button" :disabled="!prompt.trim() && selectedCount === 0">
        创建并运行
      </button>
    </div>
  </form>
</template>
