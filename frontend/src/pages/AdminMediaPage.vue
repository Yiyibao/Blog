<script setup lang="ts">
import axios from 'axios';
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import AdminSidebar from '../components/AdminSidebar.vue';
import {
  clearAdminSession,
  fetchMediaLibrary,
  hasValidAdminSession,
  type MediaLibraryItem,
} from '../api/admin';

const router = useRouter();
const items = ref<MediaLibraryItem[]>([]);
const loading = ref(true);
const error = ref('');
const sourceFilter = ref('');
const statusFilter = ref('');

const totalBytes = computed(() => items.value.reduce((total, item) => total + item.byteSize, 0));
const referenced = computed(() => items.value.filter((item) => item.referenceCount > 0).length);

function handleAuthError(cause: unknown) {
  if (axios.isAxiosError(cause) && cause.response?.status === 401) {
    clearAdminSession();
    void router.replace('/admin/login');
    return true;
  }
  return false;
}

async function load() {
  if (!hasValidAdminSession()) {
    void router.replace('/admin/login');
    return;
  }
  loading.value = true;
  error.value = '';
  try {
    items.value = await fetchMediaLibrary(sourceFilter.value || undefined, statusFilter.value || undefined);
  } catch (cause) {
    if (!handleAuthError(cause)) error.value = '媒体库加载失败，请确认后端服务正在运行。';
  } finally {
    loading.value = false;
  }
}

function formatBytes(bytes: number) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

onMounted(load);
</script>

<template>
  <section class="admin-console">
    <AdminSidebar />
    <main class="admin-main">
      <header class="admin-topbar">
        <div>
          <span class="admin-breadcrumb">后台管理 / 媒体库</span>
          <h1>统一媒体库</h1>
        </div>
        <div class="media-filters">
          <select v-model="sourceFilter" aria-label="媒体来源" @change="load">
            <option value="">全部来源</option>
            <option value="NOTE_ATTACHMENT">笔记附件</option>
            <option value="DISH_ASSET">菜品图片</option>
            <option value="AI_GENERATED_IMAGE">AI 图片</option>
            <option value="AI_ARTIFACT">AI 产物</option>
          </select>
          <select v-model="statusFilter" aria-label="媒体状态" @change="load">
            <option value="">全部状态</option>
            <option value="ACTIVE">可用</option>
            <option value="TRASHED">回收站</option>
            <option value="EXPIRED">已过期</option>
            <option value="READY">就绪</option>
          </select>
        </div>
      </header>

      <p v-if="error" class="admin-error" role="alert">{{ error }}</p>
      <p v-if="loading" role="status">正在加载媒体库…</p>
      <template v-else>
        <div class="media-summary">
          <div>
            <small>资源数</small><strong>{{ items.length }}</strong>
          </div>
          <div>
            <small>总容量</small><strong>{{ formatBytes(totalBytes) }}</strong>
          </div>
          <div>
            <small>已引用</small><strong>{{ referenced }}</strong>
          </div>
        </div>
        <p v-if="!items.length" class="admin-empty">暂无符合条件的媒体资源。</p>
        <div v-else class="media-table-wrap">
          <table class="media-table">
            <thead>
              <tr>
                <th>资源</th>
                <th>来源</th>
                <th>所有者</th>
                <th>大小</th>
                <th>引用</th>
                <th>状态</th>
                <th>创建时间</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in items" :key="`${item.sourceType}:${item.sourceId}`">
                <td class="media-name">
                  <a :href="item.url" target="_blank" rel="noopener">{{ item.fileName }}</a>
                  <small>{{ item.mediaType }} · {{ item.sha256 || 'legacy hash pending' }}</small>
                </td>
                <td>{{ item.sourceType }}</td>
                <td>{{ item.owner }}</td>
                <td>{{ formatBytes(item.byteSize) }}</td>
                <td>{{ item.referenceCount }}</td>
                <td>
                  <span class="status-badge">{{ item.status }}</span>
                </td>
                <td>{{ item.createdAt.replace('T', ' ').slice(0, 16) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>
    </main>
  </section>
</template>

<style scoped>
.media-filters {
  display: flex;
  gap: 8px;
}
.media-filters select {
  min-width: 120px;
  padding: 8px 10px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--surface);
  color: var(--ink);
}
.media-summary {
  display: flex;
  gap: 12px;
  margin: 10px 0 18px;
  flex-wrap: wrap;
}
.media-summary > div {
  min-width: 120px;
  padding: 12px 16px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--surface);
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.media-summary small {
  color: var(--muted);
  font-size: 11px;
}
.media-summary strong {
  font-size: 19px;
}
.media-table-wrap {
  overflow-x: auto;
}
.media-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}
.media-table th,
.media-table td {
  padding: 9px 10px;
  border-bottom: 1px solid var(--line);
  text-align: left;
  vertical-align: top;
}
.media-table th {
  color: var(--muted);
  font-size: 11px;
}
.media-name {
  min-width: 220px;
}
.media-name a {
  color: var(--ink);
  text-decoration: underline dotted;
}
.media-name small {
  display: block;
  margin-top: 4px;
  color: var(--muted);
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.status-badge {
  padding: 3px 7px;
  border-radius: 7px;
  background: color-mix(in srgb, #2f7d4f 12%, var(--surface));
  color: #25603e;
}
@media (max-width: 720px) {
  .media-filters {
    width: 100%;
  }
  .media-filters select {
    flex: 1;
  }
}
</style>
