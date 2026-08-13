<script setup lang="ts">
import axios from 'axios';
import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import AdminSidebar from '../components/AdminSidebar.vue';
import {
  createAdminGraphRelation,
  deleteAdminGraphRelation,
  fetchAdminGraphRelationAudit,
  fetchAdminGraphRelations,
  previewAdminGraphRelationImport,
  updateAdminGraphRelation,
  type AdminGraphRelation,
  type GraphRelationAudit,
  type GraphRelationImportPreview,
} from '../api/admin-graph';
import { clearAdminSession, hasValidAdminSession } from '../api/admin';

const router = useRouter();
const relations = ref<AdminGraphRelation[]>([]);
const sourceId = ref('');
const targetId = ref('');
const form = ref({ sourceId: '', targetId: '', relationType: '' });
const editingId = ref<string | null>(null);
const loading = ref(false);
const saving = ref(false);
const error = ref('');
const importPayload = ref('{\n  "relations": []\n}');
const importPreview = ref<GraphRelationImportPreview | null>(null);
const audit = ref<GraphRelationAudit[]>([]);

function authError(cause: unknown) {
  if (axios.isAxiosError(cause) && cause.response?.status === 401) {
    clearAdminSession();
    void router.replace('/admin/login');
    return true;
  }
  return false;
}

async function load() {
  loading.value = true;
  error.value = '';
  try {
    relations.value = await fetchAdminGraphRelations(
      sourceId.value || undefined,
      targetId.value || undefined,
    );
  } catch (cause) {
    if (!authError(cause)) error.value = '关系列表加载失败';
  } finally {
    loading.value = false;
  }
}

function resetForm() {
  editingId.value = null;
  form.value = { sourceId: '', targetId: '', relationType: '' };
}

function editRelation(relation: AdminGraphRelation) {
  editingId.value = relation.id;
  form.value = {
    sourceId: relation.sourceId,
    targetId: relation.targetId,
    relationType: relation.relationType,
  };
}

async function save() {
  saving.value = true;
  error.value = '';
  try {
    if (editingId.value) {
      const current = relations.value.find((item) => item.id === editingId.value);
      if (!current) return;
      await updateAdminGraphRelation(editingId.value, current.version, form.value);
    } else {
      await createAdminGraphRelation(form.value);
    }
    resetForm();
    await load();
  } catch (cause) {
    if (!authError(cause)) error.value = '关系保存失败，可能是节点不存在、重复或版本已过期';
  } finally {
    saving.value = false;
  }
}

async function remove(relation: AdminGraphRelation) {
  if (!window.confirm(`确认删除关系 ${relation.sourceId} → ${relation.targetId}？`)) return;
  try {
    await deleteAdminGraphRelation(relation.id, relation.version);
    if (editingId.value === relation.id) resetForm();
    await load();
  } catch (cause) {
    if (!authError(cause)) error.value = '关系删除失败，可能是版本已过期';
  }
}

async function showAudit(relation: AdminGraphRelation) {
  try {
    audit.value = await fetchAdminGraphRelationAudit(relation.id);
  } catch (cause) {
    if (!authError(cause)) error.value = '审计记录加载失败';
  }
}

async function previewImport() {
  try {
    importPreview.value = await previewAdminGraphRelationImport(importPayload.value);
  } catch (cause) {
    if (!authError(cause)) error.value = '导入预览失败，请检查 JSON 格式';
  }
}

onMounted(() => {
  if (!hasValidAdminSession()) {
    void router.replace('/admin/login');
    return;
  }
  void load();
});
</script>

<template>
  <section class="admin-console graph-admin-page">
    <AdminSidebar />
    <main class="admin-main">
      <header class="admin-topbar">
        <div>
          <span class="admin-breadcrumb">后台管理 / 知识图谱</span>
          <h1>显式关系管理</h1>
        </div>
        <button type="button" class="button secondary" @click="load">刷新</button>
      </header>

      <p v-if="error" class="admin-error" role="alert">{{ error }}</p>
      <section class="graph-admin-card">
        <h2>{{ editingId ? '编辑关系' : '新增关系' }}</h2>
        <form class="relation-form" @submit.prevent="save">
          <label>源节点 ID<input v-model="form.sourceId" required maxlength="128" /></label>
          <label>目标节点 ID<input v-model="form.targetId" required maxlength="128" /></label>
          <label
            >关系类型<input v-model="form.relationType" required maxlength="64" placeholder="related_to"
          /></label>
          <div class="form-actions">
            <button type="submit" class="button primary" :disabled="saving">
              {{ saving ? '保存中…' : '保存' }}
            </button>
            <button v-if="editingId" type="button" class="button secondary" @click="resetForm">
              取消编辑
            </button>
          </div>
        </form>
      </section>

      <section class="graph-admin-card">
        <div class="card-heading">
          <h2>关系列表</h2>
          <span>{{ relations.length }} 条</span>
        </div>
        <div class="relation-filters">
          <input v-model="sourceId" aria-label="按源节点筛选" placeholder="源节点 ID" @keyup.enter="load" />
          <input
            v-model="targetId"
            aria-label="按目标节点筛选"
            placeholder="目标节点 ID"
            @keyup.enter="load"
          />
          <button type="button" class="button secondary" @click="load">筛选</button>
        </div>
        <p v-if="loading" role="status">正在加载关系…</p>
        <div v-else class="relation-table-wrap">
          <table class="relation-table">
            <caption class="sr-only">
              显式图谱关系
            </caption>
            <thead>
              <tr>
                <th scope="col">关系</th>
                <th scope="col">来源</th>
                <th scope="col">版本</th>
                <th scope="col">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="relation in relations" :key="relation.id">
                <th scope="row">
                  {{ relation.sourceId }} → {{ relation.targetId }}<small>{{ relation.relationType }}</small>
                </th>
                <td>{{ relation.origin }} / {{ relation.createdBy }}</td>
                <td>{{ relation.version }}</td>
                <td class="row-actions">
                  <button type="button" @click="editRelation(relation)">编辑</button>
                  <button type="button" @click="showAudit(relation)">审计</button>
                  <button type="button" @click="remove(relation)">删除</button>
                </td>
              </tr>
              <tr v-if="!relations.length">
                <td colspan="4">暂无显式关系</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section class="graph-admin-card">
        <h2>导入预览</h2>
        <p class="admin-help">只解析和报告冲突，不会直接写入关系；提交仍需逐条经过 GraphService 校验。</p>
        <textarea v-model="importPayload" aria-label="关系导入 JSON" rows="8" spellcheck="false" />
        <button type="button" class="button secondary" @click="previewImport">预览冲突</button>
        <div v-if="importPreview" class="import-preview" role="status">
          <p>
            schema {{ importPreview.schemaVersion }}：可导入 {{ importPreview.acceptedCount }} 条，冲突
            {{ importPreview.conflictCount }} 条。
          </p>
          <ul>
            <li v-for="conflict in importPreview.conflicts" :key="conflict">{{ conflict }}</li>
          </ul>
        </div>
      </section>

      <section v-if="audit.length" class="graph-admin-card">
        <h2>审计记录</h2>
        <ol class="audit-list">
          <li v-for="item in audit" :key="item.id">
            {{ item.action }} · {{ item.actor }} · {{ item.createdAt }}
          </li>
        </ol>
      </section>
    </main>
  </section>
</template>

<style scoped>
.graph-admin-page .admin-main {
  max-width: 1280px;
}
.graph-admin-card {
  margin-bottom: 18px;
  padding: 20px;
  border: 1px solid var(--line);
  border-radius: 14px;
  background: var(--surface);
}
.graph-admin-card h2 {
  margin: 0 0 14px;
  font-size: 18px;
}
.relation-form,
.relation-filters {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: end;
}
.relation-form label {
  display: grid;
  gap: 5px;
  min-width: 190px;
  font-size: 12px;
  color: var(--muted);
}
.relation-form input,
.relation-filters input,
textarea {
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 9px 10px;
  background: var(--surface-solid, #fff);
  color: var(--ink);
}
.relation-filters {
  margin-bottom: 14px;
}
.card-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.card-heading span {
  color: var(--muted);
  font-size: 12px;
}
.form-actions {
  display: flex;
  gap: 8px;
}
.relation-table-wrap {
  overflow-x: auto;
}
.relation-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.relation-table th,
.relation-table td {
  padding: 10px;
  border-bottom: 1px solid var(--line);
  text-align: left;
  vertical-align: top;
}
.relation-table th small {
  display: block;
  margin-top: 4px;
  color: var(--muted);
  font-weight: 400;
}
.row-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.row-actions button {
  border: 0;
  background: none;
  color: var(--accent, #be3455);
  cursor: pointer;
  text-decoration: underline;
}
textarea {
  width: 100%;
  box-sizing: border-box;
  margin-bottom: 10px;
  font:
    12px ui-monospace,
    monospace;
}
.admin-help {
  color: var(--muted);
  font-size: 13px;
}
.import-preview {
  margin-top: 12px;
  padding: 10px;
  border-radius: 8px;
  background: color-mix(in srgb, #2f7d4f 9%, var(--surface));
}
.audit-list {
  display: grid;
  gap: 8px;
  padding-left: 20px;
  color: var(--muted);
  font-size: 13px;
}
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}
</style>
