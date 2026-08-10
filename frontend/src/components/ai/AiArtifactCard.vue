<script setup lang="ts">
import { computed, ref } from 'vue';
import { downloadAiArtifact, type AiArtifact, type AiArtifactFormat } from '../../api/ai';

const props = defineProps<{ artifacts: AiArtifact[]; hasTask: boolean; disabled?: boolean }>();
const emit = defineEmits<{
  create: [format: AiArtifactFormat, name: string, content?: string, sourceImageId?: string];
  remove: [artifact: AiArtifact];
}>();
const format = ref<AiArtifactFormat>('MARKDOWN');
const name = ref('result.md');
const content = ref('');
const sourceImageId = ref('');
const needsContent = computed(() => format.value === 'JSON' || format.value === 'CSV');

function formatChanged() {
  const names: Record<AiArtifactFormat, string> = {
    MARKDOWN: 'result.md',
    TEXT: 'result.txt',
    JSON: 'result.json',
    CSV: 'result.csv',
    IMAGE: 'generated-image.png',
  };
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
</script>

<template>
  <section class="ai-panel" aria-labelledby="ai-artifact-title">
    <div class="ai-panel__heading">
      <div>
        <p class="ai-eyebrow">受控下载</p>
        <h2 id="ai-artifact-title">生成物</h2>
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
          <option value="IMAGE">已有 AI 图片</option>
        </select>
      </label>
      <label>
        文件名
        <input v-model="name" maxlength="255" :disabled="disabled || !hasTask" />
      </label>
      <label v-if="needsContent">
        结构化内容
        <textarea
          v-model="content"
          rows="3"
          :placeholder="format === 'JSON' ? '{&quot;key&quot;: &quot;value&quot;}' : 'name,value'"
        />
      </label>
      <label v-if="format === 'IMAGE'">
        AI 生图 publicId
        <input v-model="sourceImageId" placeholder="UUID" />
      </label>
      <button
        class="ai-button ai-button--quiet"
        type="submit"
        :disabled="disabled || !hasTask || !name.trim()"
      >
        生成 artifact
      </button>
    </form>
    <ul v-if="props.artifacts.length" class="ai-artifact-list">
      <li v-for="artifact in props.artifacts" :key="artifact.id">
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
    <p v-else class="ai-empty">任务完成后可生成 Markdown、TXT、JSON、CSV 或登记已有 AI 图片。</p>
  </section>
</template>
