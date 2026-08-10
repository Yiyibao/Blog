<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import axios from 'axios';
import AdminSidebar from '../components/AdminSidebar.vue';
import {
  clearAdminSession,
  hasValidAdminSession,
  fetchAdminTracks,
  createAdminTrack,
  updateAdminTrack,
  deleteAdminTrack,
  fetchAdminQuotes,
  createAdminQuote,
  updateAdminQuote,
  deleteAdminQuote,
  type AdminMusicTrack,
  type AdminQuote,
  type MusicTrackPayload,
  type QuotePayload,
} from '../api/admin';

/** 4F/L-1：曲目与语录管理——不改迁移即可增删改；写操作后端 evict 公开缓存即时生效。 */
const router = useRouter();
const tracks = ref<AdminMusicTrack[]>([]);
const quotes = ref<AdminQuote[]>([]);
const loading = ref(true);
const error = ref('');
const saving = ref(false);

const trackForm = reactive<MusicTrackPayload>({
  trackId: '',
  title: '',
  artist: '',
  duration: 0,
  audioUrl: '',
  coverUrl: '',
  sortOrder: 0,
});
const editingTrackId = ref<number | null>(null);

const quoteForm = reactive<QuotePayload>({ content: '', author: '', category: '', displayOrder: 0 });
const editingQuoteId = ref<number | null>(null);

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
    const [remoteTracks, remoteQuotes] = await Promise.all([fetchAdminTracks(), fetchAdminQuotes()]);
    tracks.value = remoteTracks;
    quotes.value = remoteQuotes;
  } catch (cause) {
    if (!handleAuthError(cause)) error.value = '读取曲目/语录失败，请确认后端服务正在运行。';
  } finally {
    loading.value = false;
  }
}

function resetTrackForm() {
  editingTrackId.value = null;
  Object.assign(trackForm, {
    trackId: '',
    title: '',
    artist: '',
    duration: 0,
    audioUrl: '',
    coverUrl: '',
    sortOrder: (tracks.value.at(-1)?.sortOrder ?? 0) + 1,
  });
}

function editTrack(track: AdminMusicTrack) {
  editingTrackId.value = track.id;
  Object.assign(trackForm, {
    trackId: track.trackId,
    title: track.title,
    artist: track.artist,
    duration: track.duration,
    audioUrl: track.audioUrl,
    coverUrl: track.coverUrl,
    sortOrder: track.sortOrder,
  });
}

async function saveTrack() {
  saving.value = true;
  error.value = '';
  try {
    if (editingTrackId.value) await updateAdminTrack(editingTrackId.value, { ...trackForm });
    else await createAdminTrack({ ...trackForm });
    resetTrackForm();
    await load();
  } catch (cause) {
    if (!handleAuthError(cause)) {
      error.value =
        axios.isAxiosError(cause) && cause.response?.status === 400
          ? '保存失败：请检查字段格式（音频地址须为 https 外链或站内路径）。'
          : '保存曲目失败，请稍后重试。';
    }
  } finally {
    saving.value = false;
  }
}

async function removeTrack(track: AdminMusicTrack) {
  if (!window.confirm(`确认删除曲目“${track.title}”？`)) return;
  try {
    await deleteAdminTrack(track.id);
    await load();
  } catch (cause) {
    if (!handleAuthError(cause)) error.value = '删除曲目失败，请稍后重试。';
  }
}

function resetQuoteForm() {
  editingQuoteId.value = null;
  Object.assign(quoteForm, {
    content: '',
    author: '',
    category: '',
    displayOrder: (quotes.value.at(-1)?.displayOrder ?? 0) + 1,
  });
}

function editQuote(quote: AdminQuote) {
  editingQuoteId.value = quote.id;
  Object.assign(quoteForm, {
    content: quote.content,
    author: quote.author,
    category: quote.category,
    displayOrder: quote.displayOrder,
  });
}

async function saveQuote() {
  saving.value = true;
  error.value = '';
  try {
    if (editingQuoteId.value) await updateAdminQuote(editingQuoteId.value, { ...quoteForm });
    else await createAdminQuote({ ...quoteForm });
    resetQuoteForm();
    await load();
  } catch (cause) {
    if (!handleAuthError(cause)) error.value = '保存语录失败，请检查必填项。';
  } finally {
    saving.value = false;
  }
}

async function removeQuote(quote: AdminQuote) {
  if (!window.confirm('确认删除这条语录？')) return;
  try {
    await deleteAdminQuote(quote.id);
    await load();
  } catch (cause) {
    if (!handleAuthError(cause)) error.value = '删除语录失败，请稍后重试。';
  }
}

const isPlaceholderUrl = (url: string) => url.includes('cdn.example.com');

onMounted(load);
</script>

<template>
  <section class="admin-console">
    <AdminSidebar />
    <main class="admin-main">
      <header class="admin-topbar">
        <div>
          <span class="admin-breadcrumb">后台管理 / 曲目与语录</span>
          <h1>曲目与语录管理</h1>
        </div>
      </header>

      <p v-if="error" class="admin-error" role="alert">{{ error }}</p>
      <p v-if="loading" role="status">正在加载…</p>

      <template v-else>
        <section class="library-block">
          <header class="block-head">
            <h2>♪ 背景音乐曲目（{{ tracks.length }}）</h2>
            <small>写入即生效：公开播放器缓存已同步失效</small>
          </header>
          <table class="library-table">
            <thead>
              <tr>
                <th>#</th>
                <th>标题</th>
                <th>艺人</th>
                <th>音频地址</th>
                <th>排序</th>
                <th />
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="track in tracks"
                :key="track.id"
                :class="{ placeholder: isPlaceholderUrl(track.audioUrl) }"
              >
                <td>{{ track.trackId }}</td>
                <td>{{ track.title }}</td>
                <td>{{ track.artist }}</td>
                <td class="url-cell">
                  {{ track.audioUrl }}
                  <b v-if="isPlaceholderUrl(track.audioUrl)" title="占位外链，请替换为真实地址">⚠ 占位</b>
                </td>
                <td>{{ track.sortOrder }}</td>
                <td class="row-actions">
                  <button type="button" @click="editTrack(track)">编辑</button>
                  <button type="button" class="danger" @click="removeTrack(track)">删除</button>
                </td>
              </tr>
            </tbody>
          </table>
          <form class="library-form" @submit.prevent="saveTrack">
            <strong>{{ editingTrackId ? '编辑曲目' : '新增曲目' }}</strong>
            <div class="form-grid">
              <label
                >标识<input
                  v-model="trackForm.trackId"
                  required
                  pattern="[a-z0-9-]+"
                  placeholder="soft-piano"
              /></label>
              <label>标题<input v-model="trackForm.title" required maxlength="200" /></label>
              <label>艺人<input v-model="trackForm.artist" required maxlength="120" /></label>
              <label
                >时长（秒）<input v-model.number="trackForm.duration" type="number" min="0" max="36000"
              /></label>
              <label class="wide"
                >音频地址<input v-model="trackForm.audioUrl" required placeholder="https://… 或 /audio/…"
              /></label>
              <label class="wide"
                >封面地址（可空）<input v-model="trackForm.coverUrl" placeholder="https://… 或 /images/…"
              /></label>
              <label
                >排序<input v-model.number="trackForm.sortOrder" type="number" min="0" max="9999"
              /></label>
            </div>
            <footer>
              <button v-if="editingTrackId" type="button" @click="resetTrackForm">取消编辑</button>
              <button class="primary" type="submit" :disabled="saving">
                {{ saving ? '保存中…' : '保存曲目' }}
              </button>
            </footer>
          </form>
        </section>

        <section class="library-block">
          <header class="block-head">
            <h2>❝ 每日语录（{{ quotes.length }}）</h2>
            <small>NB-6 按日轮转基于此有序列表，新增删除即时生效</small>
          </header>
          <table class="library-table">
            <thead>
              <tr>
                <th>语录</th>
                <th>作者</th>
                <th>分类</th>
                <th>排序</th>
                <th />
              </tr>
            </thead>
            <tbody>
              <tr v-for="quote in quotes" :key="quote.id">
                <td class="quote-cell">{{ quote.content }}</td>
                <td>{{ quote.author }}</td>
                <td>{{ quote.category }}</td>
                <td>{{ quote.displayOrder }}</td>
                <td class="row-actions">
                  <button type="button" @click="editQuote(quote)">编辑</button>
                  <button type="button" class="danger" @click="removeQuote(quote)">删除</button>
                </td>
              </tr>
            </tbody>
          </table>
          <form class="library-form" @submit.prevent="saveQuote">
            <strong>{{ editingQuoteId ? '编辑语录' : '新增语录' }}</strong>
            <div class="form-grid">
              <label class="wide"
                >语录内容<textarea v-model="quoteForm.content" required maxlength="1000" rows="2" />
              </label>
              <label>作者<input v-model="quoteForm.author" required maxlength="120" /></label>
              <label>分类<input v-model="quoteForm.category" required maxlength="80" /></label>
              <label
                >排序<input v-model.number="quoteForm.displayOrder" type="number" min="0" max="9999"
              /></label>
            </div>
            <footer>
              <button v-if="editingQuoteId" type="button" @click="resetQuoteForm">取消编辑</button>
              <button class="primary" type="submit" :disabled="saving">
                {{ saving ? '保存中…' : '保存语录' }}
              </button>
            </footer>
          </form>
        </section>
      </template>
    </main>
  </section>
</template>

<style scoped>
.library-block {
  margin-bottom: 36px;
  padding: 20px;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: var(--surface);
}
.block-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}
.block-head h2 {
  margin: 0;
  font-size: 17px;
  color: var(--ink);
}
.block-head small {
  color: var(--muted);
  font-size: 12px;
}
.library-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
  margin-bottom: 18px;
}
.library-table th,
.library-table td {
  padding: 8px 10px;
  border-bottom: 1px solid var(--line);
  text-align: left;
  color: var(--ink);
  vertical-align: top;
}
.library-table th {
  color: var(--muted);
  font-weight: 600;
  font-size: 12px;
}
.url-cell {
  max-width: 280px;
  word-break: break-all;
}
.url-cell b {
  color: #b4452c;
  font-size: 11px;
  margin-left: 6px;
}
tr.placeholder {
  background: color-mix(in srgb, #c47b2c 6%, transparent);
}
.quote-cell {
  max-width: 380px;
}
.row-actions {
  white-space: nowrap;
}
.row-actions button {
  margin-left: 6px;
  padding: 4px 10px;
  border-radius: 8px;
  border: 1px solid var(--line-strong);
  background: var(--surface-solid);
  color: var(--ink);
  font-size: 12px;
  cursor: pointer;
}
.row-actions button.danger {
  color: #b4452c;
  border-color: color-mix(in srgb, #b4452c 40%, var(--line));
}
.library-form {
  padding: 14px;
  border: 1px dashed var(--line-strong);
  border-radius: 12px;
}
.library-form strong {
  display: block;
  margin-bottom: 10px;
  font-size: 13px;
  color: var(--ink);
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 10px;
}
.form-grid label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  color: var(--muted);
}
.form-grid label.wide {
  grid-column: 1 / -1;
}
.form-grid input,
.form-grid textarea {
  padding: 8px 10px;
  border-radius: 8px;
  border: 1px solid var(--line-strong);
  background: var(--surface-solid);
  color: var(--ink);
  font-size: 13px;
}
.library-form footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 12px;
}
.library-form footer button {
  padding: 8px 16px;
  border-radius: 10px;
  border: 1px solid var(--line-strong);
  background: var(--surface-solid);
  color: var(--ink);
  font-size: 13px;
  cursor: pointer;
}
.library-form footer button.primary {
  border: none;
  background: var(--accent);
  color: #fff;
}
</style>
