<script setup lang="ts">
import type { AiProject, AiSession, AiTask } from '../../api/ai';

const props = defineProps<{
  projects?: AiProject[];
  sessions: AiSession[];
  tasks?: AiTask[];
  currentSessionId?: number | null;
  currentTaskId?: string | null;
  memoryCount?: number;
  preview?: boolean;
}>();

const emit = defineEmits<{
  newSession: [];
  selectSession: [session: AiSession];
  selectTask: [task: AiTask];
  createProject: [];
  renameProject: [project: AiProject];
  toggleProject: [project: AiProject];
  openUtility: [panel: 'files' | 'memory' | 'artifacts' | 'timeline'];
}>();

function sessionsFor(projectId: number | null) {
  return props.sessions.filter((session) => session.projectId === projectId && session.status !== 'DELETED');
}

function taskFor(sessionId: number) {
  return props.tasks?.find((task) => task.sessionId === sessionId) ?? null;
}

function sessionLabel(session: AiSession) {
  return session.title?.trim() || '新对话';
}

function selectFirst(projectId: number) {
  const session = sessionsFor(projectId)[0];
  if (session) emit('selectSession', session);
}
</script>

<template>
  <aside class="ai-sidebar" data-testid="ai-sidebar" aria-label="AI 工作台导航">
    <div class="ai-sidebar__scroll">
      <header class="ai-brand">
        <div class="ai-brand__stamp" aria-hidden="true">拾光<br />录</div>
        <div>
          <strong>拾光录 AI</strong>
          <small>统一对话与多模态工作台</small>
        </div>
      </header>

      <button type="button" class="ai-new-chat" @click="emit('newSession')">
        <span aria-hidden="true">＋</span>
        <strong>新对话</strong>
      </button>

      <button type="button" class="ai-memory-link" @click="emit('openUtility', 'memory')">
        <span class="ai-memory-link__icon" aria-hidden="true">✧</span>
        <span>真实记忆</span>
        <b>{{ preview ? 12 : (memoryCount ?? 0) }}</b>
      </button>

      <div class="ai-sidebar__section-head">
        <span>项目</span>
        <button type="button" class="ai-sidebar__add" aria-label="新建项目" @click="emit('createProject')">
          ＋
        </button>
      </div>

      <nav class="ai-project-tree" aria-label="项目与会话">
        <section v-for="project in projects ?? []" :key="project.id" class="ai-project-group">
          <div class="ai-project-title">
            <button
              type="button"
              class="ai-project-title__button"
              :disabled="!sessionsFor(project.id).length"
              @click="selectFirst(project.id)"
            >
              <span class="ai-project-chevron" aria-hidden="true">⌄</span>
              <span class="ai-folder-icon" aria-hidden="true">▱</span>
              <strong>{{ project.title }}</strong>
            </button>
            <div class="ai-project-actions">
              <button type="button" title="重命名项目" @click="emit('renameProject', project)">···</button>
              <button
                type="button"
                :title="project.status === 'ACTIVE' ? '归档项目' : '恢复项目'"
                @click="emit('toggleProject', project)"
              >
                {{ project.status === 'ACTIVE' ? '−' : '↺' }}
              </button>
            </div>
          </div>
          <ul>
            <li v-for="session in sessionsFor(project.id)" :key="session.id">
              <button
                type="button"
                class="ai-session-link"
                :class="{ active: session.id === currentSessionId }"
                @click="emit('selectSession', session)"
              >
                <span class="ai-session-link__icon" aria-hidden="true">▤</span>
                <span>{{ sessionLabel(session) }}</span>
                <small v-if="taskFor(session.id)?.status === 'RUNNING'">运行中</small>
              </button>
            </li>
          </ul>
        </section>

        <section class="ai-project-group ai-project-group--recent">
          <div class="ai-project-title ai-project-title--plain">
            <span class="ai-recent-icon" aria-hidden="true">◷</span>
            <strong>最近聊天</strong>
            <small>{{ sessionsFor(null).length }}</small>
          </div>
          <ul>
            <li v-for="session in sessionsFor(null)" :key="session.id">
              <button
                type="button"
                class="ai-session-link"
                :class="{ active: session.id === currentSessionId }"
                @click="emit('selectSession', session)"
              >
                <span class="ai-session-link__icon" aria-hidden="true">▤</span>
                <span>{{ sessionLabel(session) }}</span>
                <small v-if="taskFor(session.id)?.status === 'RUNNING'">运行中</small>
              </button>
            </li>
          </ul>
        </section>
      </nav>

      <p v-if="!(sessions.length || (projects?.length ?? 0)) && !preview" class="ai-sidebar__empty">
        还没有会话，开始一段新对话吧。
      </p>
    </div>

    <footer class="ai-sidebar__footer">
      <div class="ai-account">
        <div class="ai-account__avatar" aria-hidden="true">H</div>
        <div>
          <strong>Hfff</strong>
          <small>管理员</small>
        </div>
        <button type="button" aria-label="账户菜单">⌄</button>
      </div>
      <div class="ai-sidebar__footer-links">
        <button type="button" @click="emit('openUtility', 'timeline')"><span>⚙</span> 设置</button>
        <RouterLink to="/admin"><span>↪</span> 返回管理后台</RouterLink>
      </div>
    </footer>
  </aside>
</template>

<style scoped>
.ai-sidebar {
  display: flex;
  flex: 0 0 368px;
  flex-direction: column;
  min-width: 0;
  height: 100vh;
  border-right: 1px solid #e7ebf2;
  background: #fff;
  color: #26334a;
}

.ai-sidebar__scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 24px 26px 18px;
  scrollbar-width: thin;
  scrollbar-color: #d9e1ef transparent;
}

.ai-brand {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 0 2px 24px;
}

.ai-brand__stamp {
  display: grid;
  place-items: center;
  width: 48px;
  height: 48px;
  border: 2px solid #1e59e8;
  border-radius: 5px;
  color: #1e59e8;
  font-family: 'Noto Serif SC', Georgia, serif;
  font-size: 15px;
  font-weight: 800;
  line-height: 1.05;
  letter-spacing: 0.04em;
}

.ai-brand strong,
.ai-brand small {
  display: block;
}

.ai-brand strong {
  color: #151f32;
  font-size: 21px;
  font-weight: 800;
  letter-spacing: -0.04em;
}

.ai-brand small {
  margin-top: 4px;
  color: #8d98aa;
  font-size: 11px;
  letter-spacing: 0.02em;
}

.ai-new-chat {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  min-height: 49px;
  border: 0;
  border-radius: 8px;
  color: #fff;
  background: linear-gradient(135deg, #1b5cf0, #2552d4);
  box-shadow: 0 9px 20px rgba(31, 91, 230, 0.18);
  font: inherit;
  cursor: pointer;
}

.ai-new-chat span {
  margin-right: 8px;
  font-size: 24px;
  font-weight: 300;
  line-height: 1;
}

.ai-new-chat strong {
  font-size: 17px;
  font-weight: 700;
}

.ai-memory-link {
  display: grid;
  grid-template-columns: 25px 1fr auto;
  align-items: center;
  width: 100%;
  margin-top: 27px;
  border: 1px solid #dfe5ee;
  border-radius: 8px;
  padding: 13px 14px;
  color: #2f3d55;
  background: #fff;
  font: inherit;
  font-size: 15px;
  font-weight: 600;
  text-align: left;
  cursor: pointer;
}

.ai-memory-link:hover,
.ai-memory-link:focus-visible {
  border-color: #b8caf3;
  background: #f8fbff;
}

.ai-memory-link__icon {
  color: #1f5be8;
  font-size: 20px;
}

.ai-memory-link b {
  min-width: 34px;
  border-radius: 7px;
  padding: 3px 8px;
  color: #2a65e8;
  background: #edf3ff;
  text-align: center;
  font-size: 13px;
  font-weight: 700;
}

.ai-sidebar__section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 30px 0 12px;
  color: #526078;
  font-size: 14px;
  font-weight: 700;
}

.ai-sidebar__add {
  width: 30px;
  height: 30px;
  border: 1px solid #d6deea;
  border-radius: 6px;
  color: #506176;
  background: #fff;
  font-size: 22px;
  font-weight: 300;
  line-height: 1;
  cursor: pointer;
}

.ai-sidebar__add:hover,
.ai-sidebar__add:focus-visible {
  color: #1d5be8;
  border-color: #a9c0f2;
}

.ai-project-tree {
  display: grid;
  gap: 14px;
}

.ai-project-group {
  min-width: 0;
}

.ai-project-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-width: 0;
  color: #344159;
}

.ai-project-title__button {
  display: flex;
  align-items: center;
  min-width: 0;
  gap: 7px;
  border: 0;
  padding: 5px 0;
  color: inherit;
  background: transparent;
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.ai-project-title__button:disabled {
  cursor: default;
}

.ai-project-title strong {
  overflow: hidden;
  font-size: 14px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-project-chevron {
  color: #64738a;
  font-size: 15px;
}

.ai-folder-icon {
  color: #4e6179;
  font-size: 22px;
  line-height: 1;
  transform: rotate(90deg);
}

.ai-project-actions {
  display: flex;
  gap: 2px;
  opacity: 0;
  transition: opacity 0.15s ease;
}

.ai-project-title:hover .ai-project-actions,
.ai-project-actions:focus-within {
  opacity: 1;
}

.ai-project-actions button {
  border: 0;
  padding: 2px 4px;
  color: #8592a7;
  background: transparent;
  font: inherit;
  font-size: 14px;
  cursor: pointer;
}

.ai-project-group ul {
  display: grid;
  gap: 3px;
  margin: 6px 0 0 14px;
  padding: 0;
  list-style: none;
}

.ai-session-link {
  display: grid;
  grid-template-columns: 18px minmax(0, 1fr) auto;
  align-items: center;
  width: 100%;
  min-height: 42px;
  gap: 7px;
  border: 1px solid transparent;
  border-radius: 7px;
  padding: 0 10px;
  color: #536178;
  background: transparent;
  font: inherit;
  font-size: 14px;
  text-align: left;
  cursor: pointer;
}

.ai-session-link:hover {
  background: #f6f8fb;
}

.ai-session-link.active {
  border-color: #e0e8fb;
  color: #1f5ce4;
  background: #eaf1ff;
  font-weight: 700;
}

.ai-session-link__icon {
  color: #52637c;
  font-size: 17px;
}

.ai-session-link.active .ai-session-link__icon {
  color: #1e5ce8;
}

.ai-session-link span:nth-child(2) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-session-link small,
.ai-project-title--plain small {
  color: #8d98aa;
  font-size: 11px;
  font-weight: 500;
}

.ai-project-group--recent {
  margin-top: 15px;
  border-top: 1px solid #e9edf3;
  padding-top: 18px;
}

.ai-project-title--plain {
  justify-content: flex-start;
  gap: 7px;
}

.ai-project-title--plain small {
  margin-left: auto;
}

.ai-recent-icon {
  color: #576980;
  font-size: 20px;
  line-height: 1;
}

.ai-sidebar__empty {
  margin: 25px 4px;
  color: #8a95a8;
  font-size: 13px;
  line-height: 1.7;
}

.ai-sidebar__footer {
  display: flex;
  min-height: 212px;
  box-sizing: border-box;
  flex-direction: column;
  flex: none;
  border-top: 1px solid #e6ebf2;
  padding: 16px 26px 18px;
}

.ai-account {
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr) auto;
  align-items: center;
  gap: 11px;
}

.ai-account__avatar {
  display: grid;
  place-items: center;
  width: 42px;
  height: 42px;
  border-radius: 50%;
  color: #fff;
  background: linear-gradient(145deg, #1762e8, #2d83ff);
  font-size: 17px;
  font-weight: 800;
}

.ai-account strong,
.ai-account small {
  display: block;
}

.ai-account strong {
  color: #29374d;
  font-size: 14px;
}

.ai-account small {
  margin-top: 3px;
  color: #8d98aa;
  font-size: 12px;
}

.ai-account button {
  border: 0;
  color: #586a83;
  background: transparent;
  font-size: 20px;
  cursor: pointer;
}

.ai-sidebar__footer-links {
  display: grid;
  grid-template-columns: 1fr 1.4fr;
  gap: 10px;
  margin-top: auto;
}

.ai-sidebar__footer-links button,
.ai-sidebar__footer-links a {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  border: 0;
  color: #56647b;
  background: transparent;
  font: inherit;
  font-size: 13px;
  text-decoration: none;
  cursor: pointer;
}

.ai-sidebar__footer-links button:hover,
.ai-sidebar__footer-links a:hover {
  color: #1d5be7;
}

.ai-sidebar__footer-links span {
  font-size: 18px;
}

@media (max-width: 1020px) {
  .ai-sidebar {
    flex-basis: 300px;
  }

  .ai-sidebar__scroll,
  .ai-sidebar__footer {
    padding-inline: 19px;
  }
}

@media (max-width: 760px) {
  .ai-sidebar {
    height: auto;
    max-height: 44vh;
    border-right: 0;
    border-bottom: 1px solid #e7ebf2;
  }

  .ai-sidebar__scroll {
    padding-bottom: 12px;
  }

  .ai-sidebar__footer {
    display: none;
  }
}
</style>
