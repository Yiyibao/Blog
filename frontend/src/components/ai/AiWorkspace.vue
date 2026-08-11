<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { storeToRefs } from 'pinia';
import { useAiStore } from '../../stores/aiStore';
import { useAiTaskStore } from '../../stores/aiTaskStore';
import type {
  AiArtifact,
  AiConversationMessage,
  AiFile,
  AiProject,
  AiReasoningEffort,
  AiSession,
  AiTask,
  AiTaskCreateInput,
} from '../../api/ai';
import type { AiReasoningSelection } from '../../api/admin';
import AiArtifactCard from './AiArtifactCard.vue';
import AiAttachmentTray from './AiAttachmentTray.vue';
import AiMemoryPanel from './AiMemoryPanel.vue';
import AiMessageList from './AiMessageList.vue';
import AiSessionSidebar from './AiSessionSidebar.vue';
import AiTaskComposer from './AiTaskComposer.vue';
import AiTaskTimeline from './AiTaskTimeline.vue';

type UtilityPanel = 'files' | 'memory' | 'artifacts' | 'timeline' | null;

const platform = useAiTaskStore();
const provider = useAiStore();
const {
  projects,
  sessions,
  tasks,
  files,
  memories,
  events,
  conversationMessages,
  currentTask,
  currentSession,
  currentProjectId,
  selectedProject,
  selectedFileIds,
  selectedFiles,
  currentArtifacts,
  loading,
  running,
  error,
} = storeToRefs(platform);

/**
 * This is an explicit local-only visual fixture. It is never enabled in a
 * production build and never gets sent to the backend. It keeps the design
 * reference reproducible while a real provider/account is tested on the
 * server side.
 */
const previewMode = import.meta.env.DEV && import.meta.env.VITE_AI_WORKSPACE_PREVIEW === 'true';
const utilityPanel = ref<UtilityPanel>(null);
const overflowOpen = ref(false);
const previewProvider = ref('OpenAI');
const previewModel = ref('GPT-5.6');
const previewReasoning = ref<AiReasoningSelection>('high');

const previewProject: AiProject = {
  id: -1,
  title: '博客内容升级',
  status: 'ACTIVE',
  archivedAt: null,
  sortOrder: 0,
  sessionCount: 2,
  version: 0,
  createdAt: '2026-07-01T00:00:00Z',
  updatedAt: '2026-07-01T00:00:00Z',
};

const previewSession: AiSession = {
  id: -1,
  title: '七月运营总结',
  mode: 'WORKSPACE',
  projectId: -1,
  status: 'ACTIVE',
  archivedAt: null,
  summary: '运营数据复盘与下月内容计划',
  version: 0,
  createdAt: '2026-07-31T09:00:00Z',
  updatedAt: '2026-07-31T09:20:00Z',
};

const previewFile: AiFile = {
  id: 'preview-visit-data',
  name: '访问数据.xlsx',
  mediaType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  sizeBytes: 10_240,
  sha256: 'preview',
  status: 'READY',
  retention: 'SESSION',
  expiresAt: null,
  referenceCount: 1,
  createdAt: '2026-07-31T09:12:00Z',
  updatedAt: '2026-07-31T09:12:00Z',
};

const previewArtifacts: AiArtifact[] = [
  {
    id: 'preview-cover',
    taskId: 'preview-task',
    name: '七月运营总结封面.png',
    mediaType: 'image/png',
    sizeBytes: 284_000,
    sha256: 'preview-cover',
    status: 'READY',
    expiresAt: null,
    createdAt: '2026-07-31T09:20:00Z',
    updatedAt: '2026-07-31T09:20:00Z',
  },
  {
    id: 'preview-report',
    taskId: 'preview-task',
    name: '七月运营总结.pdf',
    mediaType: 'application/pdf',
    sizeBytes: 2_800_000,
    sha256: 'preview-report',
    status: 'READY',
    expiresAt: null,
    createdAt: '2026-07-31T09:20:00Z',
    updatedAt: '2026-07-31T09:20:00Z',
  },
];

const previewMessages: AiConversationMessage[] = [
  {
    taskId: 'preview-task',
    sequence: 1,
    role: 'USER',
    kind: 'FILE_REF',
    text: '请根据刚才的数据生成一份中文 PDF 报告，并配一张封面图。',
    fileId: previewFile.id,
    artifactId: null,
    sourceRef: null,
    createdAt: '2026-07-31T09:13:00Z',
  },
  {
    taskId: 'preview-task',
    sequence: 2,
    role: 'ASSISTANT',
    kind: 'TEXT',
    text: '已完成报告整理，并生成了封面图和可下载的 PDF 文件。',
    fileId: null,
    artifactId: null,
    sourceRef: null,
    createdAt: '2026-07-31T09:20:00Z',
  },
  {
    taskId: 'preview-task',
    sequence: 3,
    role: 'ASSISTANT',
    kind: 'ARTIFACT_REF',
    text: null,
    fileId: null,
    artifactId: 'preview-cover',
    sourceRef: null,
    createdAt: '2026-07-31T09:20:00Z',
  },
  {
    taskId: 'preview-task',
    sequence: 4,
    role: 'ASSISTANT',
    kind: 'ARTIFACT_REF',
    text: null,
    fileId: null,
    artifactId: 'preview-report',
    sourceRef: null,
    createdAt: '2026-07-31T09:20:00Z',
  },
];

const previewTask: AiTask = {
  id: 'preview-task',
  sessionId: previewSession.id,
  taskType: 'GENERATE',
  status: 'COMPLETED',
  providerId: -1,
  providerType: 'OPENAI_RESPONSES',
  model: 'GPT-5.6',
  requestedProviderId: -1,
  requestedModel: 'GPT-5.6',
  requestedReasoningEffort: 'high',
  resolvedProviderId: -1,
  resolvedModel: 'GPT-5.6',
  resolvedReasoningEffort: 'high',
  requiredCapabilities: 'TEXT,FILE_INPUT,IMAGE_GENERATION',
  routeReason: 'design preview',
  errorCode: null,
  errorMessage: null,
  version: 0,
  startedAt: '2026-07-31T09:13:00Z',
  finishedAt: '2026-07-31T09:20:00Z',
  createdAt: '2026-07-31T09:13:00Z',
  updatedAt: '2026-07-31T09:20:00Z',
  parts: previewMessages.map((message) => ({
    sequence: message.sequence,
    role: message.role,
    kind: message.kind,
    text: message.text,
    fileId: message.fileId,
    artifactId: message.artifactId,
    sourceRef: message.sourceRef,
    createdAt: message.createdAt,
  })),
};

const previewSessions: AiSession[] = [
  previewSession,
  {
    ...previewSession,
    id: -2,
    title: '文章选题规划',
    updatedAt: '2026-07-30T08:00:00Z',
    summary: '下一阶段内容选题',
  },
  { ...previewSession, id: -3, title: '生成七月总结 PDF', projectId: null },
  { ...previewSession, id: -4, title: '整理菜谱图片', projectId: null },
  { ...previewSession, id: -5, title: '设计首页封面', projectId: null },
];

const previewTasks: AiTask[] = [previewTask];

const displayedProjects = computed(() => (previewMode ? [previewProject] : projects.value));
const displayedSessions = computed(() => (previewMode ? previewSessions : sessions.value));
const displayedTasks = computed(() => (previewMode ? previewTasks : tasks.value));
const displayedFiles = computed(() => (previewMode ? [previewFile] : files.value));
const displayedMessages = computed(() => (previewMode ? previewMessages : conversationMessages.value));
const displayedArtifacts = computed(() => (previewMode ? previewArtifacts : currentArtifacts.value));
const displayedCurrentSession = computed(() => (previewMode ? previewSession : currentSession.value));
const displayedCurrentTask = computed(() => (previewMode ? previewTask : currentTask.value));
const displayedCurrentProjectId = computed(() => (previewMode ? previewProject.id : currentProjectId.value));
const displayedCurrentProject = computed(() => (previewMode ? previewProject : selectedProject.value));
const displayedSelectedFiles = computed(() => (previewMode ? [] : selectedFiles.value));
const displayedMemories = computed(() => {
  if (previewMode || displayedCurrentProjectId.value == null) return memories.value;
  const projectScope = `PROJECT:${displayedCurrentProjectId.value}`;
  const sessionScope = displayedCurrentSession.value?.id
    ? `SESSION:${displayedCurrentSession.value.id}`
    : null;
  return memories.value.filter(
    (memory) =>
      memory.scope === projectScope ||
      memory.scope === 'USER' ||
      memory.scope === 'GLOBAL' ||
      memory.scope === 'SITE' ||
      (sessionScope != null && memory.scope === sessionScope),
  );
});
const activeMemoryCount = computed(() => {
  if (previewMode) return 3;
  if (displayedCurrentProjectId.value == null) {
    return memories.value.filter((memory) => memory.status === 'ACTIVE').length;
  }
  const projectScope = `PROJECT:${displayedCurrentProjectId.value}`;
  return memories.value.filter((memory) => memory.status === 'ACTIVE' && memory.scope === projectScope)
    .length;
});

const providerOptions = computed(() =>
  previewMode
    ? [{ id: -1, name: 'OpenAI' }]
    : provider.providers.map((item) => ({ id: item.id, name: item.name })),
);
const modelOptions = computed(() => (previewMode ? ['GPT-5.6'] : provider.modelOptions));
const selectedProviderValue = computed(() =>
  previewMode ? '-1' : provider.selectedProviderId == null ? '' : String(provider.selectedProviderId),
);
const selectedModelValue = computed(() =>
  previewMode ? previewModel.value : (provider.selectedModel ?? ''),
);
const selectedReasoningValue = computed(() =>
  previewMode ? previewReasoning.value : provider.selectedReasoningEffort,
);
const reasoningOptions = computed(() => (previewMode ? ['high'] : provider.reasoningOptions));
const reasoningSupported = computed(() => (previewMode ? true : provider.reasoningSupported));
const selectedCapabilityLabel = computed(() =>
  previewMode
    ? '文本 · 视觉 · 文件 · 工具调用'
    : provider.selectedCapabilities.length
      ? provider.selectedCapabilities.join(' · ')
      : '能力元数据未配置',
);

function selectProvider(event: Event) {
  const value = (event.target as HTMLSelectElement).value;
  if (previewMode) {
    previewProvider.value = providerOptions.value.find((item) => String(item.id) === value)?.name ?? 'OpenAI';
    return;
  }
  provider.selectProvider(value);
}

function selectModel(event: Event) {
  const value = (event.target as HTMLSelectElement).value;
  if (previewMode) {
    previewModel.value = value;
    return;
  }
  provider.selectModel(value);
}

function selectReasoning(event: Event) {
  const value = (event.target as HTMLSelectElement).value as AiReasoningSelection;
  if (previewMode) {
    previewReasoning.value = value;
    return;
  }
  provider.selectReasoningEffort(value);
}

function submit(prompt: string, taskType: AiTaskCreateInput['taskType']) {
  if (previewMode) return;
  const selected = provider.selectedReasoningEffort;
  const reasoning: AiReasoningEffort = selected === 'auto' || selected === 'minimal' ? 'none' : selected;
  return platform.submit(
    prompt,
    provider.selectedProviderId,
    provider.selectedModel,
    taskType,
    reasoning,
    currentProjectId.value,
  );
}

function newSession() {
  if (previewMode) return;
  platform.startNewTask();
}

function selectSession(session: AiSession) {
  if (previewMode) return;
  return platform.selectSession(session.id);
}

function selectProject(project: AiProject) {
  if (previewMode) return;
  return platform.selectProject(project.id);
}

function newProjectTask(project: AiProject) {
  if (previewMode) return;
  platform.startNewTask(project.id);
}

function selectTask(task: AiTask) {
  if (previewMode) return;
  return platform.selectTask(task);
}

function createProject() {
  if (previewMode) return;
  const title = window.prompt('项目名称', '新项目')?.trim();
  if (title) {
    void platform.addProject(title).then((project) => platform.startNewTask(project.id));
  }
}

function renameProject(project: AiProject) {
  if (previewMode) return;
  const title = window.prompt('重命名项目', project.title)?.trim();
  if (title && title !== project.title) void platform.renameProject(project, title);
}

function toggleProject(project: AiProject) {
  if (!previewMode) void platform.toggleProject(project);
}

function openUtility(panel: Exclude<UtilityPanel, null>) {
  overflowOpen.value = false;
  utilityPanel.value = panel;
}

function utilityTitle() {
  return (
    {
      files: '附件与多模态输入',
      memory: '真实记忆',
      artifacts: '可下载产物',
      timeline: '任务时间线',
    }[utilityPanel.value ?? 'files'] ?? '工作台'
  );
}

onMounted(async () => {
  if (previewMode) return;
  await Promise.all([platform.initialize(), provider.ensureProviders()]);
});
</script>

<template>
  <main class="ai-workspace" data-testid="ai-workspace" aria-labelledby="ai-workspace-title">
    <AiSessionSidebar
      :projects="displayedProjects"
      :sessions="displayedSessions"
      :tasks="displayedTasks"
      :current-session-id="displayedCurrentSession?.id"
      :current-project-id="displayedCurrentProjectId"
      :current-task-id="displayedCurrentTask?.id"
      :memory-count="activeMemoryCount"
      :preview="previewMode"
      @new-session="newSession"
      @select-session="selectSession"
      @select-task="selectTask"
      @select-project="selectProject"
      @new-project-task="newProjectTask"
      @create-project="createProject"
      @rename-project="renameProject"
      @toggle-project="toggleProject"
      @open-utility="openUtility"
    />

    <section class="ai-workspace__main">
      <header class="ai-topbar">
        <div class="ai-topbar__title">
          <h1 id="ai-workspace-title">{{ displayedCurrentSession?.title || '新对话' }}</h1>
          <div class="ai-context-chips">
            <span v-if="displayedCurrentProject" class="ai-context-chip ai-context-chip--project">
              <span aria-hidden="true">▱</span> {{ displayedCurrentProject.title }}
            </span>
            <span class="ai-context-chip ai-context-chip--memory">
              <span aria-hidden="true">▣</span>
              {{ displayedCurrentProject ? '项目记忆' : '可用记忆' }} {{ activeMemoryCount }} 条
            </span>
          </div>
        </div>

        <div class="ai-topbar__controls" aria-label="模型与推理设置" :title="selectedCapabilityLabel">
          <label class="ai-selector">
            <span>供应商</span>
            <select
              data-testid="ai-provider-select"
              :value="selectedProviderValue"
              :disabled="(!providerOptions.length && !previewMode) || running"
              @change="selectProvider"
            >
              <option v-if="!providerOptions.length" value="">暂无供应商</option>
              <option v-for="item in providerOptions" :key="item.id" :value="item.id">
                {{ item.name }}
              </option>
            </select>
          </label>
          <label class="ai-selector">
            <span>模型</span>
            <select
              data-testid="ai-model-select"
              :value="selectedModelValue"
              :disabled="(!modelOptions.length && !previewMode) || running"
              @change="selectModel"
            >
              <option v-if="!modelOptions.length" value="">暂无模型</option>
              <option v-for="model in modelOptions" :key="model" :value="model">{{ model }}</option>
            </select>
          </label>
          <label class="ai-selector ai-selector--reasoning">
            <span>推理</span>
            <select
              data-testid="ai-reasoning-select"
              :value="selectedReasoningValue"
              :disabled="!reasoningSupported || running"
              @change="selectReasoning"
            >
              <option v-if="!reasoningSupported" value="none">未配置</option>
              <option value="auto">自动</option>
              <option value="none">关闭</option>
              <option v-for="effort in reasoningOptions" :key="effort" :value="effort">
                {{ effort === 'high' ? '高' : effort }}
              </option>
            </select>
          </label>
          <button
            type="button"
            class="ai-overflow-button"
            aria-label="更多工作台工具"
            :aria-expanded="overflowOpen"
            @click="overflowOpen = !overflowOpen"
          >
            ⋮
          </button>
          <div v-if="overflowOpen" class="ai-overflow-menu">
            <button type="button" @click="openUtility('files')">▧ 附件与多模态输入</button>
            <button type="button" @click="openUtility('memory')">✧ 真实记忆</button>
            <button type="button" @click="openUtility('artifacts')">▤ 可下载产物</button>
            <button type="button" @click="openUtility('timeline')">◷ 任务时间线</button>
          </div>
        </div>
      </header>

      <div v-if="error && !previewMode" class="ai-workspace__error" role="alert">{{ error }}</div>

      <div class="ai-conversation-area">
        <p v-if="loading && !previewMode" class="ai-loading" role="status">正在恢复项目、会话与附件…</p>
        <AiMessageList
          v-else
          :task="displayedCurrentTask"
          :messages="displayedMessages"
          :artifacts="displayedArtifacts"
          :files="displayedFiles"
          :preview="previewMode"
        />
      </div>

      <div class="ai-composer-zone">
        <AiTaskComposer
          :running="running"
          :selected-count="displayedSelectedFiles.length"
          :selected-files="displayedSelectedFiles"
          :preview="previewMode"
          @submit="submit"
          @upload-files="platform.upload"
          @open-files="openUtility('files')"
          @cancel="platform.cancelCurrent"
        />
      </div>
    </section>

    <div v-if="utilityPanel" class="ai-utility-backdrop" @click.self="utilityPanel = null">
      <aside class="ai-utility-drawer" aria-label="工作台工具面板">
        <header>
          <div>
            <p>Workspace tools</p>
            <h2>{{ utilityTitle() }}</h2>
          </div>
          <button type="button" aria-label="关闭工具面板" @click="utilityPanel = null">×</button>
        </header>
        <AiAttachmentTray
          v-if="utilityPanel === 'files'"
          :files="displayedFiles"
          :selected-ids="previewMode ? [previewFile.id] : selectedFileIds"
          :disabled="running || previewMode"
          @upload="platform.upload"
          @toggle="platform.toggleFile"
          @remove="platform.removeFile"
        />
        <AiMemoryPanel
          v-else-if="utilityPanel === 'memory'"
          :memories="displayedMemories"
          :disabled="running || previewMode"
          :current-session-id="displayedCurrentSession?.id"
          :current-project-id="displayedCurrentProjectId"
          :current-project-title="displayedCurrentProject?.title"
          :current-task-id="displayedCurrentTask?.id"
          :session-summary="displayedCurrentSession?.summary"
          @create="platform.addMemory"
          @confirm="platform.confirmMemory"
          @toggle="platform.toggleMemory"
          @update="platform.editMemory"
          @reject="platform.rejectMemory"
          @forget="platform.forgetMemory"
          @clear-summary="platform.clearSessionSummary"
        />
        <AiArtifactCard
          v-else-if="utilityPanel === 'artifacts'"
          :artifacts="displayedArtifacts"
          :task="displayedCurrentTask"
          :has-task="Boolean(displayedCurrentTask)"
          :disabled="running || previewMode"
          @create="platform.materialize"
          @remove="platform.removeArtifact"
        />
        <AiTaskTimeline v-else :events="events" />
      </aside>
    </div>
  </main>
</template>

<style scoped>
.ai-workspace {
  --ai-blue: #1d5be7;
  --ai-ink: #26354e;
  --ai-muted: #8895a9;
  display: grid;
  grid-template-columns: 368px minmax(0, 1fr);
  width: 100%;
  height: 100vh;
  min-height: 680px;
  overflow: hidden;
  color: var(--ai-ink);
  background: #fff;
  font-family: Inter, 'PingFang SC', 'Microsoft YaHei', sans-serif;
}

.ai-workspace__main {
  display: flex;
  min-width: 0;
  min-height: 0;
  flex-direction: column;
  background: #fff;
}

.ai-topbar {
  position: relative;
  z-index: 4;
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 94px;
  gap: 24px;
  border-bottom: 1px solid #e6ebf2;
  padding: 0 26px 0 34px;
  background: rgba(255, 255, 255, 0.97);
}

.ai-topbar__title {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 22px;
}

.ai-topbar h1 {
  overflow: hidden;
  margin: 0;
  color: #1e2a40;
  font-size: clamp(20px, 2vw, 27px);
  font-weight: 800;
  letter-spacing: -0.06em;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-context-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.ai-context-chip {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  border: 1px solid #dae4f3;
  border-radius: 8px;
  padding: 7px 11px;
  color: #4165bd;
  background: #f7faff;
  font-size: 13px;
  font-weight: 600;
  white-space: nowrap;
}

.ai-context-chip--project span {
  color: #3471ec;
  font-size: 18px;
}

.ai-context-chip--memory {
  border-color: #bde2c9;
  color: #3e8b5e;
  background: #f6fcf7;
}

.ai-context-chip--memory span {
  color: #3ea261;
}

.ai-topbar__controls {
  position: relative;
  display: flex;
  align-items: stretch;
  flex: 0 0 auto;
  min-height: 61px;
  border: 1px solid #d5dce7;
  border-radius: 11px;
  background: #fff;
}

.ai-selector {
  display: grid;
  min-width: 128px;
  align-content: center;
  gap: 2px;
  border-right: 1px solid #e2e7ef;
  padding: 0 18px;
}

.ai-selector--reasoning {
  min-width: 105px;
  border-right: 0;
}

.ai-selector span {
  color: #8995a7;
  font-size: 12px;
}

.ai-selector select {
  min-width: 0;
  border: 0;
  padding: 0;
  color: #27354c;
  background: transparent;
  font: inherit;
  font-size: 16px;
  font-weight: 500;
  outline: none;
}

.ai-overflow-button {
  align-self: center;
  width: 48px;
  height: 48px;
  margin: 0 7px 0 2px;
  border: 1px solid #d4dce8;
  border-radius: 8px;
  color: #44556f;
  background: #fff;
  font-size: 27px;
  line-height: 1;
  cursor: pointer;
}

.ai-overflow-button:hover,
.ai-overflow-button[aria-expanded='true'] {
  color: var(--ai-blue);
  border-color: #a9c0ee;
  background: #f5f8ff;
}

.ai-overflow-menu {
  position: absolute;
  z-index: 12;
  top: calc(100% + 8px);
  right: 7px;
  display: grid;
  min-width: 205px;
  gap: 3px;
  border: 1px solid #dce3ed;
  border-radius: 10px;
  padding: 8px;
  background: #fff;
  box-shadow: 0 14px 32px rgba(29, 51, 87, 0.16);
}

.ai-overflow-menu button {
  border: 0;
  border-radius: 7px;
  padding: 9px 10px;
  color: #42536e;
  background: transparent;
  font: inherit;
  font-size: 13px;
  text-align: left;
  cursor: pointer;
}

.ai-overflow-menu button:hover {
  color: var(--ai-blue);
  background: #f2f6ff;
}

.ai-conversation-area {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  scrollbar-color: #dce4f0 transparent;
  scrollbar-width: thin;
}

.ai-composer-zone {
  flex: none;
  border-top: 1px solid #e8edf3;
  background: #fff;
}

.ai-workspace__error,
.ai-loading {
  margin: 15px 28px 0;
  border-radius: 8px;
  padding: 10px 13px;
  color: #b34b4b;
  background: #fff4f4;
  font-size: 13px;
}

.ai-loading {
  color: #72819a;
  background: #f8faff;
}

.ai-utility-backdrop {
  position: fixed;
  z-index: 30;
  inset: 0;
  display: flex;
  justify-content: flex-end;
  padding-left: 368px;
  background: rgba(25, 39, 66, 0.25);
  backdrop-filter: blur(2px);
}

.ai-utility-drawer {
  width: min(500px, 100%);
  height: 100%;
  overflow: auto;
  border-left: 1px solid #dfe5ee;
  padding: 25px;
  background: #fff;
  box-shadow: -18px 0 45px rgba(32, 50, 84, 0.13);
}

.ai-utility-drawer > header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
  margin-bottom: 18px;
  border-bottom: 1px solid #e8edf3;
  padding-bottom: 18px;
}

.ai-utility-drawer > header p {
  margin: 0 0 5px;
  color: #8995a7;
  font-size: 11px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.ai-utility-drawer > header h2 {
  margin: 0;
  color: #26354e;
  font-size: 21px;
}

.ai-utility-drawer > header button {
  width: 34px;
  height: 34px;
  border: 1px solid #d9e1eb;
  border-radius: 50%;
  color: #61718a;
  background: #fff;
  font-size: 22px;
  line-height: 1;
  cursor: pointer;
}

@media (max-width: 1160px) {
  .ai-workspace {
    grid-template-columns: 310px minmax(0, 1fr);
  }

  .ai-topbar {
    align-items: flex-start;
    flex-direction: column;
    justify-content: center;
    padding-block: 15px;
  }

  .ai-topbar__controls {
    width: 100%;
  }

  .ai-selector {
    flex: 1;
  }

  .ai-utility-backdrop {
    padding-left: 310px;
  }
}

@media (max-width: 760px) {
  .ai-workspace {
    display: block;
    height: auto;
    min-height: 100vh;
    overflow: visible;
  }

  .ai-workspace__main {
    min-height: 56vh;
  }

  .ai-topbar {
    min-height: 0;
    gap: 15px;
    padding: 18px 14px;
  }

  .ai-topbar__title {
    align-items: flex-start;
    flex-direction: column;
    gap: 11px;
  }

  .ai-topbar__controls {
    overflow-x: auto;
  }

  .ai-selector {
    min-width: 105px;
    padding-inline: 11px;
  }

  .ai-selector select {
    font-size: 13px;
  }

  .ai-overflow-button {
    flex: 0 0 42px;
    width: 42px;
    height: 42px;
  }

  .ai-utility-backdrop {
    padding-left: 0;
  }

  .ai-utility-drawer {
    width: 100%;
    padding: 18px;
  }
}
</style>
