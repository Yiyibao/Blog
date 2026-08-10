<script setup lang="ts">
import { ref } from 'vue';
import AdminAiChat from '../components/AdminAiChat.vue';
import AiWorkspace from '../components/ai/AiWorkspace.vue';

const platformEnabled = import.meta.env.DEV || import.meta.env.VITE_AI_PLATFORM_ENABLED === 'true';
const view = ref<'workspace' | 'legacy'>(platformEnabled ? 'workspace' : 'legacy');
</script>

<template>
  <div v-if="platformEnabled" class="ai-page-switcher" role="group" aria-label="AI 工作区模式">
    <button type="button" :aria-pressed="view === 'workspace'" @click="view = 'workspace'">
      多模态工作台
    </button>
    <button type="button" :aria-pressed="view === 'legacy'" @click="view = 'legacy'">兼容文本聊天</button>
  </div>
  <AiWorkspace v-if="view === 'workspace'" />
  <AdminAiChat v-else />
</template>

<style scoped>
.ai-page-switcher {
  position: sticky;
  z-index: 5;
  top: 0;
  display: flex;
  justify-content: center;
  gap: 0.35rem;
  padding: 0.55rem;
  border-bottom: 1px solid rgba(23, 35, 61, 0.12);
  background: rgba(249, 251, 255, 0.92);
  backdrop-filter: blur(14px);
}

.ai-page-switcher button {
  border: 0;
  border-radius: 99rem;
  padding: 0.55rem 0.9rem;
  color: #536079;
  background: transparent;
  font: inherit;
  font-weight: 700;
  cursor: pointer;
}

.ai-page-switcher button[aria-pressed='true'] {
  color: white;
  background: #3856d6;
}
</style>
