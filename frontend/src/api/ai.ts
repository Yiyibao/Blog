import { api, tokenHeader, unwrap } from './admin-client';

export type AiPartKind =
  'TEXT' | 'IMAGE_REF' | 'FILE_REF' | 'ARTIFACT_REF' | 'TOOL_CALL' | 'TOOL_RESULT' | 'SOURCE_REF';
export type AiTaskStatus = 'QUEUED' | 'RUNNING' | 'WAITING_APPROVAL' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
export type AiMemoryStatus = 'PROPOSED' | 'ACTIVE' | 'REJECTED' | 'DISABLED' | 'DELETED';
export type AiArtifactFormat = 'MARKDOWN' | 'TEXT' | 'JSON' | 'CSV' | 'IMAGE';

export interface AiSession {
  id: number;
  title: string | null;
  mode: 'WORKSPACE' | 'COMPACT' | 'PET';
  summary: string | null;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface AiFile {
  id: string;
  name: string;
  mediaType: string;
  sizeBytes: number;
  sha256: string;
  status: 'UPLOADED' | 'VALIDATING' | 'READY' | 'REJECTED' | 'EXPIRED' | 'DELETED';
  retention: 'SESSION' | 'THIRTY_DAYS' | 'PINNED';
  expiresAt: string | null;
  referenceCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface AiTaskPart {
  sequence: number;
  role: 'SYSTEM' | 'USER' | 'ASSISTANT' | 'TOOL';
  kind: AiPartKind;
  text: string | null;
  fileId: string | null;
  artifactId: string | null;
  sourceRef: string | null;
  createdAt: string;
}

export interface AiTask {
  id: string;
  sessionId: number;
  taskType: 'CHAT' | 'ANALYZE' | 'GENERATE';
  status: AiTaskStatus;
  providerId: number | null;
  providerType: string | null;
  model: string | null;
  errorCode: string | null;
  errorMessage: string | null;
  version: number;
  startedAt: string | null;
  finishedAt: string | null;
  createdAt: string;
  updatedAt: string;
  parts: AiTaskPart[];
}

export interface AiTaskEvent {
  taskId: string;
  sequence: number;
  eventType: string;
  payload: Record<string, unknown>;
  createdAt: string;
}

export interface AiMemory {
  id: string;
  scope: string;
  kind: string;
  content: string | null;
  sourceTaskId: string | null;
  sourceRef: string | null;
  status: AiMemoryStatus;
  confidence: number | null;
  expiresAt: string | null;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface AiArtifact {
  id: string;
  taskId: string;
  name: string;
  mediaType: string;
  sizeBytes: number;
  sha256: string;
  status: 'PENDING' | 'READY' | 'FAILED' | 'EXPIRED' | 'DELETED';
  expiresAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface AiTaskCreateInput {
  sessionId?: number | null;
  sessionTitle?: string;
  taskType?: 'CHAT' | 'ANALYZE' | 'GENERATE';
  providerId?: number | null;
  model?: string | null;
  idempotencyKey: string;
  parts: Array<{
    kind: 'TEXT' | 'IMAGE_REF' | 'FILE_REF';
    text?: string;
    fileId?: string;
  }>;
}

export function createAiSession(title?: string) {
  return unwrap<AiSession>(api.post('/ai/sessions', { title, mode: 'WORKSPACE' }));
}

export function fetchAiSessions() {
  return unwrap<AiSession[]>(api.get('/ai/sessions'));
}

export function createAiTask(input: AiTaskCreateInput) {
  return unwrap<AiTask>(api.post('/ai/tasks', input));
}

export function runAiTask(taskId: string) {
  return unwrap<AiTask>(api.post(`/ai/tasks/${encodeURIComponent(taskId)}/run`));
}

export function fetchAiTasks() {
  return unwrap<AiTask[]>(api.get('/ai/tasks'));
}

export function fetchAiTask(taskId: string) {
  return unwrap<AiTask>(api.get(`/ai/tasks/${encodeURIComponent(taskId)}`));
}

export function cancelAiTask(taskId: string) {
  return unwrap<AiTask>(api.delete(`/ai/tasks/${encodeURIComponent(taskId)}`));
}

export function fetchAiTaskEvents(taskId: string, afterSequence = 0) {
  return unwrap<AiTaskEvent[]>(
    api.get(`/ai/tasks/${encodeURIComponent(taskId)}/events`, { params: { afterSequence } }),
  );
}

export async function replayAiTaskStream(
  taskId: string,
  afterSequence: number,
  onEvent: (event: AiTaskEvent) => void,
) {
  const base = import.meta.env.VITE_API_BASE_URL || '/api/v1';
  const headers = new Headers({
    Accept: 'text/event-stream',
    'Last-Event-ID': String(afterSequence),
  });
  Object.entries(tokenHeader()).forEach(([name, value]) => {
    if (value) headers.set(name, value);
  });
  const response = await fetch(
    `${base}/ai/tasks/${encodeURIComponent(taskId)}/stream?afterSequence=${afterSequence}`,
    {
      headers,
      credentials: 'include',
    },
  );
  if (!response.ok) throw new Error(`AI event replay failed (${response.status})`);
  const text = await response.text();
  for (const block of text.split(/\r?\n\r?\n/)) {
    const data = block
      .split(/\r?\n/)
      .filter((line) => line.startsWith('data:'))
      .map((line) => line.slice(5).trimStart())
      .join('\n');
    if (!data) continue;
    try {
      onEvent(JSON.parse(data) as AiTaskEvent);
    } catch {
      // Ignore malformed/non-data heartbeat frames; persisted REST replay remains authoritative.
    }
  }
}

export function uploadAiFile(file: File, retention: AiFile['retention'] = 'THIRTY_DAYS') {
  const body = new FormData();
  body.append('file', file);
  return unwrap<AiFile>(
    api.post('/ai/files', body, {
      params: { retention },
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 30_000,
    }),
  );
}

export function fetchAiFiles() {
  return unwrap<AiFile[]>(api.get('/ai/files'));
}

export function deleteAiFile(fileId: string) {
  return unwrap<void>(api.delete(`/ai/files/${encodeURIComponent(fileId)}`));
}

export function fetchAiMemories() {
  return unwrap<AiMemory[]>(api.get('/ai/memories'));
}

export function createAiMemory(content: string, scope = 'USER', sourceTaskId?: string | null) {
  return unwrap<AiMemory>(
    api.post('/ai/memories', {
      scope,
      kind: 'PREFERENCE',
      content,
      sourceTaskId: sourceTaskId || undefined,
      sourceRef: sourceTaskId ? `task:${sourceTaskId}` : undefined,
    }),
  );
}

export function confirmAiMemory(memoryId: string) {
  return unwrap<AiMemory>(api.post(`/ai/memories/${encodeURIComponent(memoryId)}/confirm`));
}

export function setAiMemoryEnabled(memoryId: string, enabled: boolean) {
  return unwrap<AiMemory>(
    api.post(`/ai/memories/${encodeURIComponent(memoryId)}/${enabled ? 'enable' : 'disable'}`),
  );
}

export function updateAiMemory(memory: AiMemory, content: string, scope = memory.scope) {
  return unwrap<AiMemory>(
    api.patch(`/ai/memories/${encodeURIComponent(memory.id)}`, {
      scope,
      kind: memory.kind,
      content,
      expiresAt: memory.expiresAt,
      version: memory.version,
    }),
  );
}

export function rejectAiMemory(memoryId: string) {
  return unwrap<AiMemory>(api.post(`/ai/memories/${encodeURIComponent(memoryId)}/reject`));
}

export function deleteAiMemory(memoryId: string) {
  return unwrap<void>(api.delete(`/ai/memories/${encodeURIComponent(memoryId)}`));
}

export function clearAiSessionSummary(sessionId: number) {
  return unwrap<AiSession>(api.delete(`/ai/sessions/${encodeURIComponent(sessionId)}/summary`));
}

export function fetchAiArtifacts() {
  return unwrap<AiArtifact[]>(api.get('/ai/artifacts'));
}

export function createAiArtifact(
  taskId: string,
  input: { name: string; format: AiArtifactFormat; content?: string; sourceImageId?: string },
) {
  return unwrap<AiArtifact>(api.post(`/ai/tasks/${encodeURIComponent(taskId)}/artifacts`, input));
}

export function deleteAiArtifact(artifactId: string) {
  return unwrap<void>(api.delete(`/ai/artifacts/${encodeURIComponent(artifactId)}`));
}

export async function downloadAiArtifact(artifact: AiArtifact) {
  const response = await api.get<Blob>(`/ai/artifacts/${encodeURIComponent(artifact.id)}/download`, {
    responseType: 'blob',
    timeout: 30_000,
  });
  const url = URL.createObjectURL(response.data);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = artifact.name;
  anchor.rel = 'noopener';
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}
