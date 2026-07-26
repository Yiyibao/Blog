<script setup lang="ts">
import axios from 'axios'
import { nextTick, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  clearAdminSession, getAdminSessionName, sendAiChat,
  type AiChatMessage,
} from '../api/admin'
import AdminSidebar from './AdminSidebar.vue'

const STORAGE_KEY = 'yubai-admin-ai-messages'

const router = useRouter()
const username = getAdminSessionName() || 'Admin'
const userInput = ref('')
const loading = ref(false)
const error = ref('')
const chatBoxRef = ref<HTMLElement | null>(null)

function loadStoredMessages(): AiChatMessage[] {
  try {
    const raw = window.sessionStorage?.getItem(STORAGE_KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw)
    if (!Array.isArray(parsed)) throw new Error('Invalid stored messages')
    const valid: AiChatMessage[] = []
    for (const item of parsed) {
      if (
        item &&
        typeof item === 'object' &&
        (item.role === 'user' || item.role === 'assistant') &&
        typeof item.content === 'string' &&
        item.content.trim() &&
        item.content.length <= 8000
      ) {
        valid.push({ role: item.role as 'user' | 'assistant', content: item.content })
      } else {
        throw new Error('Invalid stored message')
      }
    }
    const recent = valid.slice(-20)
    if (recent.length !== valid.length) saveMessagesToStorage(recent)
    return recent
  } catch {
    try {
      window.sessionStorage?.removeItem(STORAGE_KEY)
    } catch {
      // ignore
    }
    return []
  }
}

function saveMessagesToStorage(msgList: AiChatMessage[]) {
  try {
    window.sessionStorage?.setItem(STORAGE_KEY, JSON.stringify(msgList.slice(-20)))
  } catch {
    // Privacy mode fallback
  }
}

const messages = ref<AiChatMessage[]>(loadStoredMessages())

async function scrollToBottom() {
  await nextTick()
  if (chatBoxRef.value) {
    chatBoxRef.value.scrollTop = chatBoxRef.value.scrollHeight
  }
}

function logout() {
  clearAdminSession()
  void router.replace('/admin/login')
}

function clearConversation() {
  if (!window.confirm('确认清空所有对话记录？')) return
  messages.value = []
  error.value = ''
  try {
    window.sessionStorage?.removeItem(STORAGE_KEY)
  } catch {
    // ignore
  }
}

function handleKeyDown(event: KeyboardEvent) {
  if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
    event.preventDefault()
    void sendMessage()
  }
}

async function sendMessage() {
  const content = userInput.value.trim()
  if (!content || loading.value || content.length > 8000) return

  const userMsg: AiChatMessage = { role: 'user', content }
  const updatedMessages = [...messages.value, userMsg].slice(-20)
  messages.value = updatedMessages
  saveMessagesToStorage(messages.value)

  userInput.value = ''
  error.value = ''
  loading.value = true
  await scrollToBottom()

  try {
    const result = await sendAiChat(updatedMessages)
    const assistantMsg: AiChatMessage = { role: 'assistant', content: result.content }
    messages.value = [...messages.value, assistantMsg].slice(-20)
    saveMessagesToStorage(messages.value)
    await scrollToBottom()
  } catch (cause) {
    if (axios.isAxiosError(cause) && cause.response?.status === 401) {
      clearAdminSession()
      void router.replace('/admin/login')
      return
    }
    error.value = 'AI 响应失败，请检查网络或稍后重试。'
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void scrollToBottom()
})
</script>

<template>
  <section class="admin-console ai-chat-console">
    <AdminSidebar />

    <main class="admin-main ai-chat-main">
      <header class="admin-topbar">
        <div>
          <span class="admin-breadcrumb">后台管理 / AI 助手</span>
          <h1>DeepSeek AI 对话</h1>
        </div>
        <div class="topbar-actions">
          <button v-if="messages.length" class="clear-btn" type="button" @click="clearConversation">清空对话</button>
          <RouterLink to="/">查看博客 ↗</RouterLink>
          <button @click="logout">退出登录</button>
        </div>
      </header>

      <div class="ai-chat-container">
        <div ref="chatBoxRef" class="chat-messages" role="log" aria-live="polite">
          <div v-if="!messages.length && !loading" class="chat-welcome">
            <div class="welcome-icon">🤖</div>
            <h2>管理员 AI 助手</h2>
            <p>基于 DeepSeek 大模型，协助文章撰写、代码重构与内容总结。</p>
            <small>最多保存最近 20 条消息 · 支持 8,000 字长文本输入</small>
          </div>

          <div
            v-for="(msg, index) in messages"
            :key="index"
            class="chat-bubble-wrap"
            :class="msg.role"
          >
            <div class="bubble-avatar">
              {{ msg.role === 'user' ? username.slice(0, 1).toUpperCase() : '🤖' }}
            </div>
            <div class="bubble-body">
              <header class="bubble-header">
                <span class="sender-name">{{ msg.role === 'user' ? username : 'DeepSeek AI' }}</span>
              </header>
              <div class="bubble-content">{{ msg.content }}</div>
            </div>
          </div>

          <div v-if="loading" class="chat-bubble-wrap assistant loading-bubble">
            <div class="bubble-avatar">🤖</div>
            <div class="bubble-body">
              <header class="bubble-header">
                <span class="sender-name">DeepSeek AI</span>
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
              placeholder="输入消息，按 Ctrl + Enter 或 Cmd + Enter 快速发送…"
              maxlength="8000"
              rows="3"
              :disabled="loading"
              @keydown="handleKeyDown"
            />
            <div class="input-footer">
              <span class="char-count" :class="{ 'near-limit': userInput.length > 7500 }">
                {{ userInput.length.toLocaleString() }} / 8,000 字
              </span>
              <button
                class="send-btn"
                type="button"
                :disabled="loading || !userInput.trim() || userInput.length > 8000"
                @click="sendMessage"
              >
                {{ loading ? '发送中…' : '发送 ↗' }}
              </button>
            </div>
          </div>
        </div>
      </div>
    </main>
  </section>
</template>

<style scoped>
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
  color: #b84f48 !important;
  border-color: rgba(184, 79, 72, 0.3) !important;
}
.clear-btn:hover {
  background: rgba(184, 79, 72, 0.08) !important;
}

.ai-chat-container {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  margin-top: 16px;
  border: 1px solid var(--console-line, #d9d6cf);
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.6);
  box-shadow: 0 10px 40px rgba(34, 32, 27, 0.04);
  overflow: hidden;
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
  font: 500 24px Georgia, 'Noto Serif SC', serif;
  margin: 0 0 8px;
  color: var(--console-ink, #20211e);
}
.chat-welcome p {
  color: var(--console-muted, #7f7e77);
  font-size: 14px;
  line-height: 1.6;
  margin: 0 0 12px;
}
.chat-welcome small {
  color: #a17450;
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
  font: 600 14px Georgia, serif;
}
.user .bubble-avatar {
  background: #292a27;
  color: #f8f5ee;
}
.assistant .bubble-avatar {
  background: #d5b18a;
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
  color: var(--console-muted, #7f7e77);
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
  background: #292a27;
  color: #f8f6f0;
  border-top-right-radius: 4px;
}
.assistant .bubble-content {
  background: #ffffff;
  color: #20211e;
  border-top-left-radius: 4px;
  border: 1px solid rgba(0, 0, 0, 0.08);
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.04);
}

.loading-indicator {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--console-muted, #7f7e77);
}
.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #d5b18a;
  animation: dot-bounce 1.4s infinite ease-in-out both;
}
.dot:nth-child(1) { animation-delay: -0.32s; }
.dot:nth-child(2) { animation-delay: -0.16s; }
@keyframes dot-bounce {
  0%, 80%, 100% { transform: scale(0.6); opacity: 0.4; }
  40% { transform: scale(1); opacity: 1; }
}
.loading-text {
  font-size: 12px;
  margin-left: 4px;
}

.chat-error-bar {
  padding: 10px 18px;
  background: #fdf2f2;
  border-top: 1px solid #f8d7da;
  color: #b84f48;
  font-size: 13px;
}

.chat-input-area {
  padding: 16px 20px;
  background: #ffffff;
  border-top: 1px solid var(--console-line, #d9d6cf);
}
.input-wrapper {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.chat-textarea {
  width: 100%;
  border: 1px solid var(--console-line, #d9d6cf);
  border-radius: 12px;
  padding: 12px 14px;
  font: 14px/1.6 inherit;
  color: var(--console-ink, #20211e);
  background: #faf8f5;
  resize: vertical;
  outline: none;
  min-height: 72px;
  max-height: 200px;
  transition: border-color 0.2s, box-shadow 0.2s;
}
.chat-textarea:focus {
  border-color: #d5b18a;
  box-shadow: 0 0 0 3px rgba(213, 177, 138, 0.2);
  background: #ffffff;
}
.input-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.char-count {
  font-size: 11px;
  color: var(--console-muted, #7f7e77);
}
.char-count.near-limit {
  color: #b84f48;
  font-weight: 600;
}
.send-btn {
  padding: 8px 20px;
  border-radius: 8px;
  background: #292a27;
  color: #ffffff;
  border: none;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.2s, opacity 0.2s, transform 0.2s;
}
.send-btn:hover:not(:disabled) {
  background: #3c3d39;
  transform: translateY(-1px);
}
.send-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
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
