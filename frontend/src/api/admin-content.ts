import type { Dish, PageResult, Post, PostStatus, PostSummary } from '../data';
import { api, tokenHeader, unwrap } from './admin-client';
import type { ApiEnvelope } from './admin-client';

export interface AdminPost extends Post {
  id: number;
}

// P1-2：管理端列表为摘要 DTO（不含 content），编辑前必须经 fetchAdminPost 拉取全文。
export type AdminPostSummary = PostSummary & { id: number };

export type PostPayload = Omit<AdminPost, 'id' | 'slug'> & { slug?: string | null };

export interface AdminPostCategory {
  id: number;
  name: string;
  slug: string;
  description: string;
  postCount: number;
  publishedPostCount: number;
}

export interface PostCategoryPayload {
  name: string;
  description: string;
}

export interface AdminDishCategory {
  id: number;
  name: string;
  slug: string;
  description: string;
  dishCount: number;
  publishedDishCount: number;
}

export interface AdminDish extends Dish {}
// favoriteCount 只经收藏端点原子自增，管理端编辑不提交也不覆盖（后端 DishRequest 亦无此字段）
export type DishPayload = Omit<AdminDish, 'id' | 'slug' | 'createdAt' | 'updatedAt' | 'favoriteCount'>;

export interface DishImageUpload {
  publicId: string;
  fileName: string;
  mediaType: string;
  byteSize: number;
  width: number | null;
  height: number | null;
  url: string;
  createdAt: string;
}

export type NoteStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';

// P1-2：笔记列表为摘要 DTO（不含 markdownContent），正文经 fetchAdminNote 详情获取。
export interface AdminNoteSummary {
  id: number;
  title: string;
  folder: string;
  status: NoteStatus;
  tags: string[];
  sourceFileName: string | null;
  wordCount: number;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface AdminNote extends AdminNoteSummary {
  markdownContent: string;
}

export interface NotePayload {
  title: string;
  markdownContent: string;
  folder: string;
  status: NoteStatus;
  tags: string[];
  version: number;
}

export interface NoteAttachment {
  id: number;
  publicId: string;
  noteId: number;
  fileName: string;
  mediaType: string;
  byteSize: number;
  url: string;
  createdAt: string;
}

function asPage<T>(data: PageResult<T> | T[], page = 0, size = 20): PageResult<T> {
  if (Array.isArray(data)) {
    return {
      items: data,
      page,
      size: data.length || size,
      totalElements: data.length,
      totalPages: data.length ? 1 : 0,
    };
  }
  return {
    items: data.items ?? [],
    page: data.page ?? page,
    size: data.size ?? size,
    totalElements: data.totalElements ?? data.items?.length ?? 0,
    totalPages: Math.max(1, data.totalPages ?? 1),
  };
}

export async function fetchAdminPosts(page = 0, size = 20, status?: PostStatus | '') {
  const data = await unwrap<PageResult<AdminPostSummary> | AdminPostSummary[]>(
    api.get('/admin/posts', {
      headers: tokenHeader(),
      params: { page, size, ...(status ? { status } : {}) },
    }),
  );
  return asPage(data, page, size);
}

export function fetchAdminPost(id: number) {
  return unwrap<AdminPost>(api.get(`/admin/posts/${id}`, { headers: tokenHeader() }));
}

export function createPost(payload: PostPayload) {
  return unwrap<AdminPost>(api.post('/admin/posts', payload, { headers: tokenHeader() }));
}

export function updatePost(id: number, payload: PostPayload) {
  return unwrap<AdminPost>(api.put(`/admin/posts/${id}`, payload, { headers: tokenHeader() }));
}

export function deletePost(id: number) {
  return api.delete(`/admin/posts/${id}`, { headers: tokenHeader() });
}

export type PostBatchAction = 'PUBLISH' | 'ARCHIVE' | 'DRAFT' | 'ADD_TAGS';

export interface PostWorkflowResult {
  id: number;
  status: PostStatus;
  scheduledPublishAt: string | null;
  tags: string[];
}

export interface PostPublicationAudit {
  id: number;
  postId: number | null;
  action: string;
  actor: string;
  detail: string | null;
  createdAt: string;
}

export function schedulePost(id: number, publishAt: string) {
  return unwrap<PostWorkflowResult>(
    api.post(`/admin/posts/${id}/schedule`, { publishAt }, { headers: tokenHeader() }),
  );
}

export function cancelPostSchedule(id: number) {
  return unwrap<PostWorkflowResult>(api.delete(`/admin/posts/${id}/schedule`, { headers: tokenHeader() }));
}

export function batchUpdatePosts(ids: number[], action: PostBatchAction, tags: string[] = []) {
  return unwrap<PostWorkflowResult[]>(
    api.post('/admin/posts/batch', { ids, action, tags }, { headers: tokenHeader() }),
  );
}

export function fetchPostPublicationAudit() {
  return unwrap<PostPublicationAudit[]>(api.get('/admin/posts/audit', { headers: tokenHeader() }));
}

export function fetchAdminCategories() {
  return unwrap<AdminPostCategory[]>(api.get('/admin/categories', { headers: tokenHeader() }));
}

export function createPostCategory(payload: PostCategoryPayload) {
  return unwrap<AdminPostCategory>(api.post('/admin/categories', payload, { headers: tokenHeader() }));
}

export function updatePostCategory(id: number, payload: PostCategoryPayload) {
  return unwrap<AdminPostCategory>(api.put(`/admin/categories/${id}`, payload, { headers: tokenHeader() }));
}

export function deletePostCategory(id: number) {
  return api.delete(`/admin/categories/${id}`, { headers: tokenHeader() });
}

export function fetchAdminDishCategories() {
  return unwrap<AdminDishCategory[]>(api.get('/admin/dish-categories', { headers: tokenHeader() }));
}

export function createDishCategory(payload: PostCategoryPayload) {
  return unwrap<AdminDishCategory>(api.post('/admin/dish-categories', payload, { headers: tokenHeader() }));
}

export function updateDishCategory(id: number, payload: PostCategoryPayload) {
  return unwrap<AdminDishCategory>(
    api.put(`/admin/dish-categories/${id}`, payload, { headers: tokenHeader() }),
  );
}

export function deleteDishCategory(id: number) {
  return api.delete(`/admin/dish-categories/${id}`, { headers: tokenHeader() });
}

// 4F：曲目与语录管理

export async function fetchAdminDishes(page = 0, size = 20) {
  const data = await unwrap<PageResult<AdminDish> | AdminDish[]>(
    api.get('/admin/dishes', {
      headers: tokenHeader(),
      params: { page, size },
    }),
  );
  return asPage(data, page, size);
}

export function createDish(payload: DishPayload) {
  return unwrap<AdminDish>(api.post('/admin/dishes', payload, { headers: tokenHeader() }));
}

export function updateDish(id: number, payload: DishPayload) {
  return unwrap<AdminDish>(api.put(`/admin/dishes/${id}`, payload, { headers: tokenHeader() }));
}

export function deleteDish(id: number) {
  return api.delete(`/admin/dishes/${id}`, { headers: tokenHeader() });
}

export function uploadDishImage(file: File) {
  const body = new FormData();
  body.append('file', file);
  return unwrap<DishImageUpload>(
    api.post('/admin/dish-assets', body, {
      headers: { ...tokenHeader(), 'Content-Type': 'multipart/form-data' },
      timeout: 30000,
    }),
  );
}

export function attachDishImage(publicId: string, dishId: number) {
  return api.post(`/admin/dish-assets/${publicId}/attach/${dishId}`, null, { headers: tokenHeader() });
}

export function deleteDishImage(publicId: string) {
  return api.delete(`/admin/dish-assets/${publicId}`, { headers: tokenHeader() });
}

// 6D：.yrecipe 导入/导出
export interface YrecipePreview {
  token: string;
  expiresAt: string;
  recipe: {
    schemaVersion: string;
    kind: string;
    packageId: string;
    recipe: {
      name: string;
      slug: string | null;
      summary: string;
      categoryHint: string | null;
      prepMinutes: number;
      difficulty: string | null;
      baseServings: number;
      ingredients: string[];
      steps: string[];
    };
    cover: {
      path: string;
      alt: string | null;
    };
    source: Record<string, unknown> | null;
    generation: Record<string, unknown> | null;
  };
  warnings: string[];
  categoryMatch: string | null;
  slugAvailable: boolean;
  coverPreviewUrl: string;
}

export interface DishImportCommitRequest {
  category: string;
  published?: boolean;
}

export async function previewDishImport(file: File) {
  const body = new FormData();
  body.append('file', file);
  return unwrap<YrecipePreview>(
    api.post('/admin/dish-imports/preview', body, {
      headers: { ...tokenHeader(), 'Content-Type': 'multipart/form-data' },
      timeout: 30000,
    }),
  );
}

export function commitDishImport(token: string, payload: DishImportCommitRequest) {
  return api
    .post<ApiEnvelope<AdminDish>>(`/admin/dish-imports/${token}/commit`, payload, {
      headers: tokenHeader(),
    })
    .then((r) => {
      if (r.status === 201) return r.data.data as AdminDish;
      return r.data.data as AdminDish;
    });
}

export function cancelDishImport(token: string) {
  return api.delete(`/admin/dish-imports/${token}`, { headers: tokenHeader() });
}

export async function downloadStagedRecipe(token: string) {
  const response = await api.get<Blob>(`/admin/dish-imports/${token}/download`, {
    headers: tokenHeader(),
    responseType: 'blob',
  });
  downloadBlobResponse(response.data, response.headers['content-disposition'], 'generated-recipe.yrecipe');
}

export async function exportDish(id: number) {
  const response = await api.get<Blob>(`/admin/dishes/${id}/export`, {
    headers: tokenHeader(),
    responseType: 'blob',
  });
  downloadBlobResponse(response.data, response.headers['content-disposition'], `${id}.yrecipe`);
}

function downloadBlobResponse(data: Blob, contentDisposition: string | undefined, fallbackName: string) {
  const disposition = contentDisposition || '';
  const match = disposition.match(/filename\*?=(?:UTF-8'')?([^;]+)/i);
  const filename = match ? decodeURIComponent(match[1].trim()) : fallbackName;
  const url = URL.createObjectURL(data);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  URL.revokeObjectURL(url);
}

export async function fetchNotes(page = 0, size = 20, status?: NoteStatus | '') {
  const data = await unwrap<PageResult<AdminNoteSummary> | AdminNoteSummary[]>(
    api.get('/admin/notes', {
      headers: tokenHeader(),
      params: { page, size, ...(status ? { status } : {}) },
    }),
  );
  return asPage(data, page, size);
}

export function fetchAdminNote(id: number) {
  return unwrap<AdminNote>(api.get(`/admin/notes/${id}`, { headers: tokenHeader() }));
}

export function createNote(payload: NotePayload) {
  return unwrap<AdminNote>(api.post('/admin/notes', payload, { headers: tokenHeader() }));
}

export function updateNote(id: number, payload: NotePayload) {
  return unwrap<AdminNote>(api.put(`/admin/notes/${id}`, payload, { headers: tokenHeader() }));
}

export function publishNote(id: number, version: number) {
  return unwrap<AdminNote>(api.put(`/admin/notes/${id}/publish`, { version }, { headers: tokenHeader() }));
}

export function unpublishNote(id: number, version: number) {
  return unwrap<AdminNote>(api.put(`/admin/notes/${id}/unpublish`, { version }, { headers: tokenHeader() }));
}

export function archiveNote(id: number, version: number) {
  return unwrap<AdminNote>(api.put(`/admin/notes/${id}/archive`, { version }, { headers: tokenHeader() }));
}

export function deleteNote(id: number) {
  return api.delete(`/admin/notes/${id}`, { headers: tokenHeader() });
}

export function importNote(file: File) {
  const body = new FormData();
  body.append('file', file);
  return unwrap<AdminNote>(api.post('/admin/notes/import', body, { headers: tokenHeader() }));
}

export async function exportNote(note: AdminNoteSummary) {
  const response = await api.get<Blob>(`/admin/notes/${note.id}/export`, {
    headers: tokenHeader(),
    responseType: 'blob',
  });
  const url = URL.createObjectURL(response.data);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = `${note.title.replace(/[\\/:*?\"<>|]/g, '_')}.md`;
  anchor.click();
  URL.revokeObjectURL(url);
}

export function fetchNoteAttachments(noteId: number) {
  return unwrap<NoteAttachment[]>(api.get(`/admin/notes/${noteId}/attachments`, { headers: tokenHeader() }));
}

export async function fetchNoteAttachmentContent(noteId: number, attachmentId: number) {
  const response = await api.get<Blob>(`/admin/notes/${noteId}/attachments/${attachmentId}/content`, {
    headers: tokenHeader(),
    responseType: 'blob',
  });
  return response.data;
}

export function uploadNoteAttachment(noteId: number, file: File) {
  const body = new FormData();
  body.append('file', file);
  return unwrap<NoteAttachment>(
    api.post(`/admin/notes/${noteId}/attachments`, body, { headers: tokenHeader() }),
  );
}

export function deleteNoteAttachment(noteId: number, attachmentId: number) {
  return api.delete(`/admin/notes/${noteId}/attachments/${attachmentId}`, { headers: tokenHeader() });
}

// 4D：仪表盘统计扩展——30 天趋势 / TOP5 热文 / 状态计数 / 附件容量 / AI 用量卡片
export interface DayViews {
  day: string;
  views: number;
}

export interface TopPost {
  title: string;
  slug: string;
  viewsCount: number;
  likeCount: number;
}

export interface AdminStats {
  posts: number;
  dishes: number;
  notes: number;
  publishedPosts: number;
  draftPosts: number;
  attachmentCount: number;
  attachmentBytes: number;
  viewTrend: DayViews[];
  topPosts: TopPost[];
  aiUsage: { requests: number; tokens: number };
}

export function fetchAdminStats() {
  return unwrap<AdminStats>(api.get('/admin/stats', { headers: tokenHeader() }));
}

// 4E：附件总览（孤儿 = 笔记正文不再引用且创建超 7 天）
export interface AttachmentOverviewItem {
  id: number;
  noteId: number;
  noteTitle: string;
  fileName: string;
  mediaType: string;
  byteSize: number;
  url: string;
  createdAt: string;
  orphan: boolean;
}

export interface AttachmentOverview {
  count: number;
  totalBytes: number;
  orphanCount: number;
  items: AttachmentOverviewItem[];
}

export function fetchAttachmentOverview() {
  return unwrap<AttachmentOverview>(api.get('/admin/attachments', { headers: tokenHeader() }));
}
