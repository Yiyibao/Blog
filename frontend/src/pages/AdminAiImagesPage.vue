<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import AiImageThumb from '../components/AiImageThumb.vue';
import {
  AI_IMAGE_MAX_PROMPT_CHARS,
  AI_IMAGE_MAX_REFERENCE_BYTES,
  AI_IMAGE_REFERENCE_ACCEPT,
} from '../config/aiLimits';
import {
  deleteAiGeneratedImage,
  deleteAiImageSession,
  fetchAiImageModels,
  fetchAiImageSessions,
  fetchAiImageSessionImages,
  generateAiImages,
  type AiGeneratedImage,
  type AiImageModel,
  type AiImageSession,
} from '../api/admin';

interface Turn {
  prompt: string;
  provider: string;
  model: string;
  images: AiGeneratedImage[];
  referenceImageUrl?: string;
}

interface PreviewState {
  publicId: string;
  objectUrl: string;
  prompt: string;
}

const models = ref<AiImageModel[]>([]);
const selectedKey = ref('');
const prompt = ref('');
const size = ref('1024x1024');
const quality = ref('auto');
const aspectRatio = ref('1:1');
const resolution = ref('auto');
const loading = ref(false);
const loadingModels = ref(true);
const error = ref('');
const turns = ref<Turn[]>([]);
const preview = ref<PreviewState | null>(null);
const sessions = ref<AiImageSession[]>([]);
const sessionsLoading = ref(false);
const sessionsError = ref('');
const currentSessionId = ref<number | null>(null);
const sidebarOpen = ref(true);
const chatBoxRef = ref<HTMLElement | null>(null);
const referenceImageInput = ref<HTMLInputElement | null>(null);
const referenceImage = ref<File | null>(null);
const referenceImageUrl = ref('');
const thumbRegistry = new Map<string, { url: string }>();

const selected = computed(() =>
  models.value.find((item) => `${item.provider}:${item.model}` === selectedKey.value),
);

function apiErrorMessage(cause: unknown, fallback: string) {
  const responseData = (cause as { response?: { data?: unknown } } | null)?.response?.data;
  if (responseData && typeof responseData === 'object' && 'message' in responseData) {
    const message = (responseData as { message?: unknown }).message;
    if (typeof message === 'string' && message.trim()) return message;
  }
  return cause instanceof Error ? cause.message : fallback;
}

function revokeTurns() {
  for (const turn of turns.value) {
    if (turn.referenceImageUrl) URL.revokeObjectURL(turn.referenceImageUrl);
  }
  turns.value = [];
}

function createObjectUrl(file: File) {
  return typeof URL.createObjectURL === 'function' ? URL.createObjectURL(file) : '';
}

function clearReferenceImage() {
  if (referenceImageUrl.value) URL.revokeObjectURL(referenceImageUrl.value);
  referenceImage.value = null;
  referenceImageUrl.value = '';
  if (referenceImageInput.value) referenceImageInput.value.value = '';
}

function chooseReferenceImage() {
  if (!loading.value) referenceImageInput.value?.click();
}

function handleReferenceImageChange(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file) return;
  setReferenceImage(file);
}

function setReferenceImage(file: File) {
  const acceptedTypes = new Set(['image/png', 'image/jpeg', 'image/webp', 'image/gif']);
  if (!acceptedTypes.has(file.type.toLowerCase())) {
    error.value = '参考图只支持 PNG、JPG/JPEG、WebP 或 GIF 格式';
    return;
  }
  if (file.size > AI_IMAGE_MAX_REFERENCE_BYTES) {
    error.value = `参考图不能超过 ${(AI_IMAGE_MAX_REFERENCE_BYTES / 1_000_000).toFixed(0)} MB`;
    return;
  }
  clearReferenceImage();
  referenceImage.value = file;
  referenceImageUrl.value = createObjectUrl(file);
  error.value = '';
}

function handleReferenceDrop(event: DragEvent) {
  if (loading.value) return;
  const file = event.dataTransfer?.files?.[0];
  if (file) setReferenceImage(file);
}

function handleReferencePaste(event: ClipboardEvent) {
  if (loading.value) return;
  const file = Array.from(event.clipboardData?.files ?? []).find((item) => item.type.startsWith('image/'));
  if (!file) return;
  event.preventDefault();
  setReferenceImage(file);
}

function formatBytes(bytes: number) {
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function removeTurn(turn: Turn) {
  if (turn.referenceImageUrl) URL.revokeObjectURL(turn.referenceImageUrl);
  turns.value = turns.value.filter((item) => item !== turn);
}

async function scrollToBottom() {
  await nextTick();
  if (chatBoxRef.value) {
    chatBoxRef.value.scrollTop = chatBoxRef.value.scrollHeight;
  }
}

function openPreview(state: PreviewState) {
  preview.value = state;
}

function registerThumb(publicId: string, instance: unknown) {
  if (instance) thumbRegistry.set(publicId, instance as { url: string });
  else thumbRegistry.delete(publicId);
}

async function loadModels() {
  loadingModels.value = true;
  error.value = '';
  try {
    models.value = await fetchAiImageModels();
    const defaultModel = models.value.find((item) => item.isDefault) ?? models.value[0];
    selectedKey.value = defaultModel ? `${defaultModel.provider}:${defaultModel.model}` : '';
  } catch (cause) {
    error.value = apiErrorMessage(cause, '图片模型加载失败');
  } finally {
    loadingModels.value = false;
  }
}

async function loadSessions() {
  sessionsLoading.value = true;
  sessionsError.value = '';
  try {
    sessions.value = await fetchAiImageSessions();
  } catch (cause) {
    sessionsError.value = apiErrorMessage(cause, '聊天记录加载失败');
  } finally {
    sessionsLoading.value = false;
  }
}

function newChat() {
  currentSessionId.value = null;
  error.value = '';
  clearReferenceImage();
  revokeTurns();
  void scrollToBottom();
}

async function openSession(session: AiImageSession) {
  if (currentSessionId.value === session.id) return;
  currentSessionId.value = session.id;
  error.value = '';
  clearReferenceImage();
  revokeTurns();
  try {
    const images = await fetchAiImageSessionImages(session.id);
    const turnsOf: Turn[] = [];
    const grouped = new Map<string, AiGeneratedImage[]>();
    for (const image of images) {
      const bucket = grouped.get(image.generationId) ?? [];
      bucket.push(image);
      grouped.set(image.generationId, bucket);
    }
    for (const bucket of grouped.values()) {
      const first = bucket[0]!;
      turnsOf.push({ prompt: first.prompt, provider: first.provider, model: first.model, images: bucket });
    }
    turns.value = turnsOf;
  } catch (cause) {
    currentSessionId.value = null;
    revokeTurns();
    error.value = apiErrorMessage(cause, '聊天记录加载失败');
  }
  await scrollToBottom();
}

async function deleteSession(session: AiImageSession) {
  if (!window.confirm('确认删除这条图片会话及其所有图片？')) return;
  try {
    await deleteAiImageSession(session.id);
    if (currentSessionId.value === session.id) newChat();
    await loadSessions();
  } catch (cause) {
    sessionsError.value = apiErrorMessage(cause, '删除会话失败');
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

async function generate() {
  const text = prompt.value.trim();
  if (!text || !selected.value || loading.value) return;
  const referenceFile = referenceImage.value;
  loading.value = true;
  error.value = '';
  const userMsg: Turn = {
    prompt: text,
    provider: selected.value.provider,
    model: selected.value.model,
    images: [],
    referenceImageUrl: referenceFile ? createObjectUrl(referenceFile) : undefined,
  };
  turns.value = [...turns.value, userMsg];
  const liveTurn = turns.value[turns.value.length - 1]!;
  prompt.value = '';
  clearReferenceImage();
  await scrollToBottom();
  try {
    const result = await generateAiImages({
      prompt: text,
      sessionId: currentSessionId.value,
      provider: selected.value.provider,
      model: selected.value.model,
      n: 1,
      size: size.value,
      quality: quality.value,
      aspectRatio: aspectRatio.value,
      resolution: resolution.value,
      referenceImage: referenceFile ?? undefined,
    });
    currentSessionId.value = result.sessionId;
    liveTurn.provider = result.images[0]?.provider ?? liveTurn.provider;
    liveTurn.model = result.images[0]?.model ?? liveTurn.model;
    liveTurn.images = result.images;
    if (!liveTurn.images.length) {
      removeTurn(liveTurn);
      throw new Error('图片生成结果为空');
    }
    await loadSessions();
  } catch (cause) {
    error.value = apiErrorMessage(cause, '图片生成失败');
    if (!liveTurn.images.length) {
      removeTurn(liveTurn);
    }
  } finally {
    loading.value = false;
    await scrollToBottom();
  }
}

async function removeImage(turn: Turn, image: AiGeneratedImage) {
  try {
    await deleteAiGeneratedImage(image.publicId);
    turn.images = turn.images.filter((item) => item.publicId !== image.publicId);
    await loadSessions();
  } catch (cause) {
    error.value = apiErrorMessage(cause, '删除图片失败');
  }
}

function downloadImage(turn: Turn, image: AiGeneratedImage) {
  const thumb = thumbRegistry.get(image.publicId);
  if (!thumb?.url) return;
  const anchor = document.createElement('a');
  anchor.href = thumb.url;
  anchor.download = `${turn.provider}-${turn.model}-${image.publicId}.${image.mediaType.split('/')[1] || 'png'}`;
  anchor.click();
}

function previewHistory(turn: Turn, image: AiGeneratedImage) {
  const thumb = thumbRegistry.get(image.publicId);
  if (thumb?.url) {
    openPreview({ publicId: image.publicId, objectUrl: thumb.url, prompt: turn.prompt });
  }
}

function closePreview() {
  preview.value = null;
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && preview.value) closePreview();
}

watch(preview, (open) => {
  document.body.style.overflow = open ? 'hidden' : '';
});

onMounted(() => {
  window.addEventListener('keydown', onKeydown);
  void loadModels();
  void loadSessions();
});
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown);
  document.body.style.overflow = '';
  revokeTurns();
});
</script>

<template>
  <main class="ai-images-page">
    <header class="page-header">
      <div>
        <p class="eyebrow">AI CREATIVE STUDIO</p>
        <h1>AI 生图</h1>
        <p class="subtitle">
          通过服务器端中转安全调用 Grok 或 GPT
          图片模型，生成结果只对当前管理账号可见，可基于上一张图继续描述生成。
        </p>
      </div>
      <RouterLink class="back-link" to="/admin/ai">返回 AI 助手</RouterLink>
    </header>

    <section class="image-chat-card">
      <aside class="image-chat-panel" :class="{ hidden: !sidebarOpen }">
        <div class="image-chat-inner">
          <button class="new-chat-btn" type="button" @click="newChat">＋ 新建图片</button>
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
                title="删除这条图片会话"
                aria-label="删除图片会话"
                @click="deleteSession(session)"
              >
                ×
              </button>
            </li>
          </ul>
          <p v-else-if="!sessionsLoading" class="sessions-empty">暂无图片会话</p>
        </div>
      </aside>
      <button
        type="button"
        class="sidebar-toggle"
        :aria-label="sidebarOpen ? '隐藏图片会话' : '展开图片会话'"
        @click="sidebarOpen = !sidebarOpen"
      >
        {{ sidebarOpen ? '◀' : '▶' }}
      </button>

      <div class="image-chat-main">
        <div ref="chatBoxRef" class="chat-messages" role="log" aria-live="polite">
          <div v-if="!turns.length && !loading" class="chat-welcome">
            <div class="welcome-icon">🖼️</div>
            <h2>AI 生图工作台</h2>
            <p>输入提示词生成图片；每一轮生成会保存为一条会话，点击左侧记录即可回到当时的对话继续创作。</p>
          </div>

          <div v-for="(turn, index) in turns" :key="index" class="turn">
            <div class="chat-bubble-wrap user">
              <div class="bubble-avatar">{{ '你'.slice(0, 1) }}</div>
              <div class="bubble-body">
                <header class="bubble-header"><span class="sender-name">我</span></header>
                <div v-if="turn.referenceImageUrl" class="user-reference-image">
                  <img :src="turn.referenceImageUrl" alt="本轮参考图" />
                  <span>参考图</span>
                </div>
                <div class="bubble-content user-content">{{ turn.prompt }}</div>
              </div>
            </div>
            <div class="chat-bubble-wrap assistant">
              <div class="bubble-avatar">🖼️</div>
              <div class="bubble-body">
                <header class="bubble-header">
                  <span class="sender-name">{{ turn.provider.toUpperCase() }} · {{ turn.model }}</span>
                </header>
                <div class="image-grid">
                  <div v-for="image in turn.images" :key="image.publicId" class="image-item">
                    <AiImageThumb
                      :ref="(el: unknown) => registerThumb(image.publicId, el)"
                      :public-id="image.publicId"
                      :alt="turn.prompt"
                      @click="previewHistory(turn, image)"
                    />
                    <div class="image-actions">
                      <button type="button" title="下载图片" @click="downloadImage(turn, image)">下载</button>
                      <button
                        type="button"
                        class="danger"
                        title="删除这张图片"
                        @click="removeImage(turn, image)"
                      >
                        删除
                      </button>
                    </div>
                    <div class="image-meta">
                      <span>{{
                        image.width && image.height ? `${image.width} × ${image.height}` : '尺寸未知'
                      }}</span>
                      <span>{{ formatBytes(image.byteSize) }}</span>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <div v-if="loading" class="chat-bubble-wrap assistant loading-bubble">
            <div class="bubble-avatar">🖼️</div>
            <div class="bubble-body">
              <header class="bubble-header"><span class="sender-name">AI 生图</span></header>
              <div class="bubble-content loading-indicator">
                <span class="dot" /><span class="dot" /><span class="dot" />
                <span class="loading-text">生成中…</span>
              </div>
            </div>
          </div>
        </div>

        <div v-if="error" class="chat-error-bar" role="alert">
          <span>{{ error }}</span>
        </div>

        <div class="chat-input-area">
          <input
            ref="referenceImageInput"
            class="reference-file-input"
            type="file"
            :accept="AI_IMAGE_REFERENCE_ACCEPT"
            @change="handleReferenceImageChange"
          />
          <div v-if="referenceImage" class="reference-image-preview">
            <img v-if="referenceImageUrl" :src="referenceImageUrl" alt="待使用的参考图" />
            <div class="reference-image-info">
              <strong>参考图已选择</strong>
              <span>{{ referenceImage.name }} · {{ formatBytes(referenceImage.size) }}</span>
            </div>
            <button
              type="button"
              class="reference-remove-btn"
              :disabled="loading"
              @click="clearReferenceImage"
            >
              移除
            </button>
          </div>
          <div
            class="reference-image-toolbar"
            @dragenter.prevent
            @dragover.prevent
            @drop.prevent="handleReferenceDrop"
          >
            <button
              type="button"
              class="reference-upload-btn"
              :disabled="loading"
              @click="chooseReferenceImage"
            >
              ＋ 上传参考图
            </button>
            <span>支持 PNG、JPG/JPEG、WebP、GIF，单张不超过 15 MB</span>
          </div>
          <div class="prompt-presets" aria-label="常用提示词">
            <span>快捷开始</span>
            <button
              type="button"
              @click="prompt = '把这张图改成适合社交媒体发布的高级海报，保留主体和核心信息。'"
            >
              改成海报
            </button>
            <button
              type="button"
              @click="prompt = '保持主体不变，优化光线、构图和细节，生成更自然的高清版本。'"
            >
              高清优化
            </button>
            <button
              type="button"
              @click="prompt = '提取这张图的视觉风格，并生成一张同风格但内容全新的图片。'"
            >
              延展风格
            </button>
          </div>
          <textarea
            v-model="prompt"
            class="chat-textarea"
            placeholder="描述你想生成的图片，可参考上方已有图片继续创作…"
            :maxlength="AI_IMAGE_MAX_PROMPT_CHARS"
            rows="3"
            :disabled="loading"
            @paste="handleReferencePaste"
            @keydown="
              (event: KeyboardEvent) => {
                if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
                  event.preventDefault();
                  void generate();
                }
              }
            "
          />
          <div class="chat-model-picker">
            <label
              >模型
              <select v-model="selectedKey" :disabled="loadingModels || loading">
                <option v-if="!models.length" value="">暂无可用模型</option>
                <option
                  v-for="model in models"
                  :key="`${model.provider}:${model.model}`"
                  :value="`${model.provider}:${model.model}`"
                >
                  {{ model.provider.toUpperCase() }} · {{ model.model }}
                </option>
              </select>
            </label>
            <label
              >尺寸
              <select v-model="size" :disabled="loading">
                <option value="1024x1024">1024 × 1024</option>
                <option value="1024x1536">1024 × 1536</option>
                <option value="1536x1024">1536 × 1024</option>
              </select>
            </label>
            <label
              >质量
              <select v-model="quality" :disabled="loading">
                <option value="auto">自动</option>
                <option value="low">低（测试）</option>
                <option value="medium">中</option>
                <option value="high">高</option>
              </select>
            </label>
            <label
              >比例
              <select v-model="aspectRatio" :disabled="loading">
                <option value="1:1">1:1</option>
                <option value="16:9">16:9</option>
                <option value="9:16">9:16</option>
                <option value="4:3">4:3</option>
                <option value="3:4">3:4</option>
              </select>
            </label>
            <label
              >清晰度
              <select v-model="resolution" :disabled="loading">
                <option value="auto">自动</option>
                <option value="1k">1K</option>
                <option value="2k">2K</option>
                <option value="4k">4K</option>
              </select>
            </label>
          </div>
          <div class="input-footer">
            <span
              class="char-count"
              :class="{ 'near-limit': prompt.length > AI_IMAGE_MAX_PROMPT_CHARS - 1_000 }"
              >{{ prompt.length.toLocaleString() }} /
              {{ AI_IMAGE_MAX_PROMPT_CHARS.toLocaleString() }} 字</span
            >
            <button
              class="send-btn"
              type="button"
              :disabled="loading || loadingModels || !prompt.trim() || !selected"
              @click="generate"
            >
              {{ loading ? '生成中…' : '生成图片 ↗' }}
            </button>
          </div>
        </div>
      </div>
    </section>

    <div
      v-if="preview"
      class="lightbox"
      role="dialog"
      aria-modal="true"
      aria-label="图片预览"
      @click.self="closePreview"
    >
      <img :src="preview.objectUrl" :alt="preview.prompt" />
      <button type="button" class="lightbox-close" aria-label="关闭预览" @click="closePreview">×</button>
    </div>
  </main>
</template>

<style scoped>
.ai-images-page {
  width: min(80vw, 1600px);
  max-width: none;
  box-sizing: border-box;
  margin: 0 auto;
  padding: 42px 32px 72px;
  color: var(--text-primary, #20252d);
}
.page-header {
  display: flex;
  justify-content: space-between;
  gap: 24px;
  align-items: flex-start;
  margin-bottom: 28px;
}
.eyebrow {
  margin: 0 0 8px;
  color: #8b6b43;
  font-size: 11px;
  letter-spacing: 0.16em;
  font-weight: 700;
}
h1 {
  margin: 0;
  font-size: clamp(28px, 4vw, 42px);
  letter-spacing: -0.03em;
}
.subtitle {
  margin: 10px 0 0;
  max-width: 660px;
  color: #69717c;
  line-height: 1.6;
}
.back-link {
  color: #7a5a35;
  text-decoration: none;
  white-space: nowrap;
  padding-top: 8px;
}

.image-chat-card {
  display: flex;
  position: relative;
  height: calc(100vh - 210px);
  min-height: 520px;
  background: rgba(255, 255, 255, 0.82);
  border: 1px solid rgba(125, 100, 70, 0.16);
  border-radius: 20px;
  box-shadow: 0 16px 40px rgba(65, 48, 30, 0.06);
  overflow: hidden;
}
.image-chat-panel {
  flex-shrink: 0;
  width: 250px;
  overflow: hidden;
  border-right: 1px solid rgba(125, 100, 70, 0.16);
  background: #faf8f5;
  transition:
    width 0.25s ease,
    border-right-width 0.25s ease;
}
.image-chat-panel.hidden {
  width: 0;
  border-right-width: 0;
}
.image-chat-inner {
  display: flex;
  flex-direction: column;
  width: 250px;
  height: 100%;
  padding: 14px 12px;
  box-sizing: border-box;
}
.new-chat-btn {
  flex-shrink: 0;
  margin-bottom: 12px;
  padding: 10px 14px;
  border: 1px solid #d8d0c6;
  border-radius: 10px;
  background: #fff;
  color: #29251f;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition:
    border-color 0.2s,
    transform 0.2s;
}
.new-chat-btn:hover {
  border-color: #8b6b43;
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
  background: rgba(32, 37, 45, 0.04);
}
.session-item.active {
  background: rgba(139, 107, 67, 0.12);
  border-color: rgba(139, 107, 67, 0.35);
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
  color: #20252d;
}
.session-time {
  display: block;
  margin-top: 3px;
  font-size: 10px;
  color: #81766d;
}
.session-delete {
  flex-shrink: 0;
  margin-right: 6px;
  width: 22px;
  height: 22px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: #81766d;
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
  background: rgba(179, 66, 50, 0.12);
  color: #a33b2c;
}
.sessions-empty {
  margin: 18px 0 0;
  color: #81766d;
  font-size: 12px;
  text-align: center;
}
.sessions-error {
  margin: 0 0 10px;
  padding: 8px 10px;
  border-radius: 8px;
  background: rgba(179, 66, 50, 0.1);
  color: #a33b2c;
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
  border: 1px solid #d8d0c6;
  border-radius: 8px;
  background: #fff;
  color: #81766d;
  font-size: 11px;
  cursor: pointer;
  box-shadow: 0 4px 12px rgba(34, 32, 27, 0.12);
  transition:
    left 0.25s ease,
    color 0.2s,
    border-color 0.2s;
}
.sidebar-toggle:hover {
  color: #8b6b43;
  border-color: #8b6b43;
}
.image-chat-panel.hidden ~ .sidebar-toggle {
  left: 0;
}

.image-chat-main {
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
  max-width: 460px;
  padding: 40px 20px;
}
.welcome-icon {
  font-size: 48px;
  margin-bottom: 16px;
}
.chat-welcome h2 {
  margin: 0 0 8px;
  font-size: 24px;
}
.chat-welcome p {
  color: #69717c;
  font-size: 14px;
  line-height: 1.6;
  margin: 0;
}

.turn {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.chat-bubble-wrap {
  display: flex;
  gap: 12px;
  max-width: 96%;
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
  width: min(100%, 920px);
}
.bubble-header {
  margin-bottom: 4px;
}
.sender-name {
  font-size: 11px;
  color: #81766d;
}
.bubble-content {
  padding: 14px 18px;
  border-radius: 16px;
  font-size: 14px;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
}
.user-content {
  background: #292a27;
  color: #f8f6f0;
  border-top-right-radius: 4px;
}
.user-reference-image {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  margin-bottom: 8px;
  color: #81766d;
  font-size: 11px;
}
.user-reference-image img {
  display: block;
  width: 88px;
  height: 88px;
  object-fit: cover;
  border: 1px solid #d8d0c6;
  border-radius: 10px;
  background: #f1ece6;
}

.image-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 18px;
  width: 100%;
}
.image-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}
.image-meta {
  display: flex;
  gap: 7px;
  color: #9a8f84;
  font-size: 10px;
}
.image-actions {
  display: flex;
  gap: 8px;
}
.image-actions button {
  border: 1px solid #d8d0c6;
  border-radius: 8px;
  padding: 5px 12px;
  background: #fff;
  color: #29251f;
  font-size: 12px;
  cursor: pointer;
}
.image-actions .danger {
  color: #a33b2c;
}

.loading-indicator {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #81766d;
}
.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #d5b18a;
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
  background: rgba(179, 66, 50, 0.1);
  border-top: 1px solid rgba(179, 66, 50, 0.24);
  color: #a33b2c;
  font-size: 13px;
}
.chat-input-area {
  padding: 16px 20px;
  background: #fffdf9;
  border-top: 1px solid rgba(125, 100, 70, 0.16);
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.reference-file-input {
  display: none;
}
.reference-image-preview {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  padding: 8px 10px;
  border: 1px solid rgba(139, 107, 67, 0.28);
  border-radius: 12px;
  background: #faf4eb;
}
.reference-image-preview img {
  flex: 0 0 auto;
  width: 52px;
  height: 52px;
  object-fit: cover;
  border-radius: 8px;
  background: #f1ece6;
}
.reference-image-info {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
  gap: 3px;
  color: #6e655d;
  font-size: 12px;
}
.reference-image-info strong,
.reference-image-info span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.reference-image-info strong {
  color: #4a3b2c;
}
.reference-remove-btn,
.reference-upload-btn {
  border: 1px solid #d8d0c6;
  border-radius: 9px;
  background: #fff;
  color: #5c4935;
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}
.reference-remove-btn {
  flex: 0 0 auto;
  padding: 6px 10px;
}
.reference-remove-btn:hover:not(:disabled) {
  border-color: #a33b2c;
  color: #a33b2c;
}
.reference-image-toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding: 8px 10px;
  border: 1px dashed rgba(139, 107, 67, 0.26);
  border-radius: 10px;
  color: #81766d;
  font-size: 11px;
}
.reference-upload-btn {
  padding: 7px 11px;
  font-weight: 700;
}
.reference-upload-btn:hover:not(:disabled) {
  border-color: #8b6b43;
  color: #8b6b43;
}
.prompt-presets {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  color: #81766d;
  font-size: 11px;
}
.prompt-presets button {
  border: 1px solid #e0d8cf;
  border-radius: 999px;
  padding: 5px 9px;
  background: #fffdf9;
  color: #665443;
  font: inherit;
  cursor: pointer;
}
.prompt-presets button:hover {
  border-color: #8b6b43;
  color: #8b6b43;
}
.reference-remove-btn:disabled,
.reference-upload-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.chat-textarea {
  width: 100%;
  border: 1px solid #d8d0c6;
  border-radius: 12px;
  padding: 12px 14px;
  font: 14px/1.6 inherit;
  color: #20252d;
  background: #faf8f5;
  resize: vertical;
  outline: none;
  min-height: 72px;
  max-height: 200px;
}
.chat-textarea:focus {
  border-color: #8b6b43;
  background: #fff;
}
.chat-model-picker {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  color: #6e655d;
}
.chat-model-picker label {
  display: grid;
  gap: 5px;
  font-weight: 700;
}
.chat-model-picker select {
  border: 1px solid #d8d0c6;
  border-radius: 10px;
  padding: 7px 10px;
  background: #fffdf9;
  color: #29251f;
  font: inherit;
}
.input-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.char-count {
  font-size: 11px;
  color: #81766d;
}
.char-count.near-limit {
  color: #a33b2c;
  font-weight: 600;
}
.send-btn {
  padding: 9px 22px;
  border-radius: 999px;
  border: 0;
  background: #34291f;
  color: #fff;
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
  transition:
    background 0.2s,
    transform 0.2s;
}
.send-btn:hover:not(:disabled) {
  background: #4a3b2c;
  transform: translateY(-1px);
}
.send-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.lightbox {
  position: fixed;
  inset: 0;
  z-index: 100;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: rgba(10, 8, 5, 0.82);
}
.lightbox > img {
  display: block;
  max-width: 96vw;
  max-height: 94vh;
  width: auto;
  height: auto;
  object-fit: contain;
  border-radius: 8px;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.5);
}
.lightbox-close {
  position: absolute;
  top: 16px;
  right: 16px;
  width: 40px;
  height: 40px;
  border: 0;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.14);
  color: #fff;
  font-size: 22px;
  line-height: 1;
  cursor: pointer;
}
.lightbox-close:hover {
  background: rgba(255, 255, 255, 0.28);
}

@media (max-width: 820px) {
  .ai-images-page {
    width: calc(100% - 24px);
    padding: 28px 12px 48px;
  }
  .image-chat-card {
    height: calc(100vh - 190px);
  }
  .chat-bubble-wrap {
    max-width: 94%;
  }
}
</style>
