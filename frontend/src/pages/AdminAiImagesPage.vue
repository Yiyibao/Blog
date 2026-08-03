<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  deleteAiGeneratedImage,
  fetchAiImageContent,
  fetchAiImageModels,
  generateAiImages,
  type AiGeneratedImage,
  type AiImageModel,
} from '../api/admin'

type DisplayImage = AiGeneratedImage & { objectUrl: string }

const models = ref<AiImageModel[]>([])
const selectedKey = ref('')
const prompt = ref('')
const size = ref('1024x1024')
const quality = ref('auto')
const aspectRatio = ref('1:1')
const resolution = ref('auto')
const loading = ref(false)
const loadingModels = ref(true)
const error = ref('')
const images = ref<DisplayImage[]>([])

const selected = computed(() => models.value.find(item => `${item.provider}:${item.model}` === selectedKey.value))

function revokeImages() {
  for (const image of images.value) URL.revokeObjectURL(image.objectUrl)
  images.value = []
}

async function loadModels() {
  loadingModels.value = true
  error.value = ''
  try {
    models.value = await fetchAiImageModels()
    const defaultModel = models.value.find(item => item.isDefault) ?? models.value[0]
    selectedKey.value = defaultModel ? `${defaultModel.provider}:${defaultModel.model}` : ''
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '图片模型加载失败'
  } finally {
    loadingModels.value = false
  }
}

async function generate() {
  const text = prompt.value.trim()
  if (!text || !selected.value || loading.value) return
  loading.value = true
  error.value = ''
  revokeImages()
  try {
    const generated = await generateAiImages({
      prompt: text,
      provider: selected.value.provider,
      model: selected.value.model,
      n: 1,
      size: size.value,
      quality: quality.value,
      aspectRatio: aspectRatio.value,
      resolution: resolution.value,
    })
    const displayed: DisplayImage[] = []
    for (const image of generated) {
      const blob = await fetchAiImageContent(image.publicId)
      displayed.push({ ...image, objectUrl: URL.createObjectURL(blob) })
    }
    images.value = displayed
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '图片生成失败'
  } finally {
    loading.value = false
  }
}

async function remove(image: DisplayImage) {
  try {
    await deleteAiGeneratedImage(image.publicId)
    URL.revokeObjectURL(image.objectUrl)
    images.value = images.value.filter(item => item.publicId !== image.publicId)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '删除图片失败'
  }
}

function download(image: DisplayImage) {
  const anchor = document.createElement('a')
  anchor.href = image.objectUrl
  anchor.download = `${image.provider}-${image.model}-${image.publicId}.${image.mediaType.split('/')[1] || 'png'}`
  anchor.click()
}

onMounted(() => { void loadModels() })
onBeforeUnmount(revokeImages)
</script>

<template>
  <main class="ai-images-page">
    <header class="page-header">
      <div>
        <p class="eyebrow">AI CREATIVE STUDIO</p>
        <h1>AI 生图</h1>
        <p class="subtitle">通过服务器端中转安全调用 Grok 或 GPT 图片模型，生成结果只对当前管理账号可见。</p>
      </div>
      <RouterLink class="back-link" to="/admin/ai">返回 AI 助手</RouterLink>
    </header>

    <section class="generator-card">
      <label class="prompt-label" for="image-prompt">描述你想生成的图片</label>
      <textarea id="image-prompt" v-model="prompt" maxlength="4000"
        placeholder="例如：雨后清晨的杭州西湖，国风插画，柔和的薄雾与金色光线" />
      <div class="form-grid">
        <label>模型
          <select v-model="selectedKey" :disabled="loadingModels || loading">
            <option v-if="!models.length" value="">暂无可用模型</option>
            <option v-for="model in models" :key="`${model.provider}:${model.model}`" :value="`${model.provider}:${model.model}`">
              {{ model.provider.toUpperCase() }} · {{ model.model }}
            </option>
          </select>
        </label>
        <label>尺寸
          <select v-model="size" :disabled="loading">
            <option value="1024x1024">1024 × 1024</option>
            <option value="1024x1536">1024 × 1536</option>
            <option value="1536x1024">1536 × 1024</option>
          </select>
        </label>
        <label>质量
          <select v-model="quality" :disabled="loading">
            <option value="auto">自动</option>
            <option value="low">低（测试）</option>
            <option value="medium">中</option>
            <option value="high">高</option>
          </select>
        </label>
        <label>比例
          <select v-model="aspectRatio" :disabled="loading">
            <option value="1:1">1:1</option>
            <option value="16:9">16:9</option>
            <option value="9:16">9:16</option>
            <option value="4:3">4:3</option>
            <option value="3:4">3:4</option>
          </select>
        </label>
      </div>
      <div class="actions">
        <span v-if="selected" class="selected-hint">当前：{{ selected.provider.toUpperCase() }} / {{ selected.model }}</span>
        <button type="button" class="generate-button" :disabled="loading || loadingModels || !prompt.trim() || !selected" @click="generate">
          {{ loading ? '生成中…' : '生成图片' }}
        </button>
      </div>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
    </section>

    <section v-if="images.length" class="results-card">
      <div class="results-heading"><h2>生成结果</h2><span>{{ images.length }} 张</span></div>
      <div class="image-grid">
        <article v-for="image in images" :key="image.publicId" class="image-item">
          <img :src="image.objectUrl" :alt="image.prompt" />
          <div class="image-meta">
            <span>{{ image.provider.toUpperCase() }} · {{ image.model }}</span>
            <span>{{ image.width }} × {{ image.height }}</span>
          </div>
          <div class="image-actions">
            <button type="button" @click="download(image)">下载</button>
            <button type="button" class="danger" @click="remove(image)">删除</button>
          </div>
        </article>
      </div>
    </section>
  </main>
</template>

<style scoped>
.ai-images-page { max-width: 1120px; margin: 0 auto; padding: 42px 32px 72px; color: var(--text-primary, #20252d); }
.page-header { display: flex; justify-content: space-between; gap: 24px; align-items: flex-start; margin-bottom: 28px; }
.eyebrow { margin: 0 0 8px; color: #8b6b43; font-size: 11px; letter-spacing: .16em; font-weight: 700; }
h1 { margin: 0; font-size: clamp(28px, 4vw, 42px); letter-spacing: -.03em; }
.subtitle { margin: 10px 0 0; max-width: 660px; color: #69717c; line-height: 1.6; }
.back-link { color: #7a5a35; text-decoration: none; white-space: nowrap; padding-top: 8px; }
.generator-card, .results-card { background: rgba(255,255,255,.82); border: 1px solid rgba(125,100,70,.16); border-radius: 20px; padding: 24px; box-shadow: 0 16px 40px rgba(65,48,30,.06); }
.prompt-label { display: block; font-size: 14px; font-weight: 700; margin-bottom: 10px; }
textarea { width: 100%; min-height: 150px; resize: vertical; box-sizing: border-box; border: 1px solid #d8d0c6; border-radius: 12px; padding: 14px; font: inherit; line-height: 1.6; color: inherit; background: #fffdf9; }
.form-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px; margin-top: 18px; }
label { display: grid; gap: 7px; font-size: 12px; color: #6e655d; font-weight: 700; }
select { border: 1px solid #d8d0c6; border-radius: 10px; padding: 10px; background: #fffdf9; color: #29251f; font: inherit; }
.actions { display: flex; justify-content: space-between; align-items: center; gap: 14px; margin-top: 22px; }
.selected-hint { color: #7c7268; font-size: 13px; }
.generate-button { border: 0; border-radius: 999px; padding: 11px 22px; background: #34291f; color: #fff; font-weight: 700; cursor: pointer; }
.generate-button:disabled { opacity: .45; cursor: not-allowed; }
.error { color: #b44332; margin: 16px 0 0; }
.results-card { margin-top: 24px; }
.results-heading { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
h2 { margin: 0; font-size: 20px; }
.results-heading span { color: #8a8179; font-size: 13px; }
.image-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 18px; }
.image-item { overflow: hidden; border: 1px solid #e6ded4; border-radius: 14px; background: #fffdf9; }
.image-item img { display: block; width: 100%; aspect-ratio: 1; object-fit: cover; background: #f1ece6; }
.image-meta { display: flex; justify-content: space-between; gap: 8px; padding: 11px 12px 0; color: #81766d; font-size: 11px; }
.image-actions { display: flex; gap: 8px; padding: 10px 12px 12px; }
.image-actions button { border: 1px solid #d8d0c6; border-radius: 8px; padding: 7px 12px; background: #fff; cursor: pointer; }
.image-actions .danger { color: #a33b2c; }
@media (max-width: 760px) { .ai-images-page { padding: 28px 18px 56px; } .page-header { display: block; } .back-link { display: inline-block; margin-top: 14px; } .form-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } .actions { align-items: stretch; flex-direction: column; } .generate-button { width: 100%; } }
</style>
