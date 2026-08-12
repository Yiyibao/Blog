import { useAuthStore } from '../stores/auth';
import { api, tokenHeader, unwrap } from './admin-client';

export type AiProviderType = 'OPENAI_COMPATIBLE' | 'OPENAI_RESPONSES' | 'ANTHROPIC' | 'OPENCODE_SERVER';

export type AiChatRole = 'user' | 'assistant';

/**
 * Reasoning effort values accepted by OpenAI reasoning models. `auto` is a
 * frontend-only value: omitting the field lets the configured provider default
 * decide the effort.
 */
export type AiReasoningEffort = 'none' | 'minimal' | 'low' | 'medium' | 'high' | 'xhigh' | 'max';
export type AiReasoningSelection = 'auto' | AiReasoningEffort;

export type AiProviderCapability =
  'TEXT' | 'VISION' | 'FILE_INPUT' | 'IMAGE_GENERATION' | 'STRUCTURED_OUTPUT' | 'REASONING' | 'TOOL_CALLING';

export interface AiProviderModelCapability {
  model: string;
  capabilities: AiProviderCapability[];
  reasoningEfforts: AiReasoningEffort[];
  enabled: boolean;
  version: number;
  updatedAt: string;
}

export interface AiChatMessage {
  role: AiChatRole;
  content: string;
}

export interface AiChatResult {
  content: string;
  model: string;
  usage?: {
    promptTokens: number;
    completionTokens: number;
    totalTokens: number;
  } | null;
}

export class AiStreamHttpError extends Error {
  status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = 'AiStreamHttpError';
    this.status = status;
  }
}

export interface AiStreamDone {
  model?: string;
  usage?: AiChatResult['usage'];
}

export interface AiStreamCallbacks {
  onDelta: (text: string) => void;
  onDone?: (info: AiStreamDone) => void;
}

export interface AiChatSession {
  id: number;
  title: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface AiChatMessageRecord {
  id: number;
  role: AiChatRole;
  content: string;
  createdAt: string;
}

export function fetchAiChatSessions() {
  return unwrap<AiChatSession[]>(api.get('/admin/ai/chat-sessions', { headers: tokenHeader() }));
}

export function createAiChatSession() {
  return unwrap<AiChatSession>(api.post('/admin/ai/chat-sessions', null, { headers: tokenHeader() }));
}

export function fetchAiChatSessionMessages(sessionId: number) {
  return unwrap<AiChatMessageRecord[]>(
    api.get(`/admin/ai/chat-sessions/${sessionId}/messages`, { headers: tokenHeader() }),
  );
}

export function appendAiChatMessages(sessionId: number, messages: AiChatMessage[]) {
  return unwrap<AiChatSession>(
    api.post(`/admin/ai/chat-sessions/${sessionId}/messages`, { messages }, { headers: tokenHeader() }),
  );
}

export function deleteAiChatSession(sessionId: number) {
  return api.delete(`/admin/ai/chat-sessions/${sessionId}`, { headers: tokenHeader() });
}

export interface AiStreamOptions {
  providerId?: number | null;
  model?: string | null;
  reasoningEffort?: AiReasoningEffort | null;
  signal?: AbortSignal;
}

// 4A-2：SSE 流式对话。EventSource 无法携带 Authorization 头，
// 改用 fetch + ReadableStream 手工解析 SSE，JWT 走标准请求头、绝不进 URL。
// 建流前的校验错误以普通 HTTP 错误返回；建流后的错误以 error 事件抛出。
export async function streamAiChat(
  messages: AiChatMessage[],
  callbacks: AiStreamCallbacks,
  options: AiStreamOptions = {},
): Promise<void> {
  const auth = useAuthStore();
  if (auth.token && auth.expiresAt && Date.parse(auth.expiresAt) <= Date.now()) {
    auth.clearSession();
    throw new AiStreamHttpError(401, '登录已过期');
  }
  const base = import.meta.env.VITE_API_BASE_URL || '/api/v1';
  const response = await fetch(`${base}/admin/ai/chat/stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...(auth.token ? { Authorization: `Bearer ${auth.token}` } : {}),
    },
    body: JSON.stringify({
      messages,
      ...(options.providerId != null ? { providerId: options.providerId } : {}),
      ...(options.model ? { model: options.model } : {}),
      ...(options.reasoningEffort ? { reasoningEffort: options.reasoningEffort } : {}),
    }),
    signal: options.signal,
  });
  if (response.status === 401) {
    auth.clearSession();
    throw new AiStreamHttpError(401, '未登录或登录已过期');
  }
  if (!response.ok || !response.body) {
    let message = 'AI 响应失败';
    try {
      const parsed: unknown = await response.json();
      if (
        parsed &&
        typeof parsed === 'object' &&
        typeof (parsed as { message?: unknown }).message === 'string'
      ) {
        message = (parsed as { message: string }).message;
      }
    } catch {
      // 非 JSON 错误体，使用默认文案
    }
    throw new AiStreamHttpError(response.status, message);
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let currentEvent = '';

  const handleLine = (rawLine: string) => {
    const line = rawLine.replace(/\r$/, '');
    if (line === '') {
      // SSE 事件边界：事件类型复位，避免粘滞到下一个事件
      currentEvent = '';
      return;
    }
    if (line.startsWith('event:')) {
      currentEvent = line.slice(6).trim();
      return;
    }
    if (!line.startsWith('data:')) return;
    const payload = line.slice(5).trim();
    if (!payload) return;
    let parsed: unknown;
    try {
      parsed = JSON.parse(payload);
    } catch {
      return;
    }
    if (!parsed || typeof parsed !== 'object') return;
    const record = parsed as { content?: unknown; status?: unknown; message?: unknown };
    const eventType = currentEvent;
    currentEvent = '';
    if (eventType === 'delta' && typeof record.content === 'string') {
      callbacks.onDelta(record.content);
    } else if (eventType === 'done') {
      callbacks.onDone?.(parsed as AiStreamDone);
    } else if (eventType === 'error') {
      const status = typeof record.status === 'number' ? record.status : 502;
      const detail = typeof record.message === 'string' ? record.message : 'AI 响应失败';
      throw new AiStreamHttpError(status, detail);
    }
  };

  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let newlineIndex = buffer.indexOf('\n');
      while (newlineIndex >= 0) {
        const line = buffer.slice(0, newlineIndex);
        buffer = buffer.slice(newlineIndex + 1);
        handleLine(line);
        newlineIndex = buffer.indexOf('\n');
      }
    }
    if (buffer) handleLine(buffer);
  } finally {
    // error 事件抛出或调用方中止时释放底层连接，避免 reader 悬挂
    reader.cancel().catch(() => {});
  }
}

// 4A-3：AI 供应商管理。密钥只写不回显——响应仅含 hasKey 与 keyTail（尾 4 位）。
export interface AiProvider {
  id: number;
  name: string;
  baseUrl: string;
  providerType: AiProviderType;
  models: string[];
  modelCapabilities?: AiProviderModelCapability[];
  defaultModel: string;
  enabled: boolean;
  isDefault: boolean;
  hasKey: boolean;
  keyTail: string | null;
  dailyRequestLimit: number;
  dailyTokenLimit: number;
  createdAt: string;
  updatedAt: string;
}

export interface AiProviderPayload {
  name: string;
  baseUrl: string;
  providerType: AiProviderType;
  /** 新建可留空（无鉴权端点）；编辑时省略或留空表示保留原密钥。 */
  apiKey?: string;
  models: string[];
  modelCapabilities?: Array<{
    model: string;
    capabilities: AiProviderCapability[];
    reasoningEfforts: AiReasoningEffort[];
    enabled: boolean;
  }>;
  defaultModel: string;
  enabled: boolean;
  dailyRequestLimit: number;
  dailyTokenLimit: number;
}

export interface AiProviderTestResult {
  ok: boolean;
  message: string;
  models: string[];
}

export const AI_PROVIDERS_CHANGED_EVENT = 'yubai-ai-providers-changed';

export function notifyAiProvidersChanged() {
  if (typeof window !== 'undefined') {
    window.dispatchEvent(new Event(AI_PROVIDERS_CHANGED_EVENT));
  }
}

export function fetchAiProviders() {
  return unwrap<AiProvider[]>(api.get('/admin/ai/providers', { headers: tokenHeader() }));
}

export function createAiProvider(payload: AiProviderPayload) {
  return unwrap<AiProvider>(api.post('/admin/ai/providers', payload, { headers: tokenHeader() }));
}

export function updateAiProvider(id: number, payload: AiProviderPayload) {
  return unwrap<AiProvider>(api.put(`/admin/ai/providers/${id}`, payload, { headers: tokenHeader() }));
}

export function deleteAiProvider(id: number) {
  return api.delete(`/admin/ai/providers/${id}`, { headers: tokenHeader() });
}

export function setDefaultAiProvider(id: number) {
  return unwrap<AiProvider>(api.put(`/admin/ai/providers/${id}/default`, null, { headers: tokenHeader() }));
}

// 连通测试由后端代发一次最小上游请求，可能较慢，放宽超时。
export function testAiProvider(id: number) {
  return unwrap<AiProviderTestResult>(
    api.post(`/admin/ai/providers/${id}/test`, null, { timeout: 30000, headers: tokenHeader() }),
  );
}

// 7：AI 提取菜谱
export interface AiImageModel {
  provider: 'grok' | 'gpt';
  model: string;
  isDefault: boolean;
}

export interface AiImageGeneratePayload {
  prompt: string;
  sessionId?: number | null;
  provider?: 'grok' | 'gpt';
  model?: string;
  n?: number;
  size?: string;
  quality?: string;
  aspectRatio?: string;
  resolution?: string;
  referenceImage?: File;
}

export interface AiGeneratedImage {
  publicId: string;
  generationId: string;
  provider: 'grok' | 'gpt';
  model: string;
  prompt: string;
  mediaType: string;
  byteSize: number;
  width: number | null;
  height: number | null;
  contentPath: string;
  createdAt: string;
}

export interface AiImageGenerateResult {
  sessionId: number;
  sessionTitle: string | null;
  images: AiGeneratedImage[];
}

export interface AiImageSession {
  id: number;
  title: string | null;
  createdAt: string;
  updatedAt: string;
}

// Image relays may need up to two 120s backend attempts before returning.
const AI_IMAGE_GENERATION_TIMEOUT_MS = 300000;

export function fetchAiImageModels() {
  return unwrap<AiImageModel[]>(api.get('/admin/ai/images/models', { headers: tokenHeader() }));
}

export function generateAiImages(payload: AiImageGeneratePayload) {
  const { referenceImage, ...requestPayload } = payload;
  const body = referenceImage
    ? (() => {
        const form = new FormData();
        form.append('payload', new Blob([JSON.stringify(requestPayload)], { type: 'application/json' }));
        form.append('referenceImage', referenceImage, referenceImage.name);
        return form;
      })()
    : requestPayload;
  return unwrap<AiImageGenerateResult>(
    api.post('/admin/ai/images', body, {
      headers: referenceImage ? { ...tokenHeader(), 'Content-Type': 'multipart/form-data' } : tokenHeader(),
      timeout: AI_IMAGE_GENERATION_TIMEOUT_MS,
    }),
  );
}

export function fetchAiImageSessions() {
  return unwrap<AiImageSession[]>(api.get('/admin/ai/images/sessions', { headers: tokenHeader() }));
}

export function fetchAiImageSessionImages(sessionId: number) {
  return unwrap<AiGeneratedImage[]>(
    api.get(`/admin/ai/images/sessions/${sessionId}/images`, { headers: tokenHeader() }),
  );
}

export function deleteAiImageSession(sessionId: number) {
  return api.delete(`/admin/ai/images/sessions/${sessionId}`, { headers: tokenHeader() });
}

export async function fetchAiImageContent(publicId: string) {
  const response = await api.get<Blob>(`/admin/ai/images/${publicId}/content`, {
    headers: tokenHeader(),
    responseType: 'blob',
    timeout: 30000,
  });
  return response.data;
}

export function deleteAiGeneratedImage(publicId: string) {
  return api.delete(`/admin/ai/images/${publicId}`, { headers: tokenHeader() });
}

export * from './admin-recipe';
