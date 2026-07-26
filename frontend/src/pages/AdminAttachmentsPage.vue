<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import axios from 'axios'
import AdminSidebar from '../components/AdminSidebar.vue'
import {
  clearAdminSession, hasValidAdminSession,
  fetchAttachmentOverview, deleteNoteAttachment,
  type AttachmentOverview, type AttachmentOverviewItem,
} from '../api/admin'

/** 4E：附件管理——全站总览、孤儿标记（正文不再引用且超 7 天）、逐个清理。 */
const router = useRouter()
const overview = ref<AttachmentOverview | null>(null)
const loading = ref(true)
const error = ref('')
const onlyOrphans = ref(false)

const items = computed(() => {
  const all = overview.value?.items ?? []
  return onlyOrphans.value ? all.filter((item) => item.orphan) : all
})

function handleAuthError(cause: unknown) {
  if (axios.isAxiosError(cause) && cause.response?.status === 401) {
    clearAdminSession()
    void router.replace('/admin/login')
    return true
  }
  return false
}

async function load() {
  if (!hasValidAdminSession()) {
    void router.replace('/admin/login')
    return
  }
  loading.value = true
  error.value = ''
  try {
    overview.value = await fetchAttachmentOverview()
  } catch (cause) {
    if (!handleAuthError(cause)) error.value = '读取附件总览失败，请确认后端服务正在运行。'
  } finally {
    loading.value = false
  }
}

async function remove(item: AttachmentOverviewItem) {
  if (!window.confirm(`确认删除附件“${item.fileName}”？此操作无法撤销。`)) return
  try {
    await deleteNoteAttachment(item.noteId, item.id)
    await load()
  } catch (cause) {
    if (!handleAuthError(cause)) error.value = '删除附件失败，请稍后重试。'
  }
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

function formatTime(iso: string): string {
  return iso.replace('T', ' ').slice(0, 16)
}

onMounted(load)
</script>

<template>
  <section class="admin-console">
    <AdminSidebar />
    <main class="admin-main">
      <header class="admin-topbar">
        <div>
          <span class="admin-breadcrumb">后台管理 / 附件</span>
          <h1>附件管理</h1>
        </div>
      </header>

      <p v-if="error" class="admin-error" role="alert">{{ error }}</p>
      <p v-if="loading" role="status">正在加载…</p>

      <template v-else-if="overview">
        <div class="attachment-summary">
          <div class="summary-card"><small>附件总数</small><strong>{{ overview.count }}</strong></div>
          <div class="summary-card"><small>总容量</small><strong>{{ formatBytes(overview.totalBytes) }}</strong></div>
          <div class="summary-card" :class="{ warn: overview.orphanCount > 0 }">
            <small>疑似孤儿</small><strong>{{ overview.orphanCount }}</strong>
          </div>
          <label class="orphan-filter">
            <input v-model="onlyOrphans" type="checkbox">
            只看孤儿（正文不再引用且超 7 天）
          </label>
        </div>

        <p v-if="!items.length" class="attachment-empty">
          {{ onlyOrphans ? '没有疑似孤儿附件，很干净。' : '还没有任何附件。' }}
        </p>
        <table v-else class="attachment-table">
          <thead>
            <tr><th>文件名</th><th>所属笔记</th><th>类型</th><th>大小</th><th>上传时间</th><th>状态</th><th /></tr>
          </thead>
          <tbody>
            <tr v-for="item in items" :key="item.id" :class="{ orphan: item.orphan }">
              <td class="file-cell">
                <a :href="item.url" target="_blank" rel="noopener">{{ item.fileName }}</a>
              </td>
              <td>{{ item.noteTitle }}</td>
              <td>{{ item.mediaType }}</td>
              <td>{{ formatBytes(item.byteSize) }}</td>
              <td>{{ formatTime(item.createdAt) }}</td>
              <td>
                <span v-if="item.orphan" class="orphan-badge" title="所属笔记正文不再引用且创建超 7 天">孤儿</span>
                <span v-else class="ok-badge">引用中</span>
              </td>
              <td class="row-actions">
                <button type="button" class="danger" @click="remove(item)">删除</button>
              </td>
            </tr>
          </tbody>
        </table>
      </template>
    </main>
  </section>
</template>

<style scoped>
.attachment-summary {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 18px;
  flex-wrap: wrap;
}
.summary-card {
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--surface);
  padding: 10px 16px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.summary-card small { color: var(--muted); font-size: 11px; }
.summary-card strong { color: var(--ink); font-size: 18px; }
.summary-card.warn strong { color: #b4452c; }
.orphan-filter {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--muted);
  font-size: 13px;
  cursor: pointer;
}
.attachment-empty { color: var(--muted); font-size: 13px; }
.attachment-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.attachment-table th, .attachment-table td {
  padding: 8px 10px;
  border-bottom: 1px solid var(--line);
  text-align: left;
  color: var(--ink);
  vertical-align: middle;
}
.attachment-table th { color: var(--muted); font-weight: 600; font-size: 12px; }
tr.orphan { background: color-mix(in srgb, #b4452c 5%, transparent); }
.file-cell a { color: var(--ink); text-decoration: underline dotted; }
.orphan-badge {
  color: #b4452c;
  border: 1px solid color-mix(in srgb, #b4452c 40%, var(--line));
  border-radius: 8px;
  padding: 2px 8px;
  font-size: 11px;
}
.ok-badge { color: #2f7d4f; font-size: 11px; }
.row-actions button {
  padding: 4px 10px;
  border-radius: 8px;
  border: 1px solid var(--line-strong);
  background: var(--surface-solid);
  color: #b4452c;
  font-size: 12px;
  cursor: pointer;
}
</style>
