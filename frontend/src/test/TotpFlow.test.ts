import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createRouter, createMemoryHistory, type Router } from 'vue-router';
import { createPinia, setActivePinia } from 'pinia';
import LoginPage from '../pages/LoginPage.vue';
import { useAuthStore } from '../stores/auth';

const mockLogin = vi.fn();
const mockFetchChallenge = vi.fn();
const mockVerifyTotp = vi.fn();

vi.mock('../api/admin', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/admin')>();
  return {
    ...actual,
    login: (...args: unknown[]) => mockLogin(...args),
    fetchLoginChallenge: (...args: unknown[]) => mockFetchChallenge(...args),
    verifyTotp: (...args: unknown[]) => mockVerifyTotp(...args),
  };
});

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

function loginResult(role: string, overrides: Record<string, unknown> = {}) {
  return {
    token: 'fresh-token',
    tokenType: 'Bearer',
    username: role === 'PARTNER' ? 'gf' : 'gxynf',
    expiresAt: '2099-12-31T23:59:59Z',
    role,
    displayName: role === 'PARTNER' ? '小伙伴' : '站长',
    ...overrides,
  };
}

function createTestRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/login', name: 'login', component: LoginPage },
      { path: '/admin/login', name: 'admin-login', component: { template: '<div>Login</div>' } },
      { path: '/admin', name: 'admin', component: { template: '<div>Admin</div>' } },
      { path: '/recipes', name: 'recipes', component: { template: '<div>Recipes</div>' } },
    ],
  });
}

async function mountLoginPage() {
  const router = createTestRouter();
  await router.push('/login');
  await router.isReady();
  const wrapper = mount(LoginPage, { global: { plugins: [router] } });
  await wrapper.find('input[autocomplete="username"]').setValue('gxynf');
  await wrapper.find('input[type="password"]').setValue('secret123');
  return { wrapper, router };
}

async function submitWithChallenge(wrapper: ReturnType<typeof mount>) {
  await wrapper.find('form').trigger('submit.prevent');
  await flushPromises();
  const startBtn = document.body.querySelector<HTMLButtonElement>('.verify-start');
  if (startBtn) {
    startBtn.click();
    await flushPromises();
    vi.advanceTimersByTime(700);
    await flushPromises();
  }
}

function totpModalEl(): HTMLElement | null {
  return document.body.querySelector('.totp-modal');
}

beforeEach(() => {
  vi.useFakeTimers();
  document.body.innerHTML = '';
  sessionStorage.clear();
  localStorage.clear();
  setActivePinia(createPinia());
  useAuthStore().clearSession();
  mockLogin.mockReset();
  mockFetchChallenge.mockReset();
  mockVerifyTotp.mockReset();
  mockFetchChallenge.mockResolvedValue(POW_CHALLENGE);
});

afterEach(() => {
  vi.useRealTimers();
});

describe('6C-3 TOTP login flow', () => {
  it('login with 202 TOTP challenge opens TOTP modal', async () => {
    mockLogin.mockResolvedValueOnce({ totpRequired: true, challengeId: 'challenge-token-123' });
    const { wrapper } = await mountLoginPage();
    await submitWithChallenge(wrapper);
    expect(totpModalEl()).toBeTruthy();
    expect(document.body.textContent).toContain('输入身份验证器中的验证码');
  });

  it('TOTP verify success saves session and redirects', async () => {
    mockLogin.mockResolvedValueOnce({ totpRequired: true, challengeId: 'ch-totp-1' });
    mockVerifyTotp.mockResolvedValue(loginResult('ADMIN'));
    const { wrapper, router } = await mountLoginPage();
    await submitWithChallenge(wrapper);
    expect(totpModalEl()).toBeTruthy();
    // Type each digit individually so v-model picks up the changes
    const input = document.body.querySelector<HTMLInputElement>('.totp-input-row input')!;
    for (const ch of '123456') {
      input.focus();
      input.value += ch;
      input.dispatchEvent(new InputEvent('input', { bubbles: true, cancelable: true }));
    }
    await flushPromises();
    expect(mockVerifyTotp).toHaveBeenCalledWith('ch-totp-1', '123456');
    expect(useAuthStore().isAuthenticated).toBe(true);
    expect(router.currentRoute.value.path).toBe('/admin');
  });

  it('TOTP verify failure shows error', async () => {
    mockLogin.mockResolvedValueOnce({ totpRequired: true, challengeId: 'ch-totp-2' });
    mockVerifyTotp.mockRejectedValueOnce({
      isAxiosError: true,
      response: { status: 401, data: { message: '验证码不正确' } },
    });
    const { wrapper } = await mountLoginPage();
    await submitWithChallenge(wrapper);
    const input = document.body.querySelector<HTMLInputElement>('.totp-input-row input');
    input!.value = '000000';
    input!.dispatchEvent(new Event('input', { bubbles: true }));
    await flushPromises();
    expect(document.body.querySelector('.totp-error')?.textContent).toContain('验证码不正确');
    expect(totpModalEl()).toBeTruthy();
  });

  it('cancel TOTP returns to login form', async () => {
    mockLogin.mockResolvedValueOnce({ totpRequired: true, challengeId: 'ch-totp-3' });
    const { wrapper } = await mountLoginPage();
    await submitWithChallenge(wrapper);
    expect(totpModalEl()).toBeTruthy();
    const cancelBtn = document.body.querySelector<HTMLButtonElement>('.totp-close');
    cancelBtn!.click();
    await flushPromises();
    expect(totpModalEl()).toBeFalsy();
  });

  it('non-TOTP login flow unchanged (200 response)', async () => {
    mockLogin.mockResolvedValue(loginResult('ADMIN'));
    const { wrapper, router } = await mountLoginPage();
    await submitWithChallenge(wrapper);
    expect(totpModalEl()).toBeFalsy();
    expect(router.currentRoute.value.path).toBe('/admin');
    expect(useAuthStore().isAuthenticated).toBe(true);
  });
});
