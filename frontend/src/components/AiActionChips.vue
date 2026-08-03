<script setup lang="ts">
import { ref } from 'vue'
import { streamAiChat, type AiChatMessage } from '../api/admin'
import { AI_ACTION_CONTEXT_CHARS } from '../config/aiLimits'

/**
 * 4A-5：编辑场景化 AI 动作 chips——总结/标题/标签/润色/续写。
 * 自动附当前编辑内容为上下文（客户端截断适配服务端单条 AI_CHAT_MAX_INPUT_CHARS 字限额），
 * 结果面板一键回填宿主表单：**只填入不保存**，保存永远是作者的显式动作。
 */
export type AiActionKind = 'summary' | 'title' | 'tags' | 'polish' | 'continue'

const props = defineProps<{
  /** 惰性取当前编辑内容（点击时才读取，保证拿到最新值） */
  getContext: () => string
}>()

const emit = defineEmits<{
  apply: [action: AiActionKind, text: string]
}>()

/** 单条消息服务端限额减去指令预留后的上下文上限。 */
const MAX_CONTEXT_CHARS = AI_ACTION_CONTEXT_CHARS

const ACTIONS: ReadonlyArray<{ id: AiActionKind; label: string; prompt: string }> = [
  { id: 'summary', label: '✦ 总结', prompt: '用不超过 120 字为以下内容写一段中文摘要，直接输出摘要文本，不要任何前后缀说明：' },
  { id: 'title', label: '✦ 标题建议', prompt: '为以下内容拟 5 个简洁有力的中文标题，每行一个，不要编号以外的解释：' },
  { id: 'tags', label: '✦ 标签建议', prompt: '为以下内容提取 3 到 6 个中文标签，用逗号分隔输出，不要其他文字：' },
  { id: 'polish', label: '✦ 润色', prompt: '润色以下内容：保持原意、语气与 Markdown 结构，修正病句与冗余，直接输出润色后的全文：' },
  { id: 'continue', label: '✦ 续写', prompt: '基于以下内容自然续写一段（200 到 400 字），风格保持一致，直接输出续写文本：' },
]

const runningAction = ref<AiActionKind | null>(null)
const resultAction = ref<AiActionKind | null>(null)
const resultText = ref('')
const error = ref('')
let abortController: AbortController | null = null

async function run(action: (typeof ACTIONS)[number]) {
  if (runningAction.value) return
  const context = (props.getContext() || '').slice(0, MAX_CONTEXT_CHARS)
  if (!context.trim()) {
    error.value = '当前没有可用的编辑内容。'
    return
  }
  runningAction.value = action.id
  resultAction.value = action.id
  resultText.value = ''
  error.value = ''
  const messages: AiChatMessage[] = [{ role: 'user', content: `${action.prompt}\n\n${context}` }]
  const controller = new AbortController()
  abortController = controller
  try {
    await streamAiChat(messages, {
      onDelta: (text) => { resultText.value += text },
    }, { signal: controller.signal })
    if (!resultText.value.trim()) error.value = 'AI 未返回内容，请重试。'
  } catch {
    if (!controller.signal.aborted) {
      error.value = 'AI 请求失败，请检查供应商配置或稍后重试。'
      resultAction.value = null
    }
  } finally {
    runningAction.value = null
    abortController = null
  }
}

function stop() {
  abortController?.abort()
}

function applyResult() {
  if (!resultAction.value || !resultText.value.trim()) return
  emit('apply', resultAction.value, resultText.value.trim())
  closeResult()
}

function closeResult() {
  resultAction.value = null
  resultText.value = ''
}

const ACTION_LABELS: Record<AiActionKind, string> = {
  summary: '总结', title: '标题建议', tags: '标签建议', polish: '润色', continue: '续写',
}
</script>

<template>
  <div class="ai-action-chips">
    <div class="chips-row" role="toolbar" aria-label="AI 场景化动作">
      <button
        v-for="action in ACTIONS"
        :key="action.id"
        type="button"
        class="ai-chip"
        :class="{ running: runningAction === action.id }"
        :disabled="!!runningAction"
        @click="run(action)"
      >
        <span v-if="runningAction === action.id" class="chip-spinner" />
        <span>{{ runningAction === action.id ? '生成中…' : action.label }}</span>
      </button>
      <button v-if="runningAction" type="button" class="ai-chip stop" @click="stop">■ 停止</button>
    </div>

    <p v-if="error" class="chips-error" role="alert">{{ error }}</p>

    <div v-if="resultAction && (resultText || runningAction)" class="chips-result">
      <header>
        <div class="result-header-title">
          <span class="ai-badge">✦ AI</span>
          <strong>{{ ACTION_LABELS[resultAction] }}</strong>
        </div>
        <small>结果只填入表单，不会自动保存</small>
      </header>
      <pre class="result-text" aria-live="polite">{{ resultText || '…' }}</pre>
      <footer>
        <button type="button" class="apply-btn" :disabled="!!runningAction || !resultText.trim()" @click="applyResult">
          <span>填入表单</span>
          <span class="btn-icon">↩</span>
        </button>
        <button type="button" class="dismiss-btn" @click="closeResult">关闭</button>
      </footer>
    </div>
  </div>
</template>

<style scoped>
.ai-action-chips {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-bottom: 12px;
}
.chips-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.ai-chip {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 6px 14px;
  height: 32px;
  border-radius: 999px;
  border: 1px solid color-mix(in srgb, var(--accent, #7c3aed) 22%, var(--line-strong, #d1d5db));
  background: color-mix(in srgb, var(--accent, #7c3aed) 4%, var(--surface, #ffffff));
  color: var(--ink, #1e293b);
  font-size: 12px;
  font-weight: 500;
  white-space: nowrap;
  cursor: pointer;
  transition: all 0.2s cubic-bezier(0.4, 0, 0.2, 1);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.03);
}
.ai-chip:hover:not(:disabled) {
  border-color: var(--accent, #7c3aed);
  background: color-mix(in srgb, var(--accent, #7c3aed) 12%, var(--surface, #ffffff));
  color: var(--accent, #7c3aed);
  transform: translateY(-1px);
  box-shadow: 0 3px 10px color-mix(in srgb, var(--accent, #7c3aed) 20%, transparent);
}
.ai-chip:active:not(:disabled) {
  transform: translateY(0);
}
.ai-chip:disabled {
  opacity: 0.55;
  cursor: default;
}
.ai-chip.running {
  border-color: var(--accent, #7c3aed);
  color: var(--accent, #7c3aed);
  background: color-mix(in srgb, var(--accent, #7c3aed) 15%, var(--surface, #ffffff));
  animation: pulse-glow 1.5s infinite ease-in-out;
}
@keyframes pulse-glow {
  0%, 100% { box-shadow: 0 0 0 0 color-mix(in srgb, var(--accent, #7c3aed) 40%, transparent); }
  50% { box-shadow: 0 0 0 4px color-mix(in srgb, var(--accent, #7c3aed) 12%, transparent); }
}
.chip-spinner {
  display: inline-block;
  width: 12px;
  height: 12px;
  border: 2px solid currentColor;
  border-right-color: transparent;
  border-radius: 50%;
  animation: spin 0.75s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}
.ai-chip.stop {
  border-color: #ef4444;
  color: #ef4444;
  background: color-mix(in srgb, #ef4444 8%, var(--surface, #ffffff));
  opacity: 1;
  cursor: pointer;
}
.ai-chip.stop:hover {
  background: color-mix(in srgb, #ef4444 18%, var(--surface, #ffffff));
  box-shadow: 0 3px 10px rgba(239, 68, 68, 0.2);
}
.chips-error {
  margin: 0;
  padding: 8px 12px;
  border-radius: 8px;
  background: color-mix(in srgb, #ef4444 10%, var(--surface, #ffffff));
  border: 1px solid color-mix(in srgb, #ef4444 25%, transparent);
  font-size: 12px;
  color: #dc2626;
}
.chips-result {
  border: 1px solid color-mix(in srgb, var(--accent, #7c3aed) 30%, var(--line-strong, #d1d5db));
  border-radius: 12px;
  background: color-mix(in srgb, var(--surface, #ffffff) 95%, transparent);
  box-shadow: 0 10px 25px -5px color-mix(in srgb, var(--accent, #7c3aed) 15%, transparent);
  overflow: hidden;
  backdrop-filter: blur(12px);
  animation: slide-down 0.22s ease-out;
}
@keyframes slide-down {
  from { opacity: 0; transform: translateY(-6px); }
  to { opacity: 1; transform: translateY(0); }
}
.chips-result header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border-bottom: 1px solid var(--line, #e5e7eb);
  background: color-mix(in srgb, var(--accent, #7c3aed) 6%, var(--surface, #ffffff));
}
.result-header-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--ink, #1e293b);
}
.ai-badge {
  padding: 2px 6px;
  border-radius: 4px;
  background: var(--accent, #7c3aed);
  color: #ffffff;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.05em;
}
.chips-result header small {
  color: var(--muted, #6b7280);
  font-size: 11px;
}
.result-text {
  margin: 0;
  padding: 14px;
  max-height: 240px;
  overflow: auto;
  font: 13px/1.7 Consolas, Monaco, 'Courier New', monospace;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--ink, #1e293b);
  background: var(--surface-solid, #ffffff);
}
.chips-result footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 10px 14px;
  border-top: 1px solid var(--line, #e5e7eb);
  background: color-mix(in srgb, var(--ink, #000) 2%, var(--surface, #ffffff));
}
.apply-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 16px;
  height: 32px;
  border-radius: 8px;
  border: none;
  background: var(--accent, #7c3aed);
  color: #ffffff;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s ease;
  box-shadow: 0 2px 8px color-mix(in srgb, var(--accent, #7c3aed) 35%, transparent);
}
.apply-btn:hover:not(:disabled) {
  filter: brightness(1.1);
  transform: translateY(-1px);
}
.apply-btn:disabled {
  opacity: 0.5;
  cursor: default;
  box-shadow: none;
}
.dismiss-btn {
  padding: 6px 14px;
  height: 32px;
  border-radius: 8px;
  border: 1px solid var(--line-strong, #d1d5db);
  background: transparent;
  color: var(--muted, #6b7280);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.15s ease;
}
.dismiss-btn:hover {
  background: color-mix(in srgb, var(--ink, #000) 5%, transparent);
  color: var(--ink, #1e293b);
}
</style>
