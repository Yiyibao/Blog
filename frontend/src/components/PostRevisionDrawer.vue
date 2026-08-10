<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import {
  fetchPostRevisions,
  fetchPostRevision,
  restorePostRevision,
  type AdminPost,
  type PostRevisionDetail,
  type PostRevisionSummary,
} from '../api/admin';
import { diffLines } from '../utils/textDiff';

/**
 * 4C：版本历史抽屉——列表（新到旧）→ 查看某版 → 与当前编辑内容纯文本行 diff → 恢复。
 * 恢复走后端（回写正文并产生新版本），父组件经 restored 事件拿到最新文章回填表单。
 */
const props = defineProps<{
  postId: number;
  /** 当前编辑器里的正文（diff 的「新」侧）。 */
  currentText: string;
}>();

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'restored', post: AdminPost): void;
}>();

const revisions = ref<PostRevisionSummary[]>([]);
const selected = ref<PostRevisionDetail | null>(null);
const loading = ref(true);
const detailLoading = ref(false);
const restoring = ref(false);
const error = ref('');

const diff = computed(() => {
  if (!selected.value) return [];
  const oldText = selected.value.markdownContent ?? selected.value.content;
  return diffLines(oldText, props.currentText);
});

const diffStats = computed(() => {
  let add = 0;
  let del = 0;
  for (const line of diff.value) {
    if (line.type === 'add') add++;
    else if (line.type === 'del') del++;
  }
  return { add, del };
});

function formatTime(iso: string): string {
  return iso.replace('T', ' ').slice(0, 19);
}

async function load() {
  loading.value = true;
  error.value = '';
  try {
    revisions.value = await fetchPostRevisions(props.postId);
  } catch {
    error.value = '读取版本历史失败。';
  } finally {
    loading.value = false;
  }
}

async function view(revision: PostRevisionSummary) {
  detailLoading.value = true;
  error.value = '';
  try {
    selected.value = await fetchPostRevision(props.postId, revision.id);
  } catch {
    error.value = '读取版本内容失败。';
  } finally {
    detailLoading.value = false;
  }
}

async function restore() {
  if (!selected.value) return;
  if (!window.confirm('恢复该版本？当前编辑器内容将被替换（恢复本身也会记录一版）。')) return;
  restoring.value = true;
  error.value = '';
  try {
    const post = await restorePostRevision(props.postId, selected.value.id);
    emit('restored', post);
    await load();
    selected.value = null;
  } catch {
    error.value = '恢复失败，请稍后重试。';
  } finally {
    restoring.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div class="revision-overlay" role="dialog" aria-label="版本历史" @click.self="emit('close')">
    <aside class="revision-drawer">
      <header>
        <strong>版本历史</strong>
        <small>保存即快照，保留最近 10 版</small>
        <button type="button" class="close-btn" aria-label="关闭" @click="emit('close')">✕</button>
      </header>

      <p v-if="error" class="drawer-error" role="alert">{{ error }}</p>
      <p v-if="loading" role="status">正在加载…</p>
      <p v-else-if="!revisions.length" class="drawer-empty">还没有版本记录，保存一次即产生。</p>

      <div v-else class="drawer-body">
        <ul class="revision-list">
          <li v-for="revision in revisions" :key="revision.id">
            <button type="button" :class="{ active: selected?.id === revision.id }" @click="view(revision)">
              <strong>{{ revision.title }}</strong>
              <small>{{ formatTime(revision.createdAt) }} · {{ revision.contentFormat }}</small>
            </button>
          </li>
        </ul>

        <section class="revision-detail">
          <p v-if="detailLoading" role="status">正在加载版本…</p>
          <template v-else-if="selected">
            <header class="detail-head">
              <div>
                <strong>{{ selected.title }}</strong>
                <small
                  >对比当前编辑内容：<b class="add">+{{ diffStats.add }}</b> /
                  <b class="del">-{{ diffStats.del }}</b> 行</small
                >
              </div>
              <button type="button" class="restore-btn" :disabled="restoring" @click="restore">
                {{ restoring ? '恢复中…' : '恢复此版本' }}
              </button>
            </header>
            <pre class="diff-view"><code><span
              v-for="(line, index) in diff"
              :key="index"
              class="diff-line"
              :class="line.type"
            >{{ line.type === 'add' ? '+ ' : line.type === 'del' ? '- ' : '  ' }}{{ line.text }}
</span></code></pre>
          </template>
          <p v-else class="drawer-empty">从左侧选择一个版本查看差异。</p>
        </section>
      </div>
    </aside>
  </div>
</template>

<style scoped>
.revision-overlay {
  position: fixed;
  inset: 0;
  z-index: 90;
  background: color-mix(in srgb, #000 40%, transparent);
  display: flex;
  justify-content: flex-end;
}
.revision-drawer {
  width: min(760px, 94vw);
  height: 100%;
  background: var(--surface-solid);
  border-left: 1px solid var(--line-strong);
  display: flex;
  flex-direction: column;
  padding: 18px 20px;
}
.revision-drawer > header {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 14px;
}
.revision-drawer > header strong {
  font-size: 16px;
  color: var(--ink);
}
.revision-drawer > header small {
  color: var(--muted);
  font-size: 12px;
  flex: 1;
}
.close-btn {
  border: 1px solid var(--line-strong);
  background: var(--surface);
  color: var(--ink);
  border-radius: 8px;
  padding: 4px 10px;
  cursor: pointer;
}
.drawer-error {
  color: #b4452c;
  font-size: 13px;
}
.drawer-empty {
  color: var(--muted);
  font-size: 13px;
}
.drawer-body {
  display: grid;
  grid-template-columns: 220px 1fr;
  gap: 14px;
  flex: 1;
  min-height: 0;
}
.revision-list {
  list-style: none;
  margin: 0;
  padding: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.revision-list button {
  width: 100%;
  text-align: left;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--surface);
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.revision-list button.active {
  border-color: var(--accent);
}
.revision-list strong {
  font-size: 13px;
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.revision-list small {
  color: var(--muted);
  font-size: 11px;
}
.revision-detail {
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.detail-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}
.detail-head strong {
  display: block;
  font-size: 14px;
  color: var(--ink);
}
.detail-head small {
  color: var(--muted);
  font-size: 12px;
}
.detail-head .add {
  color: #2f7d4f;
  font-style: normal;
}
.detail-head .del {
  color: #b4452c;
  font-style: normal;
}
.restore-btn {
  border: none;
  background: var(--accent);
  color: #fff;
  border-radius: 10px;
  padding: 8px 14px;
  font-size: 13px;
  cursor: pointer;
  white-space: nowrap;
}
.diff-view {
  flex: 1;
  margin: 0;
  overflow: auto;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--surface);
  font-size: 12px;
  line-height: 1.6;
}
.diff-view code {
  display: block;
  padding: 10px 0;
}
.diff-line {
  display: block;
  padding: 0 12px;
  white-space: pre-wrap;
  word-break: break-all;
  color: var(--ink);
}
.diff-line.add {
  background: color-mix(in srgb, #2f7d4f 12%, transparent);
}
.diff-line.del {
  background: color-mix(in srgb, #b4452c 12%, transparent);
}
@media (max-width: 700px) {
  .drawer-body {
    grid-template-columns: 1fr;
  }
  .revision-list {
    max-height: 30vh;
  }
}
</style>
