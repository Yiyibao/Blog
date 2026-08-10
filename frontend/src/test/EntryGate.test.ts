import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';
import { createRouter, createMemoryHistory, type Router } from 'vue-router';
import { createPinia, setActivePinia } from 'pinia';
import EntryGate from '../components/EntryGate.vue';
import router from '../router/index';
import { useAuthStore } from '../stores/auth';

enableAutoUnmount(afterEach);

function createTestRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div>Home</div>' } },
      { path: '/articles', name: 'articles', component: { template: '<div>Articles</div>' } },
      { path: '/login', name: 'login', component: { template: '<div>Login</div>' } },
      { path: '/admin/login', name: 'admin-login', component: { template: '<div>Admin Login</div>' } },
    ],
  });
}

async function mountGate(url = '/') {
  const testRouter = createTestRouter();
  await testRouter.push(url);
  await testRouter.isReady();
  const wrapper = mount(EntryGate, { global: { plugins: [testRouter] } });
  await flushPromises();
  return { wrapper, testRouter };
}

function gateEl(): HTMLElement | null {
  return document.body.querySelector('.entry-gate');
}

beforeEach(() => {
  document.body.innerHTML = '';
  localStorage.clear();
  sessionStorage.clear();
  setActivePinia(createPinia());
  useAuthStore().clearSession();
});

describe('L-16/D-18 入口大屏', () => {
  it('根路径首访且未登录时出现', async () => {
    await mountGate('/');
    expect(gateEl()).toBeTruthy();
    expect(document.body.textContent).toContain('以游客身份进入');
    expect(document.body.textContent).toContain('以管理员身份进入');
  });

  it('深链直达不被拦截（D-18）', async () => {
    await mountGate('/articles');
    expect(gateEl()).toBeFalsy();
  });

  it('已有既往选择时不再出现（localStorage 记忆）', async () => {
    localStorage.setItem('yubai-entry-choice', 'guest');
    await mountGate('/');
    expect(gateEl()).toBeFalsy();
  });

  it('已登录用户永不拦截', async () => {
    useAuthStore().saveSession({
      token: 't',
      tokenType: 'Bearer',
      username: 'gxynf',
      expiresAt: '2099-12-31T23:59:59Z',
      role: 'ADMIN',
      displayName: '站长',
    });
    await mountGate('/');
    expect(gateEl()).toBeFalsy();
  });

  it('选游客：关闭入口并写入记忆', async () => {
    await mountGate('/');
    document.body.querySelector<HTMLButtonElement>('.entry-choice.guest')!.click();
    await flushPromises();
    expect(gateEl()).toBeFalsy();
    expect(localStorage.getItem('yubai-entry-choice')).toBe('guest');
  });

  it('选管理员：记忆选择并跳登录页带回家来路', async () => {
    const { testRouter } = await mountGate('/');
    document.body.querySelector<HTMLButtonElement>('.entry-choice.admin')!.click();
    await flushPromises();
    expect(localStorage.getItem('yubai-entry-choice')).toBe('admin');
    expect(testRouter.currentRoute.value.path).toBe('/admin/login');
    expect(testRouter.currentRoute.value.query).toEqual({});
  });
});

describe('L-16 角色化路由 meta', () => {
  it('/notes 需登录（游客深链会被守卫送去 /login 接续）', () => {
    const notesRoute = router.getRoutes().find((r) => r.path === '/notes');
    expect(notesRoute?.meta?.requiresAuth).toBe(true);
    expect(notesRoute?.meta?.capability).toBe('account:access');
  });
});
