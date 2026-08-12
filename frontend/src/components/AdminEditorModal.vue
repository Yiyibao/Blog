<script setup lang="ts">
import { ref } from 'vue';
import type { AdminDish, AdminDishCategory, AdminPostCategory } from '../api/admin';
import type { PostStatus } from '../data';
import AiActionChips, { type AiActionKind } from './AiActionChips.vue';
import TyporaEditor from './TyporaEditor.vue';

interface PostEditorForm {
  slug: string;
  title: string;
  excerpt: string;
  date: string;
  readTime: number;
  category: string;
  tags: string;
  color: string;
  number: string;
  featured: boolean;
  status: PostStatus;
  content: string;
  markdownContent: string;
  contentFormat: 'HTML' | 'MARKDOWN';
}

interface DishEditorForm {
  name: string;
  summary: string;
  category: string;
  imageUrl: string;
  imageAlt: string;
  prepMinutes: number;
  difficulty: AdminDish['difficulty'];
  rating: number;
  featured: boolean;
  published: boolean;
  displayOrder: number;
  baseServings: number;
  ingredients: string;
  steps: string;
}

const {
  editingId,
  editorKind,
  editorNoun,
  postForm,
  dishForm,
  categories,
  dishCategories,
  postMarkdownMode,
  converting,
  error,
  saving,
  currentPostContext,
  applyAiAction,
  convertLegacyPost,
  rejectPostImageUpload,
  dishImagePreviewUrl,
  dishImageUploading,
  dishImageError,
  handleDishImageChange,
} = defineProps<{
  editingId: number | null;
  editorKind: 'post' | 'dish';
  editorNoun: string;
  postForm: PostEditorForm;
  dishForm: DishEditorForm;
  categories: AdminPostCategory[];
  dishCategories: AdminDishCategory[];
  postMarkdownMode: boolean;
  converting: boolean;
  error: string;
  saving: boolean;
  currentPostContext: () => string;
  applyAiAction: (action: AiActionKind, text: string) => void;
  convertLegacyPost: () => Promise<void>;
  rejectPostImageUpload: () => Promise<string>;
  dishImagePreviewUrl: string;
  dishImageUploading: boolean;
  dishImageError: string;
  handleDishImageChange: (event: Event) => unknown;
}>();

// The parent owns these reactive form objects; the child only edits their fields.
const mutablePostForm = postForm;
const mutableDishForm = dishForm;

const emit = defineEmits<{
  close: [];
  save: [];
  'open-revision': [];
  'new-category': [];
  'new-dish-category': [];
  error: [message: string];
}>();

const dishImageInput = ref<HTMLInputElement | null>(null);

function chooseDishImage() {
  dishImageInput.value?.click();
}

function reportPostUploadError() {
  emit('error', '文章编辑器暂不支持直传图片，请使用站内路径或外链。');
}
</script>

<template>
  <div class="admin-editor-backdrop" @click.self="emit('close')">
    <form class="admin-editor" @submit.prevent="emit('save')">
      <header>
        <div>
          <small>{{ editingId ? 'EDIT RECORD' : 'NEW RECORD' }}</small>
          <h2>{{ editingId ? '编辑' : '新建' }}{{ editorNoun }}</h2>
        </div>
        <div class="editor-head-actions">
          <button
            v-if="editorKind === 'post' && editingId"
            type="button"
            class="revision-trigger"
            @click="emit('open-revision')"
          >
            ↺ 历史版本
          </button>
          <button type="button" aria-label="关闭编辑器" @click="emit('close')">×</button>
        </div>
      </header>

      <template v-if="editorKind === 'post'">
        <div class="editor-card">
          <div class="card-title"><span class="badge-dot" />基本属性与分类</div>
          <label class="full-width-label">
            <span>文章标题</span>
            <input
              v-model="mutablePostForm.title"
              type="text"
              required
              maxlength="200"
              placeholder="请输入清晰、具有概括性的文章标题…"
            />
          </label>
          <div class="admin-form-grid">
            <label
              ><span>Slug（路由别名，可选）</span
              ><input
                v-model="mutablePostForm.slug"
                type="text"
                pattern="[a-z0-9]+(?:-[a-z0-9]+)*"
                placeholder="留空将根据标题自动生成"
            /></label>
            <label
              ><span>文章类别</span
              ><span class="category-select-row"
                ><select v-model="mutablePostForm.category" required>
                  <option value="" disabled>请先创建并选择类别</option>
                  <option v-for="category in categories" :key="category.id" :value="category.name">
                    {{ category.name }}
                  </option></select
                ><button type="button" @click="emit('new-category')">＋ 新建</button></span
              ></label
            >
            <label><span>发布日期</span><input v-model="mutablePostForm.date" type="date" required /></label>
            <label
              ><span>预计阅读时间 (分钟)</span
              ><input v-model.number="mutablePostForm.readTime" type="number" min="1" max="180" required
            /></label>
            <label
              ><span>文章编号</span
              ><input v-model="mutablePostForm.number" type="text" required maxlength="10" placeholder="01"
            /></label>
            <label
              ><span>发布状态</span>
              <select v-model="mutablePostForm.status" required>
                <option value="DRAFT">📝 草稿 (DRAFT)</option>
                <option value="PUBLISHED">🚀 已发布 (PUBLISHED)</option>
              </select>
            </label>
            <label
              ><span>主题色彩</span>
              <div class="color-picker-wrap">
                <input v-model="mutablePostForm.color" type="color" required />
                <span class="color-hex-val">{{ mutablePostForm.color }}</span>
              </div>
            </label>
            <label class="admin-check">
              <input v-model="mutablePostForm.featured" type="checkbox" />
              <span>设为首页精选文章</span>
            </label>
          </div>
        </div>

        <div class="editor-card">
          <div class="card-title"><span class="badge-dot" />标签与摘要</div>
          <label>
            <span>文章标签（英文逗号分隔）</span>
            <input v-model="mutablePostForm.tags" type="text" required placeholder="Vue, TypeScript, WebGL" />
          </label>
          <label>
            <span>内容摘要 (Excerpt)</span>
            <textarea
              v-model="mutablePostForm.excerpt"
              rows="3"
              required
              placeholder="简短总结本篇文章的核心洞见与主要内容…"
            />
          </label>
        </div>

        <div class="editor-card">
          <div class="card-title"><span class="badge-dot" />AI 智能创作与正文内容</div>
          <AiActionChips :get-context="currentPostContext" @apply="applyAiAction" />
          <div v-if="postMarkdownMode" class="post-markdown-field">
            <TyporaEditor
              v-model="mutablePostForm.markdownContent"
              :upload-image="rejectPostImageUpload"
              @upload-error="reportPostUploadError"
            />
          </div>
          <template v-else>
            <div class="legacy-html-notice" role="status">
              <span>该篇为存量 HTML，尚未生成 Markdown 稿。转换后即可用 Markdown 编辑器。</span>
              <button type="button" :disabled="converting" @click="convertLegacyPost">
                {{ converting ? '转换中…' : '一键转换' }}
              </button>
            </div>
            <label
              ><span>HTML 正文</span
              ><textarea
                v-model="mutablePostForm.content"
                class="admin-code-editor"
                rows="14"
                required
                spellcheck="false"
              />
            </label>
          </template>
        </div>
      </template>

      <template v-else>
        <div class="editor-card">
          <div class="card-title"><span class="badge-dot" />菜品基本属性</div>
          <div class="admin-form-grid">
            <label
              ><span>菜品名称</span><input v-model="mutableDishForm.name" required maxlength="120"
            /></label>
            <label
              ><span>菜品分类</span
              ><span class="category-select-row"
                ><select v-model="mutableDishForm.category" required>
                  <option value="" disabled>请先创建并选择分类</option>
                  <option v-for="category in dishCategories" :key="category.id" :value="category.name">
                    {{ category.name }}
                  </option></select
                ><button type="button" @click="emit('new-dish-category')">＋ 新建</button></span
              ></label
            >
            <label
              ><span>准备时间（分钟）</span
              ><input v-model.number="mutableDishForm.prepMinutes" type="number" min="1" max="1440" required
            /></label>
            <label
              ><span>难度</span>
              <select v-model="mutableDishForm.difficulty" required>
                <option value="简单">简单</option>
                <option value="家常">家常</option>
                <option value="进阶">进阶</option>
              </select>
            </label>
            <label
              ><span>评分</span
              ><input
                v-model.number="mutableDishForm.rating"
                type="number"
                min="0"
                max="5"
                step="0.1"
                required
            /></label>
            <label
              ><span>展示顺序</span
              ><input v-model.number="mutableDishForm.displayOrder" type="number" min="0" required
            /></label>
            <label
              ><span>份量基准（人份）</span
              ><input v-model.number="mutableDishForm.baseServings" type="number" min="1" max="20" required
            /></label>
            <label class="admin-check"
              ><input v-model="mutableDishForm.featured" type="checkbox" /><span>设为精选菜品</span></label
            >
            <label class="admin-check"
              ><input v-model="mutableDishForm.published" type="checkbox" /><span>公开发布</span></label
            >
          </div>
        </div>
        <div class="editor-card">
          <div class="card-title"><span class="badge-dot" />简介与媒体图示</div>
          <label
            ><span>简介</span
            ><textarea v-model="mutableDishForm.summary" rows="3" required maxlength="1000" />
          </label>
          <label>
            <span>菜品图片</span>
            <div class="dish-image-upload">
              <div v-if="dishImagePreviewUrl || mutableDishForm.imageUrl" class="dish-image-preview">
                <img :src="dishImagePreviewUrl || mutableDishForm.imageUrl" alt="菜品图片预览" />
              </div>
              <div class="dish-image-upload-actions">
                <input
                  ref="dishImageInput"
                  type="file"
                  hidden
                  accept="image/png,image/jpeg,image/webp,image/gif"
                  @change="handleDishImageChange"
                />
                <button
                  type="button"
                  class="button secondary"
                  :disabled="dishImageUploading"
                  @click="chooseDishImage"
                >
                  {{ dishImageUploading ? '正在上传…' : mutableDishForm.imageUrl ? '更换图片' : '选择图片' }}
                </button>
                <small>支持 PNG、JPG/JPEG、WebP、GIF，最大 8 MB；图片会保存到数据库。</small>
                <p v-if="dishImageError" class="admin-error" role="alert">{{ dishImageError }}</p>
              </div>
            </div>
          </label>
          <label
            ><span>图片替代文本</span><input v-model="mutableDishForm.imageAlt" required maxlength="240"
          /></label>
        </div>
        <div class="editor-card">
          <div class="card-title"><span class="badge-dot" />食材与烹饪步骤</div>
          <label
            ><span>食材清单（每行一项）</span
            ><textarea
              v-model="mutableDishForm.ingredients"
              rows="7"
              required
              placeholder="嫩豆腐 400 克&#10;牛肉末 80 克"
            />
          </label>
          <label
            ><span>制作步骤（每行一步）</span
            ><textarea
              v-model="mutableDishForm.steps"
              rows="8"
              required
              placeholder="豆腐切块并焯水。&#10;炒香肉末与豆瓣酱。"
            />
          </label>
        </div>
      </template>

      <p v-if="error" class="admin-error" role="alert">{{ error }}</p>
      <footer>
        <button class="button secondary" type="button" @click="emit('close')">取消</button
        ><button
          class="button primary"
          type="submit"
          :disabled="saving || (editorKind === 'dish' && dishImageUploading)"
        >
          {{ saving ? '正在保存…' : '保存内容 ↗' }}
        </button>
      </footer>
    </form>
  </div>
</template>
