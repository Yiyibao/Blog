<script setup lang="ts">
import axios from 'axios';
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { onBeforeRouteLeave, useRouter } from 'vue-router';
import TyporaEditor from './TyporaEditor.vue';
import AiActionChips, { type AiActionKind } from './AiActionChips.vue';
import PaginationNav from './PaginationNav.vue';
import {
  archiveNote,
  clearAdminSession,
  createNote,
  deleteNote,
  deleteNoteAttachment,
  exportNote,
  fetchAdminNote,
  fetchNoteAttachments,
  fetchNotes,
  fetchNoteAttachmentContent,
  hasValidAdminSession,
  importNote,
  publishNote,
  unpublishNote,
  updateNote,
  uploadNoteAttachment,
  type AdminNote,
  type AdminNoteSummary,
  type NoteAttachment,
  type NotePayload,
  type NoteStatus,
} from '../api/admin';
import {
  buildPayload,
  clearPreviewUrls,
  replacePreviewUrls,
  replaceCanonicalUrls,
} from '../utils/noteHelpers';

const router = useRouter();
// P1-2：列表为摘要 DTO（不含 markdownContent）；保存/新建/导入返回的全量笔记也会写回此列表
const notes = ref<AdminNoteSummary[]>([]);
const selectedId = ref<number | null>(null);
const query = ref('');
const statusFilter = ref<'ALL' | NoteStatus>('ALL');
const notePage = ref(0);
const notePageSize = 20;
const noteTotal = ref(0);
const noteTotalPages = ref(1);
const loading = ref(true);
const saveState = ref<'saved' | 'dirty' | 'saving' | 'error'>('saved');
const publishing = ref(false);
const publicationNotice = ref('');
const error = ref('');
const focusMode = ref(false);
const fileInput = ref<HTMLInputElement>();
const form = reactive<NotePayload>({
  title: '',
  markdownContent: '',
  folder: '未分类',
  status: 'DRAFT',
  tags: [],
  version: 0,
});
const tagText = ref('');

/** 4A-5：AI 动作结果回填（笔记场景：总结当引言插入、标题/标签直填、润色/续写改正文）。 */
function applyAiAction(action: AiActionKind, text: string) {
  if (action === 'summary') {
    form.markdownContent = `> ${text.replace(/\n+/g, ' ')}\n\n${form.markdownContent}`;
  } else if (action === 'title') {
    const first = text
      .split('\n')
      .map((line) => line.replace(/^\s*(?:[-*]|\d+[.、])\s*/, '').trim())
      .find(Boolean);
    if (first) form.title = first;
  } else if (action === 'tags') {
    tagText.value = text
      .split(/[,，、\n]/)
      .map((t) => t.trim())
      .filter(Boolean)
      .slice(0, 6)
      .join(', ');
  } else if (action === 'polish') {
    form.markdownContent = text;
  } else if (action === 'continue') {
    form.markdownContent = `${form.markdownContent.trimEnd()}\n\n${text}`;
  }
}
const attachments = ref<NoteAttachment[]>([]);
const attachmentPreviewUrls = ref<Record<number, string>>({});
const openIds = ref<number[]>([]);
let saveTimer: number | undefined;
let noticeTimer: number | undefined;
let applying = false;
let editRevision = 0;
let savedRevision = 0;
let savePromise: Promise<AdminNoteSummary | null> | null = null;
let publicationChanging = false;
let deleting = false;
let loadRevision = 0;
let attachmentLoadRevision = 0;
let editorSession = 0;
let pendingUploads = 0;

const filteredNotes = computed(() => {
  const needle = query.value.trim().toLowerCase();
  return notes.value.filter(
    (note) =>
      (statusFilter.value === 'ALL' || note.status === statusFilter.value) &&
      (!needle || [note.title, note.folder, ...note.tags].join(' ').toLowerCase().includes(needle)),
  );
});
const selected = computed(() => notes.value.find((note) => note.id === selectedId.value));
const outline = computed(() =>
  form.markdownContent.split('\n').flatMap((line, index) => {
    const match = /^(#{1,3})\s+(.+)$/.exec(line);
    return match ? [{ level: match[1].length, title: match[2].replace(/[*_`]/g, ''), index }] : [];
  }),
);
const charCount = computed(() => form.markdownContent.replace(/\s/g, '').length);
const readMinutes = computed(() => Math.max(1, Math.ceil(charCount.value / 500)));
const saveLabel = computed(
  () => ({ saved: '已保存', dirty: '等待保存', saving: '正在保存…', error: '保存失败' })[saveState.value],
);
const statusLabel = computed(() => ({ DRAFT: '草稿', PUBLISHED: '公开', ARCHIVED: '归档' })[form.status]);

function payload(): NotePayload {
  return buildPayload(form, attachments.value, attachmentPreviewUrls.value, tagText.value);
}

function clearAttachmentPreviews() {
  attachmentPreviewUrls.value = clearPreviewUrls(attachmentPreviewUrls.value);
}

function applyNote(note: AdminNote) {
  clearTimeout(saveTimer);
  clearAttachmentPreviews();
  editorSession += 1;
  applying = true;
  selectedId.value = note.id;
  Object.assign(form, {
    title: note.title,
    markdownContent: note.markdownContent,
    folder: note.folder,
    status: note.status,
    tags: note.tags,
    version: note.version,
  });
  tagText.value = note.tags.join(', ');
  editRevision = 0;
  savedRevision = 0;
  saveState.value = 'saved';
  if (!openIds.value.includes(note.id)) openIds.value.push(note.id);
  void loadAttachments(note.id);
  void nextTick(() => {
    applying = false;
  });
}

/**
 * P1-2：applyNote 需要全量笔记（含 markdownContent）。列表摘要项先经
 * 详情接口补齐正文；已携带正文的对象（保存/新建/导入的返回值）直接使用。
 * 拉取失败时返回 null，调用方不得切换选中笔记，避免空正文进入表单被自动保存。
 */
async function resolveFullNote(note: AdminNoteSummary): Promise<AdminNote | null> {
  if (typeof (note as Partial<AdminNote>).markdownContent === 'string') return note as AdminNote;
  try {
    return await fetchAdminNote(note.id);
  } catch (cause) {
    if (axios.isAxiosError(cause) && cause.response?.status === 401) {
      clearAdminSession();
      void router.replace('/admin/login');
      return null;
    }
    // 只提示不动 saveState：当前笔记并无未保存内容，误标「保存失败」会触发无谓的离开拦截
    error.value = '读取笔记正文失败，请稍后重试。';
    return null;
  }
}

async function selectNote(note: AdminNoteSummary) {
  if (deleting || pendingUploads > 0 || note.id === selectedId.value) return;
  if (!(await flushCurrent())) return;
  const full = await resolveFullNote(note);
  if (!full) return;
  // 详情拉取窗口期内用户可能继续键入当前笔记，applyNote 前再 flush 一次，防止静默丢弃
  if (!(await flushCurrent())) return;
  applyNote(full);
}

async function flushCurrent() {
  if (!selectedId.value || (saveState.value === 'saved' && !savePromise && savedRevision === editRevision))
    return true;
  return Boolean(await saveNow());
}

async function loadAttachments(noteId: number) {
  const requestRevision = ++attachmentLoadRevision;
  try {
    const loaded = await fetchNoteAttachments(noteId);
    if (selectedId.value !== noteId || requestRevision !== attachmentLoadRevision) return;
    attachments.value = loaded;
    const previews = await Promise.all(
      loaded.map(async (attachment) => {
        const blob = await fetchNoteAttachmentContent(noteId, attachment.id);
        return [attachment.id, URL.createObjectURL(blob)] as const;
      }),
    );
    if (selectedId.value !== noteId || requestRevision !== attachmentLoadRevision) {
      previews.forEach(([, url]) => URL.revokeObjectURL(url));
      return;
    }
    clearAttachmentPreviews();
    attachmentPreviewUrls.value = Object.fromEntries(previews);
    applying = true;
    form.markdownContent = replacePreviewUrls(form.markdownContent, loaded, attachmentPreviewUrls.value);
    await nextTick();
    applying = false;
  } catch {
    if (selectedId.value === noteId && requestRevision === attachmentLoadRevision) attachments.value = [];
  }
}

async function uploadEditorImage(file: File) {
  if (!selectedId.value) throw new Error('No active note');
  const noteId = selectedId.value;
  const session = editorSession;
  attachmentLoadRevision += 1;
  pendingUploads += 1;
  try {
    const attachment = await uploadNoteAttachment(noteId, file);
    const blob = await fetchNoteAttachmentContent(noteId, attachment.id);
    const previewUrl = URL.createObjectURL(blob);
    if (selectedId.value !== noteId || editorSession !== session) {
      URL.revokeObjectURL(previewUrl);
      throw new Error('The active note changed during upload');
    }
    attachments.value.unshift(attachment);
    attachmentPreviewUrls.value = { ...attachmentPreviewUrls.value, [attachment.id]: previewUrl };
    return previewUrl;
  } finally {
    pendingUploads -= 1;
  }
}

async function removeAttachment(attachment: NoteAttachment) {
  if (
    !selectedId.value ||
    pendingUploads > 0 ||
    !window.confirm(`确认删除图片“${attachment.fileName}”？笔记中已插入的引用将失效。`)
  )
    return;
  const noteId = selectedId.value;
  attachmentLoadRevision += 1;
  try {
    await deleteNoteAttachment(noteId, attachment.id);
    if (selectedId.value === noteId) {
      const previewUrl = attachmentPreviewUrls.value[attachment.id];
      if (previewUrl) {
        applying = true;
        form.markdownContent = replaceCanonicalUrls(form.markdownContent, [attachment], {
          [attachment.id]: previewUrl,
        });
        URL.revokeObjectURL(previewUrl);
      }
      const { [attachment.id]: _removed, ...remainingPreviews } = attachmentPreviewUrls.value;
      attachmentPreviewUrls.value = remainingPreviews;
      attachments.value = attachments.value.filter((item) => item.id !== attachment.id);
      await nextTick();
      applying = false;
    }
  } catch (cause) {
    handleError(cause, '删除图片失败。');
  }
}

async function copyAttachment(attachment: NoteAttachment) {
  await navigator.clipboard.writeText(`![${attachment.fileName}](${attachment.url})`);
}

async function closeTab(id: number) {
  if (deleting || pendingUploads > 0) return;
  const index = openIds.value.indexOf(id);
  if (selectedId.value === id && !(await flushCurrent())) return;
  openIds.value = openIds.value.filter((item) => item !== id);
  if (selectedId.value !== id) return;
  const nextId = openIds.value[Math.max(0, index - 1)];
  const next = notes.value.find((note) => note.id === nextId);
  const full = next ? await resolveFullNote(next) : null;
  if (full && !(await flushCurrent())) return;
  if (full) applyNote(full);
  else {
    editorSession += 1;
    selectedId.value = null;
    clearAttachmentPreviews();
  }
}

async function load() {
  if (deleting || pendingUploads > 0) return;
  const requestRevision = ++loadRevision;
  if (!hasValidAdminSession()) {
    clearAdminSession();
    return void router.replace('/admin/login');
  }
  if (!(await flushCurrent())) return;
  try {
    const result = await fetchNotes(
      notePage.value,
      notePageSize,
      statusFilter.value === 'ALL' ? '' : statusFilter.value,
    );
    if (requestRevision !== loadRevision) return;
    if (notePage.value > 0 && notePage.value >= result.totalPages) {
      notePage.value = Math.max(0, result.totalPages - 1);
      return load();
    }
    if (!(await flushCurrent())) return;
    if (requestRevision !== loadRevision) return;
    notes.value = result.items;
    noteTotal.value = result.totalElements;
    noteTotalPages.value = Math.max(1, result.totalPages);
    const first = notes.value[0];
    if (first) {
      const full = await resolveFullNote(first);
      if (requestRevision !== loadRevision) return;
      if (full) applyNote(full);
      else selectedId.value = null;
    } else {
      selectedId.value = null;
    }
  } catch (cause) {
    if (requestRevision === loadRevision) handleError(cause, '无法读取学习笔记，请确认后端服务正在运行。');
  } finally {
    if (requestRevision === loadRevision) loading.value = false;
  }
}

function changePage(page: number) {
  notePage.value = page;
  void load();
}

function handleError(cause: unknown, fallback: string) {
  if (axios.isAxiosError(cause) && cause.response?.status === 401) {
    clearAdminSession();
    return void router.replace('/admin/login');
  }
  error.value =
    axios.isAxiosError(cause) && cause.response?.status === 409
      ? '云端版本已变化，请重新选择笔记后继续编辑。'
      : fallback;
  saveState.value = 'error';
}

function jumpToOutline(title: string) {
  const root = document.querySelector('.typora-prose');
  if (!root) return;
  const heading = [...root.querySelectorAll('h1, h2, h3')].find((node) => node.textContent?.trim() === title);
  heading?.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

async function newNote() {
  if (deleting || pendingUploads > 0) return;
  if (!(await flushCurrent())) return;
  try {
    const note = await createNote({
      title: '未命名笔记',
      markdownContent: '# 未命名笔记\n\n从这里开始记录。',
      folder: '未分类',
      status: 'DRAFT',
      tags: [],
      version: 0,
    });
    notes.value.unshift(note);
    notes.value = notes.value.slice(0, notePageSize);
    noteTotal.value += 1;
    if (await flushCurrent()) applyNote(note);
  } catch (cause) {
    handleError(cause, '新建笔记失败。');
  }
}

async function syncSavedNote(saved: AdminNote, noteId = saved.id) {
  applying = true;
  const index = notes.value.findIndex((note) => note.id === saved.id);
  if (index >= 0) notes.value[index] = saved;
  if (selectedId.value === noteId) {
    form.version = saved.version;
    form.status = saved.status;
  }
  await nextTick();
  applying = false;
}

async function saveNow(): Promise<AdminNoteSummary | null> {
  clearTimeout(saveTimer);
  if (!selectedId.value) return null;
  if (publicationChanging) {
    saveState.value = 'dirty';
    saveTimer = window.setTimeout(() => {
      void saveNow();
    }, 250);
    return null;
  }
  if (savePromise) return savePromise;

  const noteId = selectedId.value;
  savePromise = (async () => {
    let lastSaved = notes.value.find((note) => note.id === noteId) ?? null;
    while (selectedId.value === noteId && savedRevision < editRevision) {
      const targetRevision = editRevision;
      const request = payload();
      saveState.value = 'saving';
      error.value = '';
      try {
        const saved = await updateNote(noteId, request);
        await syncSavedNote(saved, noteId);
        savedRevision = targetRevision;
        lastSaved = saved;
      } catch (cause) {
        handleError(cause, '保存失败，请稍后重试。');
        return null;
      }
    }
    if (selectedId.value === noteId) {
      saveState.value = savedRevision === editRevision ? 'saved' : 'dirty';
    }
    return lastSaved;
  })();

  try {
    return await savePromise;
  } finally {
    savePromise = null;
    if (
      selectedId.value === noteId &&
      savedRevision < editRevision &&
      saveState.value !== 'error' &&
      !publicationChanging
    ) {
      void saveNow();
    }
  }
}

async function changePublication(nextStatus: NoteStatus) {
  if (deleting || pendingUploads > 0 || !selectedId.value || publishing.value) return;
  const noteId = selectedId.value;
  publishing.value = true;
  publicationNotice.value = '';
  clearTimeout(saveTimer);
  clearTimeout(noticeTimer);
  try {
    const saved = await saveNow();
    if (!saved || saved.id !== noteId) return;
    publicationChanging = true;
    const changed =
      nextStatus === 'PUBLISHED'
        ? await publishNote(saved.id, saved.version)
        : nextStatus === 'ARCHIVED'
          ? await archiveNote(saved.id, saved.version)
          : await unpublishNote(saved.id, saved.version);
    await syncSavedNote(changed, noteId);
    saveState.value = 'saved';
    publicationNotice.value =
      nextStatus === 'PUBLISHED'
        ? '笔记已发布，公开页面现在可以阅读。'
        : nextStatus === 'ARCHIVED'
          ? '笔记已归档，仅后台可见。'
          : '笔记已恢复为草稿，仅后台可见。';
    noticeTimer = window.setTimeout(() => {
      publicationNotice.value = '';
    }, 3200);
  } catch (cause) {
    handleError(cause, nextStatus === 'PUBLISHED' ? '发布失败，请稍后重试。' : '状态变更失败，请稍后重试。');
  } finally {
    publicationChanging = false;
    publishing.value = false;
    if (selectedId.value === noteId && savedRevision < editRevision && saveState.value !== 'error')
      void saveNow();
  }
}

function scheduleSave() {
  if (applying || !selectedId.value) return;
  editRevision += 1;
  saveState.value = 'dirty';
  clearTimeout(saveTimer);
  if (deleting) return;
  saveTimer = window.setTimeout(saveNow, 1000);
}

async function onImport(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file || deleting || pendingUploads > 0) return;
  if (!(await flushCurrent())) {
    input.value = '';
    return;
  }
  try {
    const note = await importNote(file);
    notes.value.unshift(note);
    if (await flushCurrent()) applyNote(note);
  } catch (cause) {
    handleError(cause, '导入失败，仅支持 2MB 内的 Markdown 或文本文件。');
  } finally {
    input.value = '';
  }
}

async function removeCurrent() {
  if (!selected.value || pendingUploads > 0 || !window.confirm(`确认删除“${selected.value.title}”？`)) return;
  const removedId = selected.value.id;
  deleting = true;
  try {
    clearTimeout(saveTimer);
    if (savePromise) await savePromise;
    await deleteNote(removedId);
    notes.value = notes.value.filter((note) => note.id !== removedId);
    noteTotal.value = Math.max(0, noteTotal.value - 1);
    openIds.value = openIds.value.filter((id) => id !== removedId);
    const next = notes.value.find((note) => openIds.value.includes(note.id)) ?? notes.value[0];
    const full = next ? await resolveFullNote(next) : null;
    if (full) applyNote(full);
    else {
      selectedId.value = null;
      attachments.value = [];
    }
  } catch (cause) {
    handleError(cause, '删除失败。');
  } finally {
    deleting = false;
    if (selectedId.value === removedId && savedRevision < editRevision && saveState.value !== 'error')
      void saveNow();
  }
}

function onShortcut(event: KeyboardEvent) {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
    event.preventDefault();
    void saveNow();
  }
  if ((event.ctrlKey || event.metaKey) && event.shiftKey && event.key.toLowerCase() === 'f') {
    event.preventDefault();
    focusMode.value = !focusMode.value;
  }
}

watch([() => form.title, () => form.markdownContent, () => form.folder, tagText], scheduleSave);
watch(statusFilter, () => {
  notePage.value = 0;
  void load();
});
function warnBeforeUnload(event: BeforeUnloadEvent) {
  if (pendingUploads === 0 && saveState.value === 'saved' && !savePromise && savedRevision === editRevision)
    return;
  event.preventDefault();
  event.returnValue = '';
}
onBeforeRouteLeave(async () => pendingUploads === 0 && (!hasValidAdminSession() || (await flushCurrent())));
onMounted(() => {
  void load();
  window.addEventListener('keydown', onShortcut);
  window.addEventListener('beforeunload', warnBeforeUnload);
});
onBeforeUnmount(() => {
  editorSession += 1;
  attachmentLoadRevision += 1;
  clearTimeout(saveTimer);
  clearTimeout(noticeTimer);
  clearAttachmentPreviews();
  window.removeEventListener('keydown', onShortcut);
  window.removeEventListener('beforeunload', warnBeforeUnload);
});
</script>

<template>
  <section class="notes-studio" :class="{ 'focus-mode': focusMode }">
    <aside class="notes-library">
      <header>
        <button class="notes-back" @click="router.push('/admin')">← 工作台</button>
        <p>LEARNING ARCHIVE</p>
        <h1>学习笔记</h1>
      </header>
      <div class="notes-actions">
        <button class="new-note" @click="newNote">＋ 新建笔记</button
        ><button @click="fileInput?.click()">⇧ 导入</button
        ><input
          ref="fileInput"
          type="file"
          accept=".md,.markdown,.txt,text/markdown,text/plain"
          hidden
          @change="onImport"
        />
      </div>
      <label class="notes-search"
        >⌕<input v-model="query" type="search" placeholder="搜索标题、标签、目录"
      /></label>
      <div class="notes-filters">
        <button
          v-for="item in ['ALL', 'DRAFT', 'PUBLISHED', 'ARCHIVED'] as const"
          :key="item"
          :class="{ active: statusFilter === item }"
          @click="statusFilter = item"
        >
          {{ { ALL: '全部', DRAFT: '草稿', PUBLISHED: '公开', ARCHIVED: '归档' }[item] }}
        </button>
      </div>
      <p>{{ noteTotal }} 条笔记</p>
      <div v-if="loading" class="notes-empty">正在整理书架…</div>
      <div v-else-if="!filteredNotes.length" class="notes-empty">暂无匹配笔记</div>
      <div class="notes-list">
        <button
          v-for="note in filteredNotes"
          :key="note.id"
          :class="{ active: selectedId === note.id }"
          @click="selectNote(note)"
        >
          <span>{{ note.folder }} · {{ new Date(note.updatedAt).toLocaleDateString('zh-CN') }}</span
          ><strong>{{ note.title }}</strong
          ><small>{{ note.wordCount }} 字 · {{ note.tags.slice(0, 2).join(' / ') || '无标签' }}</small>
        </button>
      </div>
      <PaginationNav
        :page="notePage"
        :total-pages="noteTotalPages"
        aria-label="学习笔记分页"
        @change="changePage"
      />
    </aside>

    <main class="note-desk">
      <div v-if="!selectedId" class="note-welcome">
        <span>✦</span>
        <h2>留下一条学习轨迹</h2>
        <p>新建一篇笔记，或从本地导入 Markdown。</p>
        <button class="button primary" @click="newNote">新建第一篇笔记</button>
      </div>
      <template v-else>
        <header class="note-topbar">
          <div class="note-meta-fields">
            <input v-model="form.folder" aria-label="目录" placeholder="目录" /><span>/</span
            ><span class="note-status-label">{{ statusLabel }}</span>
          </div>
          <div class="note-save-state" :class="saveState"><i />{{ saveLabel }}</div>
          <div class="note-top-actions">
            <button title="专注模式 Ctrl+Shift+F" @click="focusMode = !focusMode">
              {{ focusMode ? '退出专注' : '专注' }}</button
            ><button @click="selected && exportNote(selected)">导出 .md</button
            ><button class="danger" @click="removeCurrent">删除</button
            ><button class="save-note" @click="saveNow">保存</button
            ><button
              v-if="form.status === 'DRAFT'"
              class="publish-note"
              :disabled="publishing || saveState === 'saving'"
              @click="changePublication('PUBLISHED')"
            >
              {{ publishing ? '正在发布…' : '发布笔记' }}</button
            ><button
              v-if="form.status === 'DRAFT'"
              :disabled="publishing || saveState === 'saving'"
              @click="changePublication('ARCHIVED')"
            >
              归档</button
            ><button
              v-else-if="form.status === 'PUBLISHED'"
              class="publish-note unpublish"
              :disabled="publishing || saveState === 'saving'"
              @click="changePublication('DRAFT')"
            >
              {{ publishing ? '正在撤回…' : '撤回公开' }}</button
            ><button
              v-else
              class="publish-note unpublish"
              :disabled="publishing || saveState === 'saving'"
              @click="changePublication('DRAFT')"
            >
              {{ publishing ? '正在恢复…' : '恢复草稿' }}
            </button>
          </div>
        </header>
        <nav v-if="openIds.length" class="note-tabs" aria-label="打开的笔记">
          <button
            v-for="id in openIds"
            :key="id"
            :class="{ active: selectedId === id }"
            @click="notes.find((note) => note.id === id) && selectNote(notes.find((note) => note.id === id)!)"
          >
            <span>{{ notes.find((note) => note.id === id)?.title || '未命名笔记' }}</span
            ><i @click.stop="closeTab(id)">×</i>
          </button>
        </nav>
        <div class="manuscript">
          <input
            v-model="form.title"
            class="note-title"
            maxlength="200"
            placeholder="未命名笔记"
            aria-label="笔记标题"
          />
          <input v-model="tagText" class="note-tags" placeholder="添加标签，用逗号分隔" aria-label="标签" />
          <!-- 4A-5：场景化 AI 动作（结果只填入不保存；笔记有自动保存，润色/续写落表单后随防抖入库） -->
          <AiActionChips :get-context="() => form.markdownContent" @apply="applyAiAction" />
          <TyporaEditor
            v-model="form.markdownContent"
            :upload-image="uploadEditorImage"
            @upload-error="error = $event"
          />
        </div>
        <footer class="note-statusbar">
          <span>Markdown</span><span>{{ charCount }} 字符</span><span>约 {{ readMinutes }} 分钟阅读</span
          ><span>Ctrl + S 保存</span>
        </footer>
      </template>
      <p v-if="publicationNotice" class="note-publication-notice" role="status">{{ publicationNotice }}</p>
      <p v-if="error" class="note-error">{{ error }}</p>
    </main>

    <aside class="note-outline">
      <p>OUTLINE / 大纲</p>
      <div v-if="outline.length">
        <button
          v-for="item in outline"
          :key="`${item.index}-${item.title}`"
          type="button"
          :style="{ paddingLeft: `${(item.level - 1) * 14}px` }"
          @click="jumpToOutline(item.title)"
        >
          {{ item.title }}
        </button>
      </div>
      <small v-else>添加一至三级标题后，文章结构会显示在这里。</small>
      <div class="outline-note">
        <b>写作提示</b>
        <p>输入 <code>#</code> 创建标题，<code>- [ ]</code> 创建任务，三个反引号创建代码块。</p>
      </div>
      <div class="attachment-panel">
        <header>
          <b>图片</b><span>{{ attachments.length }}</span>
        </header>
        <p v-if="!attachments.length">粘贴、拖入或从工具栏上传图片。</p>
        <article v-for="attachment in attachments" :key="attachment.id">
          <img
            :src="attachmentPreviewUrls[attachment.id] || attachment.url"
            :alt="attachment.fileName"
            loading="lazy"
            decoding="async"
          />
          <div>
            <strong>{{ attachment.fileName }}</strong
            ><small>{{ Math.ceil(attachment.byteSize / 1024) }} KB</small>
          </div>
          <button title="复制 Markdown" @click="copyAttachment(attachment)">复制</button
          ><button class="danger" title="删除图片" @click="removeAttachment(attachment)">×</button>
        </article>
      </div>
    </aside>
  </section>
</template>
