import { api, tokenHeader, unwrap } from './admin-client';

export type GraphRelationOrigin = 'MANUAL' | 'SYSTEM' | 'AI_APPROVED';
export type GraphRelationAction = 'CREATE' | 'UPDATE' | 'DELETE';

export interface AdminGraphRelation {
  id: string;
  sourceId: string;
  targetId: string;
  relationType: string;
  origin: GraphRelationOrigin;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface GraphRelationAudit {
  id: string;
  relationId: string | null;
  sourceId: string;
  targetId: string;
  relationType: string;
  origin: GraphRelationOrigin;
  action: GraphRelationAction;
  actor: string;
  relationVersion: number;
  createdAt: string;
}

export interface GraphRelationImportItem {
  sourceId: string;
  targetId: string;
  relationType: string;
}

export interface GraphRelationImportPreview {
  schemaVersion: string;
  acceptedCount: number;
  conflictCount: number;
  accepted: GraphRelationImportItem[];
  conflicts: string[];
}

export function fetchAdminGraphRelations(sourceId?: string, targetId?: string) {
  return unwrap<AdminGraphRelation[]>(
    api.get('/admin/graph/relations', {
      headers: tokenHeader(),
      params: { ...(sourceId ? { sourceId } : {}), ...(targetId ? { targetId } : {}) },
    }),
  );
}

export function createAdminGraphRelation(payload: GraphRelationImportItem) {
  return unwrap<AdminGraphRelation>(api.post('/admin/graph/relations', payload, { headers: tokenHeader() }));
}

export function updateAdminGraphRelation(id: string, version: number, payload: GraphRelationImportItem) {
  return unwrap<AdminGraphRelation>(
    api.put(`/admin/graph/relations/${encodeURIComponent(id)}`, payload, {
      headers: tokenHeader(),
      params: { version },
    }),
  );
}

export function deleteAdminGraphRelation(id: string, version: number) {
  return api.delete(`/admin/graph/relations/${encodeURIComponent(id)}`, {
    headers: tokenHeader(),
    params: { version },
  });
}

export function fetchAdminGraphRelationAudit(id: string) {
  return unwrap<GraphRelationAudit[]>(
    api.get(`/admin/graph/relations/${encodeURIComponent(id)}/audit`, { headers: tokenHeader() }),
  );
}

export function previewAdminGraphRelationImport(payload: string) {
  return unwrap<GraphRelationImportPreview>(
    api.post('/admin/graph/relations/import-preview', { payload }, { headers: tokenHeader() }),
  );
}
