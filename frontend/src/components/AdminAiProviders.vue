<script setup lang="ts">
import axios from 'axios'
import { onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  logout as apiLogout, createAiProvider, deleteAiProvider, fetchAiProviders,
  hasValidAdminSession, notifyAiProvidersChanged, setDefaultAiProvider, testAiProvider, updateAiProvider,
  type AiProvider, type AiProviderPayload, type AiProviderTestResult, type AiProviderType,
} from '../api/admin'
import { useAiStore } from '../stores/aiStore'
import AdminSidebar from './AdminSidebar.vue'

const router = useRouter()
const ai = useAiStore()

const providers = ref<AiProvider[]>([])
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const editorOpen = ref(false)
const editingId = ref<number | null>(null)
const editingKeyTail = ref<string | null>(null)
/** 正在执行行内动作（测试/启停/设默认/删除）的供应商 id，防止重复点击 */
const busyId = ref<number | null>(null)
const testingId = ref<number | null>(null)
const testResults = reactive<Record<number, AiProviderTestResult>>({})

// 密钥只写不回显：表单中的 apiKey 永远从空串开始，保存或关闭后立即清空。
const form = reactive({
  name: '',
  baseUrl: '',
  providerType: 'OPENAI_COMPATIBLE' as AiProviderType,
  apiKey: '',
  models: '',
  defaultModel: '',
  enabled: true,
  dailyRequestLimit: 200,
  dailyTokenLimit: 200000,
})

watch(() => form.providerType, () => { form.apiKey = '' })

function isOpenCodeType(providerType: AiProviderType) {
  return providerType === 'OPENCODE_SERVER'
}

function providerProtocolLabel(providerType: AiProviderType) {
  if (providerType === 'OPENCODE_SERVER') return 'OpenCode'
  if (providerType === 'ANTHROPIC') return 'Anthropic'
  if (providerType === 'OPENAI_RESPONSES') return 'Responses'
  return 'OpenAI'
}

function providerProtocolTitle(providerType: AiProviderType) {
  if (providerType === 'OPENCODE_SERVER') return 'OpenCode Server 协议'
  if (providerType === 'ANTHROPIC') return 'Anthropic Messages API'
  if (providerType === 'OPENAI_RESPONSES') return 'OpenAI Responses API'
  return 'OpenAI 兼容协议'
}

function baseUrlPlaceholder(providerType: AiProviderType) {
  if (providerType === 'OPENCODE_SERVER') return 'http://127.0.0.1:4096'
  if (providerType === 'ANTHROPIC') return 'https://api.anthropic.com'
  if (providerType === 'OPENAI_RESPONSES') return 'https://xinyue.mom'
  return 'https://api.deepseek.com'
}

function handleAuthError(cause: unknown) {
  if (axios.isAxiosError(cause) && cause.response?.status === 401) {
    logout()
    return true
  }
  return false
}

function apiErrorMessage(cause: unknown, fallback: string) {
  if (axios.isAxiosError(cause)) {
    const message = (cause.response?.data as { message?: unknown } | undefined)?.message
    if (typeof message === 'string' && message.trim()) return message
  }
  return fallback
}

function logout() {
  apiLogout()
  void router.replace('/admin/login')
}

async function load() {
  if (!hasValidAdminSession()) return logout()
  loading.value = true
  error.value = ''
  try {
    providers.value = await fetchAiProviders()
  } catch (cause) {
    if (!handleAuthError(cause)) error.value = '暂时无法读取供应商列表，请确认后端服务正在运行。'
  } finally {
    loading.value = false
  }
}

function keyDisplay(provider: AiProvider) {
  if (provider.providerType === 'OPENCODE_SERVER') return '凭据来自环境变量'
  if (!provider.hasKey) return '未设置密钥'
  return provider.keyTail ? `密钥 ····${provider.keyTail}` : '密钥已设置'
}

function newProvider() {
  editingId.value = null
  editingKeyTail.value = null
  error.value = ''
  Object.assign(form, {
    name: '', baseUrl: '', providerType: 'OPENAI_COMPATIBLE' as AiProviderType, apiKey: '', models: '', defaultModel: '',
    enabled: true, dailyRequestLimit: 200, dailyTokenLimit: 200000,
  })
  editorOpen.value = true
}

function editProvider(provider: AiProvider) {
  editingId.value = provider.id
  editingKeyTail.value = provider.hasKey ? provider.keyTail : null
  error.value = ''
  Object.assign(form, {
    name: provider.name,
    baseUrl: provider.baseUrl,
    providerType: provider.providerType,
    apiKey: '',
    models: provider.models.join('\n'),
    defaultModel: provider.defaultModel,
    enabled: provider.enabled,
    dailyRequestLimit: provider.dailyRequestLimit,
    dailyTokenLimit: provider.dailyTokenLimit,
  })
  editorOpen.value = true
}

// 保存在途时禁止关闭：否则迟到的响应会关闭（或把错误显示到）用户随后新开的抽屉
function closeEditor() {
  if (saving.value) return
  editorOpen.value = false
  form.apiKey = ''
  error.value = ''
}

function parsedModels() {
  return form.models.split(/[\n,]/).map((item) => item.trim()).filter(Boolean)
}

function providerPayload(): AiProviderPayload {
  const apiKey = form.apiKey.trim()
  const isOpenCode = isOpenCodeType(form.providerType)
  return {
    name: form.name.trim(),
    baseUrl: form.baseUrl.trim(),
    providerType: form.providerType,
    // OPENCODE_SERVER 永不携带密钥（来自服务端环境变量）；其他类型留空即保留/无密钥
    ...(!isOpenCode && apiKey ? { apiKey } : {}),
    models: parsedModels(),
    defaultModel: form.defaultModel.trim(),
    enabled: form.enabled,
    dailyRequestLimit: form.dailyRequestLimit,
    dailyTokenLimit: form.dailyTokenLimit,
  }
}

async function save() {
  saving.value = true
  error.value = ''
  try {
    const id = editingId.value
    if (id) {
      await updateAiProvider(id, providerPayload())
      // 配置已变，旧的连通测试结论对新配置不再成立
      delete testResults[id]
    } else {
      await createAiProvider(providerPayload())
    }
    notifyAiProvidersChanged()
    saving.value = false
    closeEditor()
    await load()
  } catch (cause) {
    if (!handleAuthError(cause)) {
      error.value = apiErrorMessage(cause, isOpenCodeType(form.providerType)
        ? '保存失败，请检查必填项和字段格式（base_url 需为环回地址根路径）。'
        : '保存失败，请检查必填项和字段格式（base_url 需为 https 且非内网地址）。')
    }
  } finally {
    saving.value = false
  }
}

async function toggleEnabled(provider: AiProvider) {
  if (busyId.value !== null) return
  busyId.value = provider.id
  error.value = ''
  try {
    // 不携带 apiKey → 后端保留原密钥；仅翻转启用状态
    await updateAiProvider(provider.id, {
      name: provider.name,
      baseUrl: provider.baseUrl,
      providerType: provider.providerType,
      models: provider.models,
      defaultModel: provider.defaultModel,
      enabled: !provider.enabled,
      dailyRequestLimit: provider.dailyRequestLimit,
      dailyTokenLimit: provider.dailyTokenLimit,
    })
    notifyAiProvidersChanged()
    await load()
  } catch (cause) {
    if (!handleAuthError(cause)) error.value = apiErrorMessage(cause, '切换启用状态失败，请稍后重试。')
  } finally {
    busyId.value = null
  }
}

async function makeDefault(provider: AiProvider) {
  if (busyId.value !== null) return
  busyId.value = provider.id
  error.value = ''
  try {
    await setDefaultAiProvider(provider.id)
    notifyAiProvidersChanged()
    await load()
  } catch (cause) {
    if (!handleAuthError(cause)) error.value = apiErrorMessage(cause, '设为默认失败，请稍后重试。')
  } finally {
    busyId.value = null
  }
}

async function runTest(provider: AiProvider) {
  if (testingId.value !== null) return
  testingId.value = provider.id
  delete testResults[provider.id]
  try {
    testResults[provider.id] = await testAiProvider(provider.id)
  } catch (cause) {
    if (handleAuthError(cause)) return
    const status = axios.isAxiosError(cause) ? cause.response?.status : undefined
    testResults[provider.id] = {
      ok: false,
      message: status === 429
        ? '测试过于频繁，请稍后再试（每分钟最多 6 次）。'
        : apiErrorMessage(cause, '测试请求失败，请稍后重试。'),
      models: [],
    }
  } finally {
    testingId.value = null
  }
}

async function remove(provider: AiProvider) {
  if (!window.confirm(`确认删除供应商“${provider.name}”？此操作无法撤销。`)) return
  if (busyId.value !== null) return
  busyId.value = provider.id
  error.value = ''
  try {
    await deleteAiProvider(provider.id)
    notifyAiProvidersChanged()
    delete testResults[provider.id]
    await load()
  } catch (cause) {
    if (!handleAuthError(cause)) error.value = apiErrorMessage(cause, '删除失败，请稍后再试。')
  } finally {
    busyId.value = null
  }
}

onMounted(() => {
  void load()
  void ai.ensureProviders()
  ai.subscribe()
})

onBeforeUnmount(() => {
  ai.unsubscribe()
})
</script>

<template>
  <section class="admin-console">
    <AdminSidebar />

    <main class="admin-main">
      <header class="admin-topbar">
        <div>
          <span class="admin-breadcrumb">后台管理 / AI 供应商</span>
          <h1>AI 供应商管理</h1>
        </div>
        <div>
          <RouterLink to="/admin/ai">打开 AI 助手 ↗</RouterLink>
          <button @click="logout">退出登录</button>
        </div>
      </header>

      <section class="admin-content-section provider-section">
        <header>
          <div>
            <span>AI PLATFORM · PROVIDERS</span>
            <h2>供应商注册表</h2>
          </div>
          <p class="provider-hint">密钥加密入库、永不回显；默认供应商用于未显式指定时的对话请求。</p>
          <button class="button primary" type="button" @click="newProvider">＋ 新建供应商</button>
        </header>

        <div v-if="ai.providers.length" class="page-model-switcher">
          <span class="page-model-label">当前对话模型</span>
          <select
            v-if="ai.providers.length > 1"
            class="page-model-select"
            data-testid="page-provider-select"
            aria-label="选择供应商"
            :value="ai.selectedProviderId ?? ''"
            @change="ai.selectProvider(($event.target as HTMLSelectElement).value)"
          >
            <option v-for="p in ai.providers" :key="p.id" :value="p.id">{{ p.name }}</option>
          </select>
          <select
            v-if="ai.modelOptions.length"
            class="page-model-select"
            :value="ai.selectedModel ?? ''"
            aria-label="选择模型"
            data-testid="page-model-select"
            @change="ai.selectModel(($event.target as HTMLSelectElement).value)"
          >
            <option v-if="!ai.modelOptions.length" value="">暂无可用模型</option>
            <option v-for="m in ai.modelOptions" :key="m" :value="m">{{ m }}</option>
          </select>
          <span class="page-model-sync">与 AI 助手、宠物面板实时同步</span>
        </div>

        <p v-if="error" class="admin-error admin-page-error" role="alert">{{ error }}</p>

        <div v-if="loading" class="admin-empty">正在读取供应商列表…</div>
        <div v-else-if="!providers.length" class="admin-empty">
          还没有配置任何 AI 供应商。点击「新建供应商」注册 OpenAI 兼容、Anthropic Claude 或 OpenCode Server 端点。
        </div>
        <div v-else class="admin-table provider-table">
          <div class="admin-table-head"><span>供应商</span><span>模型</span><span>状态</span><span>操作</span></div>
          <template v-for="provider in providers" :key="provider.id">
            <article>
              <div class="provider-cell">
                <small>
                  {{ provider.baseUrl }}
                  <span class="provider-key">{{ keyDisplay(provider) }}</span>
                </small>
                <strong>
                  {{ provider.name }}
                  <span class="provider-protocol-chip" :title="providerProtocolTitle(provider.providerType)">{{ providerProtocolLabel(provider.providerType) }}</span>
                  <em v-if="provider.isDefault" class="provider-default-chip">默认</em>
                </strong>
                <p>日限额 {{ provider.dailyRequestLimit.toLocaleString() }} 次 / {{ provider.dailyTokenLimit.toLocaleString() }} tokens</p>
              </div>
              <div class="provider-cell">
                <small>默认模型</small>
                <strong class="provider-model">{{ provider.defaultModel }}</strong>
                <p>{{ provider.models.length ? `${provider.models.length} 个可用模型` : '未限制模型列表' }}</p>
              </div>
              <button
                type="button"
                class="admin-status provider-toggle"
                :class="{ featured: provider.enabled }"
                :disabled="busyId !== null"
                :title="provider.enabled ? '点击停用' : '点击启用'"
                @click="toggleEnabled(provider)"
              >
                {{ provider.enabled ? '已启用' : '已停用' }}
              </button>
              <div class="admin-row-actions provider-actions">
                <button type="button" :disabled="testingId !== null" @click="runTest(provider)">
                  {{ testingId === provider.id ? '测试中…' : '测试连通' }}
                </button>
                <button type="button" :disabled="busyId !== null" @click="editProvider(provider)">编辑</button>
                <button
                  v-if="!provider.isDefault"
                  type="button"
                  :disabled="busyId !== null || !provider.enabled"
                  :title="provider.enabled ? '设为默认供应商' : '已停用的供应商不能设为默认'"
                  @click="makeDefault(provider)"
                >
                  设为默认
                </button>
                <button type="button" class="danger" :disabled="busyId !== null" @click="remove(provider)">删除</button>
              </div>
            </article>
            <div
              v-if="testResults[provider.id]"
              class="provider-test"
              :class="testResults[provider.id]!.ok ? 'ok' : 'fail'"
              role="status"
            >
              <b>{{ testResults[provider.id]!.ok ? '✓' : '✕' }}</b>
              <span>{{ testResults[provider.id]!.message }}</span>
              <ul v-if="testResults[provider.id]!.models.length" class="provider-model-list">
                <li v-for="model in testResults[provider.id]!.models" :key="model">{{ model }}</li>
              </ul>
            </div>
          </template>
        </div>
      </section>
    </main>

    <div v-if="editorOpen" class="admin-editor-backdrop" @click.self="closeEditor">
      <form class="admin-editor" @submit.prevent="save">
        <header>
          <div>
            <small>{{ editingId ? 'EDIT PROVIDER' : 'NEW PROVIDER' }}</small>
            <h2>{{ editingId ? '编辑' : '新建' }}供应商</h2>
          </div>
          <button type="button" aria-label="关闭编辑器" :disabled="saving" @click="closeEditor">×</button>
        </header>

        <div class="admin-form-grid">
          <label>名称<input v-model="form.name" required maxlength="60" placeholder="deepseek"></label>
          <label>Base URL<input v-model="form.baseUrl" type="url" required maxlength="500" :placeholder="baseUrlPlaceholder(form.providerType)"></label>
        </div>
        <label>协议类型</label>
        <div class="admin-type-group">
          <label class="admin-type-radio">
            <input type="radio" v-model="form.providerType" value="OPENAI_COMPATIBLE">
            <span>OpenAI 兼容</span>
          </label>
          <label class="admin-type-radio">
            <input type="radio" v-model="form.providerType" value="OPENAI_RESPONSES">
            <span>OpenAI Responses</span>
          </label>
          <label class="admin-type-radio">
            <input type="radio" v-model="form.providerType" value="ANTHROPIC">
            <span>Anthropic Claude</span>
          </label>
          <label class="admin-type-radio">
            <input type="radio" v-model="form.providerType" value="OPENCODE_SERVER">
            <span>OpenCode Server（内建）</span>
          </label>
        </div>
        <label v-if="!isOpenCodeType(form.providerType)">
          API 密钥{{ editingId ? '（只写不回显）' : '' }}
          <input
            v-model="form.apiKey"
            type="password"
            maxlength="500"
            autocomplete="new-password"
            :placeholder="editingId
              ? (editingKeyTail ? `留空保留现有密钥（····${editingKeyTail}）` : '留空保留现有配置')
              : '本地无鉴权端点可留空'"
          >
        </label>
        <div v-if="form.providerType === 'OPENAI_RESPONSES'" class="provider-guidance">
          <p>使用 OpenAI Responses API，后端请求 <code>/responses</code>，Base URL 可填 <code>https://xinyue.mom</code>。</p>
          <p>服务器环境会附加配置的自定义请求头；密钥只在服务端加密保存，不会返回浏览器。</p>
        </div>
        <div v-else-if="form.providerType === 'ANTHROPIC'" class="provider-guidance">
          <p>Base URL 填写 <code>https://api.anthropic.com</code>（也支持填写带 <code>/v1</code> 的网关地址）。</p>
          <p>服务端会使用 Anthropic Messages API，并通过 <code>x-api-key</code> 和 <code>anthropic-version: 2023-06-01</code> 发送密钥。</p>
          <p>模型 ID 示例：<code>claude-sonnet-4-20250514</code>；密钥会加密保存，编辑时留空可保留原密钥。</p>
        </div>
        <div v-else-if="isOpenCodeType(form.providerType)" class="provider-guidance">
          <p>Base URL 必须填写本地回环地址根路径，例如 <code>http://127.0.0.1:4096</code>。</p>
          <p>供应商/模型 ID 使用已配置的 OpenCode sidecar。</p>
          <p>凭据来自服务端环境变量 <code>APP_AI_OPENCODE_USERNAME</code> 与 <code>APP_AI_OPENCODE_PASSWORD</code>，不存入数据库。</p>
        </div>
        <label>
          模型列表（每行一个，留空则不限制）
          <textarea v-model="form.models" rows="4" placeholder="deepseek-chat&#10;deepseek-reasoner" />
        </label>
        <div class="admin-form-grid">
          <label>
            默认模型
            <select v-if="parsedModels().length" v-model="form.defaultModel" required data-testid="default-model">
              <option v-for="model in parsedModels()" :key="model" :value="model">{{ model }}</option>
            </select>
            <input
              v-else
              v-model="form.defaultModel"
              required
              maxlength="120"
              placeholder="deepseek-chat"
              data-testid="default-model"
            >
          </label>
          <label class="admin-check"><input v-model="form.enabled" type="checkbox">启用该供应商</label>
          <label>日请求上限<input v-model.number="form.dailyRequestLimit" type="number" min="1" max="100000" required></label>
          <label>日 token 上限<input v-model.number="form.dailyTokenLimit" type="number" min="1000" max="100000000" required></label>
        </div>

        <p v-if="error" class="admin-error" role="alert">{{ error }}</p>
        <footer>
          <button class="button secondary" type="button" :disabled="saving" @click="closeEditor">取消</button>
          <button class="button primary" type="submit" :disabled="saving">{{ saving ? '正在保存…' : '保存供应商 ↗' }}</button>
        </footer>
      </form>
    </div>
  </section>
</template>

<style scoped>
.provider-section > header {
  grid-template-columns: 1fr minmax(0, 1.2fr) auto;
}
.page-model-switcher {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  border: 1px solid var(--line, #d9d6cf);
  border-radius: 10px;
  background: color-mix(in srgb, var(--line, #d9d6cf) 8%, transparent);
  font-size: 13px;
  color: var(--console-muted, #7f7e77);
}
.page-model-label {
  font-weight: 600;
  color: var(--ink, #20211e);
  white-space: nowrap;
}
.page-model-select {
  min-width: 0;
  max-width: 220px;
  padding: 6px 10px;
  border: 1px solid var(--line-strong, #d9d6cf);
  border-radius: 8px;
  background: var(--surface-solid, #ffffff);
  color: var(--ink, #20211e);
  font: 13px/1.5 inherit;
  outline: none;
}
.page-model-select:focus-visible {
  border-color: var(--accent, #d5b18a);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--accent, #d5b18a) 18%, transparent);
}
.page-model-sync {
  margin-left: auto;
  font-size: 12px;
}
.provider-hint {
  margin: 0;
  color: var(--console-muted, #7f7e77);
  font-size: 12px;
  line-height: 1.7;
}

.provider-table .admin-table-head,
.provider-table article {
  grid-template-columns: minmax(0, 1.5fr) minmax(0, 1fr) 92px 250px;
}
.provider-cell {
  min-width: 0;
}
.provider-cell small {
  display: flex;
  gap: 10px;
  align-items: baseline;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.provider-key {
  flex-shrink: 0;
  color: var(--console-muted, #7f7e77);
  font-family: ui-monospace, Consolas, monospace;
  letter-spacing: .06em;
}
.provider-default-chip {
  display: inline-block;
  margin-left: 8px;
  padding: 2px 9px;
  border: 1px solid #a9825f;
  border-radius: 999px;
  color: #9e7553;
  font: 500 10px/1.6 inherit;
  letter-spacing: .08em;
  vertical-align: 3px;
}
.provider-model {
  font-size: 16px !important;
  font-family: ui-monospace, Consolas, monospace !important;
}
.provider-toggle {
  cursor: pointer;
  background: transparent;
  transition: border-color .2s ease, color .2s ease;
}
.provider-toggle:disabled {
  opacity: .5;
  cursor: not-allowed;
}
.provider-actions {
  flex-wrap: wrap;
}

.provider-test {
  display: flex;
  gap: 10px;
  align-items: baseline;
  flex-wrap: wrap;
  padding: 14px 18px;
  border-bottom: 1px solid var(--line, #d9d6cf);
  font-size: 13px;
}
.provider-test.ok {
  background: color-mix(in srgb, #5c7c52 7%, transparent);
  color: #4c6b44;
}
.provider-test.fail {
  background: color-mix(in srgb, #b84f48 7%, transparent);
  color: #b84f48;
}
.provider-test b {
  font-weight: 600;
}
.provider-model-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  width: 100%;
  margin: 4px 0 0;
  padding: 0;
  list-style: none;
}
.provider-model-list li {
  padding: 3px 10px;
  border: 1px solid color-mix(in srgb, currentcolor 35%, transparent);
  border-radius: 999px;
  font: 11px/1.6 ui-monospace, Consolas, monospace;
}

.provider-protocol-chip {
  display: inline-block;
  margin-left: 6px;
  padding: 1px 7px;
  border: 1px solid var(--line, #d9d6cf);
  border-radius: 999px;
  color: var(--console-muted, #7f7e77);
  font: 500 10px/1.6 inherit;
  letter-spacing: .06em;
  vertical-align: 3px;
}
.admin-type-group {
  display: flex;
  gap: 24px;
  margin: 6px 0 14px;
}
.admin-type-radio {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  font-size: 13px;
}
.admin-type-radio input {
  width: auto;
  margin: 0;
}
.provider-guidance {
  margin: 6px 0 14px;
  padding: 12px 16px;
  border-radius: 6px;
  background: color-mix(in srgb, var(--line, #d9d6cf) 15%, transparent);
  font-size: 13px;
  line-height: 1.7;
}
.provider-guidance code {
  font-family: ui-monospace, Consolas, monospace;
  background: color-mix(in srgb, var(--line, #d9d6cf) 25%, transparent);
  padding: 1px 5px;
  border-radius: 3px;
  font-size: 12px;
}
.provider-guidance p {
  margin: 0 0 4px;
}
.provider-guidance p:last-child {
  margin-bottom: 0;
}

@media (max-width: 1080px) {
  .provider-table .admin-table-head {
    display: none;
  }
  .provider-table article {
    grid-template-columns: minmax(0, 1fr);
    gap: 12px;
  }
  .provider-toggle {
    justify-self: start;
  }
  .provider-section > header {
    grid-template-columns: 1fr auto;
  }
  .provider-hint {
    display: none;
  }
}
</style>
