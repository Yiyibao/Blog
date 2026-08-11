<script setup lang="ts">
import { ref } from 'vue';
import type { AiMemory } from '../../api/ai';

const props = defineProps<{
  memories: AiMemory[];
  disabled?: boolean;
  currentSessionId?: number | null;
  currentProjectId?: number | null;
  currentTaskId?: string | null;
  sessionSummary?: string | null;
}>();
const emit = defineEmits<{
  create: [content: string, scope: string, sourceTaskId: string | null];
  confirm: [memory: AiMemory];
  toggle: [memory: AiMemory];
  update: [memory: AiMemory, content: string];
  reject: [memory: AiMemory];
  forget: [memory: AiMemory];
  clearSummary: [];
}>();
const content = ref('');
const scope = ref<'USER' | 'SESSION' | 'PROJECT'>('USER');
const editingId = ref<string | null>(null);
const editingContent = ref('');

function create(sourceTaskId: string | null = null) {
  const value = content.value.trim();
  if (!value) return;
  const selectedScope =
    scope.value === 'SESSION' && props.currentSessionId
      ? `SESSION:${props.currentSessionId}`
      : scope.value === 'PROJECT' && props.currentProjectId
        ? `PROJECT:${props.currentProjectId}`
        : 'USER';
  emit('create', value, selectedScope, sourceTaskId);
  content.value = '';
}

function startEditing(memory: AiMemory) {
  editingId.value = memory.id;
  editingContent.value = memory.content || '';
}

function save(memory: AiMemory) {
  const value = editingContent.value.trim();
  if (!value) return;
  emit('update', memory, value);
  editingId.value = null;
  editingContent.value = '';
}
</script>

<template>
  <section class="ai-panel" aria-labelledby="ai-memory-title">
    <div class="ai-panel__heading">
      <div>
        <p class="ai-eyebrow">用户可控</p>
        <h2 id="ai-memory-title">真实记忆</h2>
      </div>
    </div>
    <div v-if="sessionSummary" class="ai-memory-summary">
      <strong>较早会话摘要</strong>
      <p>{{ sessionSummary }}</p>
      <button type="button" class="ai-link-button ai-link-button--danger" @click="emit('clearSummary')">
        清除摘要
      </button>
    </div>
    <form class="ai-memory-form" @submit.prevent="create(null)">
      <label class="sr-only" for="ai-memory-content">新增长期记忆</label>
      <input
        id="ai-memory-content"
        v-model="content"
        :disabled="disabled"
        maxlength="4000"
        placeholder="例如：默认使用中文回答"
      />
      <label>
        作用域
        <select v-model="scope" :disabled="disabled">
          <option value="USER">所有会话</option>
          <option value="SESSION" :disabled="!currentSessionId">当前会话</option>
          <option value="PROJECT" :disabled="!currentProjectId">当前项目</option>
        </select>
      </label>
      <div class="ai-memory-form__actions">
        <button class="ai-button ai-button--quiet" type="submit" :disabled="disabled || !content.trim()">
          直接记住
        </button>
        <button
          v-if="currentTaskId"
          class="ai-button ai-button--quiet"
          type="button"
          :disabled="disabled || !content.trim()"
          @click="create(currentTaskId)"
        >
          保存为待确认提案
        </button>
      </div>
    </form>
    <ul v-if="memories.length" class="ai-memory-list">
      <li v-for="memory in memories" :key="memory.id">
        <form v-if="editingId === memory.id" class="ai-inline-form" @submit.prevent="save(memory)">
          <input v-model="editingContent" maxlength="4000" :disabled="disabled" aria-label="编辑记忆内容" />
          <button type="submit" class="ai-link-button" :disabled="!editingContent.trim()">保存</button>
        </form>
        <p v-else>{{ memory.content }}</p>
        <small>{{ memory.scope }} / {{ memory.kind }} · {{ memory.status }}</small>
        <div>
          <button
            v-if="memory.status === 'PROPOSED'"
            type="button"
            class="ai-link-button"
            @click="emit('confirm', memory)"
          >
            确认
          </button>
          <button
            v-if="memory.status === 'PROPOSED'"
            type="button"
            class="ai-link-button ai-link-button--danger"
            @click="emit('reject', memory)"
          >
            拒绝
          </button>
          <button
            v-if="memory.status === 'ACTIVE' || memory.status === 'DISABLED'"
            type="button"
            class="ai-link-button"
            @click="emit('toggle', memory)"
          >
            {{ memory.status === 'ACTIVE' ? '禁用' : '启用' }}
          </button>
          <button
            v-if="memory.status !== 'REJECTED'"
            type="button"
            class="ai-link-button"
            @click="startEditing(memory)"
          >
            编辑
          </button>
          <button type="button" class="ai-link-button ai-link-button--danger" @click="emit('forget', memory)">
            忘记
          </button>
        </div>
      </li>
    </ul>
    <p v-else class="ai-empty">尚无长期记忆。聊天历史仅用于当前会话，不会自动变成长期记忆。</p>
  </section>
</template>

<style scoped>
.ai-memory-summary {
  margin-top: 0.75rem;
  border: 1px solid var(--ai-line);
  border-radius: 0.75rem;
  padding: 0.7rem;
  background: color-mix(in srgb, white 88%, #e8edff);
}

.ai-memory-summary p {
  max-height: 8rem;
  overflow: auto;
  white-space: pre-wrap;
  font-size: 0.82rem;
}

.ai-memory-form {
  display: grid;
  gap: 0.55rem;
  margin-top: 0.75rem;
}

.ai-memory-form label {
  display: grid;
  gap: 0.25rem;
  font-size: 0.78rem;
  font-weight: 700;
}

.ai-memory-form__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.45rem;
}
</style>
