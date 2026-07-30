<script setup lang="ts">
import axios from 'axios'
import { onMounted, ref } from 'vue'
import {
  createRecipeExtraction, cancelRecipeExtraction,
  fetchAiProviders, commitDishImport, cancelDishImport, downloadStagedRecipe,
  type RecipeExtractionJob, type AiProvider,
} from '../api/admin'

const emit = defineEmits<{
  done: []
}>()

const sourceTab = ref<'TEXT' | 'WEB_URL' | 'VIDEO_URL'>('TEXT')
const sourceContent = ref('')
const providers = ref<AiProvider[]>([])
const selectedProviderId = ref<number | null>(null)
const selectedModel = ref('')
const loading = ref(false)
const error = ref('')

const runningJob = ref<RecipeExtractionJob | null>(null)
const completedJob = ref<RecipeExtractionJob | null>(null)

const history = ref<RecipeExtractionJob[]>([])

onMounted(async () => {
  try {
    providers.value = await fetchAiProviders()
  } catch {}
})

async function startExtraction() {
  if (!sourceContent.value.trim()) {
    error.value = '请输入菜谱文本或 URL'
    return
  }
  if (sourceTab.value === 'WEB_URL' || sourceTab.value === 'VIDEO_URL') {
    const url = sourceContent.value.trim()
    if (!url.startsWith('https://')) {
      error.value = '仅支持 HTTPS 链接'
      return
    }
  }
  loading.value = true
  error.value = ''
  completedJob.value = null
  runningJob.value = null
  try {
    const job = await createRecipeExtraction({
      sourceType: sourceTab.value,
      sourceContent: sourceContent.value.trim(),
      providerId: selectedProviderId.value,
      model: selectedModel.value || null,
    })
    completedJob.value = job
    history.value.unshift(job)
    if (history.value.length > 20) history.value.pop()
    if (job.preview) {
      commitSlug.value = job.preview.recipe.recipe.slug || ''
      commitCategory.value = job.preview.categoryMatch || ''
    }
    if (job.status === 'FAILED') {
      error.value = job.safeErrorMessage || '提取失败'
    }
  } catch (cause) {
    if (axios.isAxiosError(cause) && cause.response?.data?.message) {
      error.value = cause.response.data.message
    } else {
      error.value = '请求失败，请检查网络连接'
    }
  } finally {
    loading.value = false
  }
}

async function commitExtraction() {
  if (!completedJob.value?.preview) return
  loading.value = true
  error.value = ''
  try {
    await commitDishImport(completedJob.value.preview.token, {
      category: commitCategory.value,
      correctedSlug: commitSlug.value || undefined,
      published: publishAfterImport.value,
    })
    emit('done')
    close()
  } catch (cause) {
    if (axios.isAxiosError(cause) && cause.response?.data?.message) {
      error.value = cause.response.data.message
    } else {
      error.value = '创建菜品草稿失败'
    }
  } finally {
    loading.value = false
  }
}

async function handleCancel() {
  if (runningJob.value) {
    try { await cancelRecipeExtraction(runningJob.value.id) } catch {}
  }
  if (completedJob.value?.preview) {
    try { await cancelDishImport(completedJob.value.preview.token) } catch {}
  }
  close()
}

function close() {
  sourceContent.value = ''
  runningJob.value = null
  completedJob.value = null
  error.value = ''
  commitSlug.value = ''
  commitCategory.value = ''
  publishAfterImport.value = false
}

const commitSlug = ref('')
const commitCategory = ref('')
const publishAfterImport = ref(false)

async function downloadRecipePackage() {
  if (!completedJob.value?.preview) return
  error.value = ''
  try {
    await downloadStagedRecipe(completedJob.value.preview.token)
  } catch (cause) {
    error.value = axios.isAxiosError(cause) && cause.response?.data?.message
      ? cause.response.data.message : '下载菜谱文件失败。'
  }
}

function statusBadgeClass(status: string) {
  if (status === 'SUCCEEDED') return 'badge-success'
  if (status === 'FAILED') return 'badge-error'
  if (status === 'RUNNING') return 'badge-running'
  if (status === 'CANCELLED') return 'badge-cancelled'
  return 'badge-queued'
}

function statusLabel(status: string) {
  const labels: Record<string, string> = {
    QUEUED: '排队中',
    RUNNING: '进行中',
    SUCCEEDED: '已完成',
    FAILED: '失败',
    CANCELLED: '已取消',
  }
  return labels[status] || status
}
</script>

<template>
  <div class="admin-editor-backdrop" @click.self="handleCancel">
    <section class="admin-editor extraction-modal" role="dialog" aria-modal="true" aria-label="AI 提取菜谱">
      <header>
        <div>
          <small>AI RECIPE EXTRACTION</small>
          <h2>AI 提取菜谱</h2>
        </div>
        <button type="button" aria-label="关闭" @click="handleCancel">×</button>
      </header>

      <div v-if="!completedJob" class="extraction-form">
        <div class="source-tabs">
          <button :class="{ active: sourceTab === 'TEXT' }" @click="sourceTab = 'TEXT'">文本粘贴</button>
          <button :class="{ active: sourceTab === 'WEB_URL' }" @click="sourceTab = 'WEB_URL'">网页链接</button>
          <button :class="{ active: sourceTab === 'VIDEO_URL' }" @click="sourceTab = 'VIDEO_URL'">视频链接</button>
        </div>

        <div v-if="sourceTab === 'TEXT'" class="editor-card">
          <label>
            <span>粘贴菜谱文本</span>
            <textarea
              v-model="sourceContent"
              class="extraction-textarea"
              placeholder="将菜谱文本粘贴到这里…"
              rows="8"
              maxlength="100000"
              :disabled="loading"
            />
          </label>
        </div>
        <div v-else class="editor-card">
          <label>
            <span>{{ sourceTab === 'VIDEO_URL' ? '视频链接' : '网页链接' }}</span>
            <input
              v-model="sourceContent"
              type="url"
              class="extraction-input"
              :placeholder="sourceTab === 'VIDEO_URL' ? '粘贴 B 站、YouTube、抖音或小红书视频链接' : 'https://example.com/recipe'"
              maxlength="2048"
              :disabled="loading"
            >
          </label>
          <p v-if="sourceTab === 'VIDEO_URL'" class="source-help">
            仅提取视频标题、简介、字幕和缩略图，不下载完整视频；没有字幕或详细简介时会停止生成。
          </p>
        </div>

        <div class="editor-card">
          <div class="card-title"><span class="badge-dot" />AI 供应商 & 模型</div>
          <div class="provider-select-row">
            <select v-model="selectedProviderId" :disabled="loading">
              <option :value="null">默认供应商</option>
              <option v-for="p in providers" :key="p.id" :value="p.id">{{ p.name }}</option>
            </select>
            <input
              v-model="selectedModel"
              type="text"
              class="model-input"
              placeholder="模型名称（可选）"
              maxlength="120"
              :disabled="loading"
            >
          </div>
        </div>

        <p v-if="error" class="admin-error" role="alert">{{ error }}</p>

        <footer>
          <button class="button secondary" type="button" :disabled="loading" @click="handleCancel">取消</button>
          <button class="button primary" type="button" :disabled="loading || !sourceContent.trim()" @click="startExtraction">
            {{ loading ? '提取中…' : '开始提取' }}
          </button>
        </footer>
      </div>

      <div v-else class="extraction-result">
        <div v-if="completedJob.status === 'SUCCEEDED' && completedJob.preview">
          <div class="result-header">
            <span class="status-badge badge-success">提取成功</span>
          </div>

          <div class="import-preview-layout">
            <div class="import-cover">
              <img :src="completedJob.preview.coverPreviewUrl" :alt="completedJob.preview.recipe.cover.alt || completedJob.preview.recipe.recipe.name">
            </div>
            <div class="import-details">
              <h3>{{ completedJob.preview.recipe.recipe.name }}</h3>
              <p class="import-summary">{{ completedJob.preview.recipe.recipe.summary }}</p>
              <div class="import-meta">
                <span>{{ completedJob.preview.recipe.recipe.prepMinutes }} 分钟</span>
                <span>{{ completedJob.preview.recipe.recipe.difficulty || '未指定难度' }}</span>
                <span>{{ completedJob.preview.recipe.recipe.baseServings }} 人份</span>
              </div>
              <div v-if="completedJob.preview.warnings.length" class="import-warnings">
                <p v-for="(w, i) in completedJob.preview.warnings" :key="i" class="import-warning">{{ w }}</p>
              </div>
            </div>
          </div>

          <div class="editor-card">
            <div class="card-title"><span class="badge-dot" />导入设置</div>
            <div class="admin-form-grid">
              <label>
                <span>Slug（路由别名）</span>
                <input v-model="commitSlug" type="text" pattern="[a-z0-9]+(?:-[a-z0-9]+)*" maxlength="120">
              </label>
              <label>
                <span>菜品分类</span>
                <input v-model="commitCategory" type="text" placeholder="输入分类名称">
              </label>
            </div>
            <label class="publish-option">
              <input v-model="publishAfterImport" type="checkbox">
              <span>创建后直接发布，使其立即进入菜单模块的可选菜品</span>
            </label>
          </div>

          <div class="editor-card">
            <div class="card-title"><span class="badge-dot" />食材</div>
            <ul class="import-list">
              <li v-for="(item, i) in completedJob.preview.recipe.recipe.ingredients" :key="i">{{ item }}</li>
            </ul>
          </div>
          <div class="editor-card">
            <div class="card-title"><span class="badge-dot" />制作步骤</div>
            <ol class="import-list">
              <li v-for="(step, i) in completedJob.preview.recipe.recipe.steps" :key="i">{{ step }}</li>
            </ol>
          </div>

          <p v-if="error" class="admin-error" role="alert">{{ error }}</p>
          <footer>
            <button class="button secondary" type="button" :disabled="loading" @click="handleCancel">取消</button>
            <button class="button secondary" type="button" :disabled="loading" @click="downloadRecipePackage">
              下载 .yrecipe
            </button>
            <button class="button primary" type="button" :disabled="loading || !commitCategory" @click="commitExtraction">
              {{ loading ? '正在创建…' : (publishAfterImport ? '创建并发布菜品' : '创建菜品草稿') }}
            </button>
          </footer>
        </div>

        <div v-else-if="completedJob.status === 'FAILED'" class="result-failed">
          <div class="result-header">
            <span class="status-badge badge-error">提取失败</span>
          </div>
          <p class="admin-error">{{ completedJob.safeErrorMessage || '未知错误' }}</p>
          <footer>
            <button class="button secondary" type="button" @click="completedJob = null; error = ''">重新尝试</button>
            <button class="button secondary" type="button" @click="handleCancel">关闭</button>
          </footer>
        </div>

        <div v-else class="result-other">
          <div class="result-header">
            <span class="status-badge" :class="statusBadgeClass(completedJob.status)">{{ statusLabel(completedJob.status) }}</span>
          </div>
          <footer>
            <button class="button secondary" type="button" @click="handleCancel">关闭</button>
          </footer>
        </div>
      </div>

      <div v-if="history.length > 1" class="history-section">
        <h4>提取记录</h4>
        <div class="history-list">
          <div
            v-for="job in history"
            :key="job.id"
            class="history-item"
            :class="{ active: job.id === completedJob?.id }"
          >
            <div class="history-info">
              <span class="history-source">{{ job.sourceType === 'TEXT' ? '文本' : (job.sourceType === 'VIDEO_URL' ? '视频' : '网页') }}</span>
              <span class="history-time">{{ new Date(job.createdAt).toLocaleString('zh-CN') }}</span>
            </div>
            <span class="status-badge small" :class="statusBadgeClass(job.status)">{{ statusLabel(job.status) }}</span>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.extraction-modal {
  max-width: 680px;
  max-height: 90vh;
  overflow-y: auto;
}
.extraction-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 20px;
}
.source-tabs {
  display: flex;
  gap: 4px;
  background: var(--surface, #f3f4f6);
  border-radius: 10px;
  padding: 3px;
}
.source-tabs button {
  flex: 1;
  padding: 8px 16px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--muted, #6b7280);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
}
.source-tabs button.active {
  background: var(--surface-solid, #ffffff);
  color: var(--ink, #1e293b);
  box-shadow: 0 1px 3px rgba(0,0,0,0.08);
}
.source-tabs button:hover:not(.active) {
  color: var(--ink, #1e293b);
}
.extraction-textarea {
  width: 100%;
  min-height: 160px;
  border: 1px solid var(--line-strong, #d1d5db);
  border-radius: 8px;
  padding: 12px;
  font: 13px/1.6 inherit;
  color: var(--ink, #1e293b);
  background: var(--surface-solid, #ffffff);
  resize: vertical;
  outline: none;
}
.extraction-textarea:focus {
  border-color: var(--accent, #7c3aed);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--accent, #7c3aed) 20%, transparent);
}
.extraction-input {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid var(--line-strong, #d1d5db);
  border-radius: 8px;
  font-size: 13px;
  outline: none;
  color: var(--ink, #1e293b);
}
.extraction-input:focus {
  border-color: var(--accent, #7c3aed);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--accent, #7c3aed) 20%, transparent);
}
.source-help {
  margin: 8px 0 0;
  color: var(--muted, #64748b);
  font-size: 12px;
  line-height: 1.5;
}
.publish-option {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-top: 14px;
  color: var(--ink, #1e293b);
  font-size: 13px;
  line-height: 1.5;
}
.publish-option input {
  margin-top: 2px;
}
.provider-select-row {
  display: flex;
  gap: 10px;
}
.provider-select-row select, .model-input {
  flex: 1;
  padding: 8px 10px;
  border: 1px solid var(--line-strong, #d1d5db);
  border-radius: 8px;
  font-size: 13px;
  outline: none;
  color: var(--ink, #1e293b);
  background: var(--surface-solid, #ffffff);
}
.extraction-result {
  padding: 20px;
}
.result-header {
  margin-bottom: 16px;
}
.status-badge {
  display: inline-block;
  padding: 4px 12px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
}
.status-badge.small {
  padding: 2px 8px;
  font-size: 11px;
}
.badge-success { background: #d1fae5; color: #065f46; }
.badge-error { background: #fee2e2; color: #991b1b; }
.badge-running { background: #dbeafe; color: #1e40af; }
.badge-cancelled { background: #f3f4f6; color: #6b7280; }
.badge-queued { background: #fef3c7; color: #92400e; }
.result-failed {
  text-align: center;
  padding: 40px 20px;
}
.result-failed .admin-error {
  margin: 16px 0;
}
.result-other {
  text-align: center;
  padding: 40px 20px;
}
.result-other footer {
  margin-top: 20px;
}
.import-preview-layout {
  display: flex;
  gap: 16px;
  margin-bottom: 16px;
}
.import-cover {
  width: 120px;
  height: 120px;
  flex-shrink: 0;
  border-radius: 12px;
  overflow: hidden;
  background: var(--surface, #f3f4f6);
}
.import-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.import-details {
  flex: 1;
  min-width: 0;
}
.import-details h3 {
  margin: 0 0 6px;
  font-size: 16px;
  color: var(--ink, #1e293b);
}
.import-summary {
  margin: 0 0 8px;
  font-size: 13px;
  color: var(--muted, #6b7280);
  line-height: 1.5;
}
.import-meta {
  display: flex;
  gap: 12px;
  font-size: 12px;
  color: var(--muted, #6b7280);
}
.import-warnings {
  margin-top: 8px;
}
.import-warning {
  margin: 2px 0;
  font-size: 12px;
  color: #92400e;
  background: #fef3c7;
  padding: 4px 8px;
  border-radius: 4px;
}
.admin-form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
.admin-form-grid label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  font-weight: 500;
  color: var(--ink, #1e293b);
}
.admin-form-grid input {
  padding: 8px 10px;
  border: 1px solid var(--line-strong, #d1d5db);
  border-radius: 6px;
  font-size: 13px;
  outline: none;
}
.import-list {
  margin: 0;
  padding-left: 20px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--ink, #1e293b);
}
.history-section {
  border-top: 1px solid var(--line, #e5e7eb);
  padding: 16px 20px;
}
.history-section h4 {
  margin: 0 0 10px;
  font-size: 13px;
  color: var(--muted, #6b7280);
}
.history-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 200px;
  overflow-y: auto;
}
.history-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 10px;
  border-radius: 8px;
  background: var(--surface, #f9fafb);
}
.history-item.active {
  background: color-mix(in srgb, var(--accent, #7c3aed) 8%, var(--surface, #f9fafb));
}
.history-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.history-source {
  font-size: 13px;
  font-weight: 500;
  color: var(--ink, #1e293b);
}
.history-time {
  font-size: 11px;
  color: var(--muted, #6b7280);
}
.card-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink, #1e293b);
  margin-bottom: 10px;
  display: flex;
  align-items: center;
  gap: 6px;
}
.badge-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--accent, #7c3aed);
}
.editor-card {
  padding: 16px;
  border: 1px solid var(--line-strong, #d1d5db);
  border-radius: 12px;
  background: var(--surface-solid, #ffffff);
  margin-bottom: 12px;
}
.editor-card label {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 12px;
  font-weight: 500;
  color: var(--ink, #1e293b);
}
@media (max-width: 600px) {
  .import-preview-layout {
    flex-direction: column;
    align-items: center;
  }
  .admin-form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
