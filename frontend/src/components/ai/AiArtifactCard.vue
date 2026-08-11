<script setup lang="ts">
import { computed, ref } from 'vue';
import { downloadAiArtifact, type AiArtifact, type AiArtifactFormat, type AiTask } from '../../api/ai';

const props = defineProps<{
  artifacts: AiArtifact[];
  hasTask: boolean;
  task?: AiTask | null;
  disabled?: boolean;
}>();
const emit = defineEmits<{
  create: [format: AiArtifactFormat, name: string, content?: string, sourceImageId?: string];
  remove: [artifact: AiArtifact];
}>();

const format = ref<AiArtifactFormat>('MARKDOWN');
const name = ref('result.md');
const content = ref('');
const sourceImageId = ref('');
const assistantText = computed(
  () =>
    [...(props.task?.parts ?? [])]
      .filter((part) => part.kind === 'TEXT' && part.role === 'ASSISTANT' && part.text)
      .at(-1)?.text ?? '',
);
const canExportAnswer = computed(() => props.hasTask && assistantText.value.trim().length > 0);

const names: Record<AiArtifactFormat, string> = {
  MARKDOWN: 'result.md',
  TEXT: 'result.txt',
  JSON: 'result.json',
  CSV: 'result.csv',
  PDF: 'result.pdf',
  DOCX: 'result.docx',
  XLSX: 'result.xlsx',
  IMAGE: 'generated-image.png',
};

const needsContent = computed(() => ['JSON', 'CSV', 'PDF', 'DOCX', 'XLSX'].includes(format.value));

function formatChanged() {
  name.value = names[format.value];
}

function create() {
  emit(
    'create',
    format.value,
    name.value,
    content.value.trim() || undefined,
    sourceImageId.value.trim() || undefined,
  );
}

function exportAnswer(targetFormat: 'MARKDOWN' | 'TEXT') {
  if (!assistantText.value.trim()) return;
  format.value = targetFormat;
  name.value = targetFormat === 'MARKDOWN' ? 'ai-answer.md' : 'ai-answer.txt';
  content.value = assistantText.value;
  create();
}
</script>

<template>
  <section class="ai-panel ai-artifacts" aria-labelledby="ai-artifact-title">
    <div class="ai-panel__heading">
      <div>
        <p class="ai-eyebrow">可复用输出</p>
        <h2 id="ai-artifact-title">生成文件</h2>
      </div>
      <span class="ai-count-badge">{{ artifacts.length }} 个</span>
    </div>

    <div v-if="canExportAnswer" class="ai-artifact-quick-actions">
      <strong>当前回答可直接导出</strong>
      <div class="ai-artifact-quick-buttons">
        <button
          type="button"
          class="ai-button ai-button--quiet"
          :disabled="disabled"
          @click="exportAnswer('MARKDOWN')"
        >
          导出 Markdown
        </button>
        <button
          type="button"
          class="ai-button ai-button--quiet"
          :disabled="disabled"
          @click="exportAnswer('TEXT')"
        >
          导出 TXT
        </button>
      </div>
    </div>

    <form class="ai-artifact-form" @submit.prevent="create">
      <label>
        格式
        <select v-model="format" :disabled="disabled || !hasTask" @change="formatChanged">
          <option value="MARKDOWN">Markdown</option>
          <option value="TEXT">TXT</option>
          <option value="JSON">JSON</option>
          <option value="CSV">CSV</option>
          <option value="PDF">PDF</option>
          <option value="DOCX">Word DOCX</option>
          <option value="XLSX">Excel XLSX</option>
          <option value="IMAGE">已有 AI 图片</option>
        </select>
      </label>
      <label>
        文件名
        <input v-model="name" maxlength="255" :disabled="disabled || !hasTask" />
      </label>
      <label v-if="needsContent">
        内容
        <textarea
          v-model="content"
          rows="3"
          :placeholder="format === 'JSON' ? '{&quot;key&quot;: &quot;value&quot;}' : 'name,value'"
          :disabled="disabled || !hasTask"
        />
      </label>
      <label v-if="format === 'IMAGE'">
        AI 图片 publicId
        <input v-model="sourceImageId" placeholder="UUID" :disabled="disabled || !hasTask" />
      </label>
      <div class="ai-artifact-form__footer">
        <button
          class="ai-button ai-button--quiet"
          type="submit"
          :disabled="disabled || !hasTask || !name.trim()"
        >
          创建可下载文件
        </button>
        <RouterLink class="ai-link-button" to="/admin/ai/images">去 AI 生图</RouterLink>
      </div>
    </form>

    <ul v-if="artifacts.length" class="ai-artifact-list">
      <li v-for="artifact in artifacts" :key="artifact.id">
        <span>
          <strong>{{ artifact.name }}</strong>
          <small>{{ artifact.mediaType }} · {{ artifact.status }}</small>
        </span>
        <div>
          <button type="button" class="ai-link-button" @click="downloadAiArtifact(artifact)">下载</button>
          <button
            type="button"
            class="ai-link-button ai-link-button--danger"
            @click="emit('remove', artifact)"
          >
            删除
          </button>
        </div>
      </li>
    </ul>
    <p v-else class="ai-empty">任务完成后，可以下载 Markdown、TXT、PDF、DOCX、XLSX 或结构化结果。</p>
  </section>
</template>

<style scoped>
.ai-panel__heading,
.ai-artifact-form__footer,
.ai-artifact-list li {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.7rem;
}

.ai-count-badge {
  padding: 0.28rem 0.55rem;
  border-radius: 999px;
  color: #2f4bbf;
  background: #edf1ff;
  font-size: 0.7rem;
  font-weight: 800;
}

.ai-artifact-quick-actions {
  display: grid;
  gap: 0.55rem;
  margin-top: 0.8rem;
  padding: 0.7rem;
  border: 1px solid #d9e0ff;
  border-radius: 0.8rem;
  background: #f5f7ff;
}

.ai-artifact-quick-buttons {
  display: flex;
  flex-wrap: wrap;
  gap: 0.4rem;
}

.ai-artifact-form {
  display: grid;
  gap: 0.6rem;
  margin-top: 0.85rem;
}

.ai-artifact-form label {
  display: grid;
  gap: 0.25rem;
  color: var(--ai-muted, #687189);
  font-size: 0.72rem;
  font-weight: 800;
}

.ai-artifact-form input,
.ai-artifact-form select,
.ai-artifact-form textarea {
  width: 100%;
  padding: 0.48rem 0.55rem;
  border: 1px solid #d9e0f5;
  border-radius: 0.55rem;
  color: var(--ai-ink, #17233d);
  background: #fff;
  font: inherit;
  font-size: 0.78rem;
}

.ai-artifact-form textarea {
  resize: vertical;
}

.ai-artifact-form__footer {
  flex-wrap: wrap;
}

.ai-artifact-list {
  display: grid;
  gap: 0.55rem;
  margin-top: 0.85rem !important;
}

.ai-artifact-list li {
  border-top: 1px solid var(--ai-line, rgba(23, 35, 61, 0.13));
  padding-top: 0.6rem;
}

.ai-artifact-list li > span {
  display: grid;
  min-width: 0;
  gap: 0.15rem;
}

.ai-artifact-list strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-artifact-list small {
  color: var(--ai-muted, #687189);
  font-size: 0.7rem;
}
</style>
