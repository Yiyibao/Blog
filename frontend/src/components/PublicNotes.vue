<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { EditorContent, useEditor } from '@tiptap/vue-3';
import StarterKit from '@tiptap/starter-kit';
import { Markdown } from '@tiptap/markdown';
import { TaskItem, TaskList } from '@tiptap/extension-list';
import { TableKit } from '@tiptap/extension-table';
import Image from '@tiptap/extension-image';
import { Mathematics } from '@tiptap/extension-mathematics';
import CodeBlockLowlight from '@tiptap/extension-code-block-lowlight';
import { lowlight } from '../utils/codeHighlight';
import 'katex/dist/katex.min.css';
import '../styles/code-highlight.css';
import { fetchPublishedNote, fetchPublishedNotes } from '../api/content';
import type { AdminNoteSummary } from '../api/admin';
import { usePageMeta, cleanText } from '../composables/usePageMeta';
import { createSiteConfig, resolveUrl } from '../config/site';
import { techArticle, breadcrumbList, useStructuredData } from '../composables/useStructuredData';
import PaginationNav from './PaginationNav.vue';

// P1-2：公开列表为摘要 DTO；正文在选中时经详情接口补齐后回写列表
type PublicNoteItem = AdminNoteSummary & { markdownContent?: string };

const route = useRoute();
const notes = ref<PublicNoteItem[]>([]);
const selectedId = ref<number | null>(null);
const query = ref('');
const loading = ref(true);
const notePage = ref(0);
const noteTotal = ref(0);
const noteTotalPages = ref(1);

const selected = computed(() => notes.value.find((note) => note.id === selectedId.value) ?? notes.value[0]);
const filtered = computed(() => {
  const needle = query.value.trim().toLowerCase();
  return notes.value.filter(
    (note) => !needle || [note.title, note.folder, ...note.tags].join(' ').toLowerCase().includes(needle),
  );
});

const { apply } = usePageMeta();
const { apply: applyLD } = useStructuredData();

// 选中的笔记若尚无正文（摘要项），拉取详情并回写列表；详情返回后各 watch 自动重渲染
watch(
  selected,
  async (note) => {
    if (!note || typeof note.markdownContent === 'string') return;
    try {
      const full = await fetchPublishedNote(note.id);
      notes.value = notes.value.map((item) => (item.id === full.id ? full : item));
    } catch {
      // 详情暂不可得：正文区维持加载中空态，列表与元信息不受影响
    }
  },
  { immediate: true },
);

watch(
  selected,
  (note) => {
    if (note) {
      const excerpt = cleanText(note.markdownContent ?? '', 200);
      const authorName = createSiteConfig().authorName;
      applyLD([
        techArticle({
          headline: note.title,
          description: excerpt,
          url: resolveUrl(`/notes?note=${note.id}`),
          datePublished: note.createdAt,
          dateModified: note.updatedAt,
          authorName: authorName || 'Yubai',
        }),
        breadcrumbList([
          { name: '首页', path: '/' },
          { name: '学习笔记', path: '/notes' },
          { name: note.title, path: `/notes?note=${note.id}` },
        ]),
      ]);
      apply({
        title: note.title,
        description: excerpt,
        canonicalPath: `/notes?note=${note.id}`,
        openGraph: {
          title: note.title,
          description: excerpt,
          type: 'article',
          image: '/og.png',
          url: `/notes?note=${note.id}`,
        },
        twitter: {
          title: note.title,
          description: excerpt,
          image: '/og.png',
        },
      });
    }
  },
  { immediate: true },
);

const editor = useEditor({
  editable: false,
  content: '',
  contentType: 'markdown',
  extensions: [
    // L-14：与编辑器同一条 lowlight 管线——围栏语言标记在只读渲染同样生效
    StarterKit.configure({ codeBlock: false }),
    CodeBlockLowlight.configure({ lowlight }),
    Markdown,
    TaskList,
    TaskItem.configure({ nested: true }),
    TableKit,
    Image,
    Mathematics.configure({ katexOptions: { throwOnError: false } }),
  ],
  editorProps: { attributes: { class: 'typora-prose public-note-prose' } },
});

watch(
  [selected, () => selected.value?.markdownContent],
  () => {
    const note = selected.value;
    if (!note || !editor.value) return;
    // 正文未到达时先清空，避免展示上一篇笔记的内容
    editor.value.commands.setContent(note.markdownContent ?? '', { contentType: 'markdown' });
  },
  { immediate: true },
);

async function load() {
  loading.value = true;
  try {
    const result = await fetchPublishedNotes(notePage.value, 20);
    notes.value = result.items;
    noteTotal.value = result.totalElements;
    noteTotalPages.value = Math.max(1, result.totalPages);
    await selectRouteNote();
  } catch {
    notes.value = [];
  } finally {
    loading.value = false;
  }
}

function changePage(page: number) {
  notePage.value = page;
  void load();
}

async function selectRouteNote() {
  const rawId = Array.isArray(route.query.note) ? route.query.note[0] : route.query.note;
  const id = Number(rawId);
  if (!Number.isSafeInteger(id) || id <= 0) {
    selectedId.value = notes.value[0]?.id ?? null;
    return;
  }
  const loaded = notes.value.find((note) => note.id === id);
  if (loaded) {
    selectedId.value = loaded.id;
    return;
  }
  try {
    const note = await fetchPublishedNote(id);
    notes.value = [note, ...notes.value.filter((item) => item.id !== note.id)];
    selectedId.value = note.id;
  } catch {
    selectedId.value = notes.value[0]?.id ?? null;
  }
}

watch(
  () => route.query.note,
  () => void selectRouteNote(),
);

onMounted(load);
onBeforeUnmount(() => editor.value?.destroy());
</script>

<template>
  <section class="notes-page section-wrap">
    <header class="notes-page-head">
      <div>
        <p class="eyebrow"><span /> LEARNING NOTES / 学习笔记</p>
        <h1>把学到的东西，<br /><em>变成可以回来的路。</em></h1>
      </div>
      <p>这里不是答案仓库，而是一张持续生长的认知地图。公开笔记会在保存后自动出现在此处。</p>
    </header>
    <div class="public-notes-layout">
      <aside>
        <label class="search-field"
          ><span>检索笔记</span><input v-model="query" type="search" placeholder="标题、目录或标签…"
        /></label>
        <p>{{ noteTotal.toString().padStart(2, '0') }} PUBLIC NOTES</p>
        <div class="public-note-list">
          <button
            v-for="note in filtered"
            :key="note.id"
            :class="{ active: selected?.id === note.id }"
            @click="selectedId = note.id"
          >
            <small>{{ note.folder }} · {{ new Date(note.updatedAt).toLocaleDateString('zh-CN') }}</small
            ><strong>{{ note.title }}</strong
            ><span>{{ note.wordCount }} 字 · {{ note.tags.slice(0, 2).join(' / ') || '学习记录' }}</span>
          </button>
        </div>
        <PaginationNav
          :page="notePage"
          :total-pages="noteTotalPages"
          aria-label="公开笔记分页"
          @change="changePage"
        />
      </aside>
      <article v-if="selected" class="public-note-paper">
        <header>
          <p>{{ selected.folder }} / {{ selected.status === 'PUBLISHED' ? '公开笔记' : '' }}</p>
          <h2>{{ selected.title }}</h2>
          <div>
            <span v-for="tag in selected.tags" :key="tag"># {{ tag }}</span>
          </div>
        </header>
        <EditorContent :editor="editor" />
      </article>
      <div v-else class="public-notes-empty">
        <span>✦</span>
        <h2>{{ loading ? '正在翻阅笔记…' : '公开笔记正在整理中' }}</h2>
        <p>管理员将笔记状态设为“公开”后，会在这里出现。</p>
      </div>
    </div>
  </section>
</template>
