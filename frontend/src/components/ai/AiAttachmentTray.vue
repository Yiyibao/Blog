<script setup lang="ts">
import type { AiFile } from '../../api/ai';

defineProps<{ files: AiFile[]; selectedIds: string[]; disabled?: boolean }>();
const emit = defineEmits<{
  upload: [files: FileList];
  toggle: [fileId: string];
  remove: [fileId: string];
}>();

function onFiles(event: Event) {
  const input = event.target as HTMLInputElement;
  if (input.files?.length) emit('upload', input.files);
  input.value = '';
}

function formatBytes(value: number) {
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${(value / 1024 / 1024).toFixed(1)} MB`;
}
</script>

<template>
  <section class="ai-panel" aria-labelledby="ai-attachments-title">
    <div class="ai-panel__heading">
      <div>
        <p class="ai-eyebrow">受控输入</p>
        <h2 id="ai-attachments-title">附件</h2>
      </div>
      <label class="ai-button ai-button--quiet">
        选择文件
        <input
          class="sr-only"
          type="file"
          multiple
          :disabled="disabled"
          accept="image/png,image/jpeg,image/webp,.pdf,.docx,.txt,.md,.markdown,.csv,.json"
          @change="onFiles"
        />
      </label>
    </div>
    <p class="ai-help">支持图片、PDF、DOCX、TXT、Markdown、CSV、JSON；服务器会校验内容与类型。</p>
    <ul v-if="files.length" class="ai-file-list">
      <li v-for="file in files" :key="file.id" :class="{ selected: selectedIds.includes(file.id) }">
        <label>
          <input
            type="checkbox"
            :checked="selectedIds.includes(file.id)"
            :disabled="disabled || file.status !== 'READY'"
            @change="emit('toggle', file.id)"
          />
          <span>
            <strong>{{ file.name }}</strong>
            <small>{{ file.mediaType }} · {{ formatBytes(file.sizeBytes) }} · {{ file.status }}</small>
          </span>
        </label>
        <button type="button" class="ai-icon-button" :disabled="disabled" @click="emit('remove', file.id)">
          删除<span class="sr-only"> {{ file.name }}</span>
        </button>
      </li>
    </ul>
    <p v-else class="ai-empty">尚未上传附件。</p>
  </section>
</template>
