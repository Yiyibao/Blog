import type { YrecipePreview } from './admin';
import { api, tokenHeader, unwrap } from './admin-client';

export interface RecipeExtractionRequest {
  sourceType: 'TEXT' | 'WEB_URL' | 'VIDEO_URL';
  sourceContent: string;
  providerId?: number | null;
  model?: string | null;
}

export interface RecipeExtractionJob {
  id: number;
  idempotencyKey: string;
  sourceType: string;
  status: string;
  stage: string | null;
  progress: number;
  attempts: number;
  providerId: number | null;
  model: string | null;
  resultImportToken: string | null;
  errorCode: string | null;
  safeErrorMessage: string | null;
  preview: {
    token: string;
    expiresAt: string;
    recipe: YrecipePreview['recipe'];
    warnings: string[];
    categoryMatch: string | null;
    slugAvailable: boolean;
    coverPreviewUrl: string;
  } | null;
  createdAt: string;
  startedAt: string | null;
  heartbeatAt: string | null;
  finishedAt: string | null;
}

export function createRecipeExtraction(payload: RecipeExtractionRequest) {
  return unwrap<RecipeExtractionJob>(
    api.post('/admin/recipe-extractions', payload, {
      headers: { ...tokenHeader(), 'Idempotency-Key': crypto.randomUUID() },
      timeout: 15_000,
    }),
  );
}

export function fetchRecipeExtraction(id: number) {
  return unwrap<RecipeExtractionJob>(api.get(`/admin/recipe-extractions/${id}`, { headers: tokenHeader() }));
}

export function cancelRecipeExtraction(id: number) {
  return api.post(`/admin/recipe-extractions/${id}/cancel`, null, { headers: tokenHeader() });
}

export function retryRecipeExtraction(id: number) {
  return unwrap<RecipeExtractionJob>(
    api.post(`/admin/recipe-extractions/${id}/retry`, null, { headers: tokenHeader() }),
  );
}
