import { describe, expect, beforeEach, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import AdminGraphPage from '../pages/AdminGraphPage.vue';
import * as graphApi from '../api/admin-graph';

const mockFetchRelations = vi.fn();
const mockCreateRelation = vi.fn();
const mockUpdateRelation = vi.fn();
const mockDeleteRelation = vi.fn();
const mockFetchAudit = vi.fn();
const mockPreviewImport = vi.fn();

vi.mock('../api/admin-graph', () => ({
  fetchAdminGraphRelations: (...args: unknown[]) => mockFetchRelations(...args),
  createAdminGraphRelation: (...args: unknown[]) => mockCreateRelation(...args),
  updateAdminGraphRelation: (...args: unknown[]) => mockUpdateRelation(...args),
  deleteAdminGraphRelation: (...args: unknown[]) => mockDeleteRelation(...args),
  fetchAdminGraphRelationAudit: (...args: unknown[]) => mockFetchAudit(...args),
  previewAdminGraphRelationImport: (...args: unknown[]) => mockPreviewImport(...args),
}));

vi.mock('../api/admin', () => ({
  hasValidAdminSession: () => true,
  clearAdminSession: vi.fn(),
}));

const relation: graphApi.AdminGraphRelation = {
  id: 'relation-1',
  sourceId: 'post:1',
  targetId: 'tag:typescript',
  relationType: 'tagged_with',
  origin: 'MANUAL',
  createdBy: 'admin',
  createdAt: '2026-08-13T01:00:00Z',
  updatedAt: '2026-08-13T01:00:00Z',
  version: 3,
};

async function mountPage() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/admin/graph', component: AdminGraphPage },
      { path: '/admin/login', component: { template: '<div>login</div>' } },
    ],
  });
  await router.push('/admin/graph');
  await router.isReady();
  const wrapper = mount(AdminGraphPage, {
    global: {
      plugins: [router],
      stubs: { AdminSidebar: { template: '<aside class="sidebar-stub" />' } },
    },
  });
  await flushPromises();
  return { wrapper, router };
}

beforeEach(() => {
  mockFetchRelations.mockReset().mockResolvedValue([relation]);
  mockCreateRelation.mockReset().mockResolvedValue(relation);
  mockUpdateRelation.mockReset().mockResolvedValue({ ...relation, version: 4 });
  mockDeleteRelation.mockReset().mockResolvedValue(undefined);
  mockFetchAudit.mockReset().mockResolvedValue([
    {
      id: 'audit-1',
      relationId: relation.id,
      sourceId: relation.sourceId,
      targetId: relation.targetId,
      relationType: relation.relationType,
      origin: relation.origin,
      action: 'CREATE',
      actor: 'admin',
      relationVersion: 1,
      createdAt: relation.createdAt,
    },
  ]);
  mockPreviewImport.mockReset().mockResolvedValue({
    schemaVersion: '2.0',
    acceptedCount: 1,
    conflictCount: 1,
    accepted: [],
    conflicts: ['duplicate relation'],
  });
});

describe('AdminGraphPage', () => {
  it('loads relations, applies independent filters, and creates a relation', async () => {
    const { wrapper } = await mountPage();
    expect(mockFetchRelations).toHaveBeenCalledWith(undefined, undefined);

    const filters = wrapper.findAll('.relation-filters input');
    await filters[0].setValue('post:2');
    await filters[1].setValue('tag:vue');
    await filters[1].trigger('keyup.enter');
    await flushPromises();
    expect(mockFetchRelations).toHaveBeenLastCalledWith('post:2', 'tag:vue');

    const formInputs = wrapper.findAll('.relation-form input');
    await formInputs[0].setValue('post:2');
    await formInputs[1].setValue('tag:vue');
    await formInputs[2].setValue('tagged_with');
    await wrapper.find('.relation-form').trigger('submit');
    await flushPromises();
    expect(mockCreateRelation).toHaveBeenCalledWith({
      sourceId: 'post:2',
      targetId: 'tag:vue',
      relationType: 'tagged_with',
    });
  });

  it('updates, audits, deletes, and previews imports without bypassing the API', async () => {
    const { wrapper } = await mountPage();
    const rowButtons = wrapper.findAll('.row-actions button');

    await rowButtons[0].trigger('click');
    await wrapper.find('.relation-form').trigger('submit');
    await flushPromises();
    expect(mockUpdateRelation).toHaveBeenCalledWith(relation.id, relation.version, {
      sourceId: relation.sourceId,
      targetId: relation.targetId,
      relationType: relation.relationType,
    });

    const refreshedButtons = wrapper.findAll('.row-actions button');
    await refreshedButtons[1].trigger('click');
    await flushPromises();
    expect(mockFetchAudit).toHaveBeenCalledWith(relation.id);
    expect(wrapper.find('.audit-list').text()).toContain('CREATE');

    vi.spyOn(window, 'confirm').mockReturnValue(true);
    await wrapper.findAll('.row-actions button')[2].trigger('click');
    await flushPromises();
    expect(mockDeleteRelation).toHaveBeenCalledWith(relation.id, relation.version);

    await wrapper.find('textarea').setValue('{"schemaVersion":"2.0","relations":[]}');
    await wrapper.findAll('.graph-admin-card')[2].find('button').trigger('click');
    await flushPromises();
    expect(mockPreviewImport).toHaveBeenCalledWith('{"schemaVersion":"2.0","relations":[]}');
    expect(wrapper.find('.import-preview').text()).toContain('duplicate relation');
  });
});
