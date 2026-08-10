import { describe, it, expect, beforeEach, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createRouter, createMemoryHistory, type Router } from 'vue-router';
import { createPinia, setActivePinia } from 'pinia';
import AdminLogin from '../components/AdminLogin.vue';
import { clearAdminSession, getAdminSessionName, hasValidAdminSession, saveAdminSession } from '../api/admin';
import { useAuthStore } from '../stores/auth';
import { Capabilities, type Capability } from '../utils/capabilities';

const mockLogin = vi.fn();
const mockFetchChallenge = vi.fn();
const mockRefreshSession = vi.fn();

vi.mock('../api/admin', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/admin')>();
  return {
    ...actual,
    login: (...args: unknown[]) => mockLogin(...args),
    fetchLoginChallenge: (...args: unknown[]) => mockFetchChallenge(...args),
    refreshSession: (...args: unknown[]) => mockRefreshSession(...args),
  };
});

// L-7：登录组件提交前会先解 PoW，测试环境直接给定 nonce
vi.mock('../utils/pow', () => ({
  solvePow: vi.fn().mockResolvedValue('42'),
}));

const POW_CHALLENGE = {
  challengeId: 'ch-1',
  type: 'POW' as const,
  salt: 'abcd',
  difficulty: 1,
  captchaImage: null,
};

const LOGIN_RESULT = {
  token: 'fresh-token',
  tokenType: 'Bearer',
  username: 'gxynf',
  expiresAt: '2099-12-31T23:59:59Z',
  // FD-8：真实后端自 FD-6 起返回角色；无角色的会话会被启动清理（见 authRole.test.ts）
  role: 'ADMIN',
  displayName: '站长',
};

/** 复刻 src/router/index.ts 的守卫逻辑（守卫读 useAuthStore；FD-8 起 requiresAuth+capability；6C-1 加 refreshSession）。 */
function createGuardedRouter(): Router {
  const r = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div>Home</div>' } },
      { path: '/login', name: 'login', component: { template: '<div>Login</div>' } },
      { path: '/admin/login', name: 'admin-login', component: { template: '<div>AdminLogin</div>' } },
      {
        path: '/admin',
        name: 'admin',
        component: { template: '<div>Dashboard</div>' },
        meta: { requiresAuth: true, capability: Capabilities.CONTENT_MANAGE },
      },
      { path: '/recipes', name: 'recipes', component: { template: '<div>Recipes</div>' } },
    ],
  });
  r.beforeEach(async (to, _from, next) => {
    if (!to.meta.requiresAuth) {
      next();
      return;
    }
    const auth = useAuthStore();
    if (!auth.isAuthenticated) {
      const ok = await mockRefreshSession();
      if (!ok) {
        next({ name: 'login', query: { next: to.fullPath } });
        return;
      }
    }
    const required = to.meta.capability as Capability | undefined;
    if (required && !auth.can(required)) {
      next({ path: '/recipes' });
      return;
    }
    next();
  });
  return r;
}

function startUnauthenticated() {
  sessionStorage.clear();
  setActivePinia(createPinia());
}

beforeEach(() => {
  mockLogin.mockReset();
  mockFetchChallenge.mockReset();
  mockFetchChallenge.mockResolvedValue(POW_CHALLENGE);
  mockRefreshSession.mockReset();
  mockRefreshSession.mockResolvedValue(false);
});

describe('NF-1 管理端登录态单一事实源', () => {
  it('saveAdminSession 直接写入 authStore，守卫立即可见', () => {
    startUnauthenticated();
    const auth = useAuthStore();
    expect(auth.isAuthenticated).toBe(false);

    saveAdminSession(LOGIN_RESULT);

    expect(auth.isAuthenticated).toBe(true);
    expect(auth.token).toBe('fresh-token');
    expect(getAdminSessionName()).toBe('gxynf');
    // 持久化写 sessionStorage，刷新后由 refresh cookie 恢复
    expect(sessionStorage.getItem('yubai-admin-token')).toBe('fresh-token');
  });

  it('clearAdminSession 同步清空 store 与 sessionStorage', () => {
    startUnauthenticated();
    saveAdminSession(LOGIN_RESULT);

    clearAdminSession();

    expect(useAuthStore().isAuthenticated).toBe(false);
    expect(hasValidAdminSession()).toBe(false);
    expect(sessionStorage.getItem('yubai-admin-token')).toBeNull();
  });

  it('过期会话视为未登录', () => {
    startUnauthenticated();
    saveAdminSession({ ...LOGIN_RESULT, expiresAt: '2000-01-01T00:00:00Z' });

    expect(hasValidAdminSession()).toBe(false);
    expect(useAuthStore().isAuthenticated).toBe(false);
  });

  it('登录成功后跳转 /admin 不再被守卫弹回（重定向死循环回归测试）', async () => {
    startUnauthenticated();
    const router = createGuardedRouter();

    // 未登录访问 /admin：refreshSession 失败 → 被守卫送去 /login 并带上来路
    await router.push('/admin');
    await router.isReady();
    expect(mockRefreshSession).toHaveBeenCalledTimes(1);
    expect(router.currentRoute.value.name).toBe('login');
    expect(router.currentRoute.value.query.next).toBe('/admin');

    // NF-1 本义：登录流程与守卫读写同一个 authStore——saveAdminSession 后
    // 立刻跳 /admin 必须放行（完整"弹窗验证→登录"UI 链路由 adminLoginChallenge 层 1 用例覆盖）
    saveAdminSession(LOGIN_RESULT);
    await router.replace('/admin');
    expect(router.currentRoute.value.name).toBe('admin');
  });

  it('已登录访问登录页时自动回到 /admin', async () => {
    startUnauthenticated();
    saveAdminSession(LOGIN_RESULT);
    const router = createGuardedRouter();
    await router.push('/admin/login');
    await router.isReady();

    mount(AdminLogin, { global: { plugins: [router] } });
    await flushPromises();

    expect(router.currentRoute.value.name).toBe('admin');
  });
});
