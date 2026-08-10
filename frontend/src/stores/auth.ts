import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import { Capabilities, getCapabilities, type Capability } from '../utils/capabilities';

const memorySession = new Map<string, string>();

function readSessionValue(key: string) {
  try {
    return window.sessionStorage?.getItem(key) ?? memorySession.get(key) ?? null;
  } catch {
    return memorySession.get(key) ?? null;
  }
}

function writeSessionValue(key: string, value: string) {
  memorySession.set(key, value);
  try {
    window.sessionStorage?.setItem(key, value);
  } catch {
    // Some privacy modes disable sessionStorage; keep in memory.
  }
}

function removeSessionValue(key: string) {
  memorySession.delete(key);
  try {
    window.sessionStorage?.removeItem(key);
  } catch {
    // Already cleared from memory.
  }
}

const SESSION_KEYS = [
  'yubai-admin-token',
  'yubai-admin-name',
  'yubai-admin-expiry',
  'yubai-admin-role',
  'yubai-admin-display',
  'yubai-admin-capabilities',
] as const;
const LEGACY_LOCAL_KEYS = [
  'yubai-admin-token',
  'yubai-admin-name',
  'yubai-admin-expiry',
  'yubai-admin-role',
  'yubai-admin-display',
  'yubai-admin-capabilities',
];
const KNOWN_CAPABILITIES = new Set<Capability>(Object.values(Capabilities));

function normalizeCapabilities(values: unknown): Capability[] {
  if (!Array.isArray(values)) return [];
  return [
    ...new Set(
      values.filter(
        (value): value is Capability =>
          typeof value === 'string' && KNOWN_CAPABILITIES.has(value as Capability),
      ),
    ),
  ];
}

function restoreCapabilities(serialized: string | null, role: string | null): Capability[] {
  if (!serialized) return [...getCapabilities(role)];
  try {
    return normalizeCapabilities(JSON.parse(serialized));
  } catch {
    return [...getCapabilities(role)];
  }
}

/** 启动时清理所有遗留 localStorage 密钥。访问令牌仅通过 sessionStorage 暂存，
 * 跨会话恢复依赖 HttpOnly refresh cookie 经 /auth/refresh 端点获取新令牌。 */
function migrateLegacyLocalKeys() {
  try {
    for (const key of LEGACY_LOCAL_KEYS) {
      window.localStorage?.removeItem(key);
    }
  } catch {
    // 隐私模式或 localStorage 不可用，静默忽略
  }
}

export interface LoginResult {
  token: string;
  tokenType: string;
  username: string;
  expiresAt: string;
  // FD-8：可选以兼容旧调用方/夹具；真实后端自 FD-6 起必返
  role?: string;
  displayName?: string;
  capabilities?: string[];
}

export const useAuthStore = defineStore('auth', () => {
  migrateLegacyLocalKeys();
  const token = ref(readSessionValue('yubai-admin-token'));
  const username = ref(readSessionValue('yubai-admin-name'));
  const expiresAt = ref(readSessionValue('yubai-admin-expiry'));
  const role = ref(readSessionValue('yubai-admin-role'));
  const displayName = ref(readSessionValue('yubai-admin-display'));
  const storedCapabilities = readSessionValue('yubai-admin-capabilities');
  const capabilities = ref<Capability[]>(restoreCapabilities(storedCapabilities, role.value));

  // FD-4 generation guard: every saveSession or clearSession advances the generation,
  // so stale async responses (refresh after logout, refresh after manual login) are discarded.
  let generation = 0;
  function getGeneration(): number {
    return generation;
  }
  function isCurrentGeneration(gen: number): boolean {
    return gen === generation;
  }

  const isAuthenticated = computed(() => {
    if (!token.value) return false;
    if (expiresAt.value && Date.parse(expiresAt.value) <= Date.now()) {
      clearSession();
      return false;
    }
    return true;
  });

  // FD-8：fail-closed——role 缺失一律不算 ADMIN；越权判断绝不给未知角色放行
  const isAdmin = computed(() => isAuthenticated.value && role.value === 'ADMIN');
  const isPartner = computed(() => isAuthenticated.value && role.value === 'PARTNER');
  // FD-29：管理角色总开关——ADMIN 与 PARTNER 拥有完全一致的后台能力；
  // isAdmin 继续严格表示角色为 ADMIN（审计、展示标签仍用它）
  const isStaff = computed(
    () => isAuthenticated.value && (role.value === 'ADMIN' || role.value === 'PARTNER'),
  );
  const hasAdminAccess = computed(() => isStaff.value);
  const canKitchen = computed(() => can(Capabilities.KITCHEN_ACCESS));
  function can(capability: Capability): boolean {
    return isAuthenticated.value && capabilities.value.includes(capability);
  }

  function saveSession(result: LoginResult) {
    generation += 1;
    token.value = result.token;
    username.value = result.username;
    expiresAt.value = result.expiresAt;
    role.value = result.role ?? null;
    displayName.value = result.displayName ?? null;
    capabilities.value = result.capabilities
      ? normalizeCapabilities(result.capabilities)
      : [...getCapabilities(result.role)];
    const values: Record<(typeof SESSION_KEYS)[number], string | null> = {
      'yubai-admin-token': result.token,
      'yubai-admin-name': result.username,
      'yubai-admin-expiry': result.expiresAt,
      'yubai-admin-role': result.role ?? null,
      'yubai-admin-display': result.displayName ?? null,
      'yubai-admin-capabilities': capabilities.value.length ? JSON.stringify(capabilities.value) : null,
    };
    for (const key of SESSION_KEYS) {
      const value = values[key];
      if (value) writeSessionValue(key, value);
      else removeSessionValue(key);
    }
  }

  function clearSession() {
    generation += 1;
    token.value = null;
    username.value = null;
    expiresAt.value = null;
    role.value = null;
    displayName.value = null;
    capabilities.value = [];
    for (const key of SESSION_KEYS) {
      removeSessionValue(key);
    }
  }

  // FD-8：FD-6 之前签发的会话没有 role——启动即清，让持有者重登一次拿到带角色的新会话
  if (token.value && !role.value) {
    clearSession();
  }

  return {
    token,
    username,
    expiresAt,
    role,
    displayName,
    capabilities,
    isAuthenticated,
    isAdmin,
    isPartner,
    isStaff,
    hasAdminAccess,
    canKitchen,
    can,
    saveSession,
    clearSession,
    getGeneration,
    isCurrentGeneration,
  };
});
