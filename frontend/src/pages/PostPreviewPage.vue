<script setup lang="ts">
import axios from 'axios';
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import ControlledMarkdown from '../components/ControlledMarkdown.vue';
import { fetchPostPreview } from '../api/content';
import type { Post } from '../data';
import { sanitizeHtml } from '../utils/sanitizeHtml';

const route = useRoute();
const post = ref<Post | null>(null);
const loading = ref(true);
const error = ref('');

const safeHtml = computed(() => sanitizeHtml(post.value?.content ?? ''));
const markdown = computed(() => post.value?.markdownContent ?? '');

async function load() {
  const postId = Number(route.params.postId);
  const token = String(route.query.token ?? '');
  if (!Number.isSafeInteger(postId) || postId <= 0 || !token) {
    error.value = '预览链接无效或已过期。';
    loading.value = false;
    return;
  }
  loading.value = true;
  error.value = '';
  try {
    post.value = await fetchPostPreview(postId, token);
  } catch (cause) {
    error.value =
      axios.isAxiosError(cause) && cause.response?.status === 404
        ? '预览链接无效、已过期，或文章版本已经改变。'
        : '预览加载失败，请稍后重试。';
  } finally {
    loading.value = false;
  }
}

watch(
  () => [route.params.postId, route.query.token],
  () => void load(),
);
onMounted(load);
</script>

<template>
  <main class="preview-page">
    <div class="preview-banner">只读预览 · 链接将在短时间后失效，保存或发布仍需回到后台显式操作。</div>
    <p v-if="loading" role="status">正在加载预览…</p>
    <p v-else-if="error" class="preview-error" role="alert">{{ error }}</p>
    <article v-else-if="post">
      <p class="preview-meta">{{ post.category }} · {{ post.date }} · {{ post.readTime }} MIN READ</p>
      <h1>{{ post.title }}</h1>
      <p class="preview-excerpt">{{ post.excerpt }}</p>
      <div v-if="post.contentFormat === 'MARKDOWN'" class="preview-body">
        <ControlledMarkdown :markdown="markdown" />
      </div>
      <div v-else class="preview-body" v-html="safeHtml" />
    </article>
  </main>
</template>

<style scoped>
.preview-page {
  max-width: 820px;
  margin: 0 auto;
  padding: 24px 20px 72px;
  color: var(--ink);
}
.preview-banner {
  margin-bottom: 32px;
  padding: 10px 14px;
  border: 1px solid color-mix(in srgb, #a6784c 35%, var(--line));
  border-radius: 10px;
  color: var(--muted);
  font-size: 13px;
}
.preview-meta {
  color: var(--muted);
  font-size: 12px;
  letter-spacing: 0.06em;
}
h1 {
  margin: 12px 0;
  font-size: clamp(32px, 6vw, 54px);
  line-height: 1.1;
}
.preview-excerpt {
  color: var(--muted);
  font-size: 18px;
  line-height: 1.7;
}
.preview-body {
  margin-top: 32px;
  line-height: 1.9;
}
.preview-error {
  color: #b4452c;
}
</style>
