<script setup lang="ts">
import { onMounted } from 'vue';
import { storeToRefs } from 'pinia';
import { useAiStore } from '../../stores/aiStore';
import { useAiTaskStore } from '../../stores/aiTaskStore';
import AiArtifactCard from './AiArtifactCard.vue';
import AiAttachmentTray from './AiAttachmentTray.vue';
import AiMemoryPanel from './AiMemoryPanel.vue';
import AiMessageList from './AiMessageList.vue';
import AiSessionSidebar from './AiSessionSidebar.vue';
import AiTaskComposer from './AiTaskComposer.vue';
import AiTaskTimeline from './AiTaskTimeline.vue';

const platform = useAiTaskStore();
const provider = useAiStore();
const {
  sessions,
  tasks,
  files,
  memories,
  events,
  currentTask,
  currentSession,
  selectedFileIds,
  selectedFiles,
  currentArtifacts,
  loading,
  running,
  error,
} = storeToRefs(platform);

onMounted(async () => {
  await Promise.all([platform.initialize(), provider.ensureProviders()]);
});
</script>

<template>
  <main class="ai-workspace" aria-labelledby="ai-workspace-title">
    <header class="ai-workspace__hero">
      <div>
        <p class="ai-eyebrow">Internal Alpha · M1</p>
        <h1 id="ai-workspace-title">AI 多模态工作台</h1>
        <p>图片与文档进入持久任务；记忆由你确认，生成物从应用受控存储下载。</p>
      </div>
      <div class="ai-provider-summary">
        <span>当前 provider</span>
        <strong>{{ provider.selectedProvider?.name || '后端默认' }}</strong>
        <small>{{ provider.selectedModel || '默认模型' }}</small>
      </div>
    </header>

    <p v-if="error" class="ai-error ai-workspace__error" role="alert">{{ error }}</p>
    <p v-if="loading" class="ai-loading" role="status">正在恢复持久任务、记忆与附件…</p>

    <div v-else class="ai-workspace__layout">
      <AiSessionSidebar
        :sessions="sessions"
        :tasks="tasks"
        :current-task-id="currentTask?.id"
        @select-task="platform.selectTask"
      />
      <section class="ai-workspace__main">
        <AiMessageList :task="currentTask" />
        <AiTaskComposer
          :running="running"
          :selected-count="selectedFiles.length"
          @submit="platform.submit($event, provider.selectedProviderId, provider.selectedModel)"
          @cancel="platform.cancelCurrent"
        />
        <div class="ai-workspace__split">
          <AiAttachmentTray
            :files="files"
            :selected-ids="selectedFileIds"
            :disabled="running"
            @upload="platform.upload"
            @toggle="platform.toggleFile"
            @remove="platform.removeFile"
          />
          <AiTaskTimeline :events="events" />
        </div>
      </section>
      <aside class="ai-workspace__inspector">
        <AiMemoryPanel
          :memories="memories"
          :disabled="running"
          :current-session-id="currentSession?.id"
          :current-task-id="currentTask?.id"
          :session-summary="currentSession?.summary"
          @create="platform.addMemory"
          @confirm="platform.confirmMemory"
          @toggle="platform.toggleMemory"
          @update="platform.editMemory"
          @reject="platform.rejectMemory"
          @forget="platform.forgetMemory"
          @clear-summary="platform.clearSessionSummary"
        />
        <AiArtifactCard
          :artifacts="currentArtifacts"
          :has-task="Boolean(currentTask)"
          :disabled="running"
          @create="platform.materialize"
          @remove="platform.removeArtifact"
        />
      </aside>
    </div>
  </main>
</template>

<style scoped>
.ai-workspace {
  --ai-ink: #17233d;
  --ai-muted: #687189;
  --ai-line: color-mix(in srgb, var(--ai-ink) 13%, transparent);
  --ai-surface: color-mix(in srgb, white 92%, #edf3ff);
  color: var(--ai-ink);
  min-height: 100%;
  padding: clamp(1rem, 2vw, 2rem);
  background:
    radial-gradient(circle at 12% 0%, rgba(85, 120, 255, 0.14), transparent 32rem),
    linear-gradient(155deg, #f9fbff, #f1f4fa 58%, #fbfcff);
}

.ai-workspace__hero,
.ai-panel__heading,
.ai-composer__footer,
.ai-artifact-list li,
.ai-file-list li {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
}

.ai-workspace__hero {
  margin: 0 auto 1.25rem;
  max-width: 1680px;
}

.ai-workspace__hero h1 {
  margin: 0.2rem 0;
  font-size: clamp(1.7rem, 3vw, 3rem);
  letter-spacing: -0.04em;
}

.ai-workspace__hero p {
  margin: 0;
  color: var(--ai-muted);
}

.ai-eyebrow {
  margin: 0;
  color: #415bd4;
  font-size: 0.72rem;
  font-weight: 800;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

.ai-provider-summary {
  min-width: 12rem;
  padding: 0.8rem 1rem;
  border: 1px solid var(--ai-line);
  border-radius: 1rem;
  background: rgba(255, 255, 255, 0.76);
  display: grid;
}

.ai-provider-summary span,
.ai-provider-summary small,
.ai-help,
.ai-empty,
.ai-panel small,
.ai-composer__footer span {
  color: var(--ai-muted);
  font-size: 0.78rem;
}

.ai-workspace__layout {
  display: grid;
  grid-template-columns: minmax(13rem, 0.72fr) minmax(28rem, 2.2fr) minmax(18rem, 1fr);
  gap: 1rem;
  align-items: start;
  max-width: 1680px;
  margin: 0 auto;
}

.ai-workspace__main,
.ai-workspace__inspector {
  display: grid;
  gap: 1rem;
}

.ai-workspace__split {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 1rem;
}

:deep(.ai-panel),
.ai-composer {
  border: 1px solid var(--ai-line);
  border-radius: 1.1rem;
  background: var(--ai-surface);
  box-shadow: 0 18px 48px rgba(35, 50, 90, 0.08);
  padding: 1rem;
}

:deep(.ai-panel h2) {
  margin: 0.15rem 0 0;
  font-size: 1rem;
}

:deep(.ai-button),
:deep(.ai-icon-button),
:deep(.ai-link-button) {
  border: 0;
  border-radius: 0.7rem;
  cursor: pointer;
  font: inherit;
}

:deep(.ai-button) {
  padding: 0.7rem 1rem;
  color: white;
  background: #3856d6;
  font-weight: 750;
}

:deep(.ai-button--quiet) {
  color: #2f4bbf;
  background: #e8edff;
}

:deep(.ai-button--danger) {
  background: #b4233c;
}

:deep(button:disabled),
:deep(input:disabled),
:deep(textarea:disabled),
:deep(select:disabled) {
  cursor: not-allowed;
  opacity: 0.55;
}

:deep(input),
:deep(textarea),
:deep(select) {
  box-sizing: border-box;
  width: 100%;
  border: 1px solid var(--ai-line);
  border-radius: 0.72rem;
  padding: 0.68rem 0.75rem;
  color: var(--ai-ink);
  background: white;
  font: inherit;
}

.ai-composer label,
:deep(.ai-artifact-form label) {
  display: grid;
  gap: 0.35rem;
  font-size: 0.82rem;
  font-weight: 700;
}

.ai-composer textarea {
  margin: 0.45rem 0 0.8rem;
  resize: vertical;
}

:deep(.ai-history-list),
:deep(.ai-file-list),
:deep(.ai-memory-list),
:deep(.ai-artifact-list),
:deep(.ai-timeline) {
  list-style: none;
  margin: 0.8rem 0 0;
  padding: 0;
  display: grid;
  gap: 0.55rem;
}

:deep(.ai-history-list button) {
  width: 100%;
  border: 1px solid transparent;
  border-radius: 0.75rem;
  padding: 0.72rem;
  text-align: left;
  background: transparent;
  display: grid;
  gap: 0.2rem;
}

:deep(.ai-history-list button:hover),
:deep(.ai-history-list button[aria-current='true']) {
  border-color: #b6c2ff;
  background: #eef1ff;
}

:deep(.ai-message-list) {
  max-height: 34rem;
  overflow: auto;
  display: grid;
  gap: 0.75rem;
  margin-top: 1rem;
}

:deep(.ai-message) {
  max-width: 88%;
  border-radius: 1rem;
  padding: 0.8rem 1rem;
  background: #edf0f7;
}

:deep(.ai-message--user) {
  justify-self: end;
  background: #e8edff;
}

:deep(.ai-message p) {
  margin: 0.35rem 0 0;
  white-space: pre-wrap;
  line-height: 1.58;
}

:deep(.ai-status) {
  border-radius: 99rem;
  padding: 0.25rem 0.55rem;
  color: #2f4bbf;
  background: #e8edff;
  font-size: 0.72rem;
  font-weight: 800;
}

:deep(.ai-timeline li) {
  display: grid;
  grid-template-columns: 2.2rem 1fr auto;
  gap: 0.4rem;
  align-items: center;
  font-size: 0.78rem;
}

:deep(.ai-file-list li),
:deep(.ai-artifact-list li) {
  border-top: 1px solid var(--ai-line);
  padding-top: 0.55rem;
}

:deep(.ai-file-list label),
:deep(.ai-file-list span),
:deep(.ai-artifact-list span) {
  display: grid;
  gap: 0.15rem;
}

:deep(.ai-file-list label) {
  grid-template-columns: auto 1fr;
  align-items: center;
}

:deep(.ai-inline-form) {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 0.5rem;
  margin-top: 0.75rem;
}

:deep(.ai-memory-list li) {
  border-top: 1px solid var(--ai-line);
  padding-top: 0.65rem;
}

:deep(.ai-memory-list p) {
  margin: 0 0 0.2rem;
}

:deep(.ai-link-button) {
  padding: 0.3rem 0.45rem;
  color: #2f4bbf;
  background: transparent;
}

:deep(.ai-link-button--danger),
:deep(.ai-icon-button) {
  color: #a31e36;
  background: transparent;
}

:deep(.ai-artifact-form) {
  display: grid;
  gap: 0.65rem;
  margin-top: 0.75rem;
}

.ai-error {
  color: #941d34;
  background: #fff0f2;
  border: 1px solid #f2bec8;
  border-radius: 0.75rem;
  padding: 0.7rem;
}

.ai-workspace__error,
.ai-loading {
  max-width: 1680px;
  margin: 0 auto 1rem;
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
}

@media (max-width: 1180px) {
  .ai-workspace__layout {
    grid-template-columns: 15rem 1fr;
  }

  .ai-workspace__inspector {
    grid-column: 1 / -1;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 760px) {
  .ai-workspace__hero,
  .ai-composer__footer {
    align-items: stretch;
    flex-direction: column;
  }

  .ai-workspace__layout,
  .ai-workspace__split,
  .ai-workspace__inspector {
    grid-template-columns: 1fr;
  }

  .ai-workspace__inspector {
    grid-column: auto;
  }
}
</style>
