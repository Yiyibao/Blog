<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue';
import { downloadAiFile, fetchAiFileContent, type AiFile } from '../../api/ai';

const props = defineProps<{ files: AiFile[]; selectedIds: string[]; disabled?: boolean }>();
const emit = defineEmits<{
  upload: [files: FileList | File[]];
  toggle: [fileId: string];
  remove: [fileId: string];
}>();

const dragActive = ref(false);
const previewUrls = ref<Record<string, string>>({});
const previewLoading = ref<Record<string, boolean>>({});
const actionError = ref('');

const readyFiles = computed(() => props.files.filter((file) => file.status === 'READY'));
const allReadySelected = computed(
  () => readyFiles.value.length > 0 && readyFiles.value.every((file) => props.selectedIds.includes(file.id)),
);

function onFiles(event: Event) {
  const input = event.target as HTMLInputElement;
  if (input.files?.length) emit('upload', input.files);
  input.value = '';
}

function onDrop(event: DragEvent) {
  dragActive.value = false;
  if (props.disabled || !event.dataTransfer?.files.length) return;
  emit('upload', event.dataTransfer.files);
}

function toggleAll() {
  for (const file of readyFiles.value) {
    const selected = props.selectedIds.includes(file.id);
    if (allReadySelected.value ? selected : !selected) emit('toggle', file.id);
  }
}

async function preview(file: AiFile) {
  if (!file.mediaType.startsWith('image/') || previewUrls.value[file.id] || previewLoading.value[file.id]) {
    return;
  }
  previewLoading.value = { ...previewLoading.value, [file.id]: true };
  actionError.value = '';
  try {
    const blob = await fetchAiFileContent(file.id);
    previewUrls.value = { ...previewUrls.value, [file.id]: URL.createObjectURL(blob) };
  } catch {
    actionError.value = `无法预览 ${file.name}`;
  } finally {
    const next = { ...previewLoading.value };
    delete next[file.id];
    previewLoading.value = next;
  }
}

async function download(file: AiFile) {
  actionError.value = '';
  try {
    await downloadAiFile(file);
  } catch {
    actionError.value = `无法下载 ${file.name}`;
  }
}

function formatBytes(value: number) {
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${(value / 1024 / 1024).toFixed(1)} MB`;
}

function fileIcon(file: AiFile) {
  if (file.mediaType.startsWith('image/')) return '▧';
  if (file.mediaType.includes('pdf')) return 'PDF';
  if (file.mediaType.includes('spreadsheet') || file.name.endsWith('.csv')) return '▦';
  if (file.mediaType.includes('word') || file.name.endsWith('.docx')) return 'W';
  return 'TXT';
}

function statusLabel(status: AiFile['status']) {
  return {
    UPLOADED: '已上传',
    VALIDATING: '校验中',
    READY: '可使用',
    REJECTED: '已拒绝',
    EXPIRED: '已过期',
    DELETED: '已删除',
  }[status];
}

onBeforeUnmount(() => {
  Object.values(previewUrls.value).forEach((url) => URL.revokeObjectURL(url));
});
</script>

<template>
  <section class="ai-panel ai-attachments" aria-labelledby="ai-attachments-title">
    <div class="ai-panel__heading">
      <div>
        <p class="ai-eyebrow">输入资料库</p>
        <h2 id="ai-attachments-title">文件与图片</h2>
      </div>
      <span class="ai-count-badge">{{ selectedIds.length }}/{{ readyFiles.length }} 已选</span>
    </div>

    <label
      class="ai-dropzone"
      :class="{ 'ai-dropzone--active': dragActive, 'ai-dropzone--disabled': disabled }"
      @dragenter.prevent="dragActive = true"
      @dragover.prevent="dragActive = true"
      @dragleave.prevent="dragActive = false"
      @drop.prevent="onDrop"
    >
      <input
        class="ai-visually-hidden"
        type="file"
        multiple
        :disabled="disabled"
        accept="image/png,image/jpeg,image/webp,.pdf,.docx,.xlsx,.xls,.txt,.md,.markdown,.csv,.json"
        @change="onFiles"
      />
      <span class="ai-dropzone__icon">＋</span>
      <strong>拖拽文件到这里，或点击选择</strong>
      <small>图片、PDF、Word、Excel、Markdown、CSV、JSON</small>
    </label>

    <div class="ai-attachment-toolbar">
      <span>{{ files.length }} 个文件 · 仅选择的内容会发送给 AI</span>
      <button
        v-if="readyFiles.length"
        type="button"
        class="ai-link-button"
        :disabled="disabled"
        @click="toggleAll"
      >
        {{ allReadySelected ? '取消全选' : '全选可用文件' }}
      </button>
    </div>

    <p v-if="actionError" class="ai-inline-error" role="alert">{{ actionError }}</p>
    <ul v-if="files.length" class="ai-file-list">
      <li v-for="file in files" :key="file.id" :class="{ selected: selectedIds.includes(file.id) }">
        <div class="ai-file-row">
          <input
            :id="`ai-file-${file.id}`"
            type="checkbox"
            :checked="selectedIds.includes(file.id)"
            :disabled="disabled || file.status !== 'READY'"
            :aria-label="`选择 ${file.name}`"
            @change="emit('toggle', file.id)"
          />
          <button
            v-if="file.mediaType.startsWith('image/')"
            type="button"
            class="ai-file-thumb"
            :title="`预览 ${file.name}`"
            :aria-label="`预览 ${file.name}`"
            :disabled="file.status !== 'READY'"
            @click="preview(file)"
          >
            <img v-if="previewUrls[file.id]" :src="previewUrls[file.id]" :alt="file.name" />
            <span v-else>{{ previewLoading[file.id] ? '…' : fileIcon(file) }}</span>
          </button>
          <span v-else class="ai-file-icon" aria-hidden="true">{{ fileIcon(file) }}</span>
          <label class="ai-file-meta" :for="`ai-file-${file.id}`">
            <strong :title="file.name">{{ file.name }}</strong>
            <small>{{ formatBytes(file.sizeBytes) }} · {{ statusLabel(file.status) }}</small>
          </label>
          <span v-if="selectedIds.includes(file.id)" class="ai-file-selected">已加入</span>
          <button
            type="button"
            class="ai-link-button"
            :disabled="disabled || file.status !== 'READY'"
            @click="download(file)"
          >
            下载
          </button>
          <button
            type="button"
            class="ai-link-button ai-link-button--danger"
            :disabled="disabled"
            @click="emit('remove', file.id)"
          >
            删除<span class="ai-visually-hidden"> {{ file.name }}</span>
          </button>
        </div>
      </li>
    </ul>
    <p v-else class="ai-empty">尚未上传附件</p>
  </section>
</template>

<style scoped>
.ai-visually-hidden {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}

.ai-panel__heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.8rem;
}

.ai-count-badge {
  padding: 0.28rem 0.55rem;
  border-radius: 999px;
  color: #2f4bbf;
  background: #edf1ff;
  font-size: 0.7rem;
  font-weight: 800;
  white-space: nowrap;
}

.ai-dropzone {
  display: grid;
  place-items: center;
  gap: 0.28rem;
  margin-top: 0.85rem;
  padding: 1.1rem 0.8rem;
  border: 1px dashed #aebcff;
  border-radius: 0.95rem;
  color: #3349a9;
  background: linear-gradient(135deg, #f4f6ff, #fbfcff);
  cursor: pointer;
  transition: 0.18s ease;
}

.ai-dropzone:hover,
.ai-dropzone--active {
  border-color: #3856d6;
  background: #edf1ff;
  transform: translateY(-1px);
}

.ai-dropzone--disabled {
  cursor: not-allowed;
  opacity: 0.6;
  transform: none;
}

.ai-dropzone__icon {
  display: grid;
  place-items: center;
  width: 2rem;
  height: 2rem;
  border-radius: 0.7rem;
  color: white;
  background: #3856d6;
  font-size: 1.4rem;
  line-height: 1;
}

.ai-dropzone small,
.ai-attachment-toolbar,
.ai-file-meta small {
  color: var(--ai-muted, #687189);
  font-size: 0.72rem;
}

.ai-attachment-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  margin-top: 0.65rem;
}

.ai-file-list {
  margin-top: 0.6rem !important;
}

.ai-file-list li {
  padding: 0.55rem 0 !important;
  border-top: 1px solid var(--ai-line, rgba(23, 35, 61, 0.13));
}

.ai-file-row {
  display: grid;
  grid-template-columns: auto 2.3rem minmax(0, 1fr) auto auto auto;
  align-items: center;
  gap: 0.45rem;
}

.ai-file-thumb,
.ai-file-icon {
  display: grid;
  place-items: center;
  width: 2.3rem;
  height: 2.3rem;
  overflow: hidden;
  border: 1px solid #d6defe;
  border-radius: 0.65rem;
  color: #3d55bd;
  background: #f0f3ff;
  font-size: 0.6rem;
  font-weight: 850;
}

.ai-file-thumb {
  padding: 0;
  cursor: zoom-in;
}

.ai-file-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.ai-file-meta {
  display: grid;
  min-width: 0;
  gap: 0.12rem;
  cursor: pointer;
}

.ai-file-meta strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 0.79rem;
}

.ai-file-selected {
  color: #2d7b52;
  font-size: 0.68rem;
  font-weight: 800;
  white-space: nowrap;
}

.ai-link-button {
  white-space: nowrap;
}

.ai-inline-error {
  margin: 0.65rem 0 0;
  color: #a31e36;
  font-size: 0.75rem;
}

@media (max-width: 620px) {
  .ai-file-row {
    grid-template-columns: auto 2.3rem minmax(0, 1fr) auto;
  }

  .ai-file-selected {
    display: none;
  }

  .ai-file-row > .ai-link-button {
    grid-row: 2;
  }
}
</style>
