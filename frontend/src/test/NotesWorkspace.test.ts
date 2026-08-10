import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { mount } from '@vue/test-utils';
import NotesWorkspace from '../components/NotesWorkspace.vue';
import FakeTyporaEditor from './fakes/FakeTyporaEditor.vue';
import type { AdminNote, NoteAttachment } from '../api/admin';

// ── Router mock ──────────────────────────────────────────────────────────────

const routerMock = vi.hoisted(() => {
  let leaveGuard: ((...a: any[]) => any) | null = null;
  return {
    push: vi.fn(),
    replace: vi.fn(),
    get leaveGuard() {
      return leaveGuard;
    },
    set leaveGuard(fn) {
      leaveGuard = fn;
    },
  };
});

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: routerMock.push, replace: routerMock.replace }),
  useRoute: () => ({ name: 'admin-notes', params: {} }),
  onBeforeRouteLeave: (fn: any) => {
    routerMock.leaveGuard = fn;
  },
}));

// ── Admin API mock with controllable promises ────────────────────────────────

const adminApi = vi.hoisted(() => {
  type Entry = { resolve(v: any): void; reject(e: any): void };
  const queues: Record<string, Entry[]> = {};

  function mock(name: string) {
    queues[name] = [];
    return vi.fn(
      () =>
        new Promise<any>((resolve, reject) => {
          queues[name]!.push({ resolve, reject });
        }),
    );
  }

  return {
    requests(name: string) {
      return queues[name] ?? [];
    },
    resolve(name: string, value: any) {
      const q = queues[name];
      if (q && q.length) q.shift()!.resolve(value);
    },
    reject(name: string, reason: any) {
      const q = queues[name];
      if (q && q.length) q.shift()!.reject(reason);
    },
    clear() {
      Object.values(queues).forEach((q) => (q.length = 0));
    },
    fetchNotes: mock('fetchNotes'),
    fetchAdminNote: mock('fetchAdminNote'),
    updateNote: mock('updateNote'),
    publishNote: mock('publishNote'),
    unpublishNote: mock('unpublishNote'),
    archiveNote: mock('archiveNote'),
    fetchNoteAttachments: mock('fetchNoteAttachments'),
    fetchNoteAttachmentContent: mock('fetchNoteAttachmentContent'),
    uploadNoteAttachment: mock('uploadNoteAttachment'),
    deleteNoteAttachment: mock('deleteNoteAttachment'),
    createNote: mock('createNote'),
    deleteNote: mock('deleteNote'),
    exportNote: mock('exportNote'),
    importNote: mock('importNote'),
    hasValidAdminSession: vi.fn(() => true),
    clearAdminSession: vi.fn(),
  };
});

vi.mock('../api/admin', () => adminApi);

// ── Test data ────────────────────────────────────────────────────────────────

const S1: AdminNote = {
  id: 1,
  title: 'A',
  markdownContent: '# A\nHello',
  folder: 'w',
  status: 'DRAFT',
  tags: [],
  version: 1,
  wordCount: 3,
  sourceFileName: null,
  createdAt: '',
  updatedAt: '',
};
const S2: AdminNote = { ...S1, id: 2, title: 'B', markdownContent: '# B\nWorld' };
const PUBLISHED: AdminNote = { ...S1, status: 'PUBLISHED' };

const canonicalUrl = '/api/v1/note-assets/p1';
const ATTACHMENT: NoteAttachment = {
  id: 10,
  publicId: 'p1',
  noteId: 1,
  fileName: 'i.png',
  mediaType: 'image/png',
  byteSize: 100,
  url: canonicalUrl,
  createdAt: '',
};

const S1_WITH_IMG: AdminNote = {
  ...S1,
  markdownContent: `# A\nHello ![img](${canonicalUrl})`,
};

function pageResult(notes: AdminNote[]) {
  return { items: notes, page: 0, size: 20, totalElements: notes.length, totalPages: 1 };
}

// ── Async helpers (work with vi.useFakeTimers) ───────────────────────────────

/** Drain microtask queue completely using timer-based promise */
async function flush() {
  const p = new Promise<void>((r) => setTimeout(r, 0));
  await vi.advanceTimersByTimeAsync(5);
  await p;
}

/** Advance fake timers by `ms` then drain microtasks */
async function advanceTime(ms: number) {
  await vi.advanceTimersByTimeAsync(ms);
  await flush();
  await flush();
}

/** Mount component with standard stubs */
function mountNotes() {
  return mount(NotesWorkspace, {
    global: {
      stubs: { TyporaEditor: FakeTyporaEditor, RouterLink: { template: '<a><slot /></a>' } },
    },
    attachTo: document.body,
  });
}

// ── Test lifecycle helpers ───────────────────────────────────────────────────

let editCounter = 0;

beforeEach(() => {
  adminApi.clear();
  vi.clearAllMocks();
  adminApi.hasValidAdminSession.mockImplementation(() => true);
  routerMock.push.mockReset();
  routerMock.replace.mockReset();
  routerMock.leaveGuard = null;
  editCounter = 0;
});

afterEach(() => {
  document.body.innerHTML = '';
});

/** Mount and fetch initial notes */
async function init(notes: AdminNote[] = [S1, S2]) {
  const w = mountNotes();
  await flush();
  adminApi.resolve('fetchNotes', pageResult(notes));
  await flush();
  await flush();
  return w;
}

/** Edit note title with a unique value per call, then flush */
async function edit(w: ReturnType<typeof mountNotes>) {
  editCounter += 1;
  await w.find('.note-title').setValue(`Edited ${editCounter}`);
  await flush();
  await flush();
}

// ── Tests ────────────────────────────────────────────────────────────────────

describe('NotesWorkspace regression', () => {
  // ── Scenario 1: Save queue ────────────────────────────────────────────────

  it('1a: saves clean note after single edit', async () => {
    const w = await init([S1]);
    await edit(w);
    await advanceTime(1100);

    expect(adminApi.updateNote).toHaveBeenCalledTimes(1);
    adminApi.resolve('updateNote', { ...S1, title: 'Edited 1', version: 2 });
    await flush();
    await flush();
    expect(w.find('.note-save-state').text()).toContain('已保存');
    w.unmount();
  });

  it('1b: two sequential edits each trigger save', async () => {
    const w = await init([S1]);
    await edit(w);
    await advanceTime(1100);
    adminApi.resolve('updateNote', { ...S1, title: 'Edited 1', version: 2 });
    await flush();
    await flush();
    expect(w.find('.note-save-state').text()).toContain('已保存');

    await edit(w);
    await advanceTime(1100);
    expect(adminApi.updateNote).toHaveBeenCalledTimes(2);
    adminApi.resolve('updateNote', { ...S1, title: 'Edited 2', version: 3 });
    await flush();
    await flush();
    expect(w.find('.note-save-state').text()).toContain('已保存');
    w.unmount();
  });

  // ── Scenario 2: Note switching ────────────────────────────────────────────

  it('2: saves dirty note before switching; delayed response does not corrupt target', async () => {
    const w = await init();
    await edit(w);

    w.findAll('.notes-list button')[1].trigger('click');
    await flush();

    expect(adminApi.updateNote).toHaveBeenCalledTimes(1);
    adminApi.resolve('updateNote', { ...S1, title: 'Edited 1', version: 2 });
    await flush();
    await flush();

    adminApi.resolve('fetchNoteAttachments', []);
    await flush();
    await flush();

    expect((w.find('.note-title').element as HTMLInputElement).value).toBe('B');
    w.unmount();
  });

  // ── Scenario 2b: P1-2 摘要列表 → 选中先取详情 ──────────────────────────────
  it('2b: P1-2 list items without markdownContent trigger a detail fetch before applying', async () => {
    const strip = (note: AdminNote) => {
      const { markdownContent: _content, ...summary } = note;
      return summary as unknown as AdminNote;
    };

    const w = mountNotes();
    await flush();
    adminApi.resolve('fetchNotes', pageResult([strip(S1), strip(S2)]));
    await flush();
    await flush();

    // 初始选中第一篇：摘要无正文，必须经详情接口补齐后才进入表单
    expect(adminApi.fetchAdminNote).toHaveBeenCalledWith(1);
    adminApi.resolve('fetchAdminNote', S1);
    await flush();
    await flush();
    expect((w.find('.note-title').element as HTMLInputElement).value).toBe('A');

    // 切换第二篇：同样先取详情，正文到达后才切换
    w.findAll('.notes-list button')[1].trigger('click');
    await flush();
    await flush();
    expect(adminApi.fetchAdminNote).toHaveBeenCalledWith(2);
    adminApi.resolve('fetchAdminNote', S2);
    await flush();
    await flush();
    expect((w.find('.note-title').element as HTMLInputElement).value).toBe('B');
    w.unmount();
  });

  // ── Scenario 3: Save failure ──────────────────────────────────────────────

  it('3: failure shows error; retry succeeds on first attempt', async () => {
    const w = await init([S1]);
    await edit(w);
    await advanceTime(1100);

    expect(adminApi.updateNote).toHaveBeenCalledTimes(1);
    adminApi.reject('updateNote', Object.assign(new Error('x'), { isAxiosError: true }));
    await flush();
    await flush();
    expect(w.find('.note-save-state').text()).toContain('保存失败');

    await edit(w);
    await advanceTime(1100);

    expect(adminApi.updateNote).toHaveBeenCalledTimes(2);
    adminApi.resolve('updateNote', { ...S1, title: 'Edited 2', version: 2 });
    await flush();
    await flush();
    expect(w.find('.note-save-state').text()).toContain('已保存');
    w.unmount();
  });

  // ── Scenario 4: Publication ───────────────────────────────────────────────

  it('4a: publish calls publishNote after saving dirty content', async () => {
    const w = await init([S1]);
    await edit(w);
    w.find('.publish-note').trigger('click');
    await flush();

    expect(adminApi.updateNote).toHaveBeenCalledTimes(1);
    adminApi.resolve('updateNote', { ...S1, title: 'Edited 1', version: 2 });
    await flush();
    await flush();
    expect(adminApi.publishNote).toHaveBeenCalledTimes(1);
    w.unmount();
  });

  it('4b: unpublish calls unpublishNote', async () => {
    const w = await init([PUBLISHED]);
    adminApi.resolve('fetchNoteAttachments', []);
    await flush();
    await flush();
    w.find('.unpublish').trigger('click');
    await flush();

    adminApi.resolve('updateNote', { ...PUBLISHED, version: 2 });
    await flush();
    await flush();
    expect(adminApi.unpublishNote).toHaveBeenCalledTimes(1);
    w.unmount();
  });

  // ── Scenario 5: Upload race ───────────────────────────────────────────────

  it('5a: upload in progress blocks note switching', async () => {
    const w = await init();
    adminApi.resolve('fetchNoteAttachments', []);
    await flush();
    await flush();

    adminApi.uploadNoteAttachment.mockImplementationOnce(() => new Promise(() => {}));
    w.findComponent(FakeTyporaEditor).vm.simulateUpload();
    await flush();

    w.findAll('.notes-list button')[1].trigger('click');
    await flush();

    expect((w.find('.note-title').element as HTMLInputElement).value).toBe('A');
    w.unmount();
  });

  it('5b: after upload completes, note switching works', async () => {
    const w = await init();
    adminApi.resolve('fetchNoteAttachments', []);
    await flush();
    await flush();

    adminApi.uploadNoteAttachment.mockImplementationOnce(() => Promise.resolve(ATTACHMENT));
    adminApi.fetchNoteAttachmentContent.mockImplementationOnce(() => Promise.resolve(new Blob(['d'])));
    w.findComponent(FakeTyporaEditor).vm.simulateUpload();
    await flush();

    w.findAll('.notes-list button')[1].trigger('click');
    await flush();

    adminApi.resolve('fetchNoteAttachments', []);
    await flush();
    await flush();

    expect((w.find('.note-title').element as HTMLInputElement).value).toBe('B');
    w.unmount();
  });

  // ── Scenario 6: Blob URL round-trip ───────────────────────────────────────

  it('6: markdown uses blob: in editor and canonical URL in save payload', async () => {
    const w = await init([S1_WITH_IMG]);
    // Load attachment → content is fetched → preview URL replaces canonical
    adminApi.resolve('fetchNoteAttachments', [ATTACHMENT]);
    await flush();
    await flush();
    adminApi.resolve('fetchNoteAttachmentContent', new Blob(['data']));
    await flush();
    await flush();
    await flush();

    const editor = w.findComponent(FakeTyporaEditor);
    expect(editor.vm.content).toContain('blob:mock-');

    await edit(w);
    await advanceTime(1100);

    expect(adminApi.updateNote).toHaveBeenCalledTimes(1);
    const payload = (adminApi.updateNote.mock.calls[0] as any[])[1];
    expect(payload.markdownContent).toContain(canonicalUrl);
    expect(payload.markdownContent).not.toContain('blob:');
    adminApi.resolve('updateNote', { ...S1, version: 2 });
    w.unmount();
  });

  // ── Scenario 7: Page leave ────────────────────────────────────────────────

  it('7a: beforeunload calls preventDefault when dirty', async () => {
    const w = await init([S1]);
    await edit(w);
    const ev = new Event('beforeunload');
    const spy = vi.spyOn(ev, 'preventDefault');
    Object.defineProperty(ev, 'returnValue', { configurable: true, value: '', writable: true });
    window.dispatchEvent(ev);
    expect(spy).toHaveBeenCalled();
    w.unmount();
  });

  it('7b: route leave guard saves dirty content', async () => {
    const w = await init([S1]);
    await edit(w);
    const guard = routerMock.leaveGuard!;
    const promise = guard();
    await flush();

    expect(adminApi.updateNote).toHaveBeenCalledTimes(1);
    adminApi.resolve('updateNote', { ...S1, title: 'Edited 1', version: 2 });
    await flush();
    await flush();

    expect(await promise).toBe(true);
    w.unmount();
  });
});
