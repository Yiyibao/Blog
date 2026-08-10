<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import {
  AiStreamHttpError,
  appendAiChatMessages,
  createAiChatSession,
  deleteAiChatSession,
  fetchAiChatSessionMessages,
  fetchAiChatSessions,
  logout as apiLogout,
  getAdminSessionName,
  streamAiChat,
  type AiChatMessage,
  type AiChatSession,
  type AiReasoningSelection,
} from '../api/admin';
import { useAiStore } from '../stores/aiStore';
import AdminSidebar from './AdminSidebar.vue';
import { AI_CHAT_MAX_INPUT_CHARS } from '../config/aiLimits';

/**
 * 4A-4：compact=true 时去掉页面级 chrome（侧导航/顶栏），只渲染对话核心——供 AdminPetAssistant 面板复用；
 * providerId/model 由宿主（宠物助手面板）注入，全屏页缺省走默认供应商。
 * 会话存于 sessionStorage 同一键，面板与全屏两形态天然共享上下文。
 * 供应商/模型选择统一由 aiStore 维护（全屏页、宠物面板、供应商页三处共享并互相同步）。
 */
const props = withDefaults(
  defineProps<{
    compact?: boolean;
    providerId?: number | null;
    model?: string | null;
  }>(),
  {
    compact: false,
    providerId: null,
    model: null,
  },
);

/**
 * 事件仅供宿主（AdminPetAssistant）驱动宠物动画，不复制聊天状态与请求逻辑；
 * 401 仍走原有 logout + 跳转，宿主不得吞掉认证错误。
 */
const emit = defineEmits<{
  'stream-start': [];
  'stream-first-delta': [];
  'stream-complete': [];
  'stream-error': [];
  'stream-abort': [];
}>();

const STORAGE_KEY = 'yubai-admin-ai-messages';

const router = useRouter();
const ai = useAiStore();
const username = getAdminSessionName() || 'Admin';
const userInput = ref('');
const loading = ref(false);
/** 4A-2：收到首个增量后隐藏「思考中…」占位，改由增量气泡实时呈现 */
const streamingStarted = ref(false);
const error = ref('');
const chatBoxRef = ref<HTMLElement | null>(null);
let abortController: AbortController | null = null;

const selectedProvider = computed(() => ai.selectedProvider);

const modelOptions = computed(() => ai.modelOptions);

const reasoningOptions: Array<{ value: AiReasoningSelection; label: string }> = [
  { value: 'auto', label: '自动（供应商默认）' },
  { value: 'none', label: '关闭' },
  { value: 'minimal', label: '极低' },
  { value: 'low', label: '低' },
  { value: 'medium', label: '中' },
  { value: 'high', label: '高' },
  { value: 'xhigh', label: '极高' },
];

/**
 * The selected effort is sent only to protocols that have a real effort
 * parameter. OpenCode's session API does not expose a portable effort field;
 * leaving its control disabled is preferable to pretending that a UI toggle
 * changes the model.
 */
const reasoningSupported = computed(() => {
  const provider = selectedProvider.value;
  if (!provider || !provider.providerType) return true;
  if (provider.providerType === 'OPENCODE_SERVER') return false;
  if (provider.providerType === 'OPENAI_COMPATIBLE' && provider.baseUrl.toLowerCase().includes('deepseek'))
    return false;
  return (
    provider.providerType === 'OPENAI_RESPONSES' ||
    provider.providerType === 'OPENAI_COMPATIBLE' ||
    provider.providerType === 'ANTHROPIC'
  );
});

function loadStoredMessages(): AiChatMessage[] {
  try {
    const raw = window.sessionStorage?.getItem(STORAGE_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) throw new Error('Invalid stored messages');
    const valid: AiChatMessage[] = [];
    for (const item of parsed) {
      if (
        item &&
        typeof item === 'object' &&
        (item.role === 'user' || item.role === 'assistant') &&
        typeof item.content === 'string' &&
        item.content.trim() &&
        item.content.length <= AI_CHAT_MAX_INPUT_CHARS
      ) {
        valid.push({ role: item.role as 'user' | 'assistant', content: item.content });
      } else {
        throw new Error('Invalid stored message');
      }
    }
    const recent = valid.slice(-20);
    if (recent.length !== valid.length) saveMessagesToStorage(recent);
    return recent;
  } catch {
    try {
      window.sessionStorage?.removeItem(STORAGE_KEY);
    } catch {
      // ignore
    }
    return [];
  }
}

function saveMessagesToStorage(msgList: AiChatMessage[]) {
  try {
    window.sessionStorage?.setItem(STORAGE_KEY, JSON.stringify(msgList.slice(-20)));
  } catch {
    // Privacy mode fallback
  }
}

const messages = ref<AiChatMessage[]>(loadStoredMessages());
const sessions = ref<AiChatSession[]>([]);
const sessionsLoading = ref(false);
const sessionsError = ref('');
const currentSessionId = ref<number | null>(null);
const sidebarOpen = ref(true);

async function scrollToBottom() {
  await nextTick();
  if (chatBoxRef.value) {
    chatBoxRef.value.scrollTop = chatBoxRef.value.scrollHeight;
  }
}

function logout() {
  apiLogout();
  void router.replace('/admin/login');
}

function clearConversation() {
  if (!window.confirm('确认清空所有对话记录？')) return;
  messages.value = [];
  error.value = '';
  currentSessionId.value = null;
  try {
    window.sessionStorage?.removeItem(STORAGE_KEY);
  } catch {
    // ignore
  }
}

async function loadSessions() {
  sessionsLoading.value = true;
  sessionsError.value = '';
  try {
    sessions.value = await fetchAiChatSessions();
  } catch (cause) {
    sessionsError.value = cause instanceof Error ? cause.message : '聊天记录加载失败';
  } finally {
    sessionsLoading.value = false;
  }
}

function newChat() {
  abortController?.abort();
  currentSessionId.value = null;
  messages.value = [];
  error.value = '';
  saveMessagesToStorage([]);
  void nextTick(() => scrollToBottom());
}

async function openSession(session: AiChatSession) {
  if (loading.value) abortController?.abort();
  if (currentSessionId.value === session.id) return;
  currentSessionId.value = session.id;
  error.value = '';
  try {
    const records = await fetchAiChatSessionMessages(session.id);
    messages.value = records.map((record) => ({ role: record.role, content: record.content })).slice(-100);
    saveMessagesToStorage(messages.value);
  } catch (cause) {
    currentSessionId.value = null;
    error.value = cause instanceof Error ? cause.message : '聊天记录加载失败';
  }
  await scrollToBottom();
}

async function deleteSession(session: AiChatSession) {
  if (!window.confirm('确认删除这条聊天记录？')) return;
  try {
    await deleteAiChatSession(session.id);
    if (currentSessionId.value === session.id) newChat();
    await loadSessions();
  } catch (cause) {
    sessionsError.value = cause instanceof Error ? cause.message : '删除聊天记录失败';
  }
}

function formatSessionTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(new Date(value));
}

async function persistExchange(sessionId: number, userMsg: AiChatMessage, assistantContent: string) {
  const batch = assistantContent.trim()
    ? [userMsg, { role: 'assistant' as const, content: assistantContent }]
    : [userMsg];
  try {
    await appendAiChatMessages(sessionId, batch);
    await loadSessions();
  } catch {
    // 历史保存失败不阻断对话主流程
  }
}

function handleKeyDown(event: KeyboardEvent) {
  if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
    event.preventDefault();
    void sendMessage();
  }
}

async function sendMessage() {
  const content = userInput.value.trim();
  if (!content || loading.value || content.length > AI_CHAT_MAX_INPUT_CHARS) return;

  const userMsg: AiChatMessage = { role: 'user', content };
  // 发送给后端的历史窗口维持 20 条（与服务端限额一致）；
  // 展示层放宽到 100 条，避免达到 20 条后发送瞬间最早一条消息凭空消失
  const history = [...messages.value, userMsg].slice(-20);
  messages.value = [...messages.value, userMsg].slice(-100);
  saveMessagesToStorage(messages.value);

  userInput.value = '';
  error.value = '';
  loading.value = true;
  streamingStarted.value = false;
  await scrollToBottom();

  // 4A-2：流式渲染——先挂空的助手气泡，增量到达时就地追加（必须改代理对象保证响应式）
  const liveSeed: AiChatMessage = { role: 'assistant', content: '' };
  messages.value = [...messages.value, liveSeed].slice(-100);
  const live = messages.value[messages.value.length - 1]!;

  // 首次发送时先创建会话记录，流式结束再把本轮问答写入历史（仅全屏形态）
  let sessionId = currentSessionId.value;
  if (!props.compact && sessionId == null) {
    try {
      const session = await createAiChatSession();
      sessionId = session.id;
      currentSessionId.value = session.id;
    } catch {
      messages.value = messages.value.filter((msg) => msg !== live);
      error.value = '无法创建聊天记录，请稍后重试。';
      loading.value = false;
      await scrollToBottom();
      return;
    }
  }

  const controller = new AbortController();
  abortController = controller;
  emit('stream-start');

  try {
    const providerId = props.providerId ?? ai.selectedProviderId;
    const model = props.model ?? ai.selectedModel;
    const reasoningEffort = ai.selectedReasoningEffort;
    await streamAiChat(
      history,
      {
        onDelta: (text) => {
          if (!streamingStarted.value) emit('stream-first-delta');
          streamingStarted.value = true;
          live.content += text;
          void scrollToBottom();
        },
      },
      {
        signal: controller.signal,
        ...(providerId != null ? { providerId } : {}),
        ...(model ? { model } : {}),
        ...(reasoningSupported.value && reasoningEffort !== 'auto' ? { reasoningEffort } : {}),
      },
    );
    if (!live.content.trim()) {
      throw new AiStreamHttpError(502, 'empty response');
    }
    saveMessagesToStorage(messages.value);
    emit('stream-complete');
  } catch (cause) {
    if (controller.signal.aborted) {
      // 用户主动停止：保留已生成的部分；一无所出则移除空气泡
      emit('stream-abort');
      if (live.content.trim()) {
        saveMessagesToStorage(messages.value);
      } else {
        messages.value = messages.value.filter((msg) => msg !== live);
      }
    } else if (cause instanceof AiStreamHttpError && cause.status === 401) {
      apiLogout();
      void router.replace('/admin/login');
      return;
    } else {
      messages.value = messages.value.filter((msg) => msg !== live);
      // 展示后端返回的安全、可理解错误（固定中文文案，不含供应商原始响应）；
      // 内部标记（如 empty response）与网络级异常回退到通用文案
      const detail =
        cause instanceof AiStreamHttpError && cause.message && cause.message !== 'empty response'
          ? cause.message
          : '';
      error.value = detail || 'AI 响应失败，请检查网络或稍后重试。';
      emit('stream-error');
    }
  } finally {
    loading.value = false;
    streamingStarted.value = false;
    abortController = null;
    if (!props.compact && sessionId != null) void persistExchange(sessionId, userMsg, live.content);
    await scrollToBottom();
  }
}

function stopStreaming() {
  abortController?.abort();
}

onMounted(() => {
  // 面板宿主（AdminPetAssistant）负责 compact 形态的供应商/模型加载
  if (!props.compact) {
    void ai.ensureProviders();
    ai.subscribe();
    void loadSessions();
  }
  void scrollToBottom();
});

// 宿主销毁（收起面板 / logout / 路由切换）时立即中止流式请求，避免后台继续消耗配额
onBeforeUnmount(() => {
  if (!props.compact) ai.unsubscribe();
  abortController?.abort();
});
</script>

<template>
  <section :class="props.compact ? 'ai-chat-compact-root' : 'admin-console ai-chat-console'">
    <AdminSidebar v-if="!props.compact" />

    <main :class="props.compact ? 'ai-chat-compact-main' : 'admin-main ai-chat-main'">
      <header v-if="!props.compact" class="admin-topbar">
        <div>
          <span class="admin-breadcrumb">后台管理 / AI 助手</span>
          <h1>AI 助手对话</h1>
        </div>
        <div class="topbar-actions">
          <button v-if="messages.length" class="clear-btn" type="button" @click="clearConversation">
            清空对话
          </button>
          <RouterLink to="/">查看博客 ↗</RouterLink>
          <button @click="logout">退出登录</button>
        </div>
      </header>
      <div v-else-if="messages.length" class="compact-toolbar">
        <button class="clear-btn" type="button" @click="clearConversation">清空对话</button>
      </div>

      <div class="ai-chat-container" :class="{ 'sidebar-hidden': !sidebarOpen }">
        <template v-if="!props.compact">
          <aside class="chat-history-panel">
            <div class="chat-history-inner">
              <button class="new-chat-btn" type="button" @click="newChat">＋ 新建聊天</button>
              <p v-if="sessionsError" class="sessions-error" role="alert">{{ sessionsError }}</p>
              <ul v-if="sessions.length" class="session-list">
                <li
                  v-for="session in sessions"
                  :key="session.id"
                  class="session-item"
                  :class="{ active: session.id === currentSessionId }"
                >
                  <button type="button" class="session-entry" @click="openSession(session)">
                    <span class="session-title">{{ session.title || '新对话' }}</span>
                    <span class="session-time">{{ formatSessionTime(session.updatedAt) }}</span>
                  </button>
                  <button
                    type="button"
                    class="session-delete"
                    title="删除这条聊天记录"
                    aria-label="删除聊天记录"
                    @click="deleteSession(session)"
                  >
                    ×
                  </button>
                </li>
              </ul>
              <p v-else-if="!sessionsLoading" class="sessions-empty">暂无聊天记录</p>
            </div>
          </aside>
          <button
            type="button"
            class="sidebar-toggle"
            :aria-label="sidebarOpen ? '隐藏聊天记录' : '展开聊天记录'"
            @click="sidebarOpen = !sidebarOpen"
          >
            {{ sidebarOpen ? '◀' : '▶' }}
          </button>
        </template>

        <div class="chat-main-col">
          <div ref="chatBoxRef" class="chat-messages" role="log" aria-live="polite">
            <div v-if="!messages.length && !loading" class="chat-welcome">
              <div class="welcome-icon">🤖</div>
              <h2>管理员 AI 助手</h2>
              <p>基于可配置的大模型供应商，协助文章撰写、代码重构与内容总结。</p>
              <small>最多保存最近 20 条消息 · 支持 8,000 字长文本输入</small>
            </div>

            <div
              v-for="(msg, index) in messages"
              v-show="msg.role === 'user' || msg.content !== ''"
              :key="index"
              class="chat-bubble-wrap"
              :class="msg.role"
            >
              <div class="bubble-avatar">
                {{ msg.role === 'user' ? username.slice(0, 1).toUpperCase() : '🤖' }}
              </div>
              <div class="bubble-body">
                <header class="bubble-header">
                  <span class="sender-name">{{ msg.role === 'user' ? username : 'AI 助手' }}</span>
                </header>
                <div class="bubble-content">{{ msg.content }}</div>
              </div>
            </div>

            <div v-if="loading && !streamingStarted" class="chat-bubble-wrap assistant loading-bubble">
              <div class="bubble-avatar">🤖</div>
              <div class="bubble-body">
                <header class="bubble-header">
                  <span class="sender-name">AI 助手</span>
                </header>
                <div class="bubble-content loading-indicator">
                  <span class="dot" /><span class="dot" /><span class="dot" />
                  <span class="loading-text">思考中…</span>
                </div>
              </div>
            </div>
          </div>

          <div v-if="error" class="chat-error-bar" role="alert">
            <span>{{ error }}</span>
          </div>

          <div class="chat-input-area">
            <div class="input-wrapper">
              <textarea
                v-model="userInput"
                class="chat-textarea"
                data-testid="ai-chat-input"
                placeholder="输入消息，按 Ctrl + Enter 或 Cmd + Enter 快速发送…"
                :maxlength="AI_CHAT_MAX_INPUT_CHARS"
                rows="3"
                :disabled="loading"
                @keydown="handleKeyDown"
              />
              <div v-if="!props.compact" class="chat-model-picker">
                <label v-if="ai.providers.length > 1" for="ai-chat-provider">供应商</label>
                <select
                  v-if="ai.providers.length > 1"
                  id="ai-chat-provider"
                  class="chat-model-select"
                  data-testid="chat-provider-select"
                  aria-label="选择供应商"
                  :value="ai.selectedProviderId ?? ''"
                  :disabled="loading"
                  @change="ai.selectProvider(($event.target as HTMLSelectElement).value)"
                >
                  <option v-for="provider in ai.providers" :key="provider.id" :value="provider.id">
                    {{ provider.name }}
                  </option>
                </select>
                <label for="ai-chat-model">模型</label>
                <select
                  id="ai-chat-model"
                  class="chat-model-select"
                  data-testid="chat-model-select"
                  aria-label="选择模型"
                  :value="ai.selectedModel ?? ''"
                  :disabled="loading || !modelOptions.length"
                  @change="ai.selectModel(($event.target as HTMLSelectElement).value)"
                >
                  <option v-if="!modelOptions.length" value="">暂无可用模型</option>
                  <option v-for="modelOption in modelOptions" :key="modelOption" :value="modelOption">
                    {{ modelOption }}
                  </option>
                </select>
                <label for="ai-chat-reasoning">推理强度</label>
                <select
                  id="ai-chat-reasoning"
                  class="chat-model-select chat-reasoning-select"
                  data-testid="chat-reasoning-select"
                  aria-label="选择推理强度"
                  :value="ai.selectedReasoningEffort"
                  :disabled="loading || !reasoningSupported"
                  @change="
                    ai.selectReasoningEffort(
                      ($event.target as HTMLSelectElement).value as AiReasoningSelection,
                    )
                  "
                >
                  <option v-for="option in reasoningOptions" :key="option.value" :value="option.value">
                    {{ option.label }}
                  </option>
                </select>
                <span v-if="!reasoningSupported" class="chat-model-provider">当前供应商不支持可调推理</span>
                <span v-if="selectedProvider && ai.providers.length <= 1" class="chat-model-provider">{{
                  selectedProvider.name
                }}</span>
              </div>
              <div class="input-footer">
                <span
                  class="char-count"
                  :class="{ 'near-limit': userInput.length > AI_CHAT_MAX_INPUT_CHARS - 1_000 }"
                >
                  {{ userInput.length.toLocaleString() }} / {{ AI_CHAT_MAX_INPUT_CHARS.toLocaleString() }} 字
                </span>
                <div class="footer-actions">
                  <button v-if="loading" class="stop-btn" type="button" @click="stopStreaming">
                    停止生成
                  </button>
                  <button
                    class="send-btn"
                    type="button"
                    :disabled="loading || !userInput.trim() || userInput.length > AI_CHAT_MAX_INPUT_CHARS"
                    @click="sendMessage"
                  >
                    {{ loading ? '发送中…' : '发送 ↗' }}
                  </button>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </main>
  </section>
</template>

<style scoped>
/* 4A-4：停靠形态——铺满宿主容器，无页面级 chrome */
.ai-chat-compact-root {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.ai-chat-compact-main {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}
.ai-chat-compact-main .ai-chat-container {
  flex: 1;
  min-height: 0;
}
.compact-toolbar {
  display: flex;
  justify-content: flex-end;
  padding: 6px 10px 0;
}

.ai-chat-console {
  min-height: 100vh;
}
.ai-chat-main {
  display: flex;
  flex-direction: column;
  height: 100vh;
  padding-bottom: 0 !important;
}
.topbar-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}
.clear-btn {
  color: color-mix(in srgb, #b84f48 74%, var(--ink, #20211e)) !important;
  border-color: rgba(184, 79, 72, 0.3) !important;
}
.clear-btn:hover {
  background: rgba(184, 79, 72, 0.08) !important;
}

.ai-chat-container {
  display: flex;
  flex-direction: row;
  flex: 1;
  min-height: 0;
  position: relative;
  margin-top: 16px;
  border: 1px solid var(--line-strong, #d9d6cf);
  border-radius: 16px;
  background: var(--surface-solid, #fffdfb);
  box-shadow: 0 10px 40px rgba(34, 32, 27, 0.04);
  overflow: hidden;
}

.chat-history-panel {
  flex-shrink: 0;
  width: 250px;
  overflow: hidden;
  border-right: 1px solid var(--line-strong, #d9d6cf);
  background: var(--surface, #faf8f5);
  transition:
    width 0.25s ease,
    border-right-width 0.25s ease;
}
.chat-history-inner {
  display: flex;
  flex-direction: column;
  width: 250px;
  height: 100%;
  padding: 14px 12px;
  box-sizing: border-box;
}
.ai-chat-container.sidebar-hidden .chat-history-panel {
  width: 0;
  border-right-width: 0;
}
.new-chat-btn {
  flex-shrink: 0;
  margin-bottom: 12px;
  padding: 10px 14px;
  border: 1px solid var(--line-strong, #d9d6cf);
  border-radius: 10px;
  background: var(--surface-solid, #ffffff);
  color: var(--ink, #20211e);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition:
    border-color 0.2s,
    background 0.2s,
    transform 0.2s;
}
.new-chat-btn:hover {
  border-color: var(--accent, #a17450);
  background: color-mix(in srgb, var(--accent, #a17450) 8%, var(--surface-solid, #ffffff));
  transform: translateY(-1px);
}
.session-list {
  list-style: none;
  margin: 0;
  padding: 0;
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.session-item {
  position: relative;
  display: flex;
  align-items: center;
  border: 1px solid transparent;
  border-radius: 10px;
}
.session-item:hover {
  background: color-mix(in srgb, var(--ink, #20211e) 4%, transparent);
}
.session-item.active {
  background: color-mix(in srgb, var(--accent, #a17450) 12%, var(--surface-solid, #ffffff));
  border-color: color-mix(in srgb, var(--accent, #a17450) 35%, transparent);
}
.session-entry {
  flex: 1;
  min-width: 0;
  padding: 9px 10px;
  border: 0;
  background: none;
  text-align: left;
  cursor: pointer;
}
.session-title {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  color: var(--ink, #20211e);
}
.session-time {
  display: block;
  margin-top: 3px;
  font-size: 10px;
  color: var(--muted, #7f7e77);
}
.session-delete {
  flex-shrink: 0;
  margin-right: 6px;
  width: 22px;
  height: 22px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--muted, #7f7e77);
  font-size: 15px;
  line-height: 1;
  cursor: pointer;
  opacity: 0;
  transition:
    opacity 0.15s,
    background 0.15s,
    color 0.15s;
}
.session-item:hover .session-delete,
.session-item.active .session-delete {
  opacity: 1;
}
.session-delete:hover {
  background: color-mix(in srgb, #b84f48 12%, transparent);
  color: color-mix(in srgb, #b84f48 74%, var(--ink, #20211e));
}
.sessions-empty {
  margin: 18px 0 0;
  color: var(--muted, #7f7e77);
  font-size: 12px;
  text-align: center;
}
.sessions-error {
  margin: 0 0 10px;
  padding: 8px 10px;
  border-radius: 8px;
  background: color-mix(in srgb, #b84f48 10%, var(--surface-solid, #fffdfb));
  color: color-mix(in srgb, #b84f48 74%, var(--ink, #20211e));
  font-size: 12px;
}
.sidebar-toggle {
  position: absolute;
  top: 50%;
  left: 250px;
  transform: translate(-50%, -50%);
  z-index: 5;
  width: 26px;
  height: 44px;
  padding: 0;
  border: 1px solid var(--line-strong, #d9d6cf);
  border-radius: 8px;
  background: var(--surface-solid, #ffffff);
  color: var(--muted, #7f7e77);
  font-size: 11px;
  cursor: pointer;
  box-shadow: 0 4px 12px rgba(34, 32, 27, 0.12);
  transition:
    left 0.25s ease,
    color 0.2s,
    border-color 0.2s;
}
.sidebar-toggle:hover {
  color: var(--accent, #a17450);
  border-color: var(--accent, #a17450);
}
.ai-chat-container.sidebar-hidden .sidebar-toggle {
  left: 0;
}

.chat-main-col {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.chat-welcome {
  margin: auto;
  text-align: center;
  max-width: 440px;
  padding: 40px 20px;
}
.welcome-icon {
  font-size: 48px;
  margin-bottom: 16px;
}
.chat-welcome h2 {
  font:
    500 24px Georgia,
    'Noto Serif SC',
    serif;
  margin: 0 0 8px;
  color: var(--ink, #20211e);
}
.chat-welcome p {
  color: var(--muted, #7f7e77);
  font-size: 14px;
  line-height: 1.6;
  margin: 0 0 12px;
}
.chat-welcome small {
  color: var(--accent, #a17450);
  font-size: 11px;
}

.chat-bubble-wrap {
  display: flex;
  gap: 12px;
  max-width: 82%;
}
.chat-bubble-wrap.user {
  align-self: flex-end;
  flex-direction: row-reverse;
}
.chat-bubble-wrap.assistant {
  align-self: flex-start;
}

.bubble-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  flex-shrink: 0;
  font:
    600 14px Georgia,
    serif;
}
.user .bubble-avatar {
  background: var(--ink, #292a27);
  color: var(--paper, #f8f5ee);
}
.assistant .bubble-avatar {
  background: var(--accent, #d5b18a);
  color: #252521;
}

.bubble-body {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.user .bubble-body {
  align-items: flex-end;
}
.assistant .bubble-body {
  align-items: flex-start;
}

.bubble-header {
  margin-bottom: 4px;
}
.sender-name {
  font-size: 11px;
  color: var(--muted, #7f7e77);
}

.bubble-content {
  padding: 14px 18px;
  border-radius: 16px;
  font-size: 14px;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
}
.user .bubble-content {
  background: var(--ink, #292a27);
  color: var(--paper, #f8f6f0);
  border-top-right-radius: 4px;
}
.assistant .bubble-content {
  background: var(--surface-solid, #fffdfb);
  color: var(--ink, #20211e);
  border-top-left-radius: 4px;
  border: 1px solid var(--line, rgba(0, 0, 0, 0.08));
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.04);
}

.loading-indicator {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--muted, #7f7e77);
}
.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--accent, #d5b18a);
  animation: dot-bounce 1.4s infinite ease-in-out both;
}
.dot:nth-child(1) {
  animation-delay: -0.32s;
}
.dot:nth-child(2) {
  animation-delay: -0.16s;
}
@keyframes dot-bounce {
  0%,
  80%,
  100% {
    transform: scale(0.6);
    opacity: 0.4;
  }
  40% {
    transform: scale(1);
    opacity: 1;
  }
}
.loading-text {
  font-size: 12px;
  margin-left: 4px;
}

.chat-error-bar {
  padding: 10px 18px;
  background: color-mix(in srgb, #b84f48 10%, var(--surface-solid, #fffdfb));
  border-top: 1px solid color-mix(in srgb, #b84f48 24%, var(--line, #e5e1d8));
  color: color-mix(in srgb, #b84f48 74%, var(--ink, #20211e));
  font-size: 13px;
}

.chat-input-area {
  padding: 16px 20px;
  background: var(--surface-solid, #ffffff);
  border-top: 1px solid var(--line-strong, #d9d6cf);
}
.input-wrapper {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.chat-textarea {
  width: 100%;
  border: 1px solid var(--line-strong, #d9d6cf);
  border-radius: 12px;
  padding: 12px 14px;
  font: 14px/1.6 inherit;
  color: var(--ink, #20211e);
  background: var(--surface, #faf8f5);
  resize: vertical;
  outline: none;
  min-height: 72px;
  max-height: 200px;
  transition:
    border-color 0.2s,
    box-shadow 0.2s;
}
.chat-textarea:focus {
  border-color: var(--accent, #d5b18a);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--accent, #d5b18a) 20%, transparent);
  outline: 2px solid color-mix(in srgb, var(--accent, #d5b18a) 70%, var(--ink, #20211e));
  outline-offset: 2px;
  background: var(--surface-solid, #ffffff);
}
.chat-model-picker {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  color: var(--muted, #7f7e77);
  font-size: 12px;
}
.chat-model-picker label {
  flex: 0 0 auto;
  font-weight: 600;
}
.chat-model-select {
  min-width: 0;
  flex: 1;
  padding: 7px 10px;
  border: 1px solid var(--line-strong, #d9d6cf);
  border-radius: 8px;
  color: var(--ink, #20211e);
  background: var(--surface, #faf8f5);
  font: inherit;
  outline: none;
}
.chat-model-select:focus {
  border-color: var(--accent, #d5b18a);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--accent, #d5b18a) 20%, transparent);
  background: var(--surface-solid, #ffffff);
}
.chat-model-select:disabled {
  cursor: not-allowed;
  opacity: 0.65;
}
.chat-model-provider {
  flex: 0 1 180px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.input-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.char-count {
  font-size: 11px;
  color: var(--muted, #7f7e77);
}
.char-count.near-limit {
  color: color-mix(in srgb, #b84f48 74%, var(--ink, #20211e));
  font-weight: 600;
}
.send-btn {
  padding: 8px 20px;
  border-radius: 8px;
  background: var(--ink, #292a27);
  color: var(--paper, #ffffff);
  border: none;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition:
    background 0.2s,
    opacity 0.2s,
    transform 0.2s;
}
.send-btn:hover:not(:disabled) {
  background: color-mix(in srgb, var(--ink, #292a27) 84%, var(--paper, #ffffff));
  transform: translateY(-1px);
}
.send-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
.footer-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.stop-btn {
  padding: 8px 16px;
  border-radius: 8px;
  background: transparent;
  color: color-mix(in srgb, #b84f48 74%, var(--ink, #20211e));
  border: 1px solid rgba(184, 79, 72, 0.4);
  font-size: 13px;
  cursor: pointer;
  transition: background 0.2s;
}
.stop-btn:hover {
  background: rgba(184, 79, 72, 0.08);
}

@media (max-width: 820px) {
  .ai-chat-main {
    height: auto;
    min-height: calc(100vh - 62px);
  }
  .ai-chat-container {
    height: calc(100vh - 160px);
  }
  .chat-bubble-wrap {
    max-width: 92%;
  }
}
</style>
