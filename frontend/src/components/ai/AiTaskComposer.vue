<script setup lang="ts">
import { ref } from 'vue';
import type { AiFile } from '../../api/ai';

type AiTaskType = 'CHAT' | 'ANALYZE' | 'GENERATE';

const props = defineProps<{
  running: boolean;
  selectedCount: number;
  selectedFiles?: AiFile[];
  preview?: boolean;
}>();

const emit = defineEmits<{
  submit: [prompt: string, taskType: AiTaskType];
  cancel: [];
  uploadFiles: [files: File[]];
  openFiles: [];
}>();

const prompt = ref('');
const taskType = ref<AiTaskType>('CHAT');
const attachMenuOpen = ref(Boolean(props.preview));
const fileInput = ref<HTMLInputElement | null>(null);

const taskTypes: Array<{ value: AiTaskType; label: string; hint: string }> = [
  { value: 'CHAT', label: '对话', hint: '围绕资料继续追问' },
  { value: 'ANALYZE', label: '分析', hint: '提炼结论、风险和结构化信息' },
  { value: 'GENERATE', label: '生成文件', hint: '把回答整理成可下载产物' },
];

function submit() {
  const value = prompt.value.trim();
  if (!value && props.selectedCount === 0) return;
  emit('submit', value, taskType.value);
  if (value) prompt.value = '';
}

function handlePaste(event: ClipboardEvent) {
  const files = Array.from(event.clipboardData?.files ?? []).filter(
    (file) => file.type.startsWith('image/') || file.type === 'application/pdf',
  );
  if (!files.length) return;
  event.preventDefault();
  emit('uploadFiles', files);
}

function openPicker(accept: string) {
  if (!fileInput.value) return;
  fileInput.value.accept = accept;
  fileInput.value.click();
  attachMenuOpen.value = false;
}

function onFilesSelected(event: Event) {
  const input = event.target as HTMLInputElement;
  const files = Array.from(input.files ?? []);
  if (files.length) emit('uploadFiles', files);
  input.value = '';
}

function formatBytes(value: number) {
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${(value / 1024 / 1024).toFixed(1)} MB`;
}
</script>

<template>
  <form
    class="ai-composer"
    :class="{ 'ai-composer--preview': props.preview }"
    aria-label="AI 任务输入"
    @submit.prevent="submit"
  >
    <div v-if="props.selectedFiles?.length" class="ai-composer__files" aria-label="已选输入">
      <span v-for="file in props.selectedFiles" :key="file.id" class="ai-composer__file-chip">
        <b>{{ file.mediaType.startsWith('image/') ? '图片' : '文件' }}</b>
        {{ file.name }} · {{ formatBytes(file.sizeBytes) }}
      </span>
    </div>

    <div class="ai-composer__row">
      <button
        v-if="props.running"
        type="button"
        class="ai-composer__stop"
        aria-label="停止任务"
        @click="emit('cancel')"
      >
        ■
      </button>
      <button
        v-else
        type="button"
        class="ai-composer__plus"
        :aria-expanded="attachMenuOpen"
        aria-controls="ai-attach-menu"
        aria-label="添加图片或文件"
        @click="attachMenuOpen = !attachMenuOpen"
      >
        ＋
      </button>

      <textarea
        id="ai-task-prompt"
        data-testid="ai-chat-input"
        v-model="prompt"
        aria-label="AI 消息"
        rows="1"
        maxlength="32000"
        :disabled="props.running"
        placeholder="给 AI 发送消息..."
        @paste="handlePaste"
        @keydown.ctrl.enter.prevent="submit"
        @keydown.meta.enter.prevent="submit"
      />

      <button
        type="submit"
        class="ai-composer__send"
        aria-label="发送消息"
        :disabled="props.running || (!prompt.trim() && props.selectedCount === 0)"
      >
        ➤
      </button>
    </div>

    <div
      v-if="attachMenuOpen && !props.running"
      id="ai-attach-menu"
      class="ai-attach-menu"
      role="menu"
      aria-label="添加输入"
    >
      <button type="button" role="menuitem" @click="openPicker('image/*')">
        <span aria-hidden="true">▧</span> 上传图片
      </button>
      <button
        type="button"
        role="menuitem"
        @click="openPicker('.pdf,.doc,.docx,.xls,.xlsx,.csv,.txt,.md,.json')"
      >
        <span aria-hidden="true">▤</span> 上传文件
      </button>
      <button
        type="button"
        role="menuitem"
        @click="
          emit('openFiles');
          attachMenuOpen = false;
        "
      >
        <span aria-hidden="true">◷</span> 选择最近文件
      </button>
      <label class="ai-attach-menu__mode">
        <span>任务模式</span>
        <select v-model="taskType" aria-label="任务模式">
          <option v-for="item in taskTypes" :key="item.value" :value="item.value">
            {{ item.label }} · {{ item.hint }}
          </option>
        </select>
      </label>
    </div>

    <input
      ref="fileInput"
      class="ai-file-input"
      type="file"
      multiple
      aria-label="上传图片或文件"
      @change="onFilesSelected"
    />
    <p class="ai-composer__hint">AI 可能会犯错，请核对重要信息</p>
  </form>
</template>

<style scoped>
.ai-composer {
  position: relative;
  width: min(1120px, calc(100% - 52px));
  margin: auto auto 0;
  padding: 0 0 18px;
}

.ai-composer__files {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
  margin-bottom: 10px;
}

.ai-composer__file-chip {
  border: 1px solid #dbe5f4;
  border-radius: 999px;
  padding: 5px 10px;
  color: #52627a;
  background: #f7faff;
  font-size: 11px;
}

.ai-composer__file-chip b {
  margin-right: 4px;
  color: #2e65dc;
  font-weight: 700;
}

.ai-composer__row {
  display: grid;
  grid-template-columns: 48px minmax(0, 1fr) 54px;
  align-items: center;
  gap: 11px;
  min-height: 110px;
  border: 1px solid #d8e0ea;
  border-radius: 13px;
  padding: 8px 10px 8px 13px;
  background: #fff;
  box-shadow: 0 10px 28px rgba(37, 64, 111, 0.08);
}

.ai-composer__plus,
.ai-composer__stop {
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  border: 1px solid #d6e0ed;
  border-radius: 50%;
  color: #58708f;
  background: #fff;
  font: inherit;
  font-size: 26px;
  font-weight: 300;
  line-height: 1;
  cursor: pointer;
}

.ai-composer__plus:hover,
.ai-composer__plus[aria-expanded='true'] {
  border-color: #8eace8;
  color: #1d5ce7;
  background: #f4f8ff;
}

.ai-composer__stop {
  border-color: #ffd6d6;
  color: #d24646;
  font-size: 13px;
}

.ai-composer textarea {
  width: 100%;
  min-height: 38px;
  max-height: 150px;
  border: 0;
  padding: 8px 0;
  color: #283750;
  background: transparent;
  font: inherit;
  font-size: 16px;
  line-height: 1.6;
  outline: none;
  resize: vertical;
}

.ai-composer textarea::placeholder {
  color: #a0aaba;
}

.ai-composer__send {
  display: grid;
  place-items: center;
  width: 49px;
  height: 49px;
  border: 0;
  border-radius: 50%;
  color: #fff;
  background: #1d5be7;
  font-size: 23px;
  line-height: 1;
  cursor: pointer;
  transition:
    transform 0.15s ease,
    background 0.15s ease;
}

.ai-composer__send:hover:not(:disabled) {
  background: #164bc7;
  transform: translateY(-1px);
}

.ai-composer__send:disabled {
  cursor: not-allowed;
  opacity: 0.38;
}

.ai-attach-menu {
  position: absolute;
  z-index: 8;
  bottom: 130px;
  left: 0;
  display: grid;
  min-width: 205px;
  gap: 3px;
  border: 1px solid #dce3ed;
  border-radius: 10px;
  padding: 8px;
  background: #fff;
  box-shadow: 0 14px 32px rgba(29, 51, 87, 0.16);
}

.ai-attach-menu button {
  display: flex;
  align-items: center;
  gap: 11px;
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

.ai-attach-menu button:hover {
  color: #1c5be6;
  background: #f2f6ff;
}

.ai-attach-menu button span {
  width: 18px;
  color: #416fdb;
  font-size: 18px;
  text-align: center;
}

.ai-attach-menu__mode {
  display: grid;
  gap: 4px;
  margin-top: 6px;
  border-top: 1px solid #edf0f4;
  padding: 9px 4px 2px;
  color: #8b98aa;
  font-size: 11px;
}

.ai-attach-menu__mode select {
  border: 1px solid #dbe3ef;
  border-radius: 6px;
  padding: 6px 7px;
  color: #40516c;
  background: #fff;
  font: inherit;
  font-size: 11px;
}

.ai-composer--preview .ai-attach-menu__mode {
  display: none;
}

.ai-file-input {
  display: none;
}

.ai-composer__hint {
  margin: 10px 0 0;
  color: #5c697c;
  font-size: 11px;
  text-align: center;
}

@media (max-width: 760px) {
  .ai-composer {
    width: calc(100% - 28px);
  }

  .ai-composer__row {
    grid-template-columns: 40px minmax(0, 1fr) 46px;
  }

  .ai-composer__send {
    width: 42px;
    height: 42px;
    font-size: 19px;
  }
}
</style>
