import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { useAiTaskStore } from '../stores/aiTaskStore';
import * as aiApi from '../api/ai';

vi.mock('../api/ai', () => ({
  fetchAiSessions: vi.fn(),
  fetchAiTasks: vi.fn(),
  fetchAiFiles: vi.fn(),
  fetchAiMemories: vi.fn(),
  fetchAiArtifacts: vi.fn(),
  fetchAiProjects: vi.fn(),
  fetchAiSessionConversation: vi.fn(),
  fetchAiTaskEvents: vi.fn(),
  replayAiTaskStream: vi.fn(),
  createAiSession: vi.fn(),
  createAiTask: vi.fn(),
  runAiTask: vi.fn(),
  cancelAiTask: vi.fn(),
  uploadAiFile: vi.fn(),
  deleteAiFile: vi.fn(),
  fetchAiFileContent: vi.fn(),
  createAiMemory: vi.fn(),
  confirmAiMemory: vi.fn(),
  setAiMemoryEnabled: vi.fn(),
  updateAiMemory: vi.fn(),
  rejectAiMemory: vi.fn(),
  deleteAiMemory: vi.fn(),
  clearAiSessionSummary: vi.fn(),
  createAiArtifact: vi.fn(),
  deleteAiArtifact: vi.fn(),
  downloadAiArtifact: vi.fn(),
}));

const mocked = vi.mocked(aiApi);

function session() {
  return {
    id: 1,
    title: '测试任务',
    mode: 'WORKSPACE' as const,
    summary: null,
    version: 0,
    createdAt: '2026-08-10T00:00:00Z',
    updatedAt: '2026-08-10T00:00:00Z',
  };
}

function task(status: 'QUEUED' | 'COMPLETED') {
  return {
    id: '11111111-1111-1111-1111-111111111111',
    sessionId: 1,
    taskType: 'CHAT' as const,
    status,
    providerId: 4,
    providerType: status === 'COMPLETED' ? 'OPENAI_RESPONSES' : null,
    model: 'fake-vision',
    errorCode: null,
    errorMessage: null,
    version: status === 'COMPLETED' ? 2 : 0,
    startedAt: status === 'COMPLETED' ? '2026-08-10T00:00:01Z' : null,
    finishedAt: status === 'COMPLETED' ? '2026-08-10T00:00:02Z' : null,
    createdAt: '2026-08-10T00:00:00Z',
    updatedAt: '2026-08-10T00:00:02Z',
    parts: [],
  };
}

describe('aiTaskStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    mocked.fetchAiSessions.mockResolvedValue([session()]);
    mocked.fetchAiTasks.mockResolvedValue([]);
    mocked.fetchAiFiles.mockResolvedValue([]);
    mocked.fetchAiMemories.mockResolvedValue([]);
    mocked.fetchAiArtifacts.mockResolvedValue([]);
    mocked.fetchAiProjects.mockResolvedValue([]);
    mocked.fetchAiSessionConversation.mockRejectedValue(new Error('conversation mock not configured'));
    mocked.fetchAiTaskEvents.mockResolvedValue([]);
    mocked.replayAiTaskStream.mockResolvedValue(undefined);
  });

  it('uploads image and document refs before running a persisted task', async () => {
    const image = {
      id: 'image-id',
      name: 'pixel.png',
      mediaType: 'image/png',
      sizeBytes: 10,
      sha256: 'a'.repeat(64),
      status: 'READY' as const,
      retention: 'THIRTY_DAYS' as const,
      expiresAt: null,
      referenceCount: 0,
      createdAt: '2026-08-10T00:00:00Z',
      updatedAt: '2026-08-10T00:00:00Z',
    };
    const document = { ...image, id: 'doc-id', name: 'note.pdf', mediaType: 'application/pdf' };
    mocked.uploadAiFile.mockResolvedValueOnce(image).mockResolvedValueOnce(document);
    mocked.createAiTask.mockResolvedValue(task('QUEUED'));
    mocked.runAiTask.mockResolvedValue(task('COMPLETED'));
    const store = useAiTaskStore();
    await store.initialize();

    await store.upload([
      new File(['png'], 'pixel.png', { type: 'image/png' }),
      new File(['pdf'], 'note.pdf', { type: 'application/pdf' }),
    ]);
    await store.submit('理解附件', 4, 'fake-vision');

    expect(mocked.createAiTask).toHaveBeenCalledWith(
      expect.objectContaining({
        sessionId: 1,
        providerId: 4,
        model: 'fake-vision',
        parts: [
          { kind: 'TEXT', text: '理解附件' },
          { kind: 'IMAGE_REF', fileId: 'image-id' },
          { kind: 'FILE_REF', fileId: 'doc-id' },
        ],
      }),
    );
    expect(mocked.runAiTask).toHaveBeenCalledWith(task('QUEUED').id);
    expect(store.currentTask?.status).toBe('COMPLETED');
    expect(store.selectedFileIds).toEqual([]);
  });

  it('restores persisted sessions, tasks, files, memories and artifacts without browser storage', async () => {
    mocked.fetchAiTasks.mockResolvedValue([task('COMPLETED')]);
    const store = useAiTaskStore();

    await store.initialize();

    expect(store.currentTask?.id).toBe(task('COMPLETED').id);
    expect(mocked.fetchAiTaskEvents).toHaveBeenCalledWith(task('COMPLETED').id, 0);
    expect(Object.keys(window.sessionStorage).filter((key) => key.includes('ai-task'))).toEqual([]);
  });
});
