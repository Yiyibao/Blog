import { api, tokenHeader, unwrap } from './admin-client';
import type { AdminPost } from './admin-content';

export interface AdminMusicTrack {
  id: number;
  trackId: string;
  title: string;
  artist: string;
  duration: number;
  audioUrl: string;
  coverUrl: string;
  sortOrder: number;
  createdAt: string;
}

export type MusicTrackPayload = Omit<AdminMusicTrack, 'id' | 'createdAt'>;

export interface AdminQuote {
  id: number;
  content: string;
  author: string;
  category: string;
  displayOrder: number;
  createdAt: string;
}

export type QuotePayload = Omit<AdminQuote, 'id' | 'createdAt'>;

export function fetchAdminTracks() {
  return unwrap<AdminMusicTrack[]>(api.get('/admin/library/tracks', { headers: tokenHeader() }));
}

export function createAdminTrack(payload: MusicTrackPayload) {
  return unwrap<AdminMusicTrack>(api.post('/admin/library/tracks', payload, { headers: tokenHeader() }));
}

export function updateAdminTrack(id: number, payload: MusicTrackPayload) {
  return unwrap<AdminMusicTrack>(api.put(`/admin/library/tracks/${id}`, payload, { headers: tokenHeader() }));
}

export function deleteAdminTrack(id: number) {
  return api.delete(`/admin/library/tracks/${id}`, { headers: tokenHeader() });
}

export function fetchAdminQuotes() {
  return unwrap<AdminQuote[]>(api.get('/admin/library/quotes', { headers: tokenHeader() }));
}

export function createAdminQuote(payload: QuotePayload) {
  return unwrap<AdminQuote>(api.post('/admin/library/quotes', payload, { headers: tokenHeader() }));
}

export function updateAdminQuote(id: number, payload: QuotePayload) {
  return unwrap<AdminQuote>(api.put(`/admin/library/quotes/${id}`, payload, { headers: tokenHeader() }));
}

export function deleteAdminQuote(id: number) {
  return api.delete(`/admin/library/quotes/${id}`, { headers: tokenHeader() });
}

// 4B：合集管理——成员整表排序提交，乐观锁 version 随行（冲突 409）
export interface AdminSeriesEntry {
  postId: number;
  slug: string;
  title: string;
  date: string;
  chapterTitle: string | null;
  position: number;
}

export interface AdminSeries {
  id: number;
  name: string;
  slug: string;
  description: string;
  coverImage: string | null;
  status: 'DRAFT' | 'PUBLISHED';
  version: number;
  entryCount: number;
  createdAt: string;
  updatedAt: string;
  publishedAt: string | null;
  entries: AdminSeriesEntry[];
}

export interface SeriesPayload {
  name: string;
  slug: string;
  description: string;
  coverImage: string | null;
  status: 'DRAFT' | 'PUBLISHED';
}

export interface SeriesEntryInput {
  postId: number;
  chapterTitle?: string | null;
}

export function fetchAdminSeriesList() {
  return unwrap<AdminSeries[]>(api.get('/admin/series', { headers: tokenHeader() }));
}

export function fetchAdminSeries(id: number) {
  return unwrap<AdminSeries>(api.get(`/admin/series/${id}`, { headers: tokenHeader() }));
}

export function createSeries(payload: SeriesPayload) {
  return unwrap<AdminSeries>(api.post('/admin/series', payload, { headers: tokenHeader() }));
}

export function updateSeries(id: number, version: number, payload: SeriesPayload) {
  return unwrap<AdminSeries>(
    api.put(`/admin/series/${id}`, payload, {
      headers: tokenHeader(),
      params: { version },
    }),
  );
}

export function setSeriesEntries(id: number, version: number, entries: SeriesEntryInput[]) {
  return unwrap<AdminSeries>(
    api.put(`/admin/series/${id}/entries`, { entries, version }, { headers: tokenHeader() }),
  );
}

export function deleteSeries(id: number) {
  return api.delete(`/admin/series/${id}`, { headers: tokenHeader() });
}

// 4C：文章版本历史——保存即快照（后端保留最近 10 版），恢复=回写正文并产生新版本
export interface PostRevisionSummary {
  id: number;
  title: string;
  contentFormat: 'HTML' | 'MARKDOWN';
  createdAt: string;
}

export interface PostRevisionDetail extends PostRevisionSummary {
  excerpt: string;
  content: string;
  markdownContent: string | null;
}

export function fetchPostRevisions(postId: number) {
  return unwrap<PostRevisionSummary[]>(
    api.get(`/admin/posts/${postId}/revisions`, { headers: tokenHeader() }),
  );
}

export function fetchPostRevision(postId: number, revisionId: number) {
  return unwrap<PostRevisionDetail>(
    api.get(`/admin/posts/${postId}/revisions/${revisionId}`, { headers: tokenHeader() }),
  );
}

export function restorePostRevision(postId: number, revisionId: number) {
  return unwrap<AdminPost>(
    api.post(`/admin/posts/${postId}/revisions/${revisionId}/restore`, null, { headers: tokenHeader() }),
  );
}

// 3A-2：存量 HTML→Markdown 一次性转换（响应即人工校对清单）
export interface MarkdownConversionReport {
  id: number;
  slug: string;
  converted: boolean;
  risks: string[];
}

export function convertPostsMarkdown(force = false) {
  return unwrap<MarkdownConversionReport[]>(
    api.post('/admin/posts/convert-markdown', null, {
      headers: tokenHeader(),
      params: force ? { force: true } : undefined,
    }),
  );
}
