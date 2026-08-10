import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import {
  AI_PROVIDERS_CHANGED_EVENT,
  fetchAiProviders,
  type AiProvider,
  type AiReasoningSelection,
} from '../api/admin';

/**
 * AI 对话的单一事实来源：全屏聊天页 / 宠物面板 / 供应商页共享同一份
 * providerId + model 选择，任一处切换立即同步到其余两处。
 * - 选择持久化到 localStorage（跨标签页共享、刷新/重开浏览器不丢）；
 *   配合 storage 事件，多个标签页同时打开时也实时同步。
 * - 每次刷新注册表时校验选择仍有效：供应商被停用/删除或模型被移除时，
 *   自动回退到默认供应商/默认模型，绝不携带过期选择请求后端（后端会以
 *   400「模型不在该供应商的允许列表中」拒绝）。
 */
const SELECTION_KEY = 'yubai-admin-ai-selection';

interface StoredSelection {
  providerId: number | null;
  model: string | null;
  reasoningEffort?: AiReasoningSelection;
}

const DEFAULT_REASONING_EFFORT: AiReasoningSelection = 'auto';
const REASONING_EFFORTS = new Set<AiReasoningSelection>([
  'auto',
  'none',
  'minimal',
  'low',
  'medium',
  'high',
  'xhigh',
]);

function normalizeReasoningEffort(value: unknown): AiReasoningSelection {
  return typeof value === 'string' && REASONING_EFFORTS.has(value as AiReasoningSelection)
    ? (value as AiReasoningSelection)
    : DEFAULT_REASONING_EFFORT;
}

function readStoredSelection(): StoredSelection | null {
  if (typeof window === 'undefined') return null;
  try {
    const raw = window.localStorage?.getItem(SELECTION_KEY);
    if (!raw) return null;
    const parsed: unknown = JSON.parse(raw);
    if (
      parsed &&
      typeof parsed === 'object' &&
      'providerId' in (parsed as StoredSelection) &&
      'model' in (parsed as StoredSelection)
    ) {
      const selection = parsed as StoredSelection;
      return {
        providerId: selection.providerId,
        model: selection.model,
        reasoningEffort: normalizeReasoningEffort(selection.reasoningEffort),
      };
    }
  } catch {
    // 隐私模式或脏数据：忽略
  }
  return null;
}

function writeStoredSelection(selection: StoredSelection) {
  if (typeof window === 'undefined') return;
  try {
    window.localStorage?.setItem(SELECTION_KEY, JSON.stringify(selection));
  } catch {
    // 隐私模式：仅本次内存状态生效
  }
}

function clearStoredSelection() {
  if (typeof window === 'undefined') return;
  try {
    window.localStorage?.removeItem(SELECTION_KEY);
  } catch {
    // ignore
  }
}

export const useAiStore = defineStore('ai', () => {
  const providers = ref<AiProvider[]>([]);
  const selectedProviderId = ref<number | null>(null);
  const selectedModel = ref<string | null>(null);
  const selectedReasoningEffort = ref<AiReasoningSelection>(DEFAULT_REASONING_EFFORT);
  let requestId = 0;
  let subscriberCount = 0;

  const selectedProvider = computed(
    () => providers.value.find((provider) => provider.id === selectedProviderId.value) ?? null,
  );

  /** model 选项始终包含 defaultModel，即使它未出现在 models 数组中。 */
  const modelOptions = computed(() => {
    const provider = selectedProvider.value;
    if (!provider) return [];
    const models = [...(provider.models ?? [])];
    if (provider.defaultModel && !models.includes(provider.defaultModel)) {
      models.unshift(provider.defaultModel);
    }
    return models;
  });

  const reasoningSupported = computed(() => {
    const provider = selectedProvider.value;
    if (!provider || !provider.providerType) return true;
    if (provider.providerType === 'OPENCODE_SERVER') return false;
    if (provider.providerType === 'OPENAI_COMPATIBLE' && provider.baseUrl.toLowerCase().includes('deepseek'))
      return false;
    return (
      provider.providerType === 'OPENAI_RESPONSES' ||
      provider.providerType === 'OPENAI_COMPATIBLE' ||
      provider.providerType === 'ANTHROPIC'
    );
  });

  function fallbackSelection(): StoredSelection {
    const preferred = providers.value.find((provider) => provider.isDefault) ?? providers.value[0] ?? null;
    return {
      providerId: preferred?.id ?? null,
      model: preferred?.defaultModel || preferred?.models?.[0] || null,
      reasoningEffort: selectedReasoningEffort.value,
    };
  }

  /** 应用（校验过的）选择；选择无效或缺失时回退默认，只有用户主动选择才持久化。 */
  function applySelection(selection: StoredSelection | null) {
    const provider =
      selection?.providerId != null
        ? (providers.value.find((p) => p.id === selection.providerId) ?? null)
        : null;
    if (!provider) {
      const fallback = fallbackSelection();
      selectedProviderId.value = fallback.providerId;
      selectedModel.value = fallback.model;
      selectedReasoningEffort.value = normalizeReasoningEffort(selection?.reasoningEffort);
      if (fallback.providerId == null) clearStoredSelection();
      return;
    }
    const models = [...(provider.models ?? [])];
    if (provider.defaultModel && !models.includes(provider.defaultModel)) {
      models.unshift(provider.defaultModel);
    }
    selectedProviderId.value = provider.id;
    const valid = selection?.model != null && models.includes(selection.model);
    selectedModel.value = valid ? selection!.model! : provider.defaultModel || provider.models?.[0] || null;
    selectedReasoningEffort.value = normalizeReasoningEffort(selection?.reasoningEffort);
    if (valid) {
      writeStoredSelection({
        providerId: provider.id,
        model: selection!.model!,
        reasoningEffort: selectedReasoningEffort.value,
      });
    }
  }

  async function ensureProviders() {
    const myRequestId = ++requestId;
    // 同步清空旧选择：注册表响应到达前，调用方（聊天）不发显式 provider/model，
    // 由后端解析当前默认，避免提交过期选择。
    providers.value = [];
    selectedProviderId.value = null;
    selectedModel.value = null;
    try {
      const loaded = (await fetchAiProviders()).filter((provider) => provider.enabled);
      if (myRequestId !== requestId) return;
      providers.value = loaded;
      applySelection(readStoredSelection());
    } catch {
      if (myRequestId !== requestId) return;
      providers.value = [];
      selectedProviderId.value = null;
      selectedModel.value = null;
    }
  }

  function selectProvider(raw: number | string | null) {
    const id = raw === '' || raw == null ? null : Number(raw);
    const provider = id != null ? (providers.value.find((p) => p.id === id) ?? null) : null;
    if (!provider) return;
    selectedProviderId.value = provider.id;
    selectedModel.value = provider.defaultModel || provider.models?.[0] || null;
    writeStoredSelection({
      providerId: provider.id,
      model: selectedModel.value,
      reasoningEffort: selectedReasoningEffort.value,
    });
  }

  function selectModel(model: string) {
    if (!modelOptions.value.includes(model)) return;
    selectedModel.value = model;
    writeStoredSelection({
      providerId: selectedProviderId.value,
      model,
      reasoningEffort: selectedReasoningEffort.value,
    });
  }

  function selectReasoningEffort(value: AiReasoningSelection) {
    const effort = normalizeReasoningEffort(value);
    selectedReasoningEffort.value = effort;
    writeStoredSelection({
      providerId: selectedProviderId.value,
      model: selectedModel.value,
      reasoningEffort: effort,
    });
  }

  function onProvidersChanged() {
    void ensureProviders();
  }

  /** 其他标签页写入选择时（storage 事件）实时应用，跨标签页保持同步。 */
  function onStorageChanged(event: StorageEvent) {
    if (event.key !== SELECTION_KEY) return;
    applySelection(readStoredSelection());
  }

  /** 注册表变更事件：首个订阅者挂监听，全部退订后移除，避免重复请求。 */
  function subscribe() {
    subscriberCount += 1;
    if (subscriberCount === 1) {
      window.addEventListener(AI_PROVIDERS_CHANGED_EVENT, onProvidersChanged);
      window.addEventListener('storage', onStorageChanged);
    }
  }

  function unsubscribe() {
    subscriberCount = Math.max(0, subscriberCount - 1);
    if (subscriberCount === 0) {
      window.removeEventListener(AI_PROVIDERS_CHANGED_EVENT, onProvidersChanged);
      window.removeEventListener('storage', onStorageChanged);
    }
  }

  return {
    providers,
    selectedProviderId,
    selectedModel,
    selectedReasoningEffort,
    selectedProvider,
    modelOptions,
    reasoningSupported,
    ensureProviders,
    selectProvider,
    selectModel,
    selectReasoningEffort,
    subscribe,
    unsubscribe,
  };
});
