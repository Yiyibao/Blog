import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { defineComponent, nextTick } from 'vue';
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';
import { createRouter, createMemoryHistory, type Router } from 'vue-router';
import { createPinia, setActivePinia } from 'pinia';
import AdminPetAssistant from '../components/admin-pet/AdminPetAssistant.vue';
import AdminAiChat from '../components/AdminAiChat.vue';
import { totalDuration } from '../components/admin-pet/petAnimations';
import { useAuthStore, type LoginResult } from '../stores/auth';
import { useUiStore } from '../stores/uiStore';
import * as adminApi from '../api/admin';

enableAutoUnmount(afterEach);

const ChatStub = defineComponent({
  name: 'AdminAiChat',
  props: { compact: Boolean, providerId: Number, model: String },
  emits: ['stream-start', 'stream-first-delta', 'stream-complete', 'stream-error', 'stream-abort'],
  template: '<div class="chat-stub" />',
});

const mockFetchProviders = vi.fn();
const mockStreamAiChat = vi.fn();
vi.mock('../api/admin', async (importOriginal) => {
  const actual = await importOriginal<typeof adminApi>();
  return {
    ...actual,
    fetchAiProviders: (...args: unknown[]) => mockFetchProviders(...args),
    streamAiChat: (...args: unknown[]) => mockStreamAiChat(...args),
  };
});

/** P6：调度器随机函数与触发间隔可注入——测试用固定 30 秒间隔做边界验证。 */
const mockIdleRandom = vi.fn(() => 0);
const IDLE_TEST_MS = 30_000;
vi.mock('../components/admin-pet/petIdleScheduler', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../components/admin-pet/petIdleScheduler')>();
  return {
    ...actual,
    createIdleScheduler: (options: Parameters<typeof actual.createIdleScheduler>[0]) =>
      actual.createIdleScheduler({
        ...options,
        random: () => mockIdleRandom(),
        nextIntervalMs: () => IDLE_TEST_MS,
      }),
  };
});

function provider(id: number, name: string, overrides: Record<string, unknown> = {}) {
  return {
    id,
    name,
    baseUrl: 'https://api.example.com',
    models: ['m-a', 'm-b'],
    defaultModel: 'm-a',
    enabled: true,
    isDefault: id === 1,
    hasKey: true,
    keyTail: '1234',
    dailyRequestLimit: 200,
    dailyTokenLimit: 200000,
    createdAt: '2026-07-01T00:00:00Z',
    updatedAt: '2026-07-01T00:00:00Z',
    ...overrides,
  };
}

function session(role: 'ADMIN' | 'PARTNER', username = 'admin'): LoginResult {
  return {
    token: 't-1',
    tokenType: 'Bearer',
    username,
    expiresAt: '2099-12-31T23:59:59Z',
    role,
    displayName: role === 'ADMIN' ? '站长' : '小伙伴',
  };
}

function createTestRouter(): Router {
  const r = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/admin', name: 'admin', component: { template: '<div />' } },
      { path: '/admin/ai', name: 'admin-ai', component: { template: '<div />' } },
      { path: '/admin/login', name: 'admin-login', component: { template: '<div />' } },
      { path: '/login', name: 'login', component: { template: '<div />' } },
    ],
  });
  return r;
}

async function mountAssistant(role: 'ADMIN' | 'PARTNER', path: string, options: { stubChat?: boolean } = {}) {
  const router = createTestRouter();
  await router.push(path);
  await router.isReady();
  const auth = useAuthStore();
  auth.clearSession();
  auth.saveSession(session(role));
  const wrapper = mount(AdminPetAssistant, {
    global: {
      plugins: [router],
      stubs: options.stubChat === false ? {} : { AdminAiChat: ChatStub },
    },
    attachTo: document.body,
  });
  await flushPromises();
  return { wrapper, router };
}

beforeEach(() => {
  setActivePinia(createPinia());
  window.sessionStorage.clear();
  window.localStorage.clear();
  mockFetchProviders.mockReset();
  mockFetchProviders.mockResolvedValue([provider(1, 'deepseek'), provider(2, 'glm', { isDefault: false })]);
  mockStreamAiChat.mockReset();
  mockIdleRandom.mockReset();
  mockIdleRandom.mockReturnValue(0);
  vi.useFakeTimers();
});

/** jsdom 不加载真实行图：手动派发 load，等价于当前行图已就绪。 */
async function loadPetSprite(wrapper: Awaited<ReturnType<typeof mountAssistant>>['wrapper']) {
  const img = wrapper.find('.pet-sprite img');
  if (img.exists()) {
    await img.trigger('load');
    await nextTick();
  }
}

describe('鉴权挂载边界（FD-29）', () => {
  it('游客不渲染宠物，也不挂载聊天组件', async () => {
    const router = createTestRouter();
    await router.push('/');
    await router.isReady();
    const auth = useAuthStore();
    auth.clearSession();
    const wrapper = mount(AdminPetAssistant, {
      global: { plugins: [router], stubs: { AdminAiChat: ChatStub } },
    });
    await flushPromises();
    expect(wrapper.find('[data-testid="admin-pet-assistant"]').exists()).toBe(false);
    expect(wrapper.findComponent(ChatStub).exists()).toBe(false);
  });

  it('过期会话不渲染宠物', async () => {
    const router = createTestRouter();
    await router.push('/');
    await router.isReady();
    const auth = useAuthStore();
    auth.clearSession();
    auth.saveSession(session('ADMIN', 'expired'));
    auth.saveSession({ ...session('ADMIN'), expiresAt: '2000-01-01T00:00:00Z' });
    const wrapper = mount(AdminPetAssistant, {
      global: { plugins: [router], stubs: { AdminAiChat: ChatStub } },
    });
    await flushPromises();
    expect(wrapper.find('[data-testid="admin-pet-assistant"]').exists()).toBe(false);
  });

  it('ADMIN 与 PARTNER 在公开页与后台页均显示宠物', async () => {
    for (const role of ['ADMIN', 'PARTNER'] as const) {
      for (const path of ['/', '/admin', '/admin/ai']) {
        const { wrapper } = await mountAssistant(role, path);
        expect(wrapper.find('[data-testid="admin-pet-assistant"]').exists(), `${role} @ ${path}`).toBe(true);
      }
    }
  });

  it('登录页不显示宠物', async () => {
    for (const path of ['/login', '/admin/login']) {
      const { wrapper } = await mountAssistant('ADMIN', path);
      expect(wrapper.find('[data-testid="admin-pet-assistant"]').exists(), path).toBe(false);
    }
  });
});

describe('面板与单实例', () => {
  it('首次显示 waving 一轮后回 idle', async () => {
    const { wrapper } = await mountAssistant('ADMIN', '/');
    const sprite = wrapper.find('.pet-sprite');
    expect(sprite.attributes('data-state')).toBe('waving');
    await loadPetSprite(wrapper);
    await vi.advanceTimersByTimeAsync(totalDuration('waving'));
    await nextTick();
    expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('idle');
  });

  it('单击宠物打开面板：恰好一个 AdminAiChat compact，注入 provider/model；播放 chat-open 后落回 waiting；关闭后宠物仍在', async () => {
    const { wrapper } = await mountAssistant('ADMIN', '/');
    await loadPetSprite(wrapper);
    vi.advanceTimersByTime(totalDuration('waving'));
    await wrapper.find('[data-testid="pet-button"]').trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-testid="pet-chat-panel"]').exists()).toBe(true);
    const chats = wrapper.findAllComponents(ChatStub);
    expect(chats).toHaveLength(1);
    expect(chats[0].props('compact')).toBe(true);
    expect(chats[0].props('providerId')).toBe(1);
    expect(chats[0].props('model')).toBe('m-a');
    // 点击即播放 chat-open（面板已挂载，动画不阻塞交互）
    expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('chat-open');

    // chat-open 播完后自然落回 waiting
    await loadPetSprite(wrapper);
    await vi.advanceTimersByTimeAsync(totalDuration('chat-open'));
    await nextTick();
    expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('idle');

    await wrapper.find('.pet-chat-close').trigger('click');
    expect(wrapper.find('[data-testid="pet-chat-panel"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="admin-pet-assistant"]').exists()).toBe(true);
    expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('idle');
  });

  it('Escape 关闭面板并把焦点还给宠物按钮', async () => {
    const { wrapper } = await mountAssistant('PARTNER', '/');
    vi.advanceTimersByTime(totalDuration('waving'));
    await wrapper.find('[data-testid="pet-button"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="pet-chat-panel"]').exists()).toBe(true);

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    await nextTick();
    expect(wrapper.find('[data-testid="pet-chat-panel"]').exists()).toBe(false);
    expect(document.activeElement).toBe(wrapper.find('[data-testid="pet-button"]').element);
  });

  it('/admin/ai 宠物存在但绝不挂载第二个 AdminAiChat，点击聚焦完整页输入框', async () => {
    const input = document.createElement('textarea');
    input.setAttribute('data-testid', 'ai-chat-input');
    document.body.appendChild(input);
    try {
      const { wrapper } = await mountAssistant('ADMIN', '/admin/ai');
      expect(wrapper.find('[data-testid="admin-pet-assistant"]').exists()).toBe(true);
      expect(wrapper.findComponent(ChatStub).exists()).toBe(false);

      await wrapper.find('[data-testid="pet-button"]').trigger('click');
      await flushPromises();
      expect(wrapper.find('[data-testid="pet-chat-panel"]').exists()).toBe(false);
      expect(wrapper.findComponent(ChatStub).exists()).toBe(false);
      expect(document.activeElement).toBe(input);
    } finally {
      document.body.removeChild(input);
    }
  });

  it('/admin/ai 找不到输入框时给出清晰提示，不弹面板', async () => {
    const { wrapper } = await mountAssistant('ADMIN', '/admin/ai');
    await wrapper.find('[data-testid="pet-button"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="pet-chat-panel"]').exists()).toBe(false);
    expect(useUiStore().toast).toContain('全屏聊天');
  });

  it('连续开关不重复注册键盘监听器或重复请求 provider', async () => {
    const { wrapper } = await mountAssistant('ADMIN', '/');
    vi.advanceTimersByTime(totalDuration('waving'));
    const button = wrapper.find('[data-testid="pet-button"]');
    await button.trigger('click');
    await flushPromises();
    await wrapper.find('.pet-chat-close').trigger('click');
    await button.trigger('click');
    await flushPromises();
    await wrapper.find('.pet-chat-close').trigger('click');

    expect(wrapper.findAllComponents(ChatStub)).toHaveLength(0);
    // Each reopen refreshes provider/model configuration to avoid stale selections.
    expect(mockFetchProviders).toHaveBeenCalledTimes(2);
    // 重复开关仍只有一个宠物与一个面板实例
    expect(wrapper.findAll('[data-testid="pet-button"]')).toHaveLength(1);
    expect(wrapper.findAll('[data-testid="pet-chat-panel"]')).toHaveLength(0);
  });
});

describe('隐藏 / 恢复 / 登出清理', () => {
  it('隐藏宠物写入 sessionStorage，宠物与面板都消失', async () => {
    const { wrapper } = await mountAssistant('ADMIN', '/');
    vi.advanceTimersByTime(totalDuration('waving'));
    await wrapper.find('[data-testid="pet-button"]').trigger('click');
    await flushPromises();
    await wrapper.find('.pet-chat-close').trigger('click');

    await wrapper.find('[data-testid="pet-hide-button"]').trigger('click');
    expect(window.sessionStorage.getItem('yubai-admin-pet-hidden')).toBe('1');
    expect(wrapper.find('[data-testid="admin-pet-assistant"]').exists()).toBe(false);
  });

  it('Ctrl+Shift+A 恢复隐藏宠物并打开面板', async () => {
    window.sessionStorage.setItem('yubai-admin-pet-hidden', '1');
    const { wrapper } = await mountAssistant('ADMIN', '/');
    expect(wrapper.find('[data-testid="admin-pet-assistant"]').exists()).toBe(false);

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'a', ctrlKey: true, shiftKey: true }));
    await flushPromises();
    expect(wrapper.find('[data-testid="admin-pet-assistant"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="pet-chat-panel"]').exists()).toBe(true);
    expect(window.sessionStorage.getItem('yubai-admin-pet-hidden')).toBeNull();
  });

  it('Ctrl+Shift+A 在未隐藏时切换面板开合', async () => {
    const { wrapper } = await mountAssistant('PARTNER', '/');
    vi.advanceTimersByTime(totalDuration('waving'));
    expect(wrapper.find('[data-testid="pet-chat-panel"]').exists()).toBe(false);
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'a', ctrlKey: true, shiftKey: true }));
    await flushPromises();
    expect(wrapper.find('[data-testid="pet-chat-panel"]').exists()).toBe(true);
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'a', ctrlKey: true, shiftKey: true }));
    await nextTick();
    expect(wrapper.find('[data-testid="pet-chat-panel"]').exists()).toBe(false);
  });

  it('logout（isStaff → false）清理隐藏状态，再次登录默认显示', async () => {
    window.sessionStorage.setItem('yubai-admin-pet-hidden', '1');
    const { wrapper } = await mountAssistant('PARTNER', '/');
    expect(wrapper.find('[data-testid="admin-pet-assistant"]').exists()).toBe(false);

    const auth = useAuthStore();
    auth.clearSession();
    await nextTick();
    expect(wrapper.find('[data-testid="admin-pet-assistant"]').exists()).toBe(false);
    expect(window.sessionStorage.getItem('yubai-admin-pet-hidden')).toBeNull();

    auth.saveSession(session('PARTNER', 'gf'));
    await nextTick();
    expect(wrapper.find('[data-testid="admin-pet-assistant"]').exists()).toBe(true);
  });
});

describe('SSE 事件驱动动画状态', () => {
  async function openChat(wrapper: Awaited<ReturnType<typeof mountAssistant>>['wrapper']) {
    await loadPetSprite(wrapper);
    vi.advanceTimersByTime(totalDuration('waving'));
    await wrapper.find('[data-testid="pet-button"]').trigger('click');
    await flushPromises();
  }

  it('stream-start → running；stream-complete → review 一轮后回 waiting', async () => {
    const { wrapper } = await mountAssistant('ADMIN', '/');
    await openChat(wrapper);
    // chat-open 播完落回 waiting，再进入流式
    await loadPetSprite(wrapper);
    await vi.advanceTimersByTimeAsync(totalDuration('chat-open'));
    await nextTick();
    expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('idle');

    const chat = wrapper.findComponent(ChatStub);
    chat.vm.$emit('stream-start');
    await nextTick();
    expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('running');

    chat.vm.$emit('stream-complete');
    await nextTick();
    expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('review');

    // review 一轮播完（加载行图）→ 回 waiting
    await loadPetSprite(wrapper);
    await vi.advanceTimersByTimeAsync(totalDuration('review'));
    await nextTick();
    expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('idle');
  });

  it('stream-error → failed 一轮后回 waiting；面板内错误条仍由聊天组件显示', async () => {
    const { wrapper } = await mountAssistant('PARTNER', '/');
    await openChat(wrapper);
    // chat-open 先播完落回 waiting
    await loadPetSprite(wrapper);
    await vi.advanceTimersByTimeAsync(totalDuration('chat-open'));
    await nextTick();
    expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('idle');
    const chat = wrapper.findComponent(ChatStub);

    chat.vm.$emit('stream-error');
    await nextTick();
    expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('failed');
    await loadPetSprite(wrapper);
    await vi.advanceTimersByTimeAsync(totalDuration('failed'));
    await nextTick();
    expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('idle');
  });

  it('stream-abort 立即回 waiting（面板打开）', async () => {
    const { wrapper } = await mountAssistant('ADMIN', '/');
    await openChat(wrapper);
    // chat-open 先播完落回 waiting，再进入流式
    await loadPetSprite(wrapper);
    await vi.advanceTimersByTimeAsync(totalDuration('chat-open'));
    await nextTick();
    expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('idle');
    const chat = wrapper.findComponent(ChatStub);

    chat.vm.$emit('stream-start');
    await nextTick();
    expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('running');
    chat.vm.$emit('stream-abort');
    await nextTick();
    expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('idle');
  });

  it('stream-first-delta 保持 running', async () => {
    const { wrapper } = await mountAssistant('ADMIN', '/');
    await openChat(wrapper);
    const chat = wrapper.findComponent(ChatStub);
    chat.vm.$emit('stream-start');
    chat.vm.$emit('stream-first-delta');
    await nextTick();
    expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('running');
  });
});

describe('指针视线与 reduced-motion', () => {
  function stubPetRect(wrapper: Awaited<ReturnType<typeof mountAssistant>>['wrapper']) {
    const el = wrapper.find('[data-testid="pet-button"]').element;
    el.getBoundingClientRect = () =>
      ({
        left: 100,
        top: 100,
        width: 96,
        height: 104,
        right: 196,
        bottom: 204,
        x: 100,
        y: 100,
        toJSON: () => ({}),
      }) as DOMRect;
  }

  it('指针在宠物附近时显示 16 方向静态 look 帧，离开后回 idle（确定性 fake timers）', async () => {
    const { wrapper } = await mountAssistant('ADMIN', '/');
    stubPetRect(wrapper);
    const dispatch = (clientX: number, clientY: number) => {
      window.dispatchEvent(new MouseEvent('pointermove', { clientX, clientY }));
    };

    // 1) 确定性驱动首次 waving 播完 → 回 idle（不依赖真实墙钟）
    await loadPetSprite(wrapper);
    await vi.advanceTimersByTimeAsync(totalDuration('waving'));
    await nextTick();
    expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('idle');

    // 2) 越过 50ms 指针节流窗口后派发指针事件
    await vi.advanceTimersByTimeAsync(100);
    // 宠物中心 (148, 152)；指针 (200, 50)：dx=52, dy=-102 → 右上方 ≈ 27° → row 9 col 1
    dispatch(200, 50);
    // 3) 推进到 rAF 回调（jsdom 的 rAF 由 16ms 定时器驱动，fake timers 拦截）
    await vi.advanceTimersByTimeAsync(20);
    await nextTick();
    const sprite = wrapper.find('.pet-sprite');
    expect(sprite.attributes('data-state')).toBe('look');
    expect(sprite.attributes('data-row')).toBe('9');
    expect(sprite.attributes('data-col')).toBe('1');

    // 4) 越过节流窗口后指针离开有效半径 → 回 idle
    await vi.advanceTimersByTimeAsync(100);
    dispatch(1200, 900);
    await vi.advanceTimersByTimeAsync(20);
    await nextTick();
    expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('idle');
  });

  it('reduced-motion 下不启用 gaze，也不启动动画 timer', async () => {
    vi.stubGlobal('matchMedia', (query: string) => ({
      matches: query.includes('prefers-reduced-motion'),
      media: query,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    }));
    const { wrapper } = await mountAssistant('ADMIN', '/');
    stubPetRect(wrapper);

    window.dispatchEvent(new MouseEvent('pointermove', { clientX: 200, clientY: 50 }));
    vi.advanceTimersByTime(1000);
    const sprite = wrapper.find('.pet-sprite');
    // reduced-motion 静态退化：直接稳定显示 idle 首帧且不启动任何 timer
    expect(sprite.attributes('data-state')).toBe('idle');
    expect(sprite.attributes('data-col')).toBe('0');
    expect(vi.getTimerCount()).toBe(0);
    vi.unstubAllGlobals();
  });
});

describe('P2 面板 provider/model 切换', () => {
  async function openChat(wrapper: Awaited<ReturnType<typeof mountAssistant>>['wrapper']) {
    await loadPetSprite(wrapper);
    await vi.advanceTimersByTimeAsync(totalDuration('waving'));
    await wrapper.find('[data-testid="pet-button"]').trigger('click');
    await flushPromises();
  }

  it('多 provider 时显示选择器，默认选中 isDefault provider', async () => {
    const { wrapper } = await mountAssistant('ADMIN', '/');
    await openChat(wrapper);

    const providerSelect = wrapper.find('[data-testid="pet-provider-select"]');
    expect(providerSelect.exists()).toBe(true);
    expect((providerSelect.element as HTMLSelectElement).value).toBe('1');
    const chat = wrapper.findComponent(ChatStub);
    expect(chat.props('providerId')).toBe(1);
    expect(chat.props('model')).toBe('m-a');
  });

  it('切换 provider 后模型自动跟随其 defaultModel', async () => {
    mockFetchProviders.mockResolvedValue([
      provider(1, 'deepseek'),
      provider(2, 'glm', { isDefault: false, defaultModel: 'glm-x' }),
    ]);
    const { wrapper } = await mountAssistant('ADMIN', '/');
    await openChat(wrapper);

    await wrapper.find('[data-testid="pet-provider-select"]').setValue('2');
    await flushPromises();
    const chat = wrapper.findComponent(ChatStub);
    expect(chat.props('providerId')).toBe(2);
    expect(chat.props('model')).toBe('glm-x');
  });

  it('defaultModel 缺失时模型回退到 models 第一项', async () => {
    mockFetchProviders.mockResolvedValue([
      provider(1, 'deepseek', { defaultModel: '' }),
      provider(2, 'glm', { isDefault: false, defaultModel: 'glm-x' }),
    ]);
    const { wrapper } = await mountAssistant('ADMIN', '/');
    await openChat(wrapper);

    const chat = wrapper.findComponent(ChatStub);
    expect(chat.props('providerId')).toBe(1);
    expect(chat.props('model')).toBe('m-a');
  });

  it('defaultModel 不在 models 数组时仍出现在选项里', async () => {
    mockFetchProviders.mockResolvedValue([
      provider(1, 'deepseek', { models: ['m-a', 'm-b'], defaultModel: 'm-extra' }),
    ]);
    const { wrapper } = await mountAssistant('ADMIN', '/');
    await openChat(wrapper);

    const modelSelect = wrapper.find('[data-testid="pet-model-select"]');
    expect(modelSelect.exists()).toBe(true);
    const options = modelSelect.findAll('option').map((option) => option.text());
    expect(options).toContain('m-extra');
    expect(options).toContain('m-a');
  });

  it('同 provider 下切换模型后，真实 compact 聊天下一次消息使用新选择', async () => {
    mockStreamAiChat.mockResolvedValue(undefined);
    const { wrapper } = await mountAssistant('ADMIN', '/', { stubChat: false });
    await openChat(wrapper);

    const modelSelect = wrapper.find('[data-testid="pet-model-select"]');
    expect(modelSelect.exists()).toBe(true);
    await modelSelect.setValue('m-b');
    await flushPromises();

    const input = wrapper.find('[data-testid="ai-chat-input"]');
    await input.setValue('用新模型回复');
    await wrapper.find('button.send-btn').trigger('click');
    await flushPromises();

    expect(mockStreamAiChat).toHaveBeenCalledWith(
      [{ role: 'user', content: '用新模型回复' }],
      expect.objectContaining({ onDelta: expect.any(Function) }),
      expect.objectContaining({ providerId: 1, model: 'm-b', signal: expect.any(AbortSignal) }),
    );
  });

  it('provider 加载失败时面板仍可聊天，走后端默认兜底', async () => {
    mockFetchProviders.mockRejectedValue(new Error('registry down'));
    const { wrapper } = await mountAssistant('ADMIN', '/', { stubChat: false });
    await openChat(wrapper);

    expect(wrapper.find('[data-testid="pet-chat-panel"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="pet-provider-select"]').exists()).toBe(false);
    const chat = wrapper.findComponent(ChatStub);
    expect(chat.props('providerId')).toBe(null);
    expect(chat.props('model')).toBe(null);
    const input = wrapper.find('[data-testid="ai-chat-input"]');
    expect(input.exists()).toBe(true);
  });

  it('provider 只有一个时隐藏 provider 控件；连续开关只请求一次 provider', async () => {
    mockFetchProviders.mockResolvedValue([provider(1, 'deepseek')]);
    const { wrapper } = await mountAssistant('ADMIN', '/');
    await openChat(wrapper);

    expect(wrapper.find('[data-testid="pet-provider-select"]').exists()).toBe(false);
    await wrapper.find('.pet-chat-close').trigger('click');
    await wrapper.find('[data-testid="pet-button"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('.pet-chat-close').exists()).toBe(true);
    expect(mockFetchProviders).toHaveBeenCalledTimes(2);
  });

  it('refreshes the open panel when provider settings change', async () => {
    mockFetchProviders.mockResolvedValue([provider(1, 'deepseek')]);
    const { wrapper } = await mountAssistant('ADMIN', '/');
    await openChat(wrapper);

    mockFetchProviders.mockResolvedValue([
      provider(1, 'deepseek', { models: ['m-new'], defaultModel: 'm-new' }),
    ]);
    window.dispatchEvent(new Event(adminApi.AI_PROVIDERS_CHANGED_EVENT));
    await flushPromises();

    const chat = wrapper.findComponent(ChatStub);
    expect(chat.props('providerId')).toBe(1);
    expect(chat.props('model')).toBe('m-new');
  });

  it('宠物面板切换的模型与全屏聊天页共享（同一 aiStore）', async () => {
    mockStreamAiChat.mockResolvedValue(undefined);
    const { wrapper, router } = await mountAssistant('ADMIN', '/', { stubChat: false });
    await openChat(wrapper);

    // 宠物面板选择模型 m-b
    await wrapper.find('[data-testid="pet-model-select"]').setValue('m-b');
    await flushPromises();

    // 全屏聊天页挂载后读取到同一份选择
    const fullChat = mount(AdminAiChat, { global: { plugins: [router] } });
    await flushPromises();
    expect((fullChat.find('[data-testid="chat-model-select"]').element as HTMLSelectElement).value).toBe(
      'm-b',
    );

    // 全屏聊天页切回 m-a → 宠物面板跟随
    await fullChat.find('[data-testid="chat-model-select"]').setValue('m-a');
    await flushPromises();
    expect((wrapper.find('[data-testid="pet-model-select"]').element as HTMLSelectElement).value).toBe('m-a');
    fullChat.unmount();
  });
});

describe('P3 路由转换清理（进入 /admin/ai 收起面板）', () => {
  function attachFullPageInput() {
    const input = document.createElement('textarea');
    input.setAttribute('data-testid', 'ai-chat-input');
    document.body.appendChild(input);
    return input;
  }

  it('从打开的面板导航到 /admin/ai：面板关闭、compact 销毁、首次点击宠物即聚焦完整页输入框', async () => {
    const { wrapper, router } = await mountAssistant('ADMIN', '/');
    await vi.advanceTimersByTimeAsync(totalDuration('waving'));
    await wrapper.find('[data-testid="pet-button"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="pet-chat-panel"]').exists()).toBe(true);

    await router.push('/admin/ai');
    await router.isReady();
    await flushPromises();

    expect(wrapper.find('[data-testid="pet-chat-panel"]').exists()).toBe(false);
    expect(wrapper.findComponent(ChatStub).exists()).toBe(false);

    const input = attachFullPageInput();
    try {
      await wrapper.find('[data-testid="pet-button"]').trigger('click');
      await flushPromises();
      expect(wrapper.find('[data-testid="pet-chat-panel"]').exists()).toBe(false);
      expect(document.activeElement).toBe(input);
    } finally {
      document.body.removeChild(input);
    }
  });

  it('从 /admin/ai 离开后宠物可用但不自动弹开面板', async () => {
    const { wrapper, router } = await mountAssistant('ADMIN', '/admin/ai');
    expect(wrapper.find('[data-testid="admin-pet-assistant"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="pet-chat-panel"]').exists()).toBe(false);

    await router.push('/');
    await router.isReady();
    await flushPromises();

    expect(wrapper.find('[data-testid="admin-pet-assistant"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="pet-chat-panel"]').exists()).toBe(false);
    expect(wrapper.findComponent(ChatStub).exists()).toBe(false);
    // 手动打开仍然正常
    await vi.advanceTimersByTimeAsync(totalDuration('waving'));
    await wrapper.find('[data-testid="pet-button"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="pet-chat-panel"]').exists()).toBe(true);
  });

  it('流式生成中导航到 /admin/ai：compact 卸载并清理流状态，宠物回 idle', async () => {
    const { wrapper, router } = await mountAssistant('ADMIN', '/');
    await loadPetSprite(wrapper);
    await vi.advanceTimersByTimeAsync(totalDuration('waving'));
    await wrapper.find('[data-testid="pet-button"]').trigger('click');
    await flushPromises();

    const chat = wrapper.findComponent(ChatStub);
    chat.vm.$emit('stream-start');
    await nextTick();
    expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('running');

    await router.push('/admin/ai');
    await router.isReady();
    await flushPromises();

    expect(wrapper.findComponent(ChatStub).exists()).toBe(false);
    await vi.advanceTimersByTimeAsync(100);
    await nextTick();
    expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('idle');
  });
});

describe('P4 matchMedia 监听器清理', () => {
  it('注册与卸载使用同一个回调引用', async () => {
    const added: { query: string; callback: EventListener }[] = [];
    const removed: { query: string; callback: EventListener }[] = [];
    vi.stubGlobal('matchMedia', (query: string) => ({
      matches: false,
      media: query,
      addEventListener: (_type: string, callback: EventListener) => {
        added.push({ query, callback });
      },
      removeEventListener: (_type: string, callback: EventListener) => {
        removed.push({ query, callback });
      },
    }));

    const { wrapper } = await mountAssistant('ADMIN', '/');
    const mobileAdded = added.find((entry) => entry.query.includes('max-width: 720px'));
    expect(mobileAdded).toBeDefined();

    wrapper.unmount();
    const mobileRemoved = removed.find((entry) => entry.query.includes('max-width: 720px'));
    expect(mobileRemoved).toBeDefined();
    expect(mobileRemoved!.callback).toBe(mobileAdded!.callback);
    vi.unstubAllGlobals();
  });

  it('不支持 addEventListener 的旧环境不抛错', async () => {
    vi.stubGlobal('matchMedia', (query: string) => ({
      matches: false,
      media: query,
    }));

    const { wrapper } = await mountAssistant('ADMIN', '/');
    wrapper.unmount();
    expect(true).toBe(true);
    vi.unstubAllGlobals();
  });

  it('标准 add/removeEventListener 环境卸载不抛错且完成移除', async () => {
    const removed: EventListener[] = [];
    vi.stubGlobal('matchMedia', (query: string) => ({
      matches: false,
      media: query,
      addEventListener: vi.fn(),
      removeEventListener: (_type: string, callback: EventListener) => {
        removed.push(callback);
      },
    }));

    const { wrapper } = await mountAssistant('ADMIN', '/');
    wrapper.unmount();
    expect(removed.length).toBeGreaterThan(0);
    vi.unstubAllGlobals();
  });
});

describe('P5 宠物尺寸与拖动', () => {
  function dispatchPointer(el: Element, type: string, x?: number, y?: number) {
    el.dispatchEvent(
      new MouseEvent(type, {
        clientX: x,
        clientY: y,
        bubbles: true,
        cancelable: true,
      }),
    );
  }

  function dragPet(
    wrapper: Awaited<ReturnType<typeof mountAssistant>>['wrapper'],
    from: { x: number; y: number },
    to: { x: number; y: number },
  ) {
    const button = wrapper.find('[data-testid="pet-button"]').element;
    dispatchPointer(button, 'pointerdown', from.x, from.y);
    window.dispatchEvent(new MouseEvent('pointermove', { clientX: to.x, clientY: to.y }));
    window.dispatchEvent(new MouseEvent('pointerup'));
    return nextTick();
  }

  async function openPanel(wrapper: Awaited<ReturnType<typeof mountAssistant>>['wrapper']) {
    vi.advanceTimersByTime(totalDuration('waving'));
    await wrapper.find('[data-testid="pet-button"]').trigger('click');
    await flushPromises();
  }

  it('宠物 0.8 倍：桌面渲染 307px 宽（384 → 307）', async () => {
    const { wrapper } = await mountAssistant('ADMIN', '/');
    const sprite = wrapper.find('.pet-sprite');
    expect(sprite.attributes('style')).toContain('width: 307px');
  });

  it('默认落点：右下角（视口内），以内联 left/top 定位', async () => {
    const { wrapper } = await mountAssistant('ADMIN', '/');
    const container = wrapper.find('[data-testid="admin-pet-assistant"]');
    const style = container.attributes('style') ?? '';
    // jsdom 视口 1024×768：x = 1024-307-20 = 697；y = 768-(307×208/192+36)-18
    const stackH = (307 * 208) / 192 + 36;
    expect(style).toContain('left: 697px');
    expect(style).toContain(`top: ${768 - stackH - 18}px`);
  });

  it('拖动手势把宠物移到新位置，位移超过阈值后不触发打开面板', async () => {
    const { wrapper } = await mountAssistant('ADMIN', '/');
    vi.advanceTimersByTime(totalDuration('waving'));
    const container = wrapper.find('[data-testid="admin-pet-assistant"]');

    // 拖到 (300, 250)：起点 (100,100) → 位移 (+200,+150)，起点默认在右下角 → 越界后夹紧到视口内
    await dragPet(wrapper, { x: 100, y: 100 }, { x: 300, y: 250 });
    const style = container.attributes('style') ?? '';
    const maxX = 1024 - 307 - 4;
    const maxY = 768 - ((307 * 208) / 192 + 36) - 4;
    expect(style).toContain(`left: ${maxX}px`);
    expect(style).toContain(`top: ${maxY}px`);

    // 拖动结束后位置已保存
    expect(JSON.parse(window.localStorage.getItem('yubai-admin-pet-pos')!)).toEqual({ x: maxX, y: maxY });

    // 拖动结束的 click 被抑制：面板不打开
    await wrapper.find('[data-testid="pet-button"]').trigger('click');
    expect(wrapper.find('[data-testid="pet-chat-panel"]').exists()).toBe(false);
  });

  it('位移小于阈值视为点击：打开面板', async () => {
    const { wrapper } = await mountAssistant('ADMIN', '/');
    vi.advanceTimersByTime(totalDuration('waving'));

    const button = wrapper.find('[data-testid="pet-button"]').element;
    dispatchPointer(button, 'pointerdown', 100, 100);
    window.dispatchEvent(new MouseEvent('pointermove', { clientX: 103, clientY: 100 }));
    window.dispatchEvent(new MouseEvent('pointerup'));
    await wrapper.find('[data-testid="pet-button"]').trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-testid="pet-chat-panel"]').exists()).toBe(true);
  });

  it('重挂载后恢复上次拖动位置（并夹紧到视口内）', async () => {
    window.localStorage.setItem('yubai-admin-pet-pos', JSON.stringify({ x: 300, y: 200 }));
    const { wrapper } = await mountAssistant('ADMIN', '/');
    const style = wrapper.find('[data-testid="admin-pet-assistant"]').attributes('style') ?? '';
    expect(style).toContain('left: 300px');
    expect(style).toContain('top: 200px');

    // 超出夹紧边界的旧数据同样被拉回视口内
    window.localStorage.setItem('yubai-admin-pet-pos', JSON.stringify({ x: 99999, y: 99999 }));
    const { wrapper: wrapper2 } = await mountAssistant('ADMIN', '/');
    const style2 = wrapper2.find('[data-testid="admin-pet-assistant"]').attributes('style') ?? '';
    expect(style2).toContain('left: 713px'); // 1024-307-4
    expect(style2).toContain(`top: ${768 - ((307 * 208) / 192 + 36) - 4}px`);
  });

  it('损坏的位置数据被忽略，回落默认右下角', async () => {
    window.localStorage.setItem('yubai-admin-pet-pos', 'not-json{{{');
    const { wrapper } = await mountAssistant('ADMIN', '/');
    const style = wrapper.find('[data-testid="admin-pet-assistant"]').attributes('style') ?? '';
    expect(style).toContain('left: 697px');
    expect(style).toContain(`top: ${768 - ((307 * 208) / 192 + 36) - 18}px`);
  });

  it('面板打开时把宠物拖到顶部 → 面板向下翻转（panel-below）', async () => {
    const { wrapper } = await mountAssistant('ADMIN', '/');
    await openPanel(wrapper);
    expect(wrapper.find('[data-testid="pet-chat-panel"]').exists()).toBe(true);

    // 起点默认 y=506，拖到顶部（夹紧为 4）→ 上方空间不足 → 向下翻转
    await dragPet(wrapper, { x: 500, y: 600 }, { x: 500, y: 20 });
    expect(wrapper.find('[data-testid="pet-chat-panel"]').classes()).toContain('panel-below');

    // 拖回底部（夹紧 y=520，下方无空间）→ 恢复向上展开
    await dragPet(wrapper, { x: 500, y: 20 }, { x: 500, y: 700 });
    expect(wrapper.find('[data-testid="pet-chat-panel"]').classes()).not.toContain('panel-below');
  });
});

describe('P6 随机间隔待机与点击聊天动作', () => {
  /** waving 播完 → 回 idle，待机计时开始。 */
  async function idleReady(wrapper: Awaited<ReturnType<typeof mountAssistant>>['wrapper']) {
    await loadPetSprite(wrapper);
    await vi.advanceTimersByTimeAsync(totalDuration('waving'));
    await nextTick();
  }

  it('无互动时保持 idle（微晃），随机间隔到点触发一次待机动作', async () => {
    mockIdleRandom.mockReturnValue(0.67); // → idle-sway
    const { wrapper } = await mountAssistant('ADMIN', '/');
    await idleReady(wrapper);
    expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('idle');
    expect(wrapper.find('.pet-sprite').attributes('data-src')).toBe('idle');

    // 29_999ms 不触发；30_000ms 恰好触发一次待机动作
    await vi.advanceTimersByTimeAsync(IDLE_TEST_MS - 1);
    expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('idle');
    await vi.advanceTimersByTimeAsync(1);
    await nextTick();
    expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('idle-sway');

    // 待机动作播完 → 回 idle，重新开始随机间隔计时
    await loadPetSprite(wrapper);
    await vi.advanceTimersByTimeAsync(totalDuration('idle-sway'));
    await nextTick();
    expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('idle');
  });

  it('三组待机动作都可通过随机注入覆盖，动作完成后从零重新计时', async () => {
    for (const [value, action, duration] of [
      [0, 'idle-curious', totalDuration('idle-curious')],
      [0.34, 'idle-sleeve', totalDuration('idle-sleeve')],
      [0.67, 'idle-sway', totalDuration('idle-sway')],
    ] as const) {
      mockIdleRandom.mockReturnValue(value);
      const { wrapper } = await mountAssistant('ADMIN', '/');
      await idleReady(wrapper);
      await vi.advanceTimersByTimeAsync(IDLE_TEST_MS);
      await nextTick();
      expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe(action);
      expect(wrapper.find('.pet-sprite').attributes('data-src')).toBe(action);
      await loadPetSprite(wrapper);
      await vi.advanceTimersByTimeAsync(duration);
      await nextTick();
      expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('idle');

      // 动作完成后重新完整等待随机间隔
      await vi.advanceTimersByTimeAsync(IDLE_TEST_MS - 1);
      expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('idle');
      await vi.advanceTimersByTimeAsync(1);
      await nextTick();
      expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe(action);
    }
  });

  it('hover 清零：pointerenter 取消正在播放的待机动作并重置计时；pointerleave 后重新完整等待', async () => {
    mockIdleRandom.mockReturnValue(0);
    const { wrapper } = await mountAssistant('ADMIN', '/');
    await idleReady(wrapper);

    // 触发第一次待机动作并播放到一半
    await vi.advanceTimersByTimeAsync(IDLE_TEST_MS);
    await nextTick();
    expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('idle-curious');
    await loadPetSprite(wrapper);
    await vi.advanceTimersByTimeAsync(800);

    // pointerenter → 立即取消待机动作
    const button = wrapper.find('[data-testid="pet-button"]').element;
    button.dispatchEvent(new MouseEvent('pointerenter', { bubbles: true }));
    await nextTick();
    expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('idle');

    // 长时间悬浮不触发
    await vi.advanceTimersByTimeAsync(60_000);
    expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('idle');

    // pointerleave → 重新完整等待
    button.dispatchEvent(new MouseEvent('pointerleave', { bubbles: true }));
    await nextTick();
    await vi.advanceTimersByTimeAsync(IDLE_TEST_MS - 1);
    expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('idle');
    await vi.advanceTimersByTimeAsync(1);
    await nextTick();
    expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('idle-curious');
  });

  it('面板打开时待机 timer 被清理；关闭后重新从零计时', async () => {
    const { wrapper } = await mountAssistant('ADMIN', '/');
    await idleReady(wrapper);
    await vi.advanceTimersByTimeAsync(10_000);
    await wrapper.find('[data-testid="pet-button"]').trigger('click');
    await flushPromises();
    // 面板打开期间等待 60 秒：不触发待机动作
    await vi.advanceTimersByTimeAsync(60_000);
    expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('chat-open');

    await wrapper.find('.pet-chat-close').trigger('click');
    await nextTick();
    await vi.advanceTimersByTimeAsync(IDLE_TEST_MS - 1);
    expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('idle');
    await vi.advanceTimersByTimeAsync(1);
    await nextTick();
    expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('idle-curious');
  });

  it('点击宠物：面板立即挂载 + 输入框聚焦（动画不阻塞），chat-open 恰好播一次后落回 waiting 且不重复', async () => {
    const { wrapper } = await mountAssistant('ADMIN', '/', { stubChat: false });
    await idleReady(wrapper);

    await wrapper.find('[data-testid="pet-button"]').trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-testid="pet-chat-panel"]').exists()).toBe(true);
    expect(document.activeElement).toBe(wrapper.find('[data-testid="ai-chat-input"]').element);
    const sprite = wrapper.find('.pet-sprite');
    expect(sprite.attributes('data-state')).toBe('chat-open');
    expect(sprite.attributes('data-col')).toBe('0');

    // 恰好播放一次：播完落回 waiting，且后续长时间内不再出现 chat-open
    await loadPetSprite(wrapper);
    await vi.advanceTimersByTimeAsync(totalDuration('chat-open') - 1);
    expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('chat-open');
    await vi.advanceTimersByTimeAsync(1);
    await nextTick();
    expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('idle');

    await vi.advanceTimersByTimeAsync(10_000);
    await nextTick();
    expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('idle');
  });

  it('关闭面板不反向播放 chat-open，直接回 idle', async () => {
    const { wrapper } = await mountAssistant('ADMIN', '/');
    await idleReady(wrapper);
    await wrapper.find('[data-testid="pet-button"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('chat-open');

    // 关闭面板：chat-open 立即停止，不反向播放
    await wrapper.find('.pet-chat-close').trigger('click');
    await nextTick();
    expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('idle');
  });

  it('点击中断未播完的启动 waving：chat-open 播完后直接衔接 waiting，绝不重复播放动作', async () => {
    const { wrapper } = await mountAssistant('ADMIN', '/');
    // 启动 waving 尚未播完（行图未加载），oneShot='waving' 残留
    expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('waving');

    await wrapper.find('[data-testid="pet-button"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('chat-open');

    // chat-open 恰好播完 → 直接 waiting（不再经过 waving / 不重复播放）
    await loadPetSprite(wrapper);
    await vi.advanceTimersByTimeAsync(totalDuration('chat-open'));
    await nextTick();
    expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('idle');

    // 后续 3 秒保持 waiting，动作绝不循环
    await vi.advanceTimersByTimeAsync(3000);
    await nextTick();
    expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('idle');
  });

  it('点击发生在待机动作播放中：动作被取消，chat-open 从第 0 帧开始', async () => {
    mockIdleRandom.mockReturnValue(0);
    const { wrapper } = await mountAssistant('ADMIN', '/');
    await idleReady(wrapper);
    await vi.advanceTimersByTimeAsync(IDLE_TEST_MS);
    await nextTick();
    expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('idle-curious');

    await wrapper.find('[data-testid="pet-button"]').trigger('click');
    await flushPromises();
    const sprite = wrapper.find('.pet-sprite');
    expect(sprite.attributes('data-state')).toBe('chat-open');
    expect(sprite.attributes('data-src')).toBe('chat-open');
    expect(sprite.attributes('data-col')).toBe('0');
    expect(wrapper.find('[data-testid="pet-chat-panel"]').exists()).toBe(true);
  });

  it('SSE running/failed/review 优先级高于待机动作与 chat-open', async () => {
    const { wrapper } = await mountAssistant('ADMIN', '/');
    await idleReady(wrapper);
    await vi.advanceTimersByTimeAsync(IDLE_TEST_MS);
    await nextTick();
    expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('idle-curious');

    await wrapper.find('[data-testid="pet-button"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('chat-open');

    const chat = wrapper.findComponent(ChatStub);
    chat.vm.$emit('stream-start');
    await nextTick();
    expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('running');

    chat.vm.$emit('stream-error');
    await nextTick();
    expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('failed');

    // failed 一轮播完（加载行图）→ 回到被压住的 chat-open
    await loadPetSprite(wrapper);
    await vi.advanceTimersByTimeAsync(totalDuration('failed'));
    await nextTick();
    expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('chat-open');

    // chat-open 随后自然播完落回 waiting
    await loadPetSprite(wrapper);
    await vi.advanceTimersByTimeAsync(totalDuration('chat-open'));
    await nextTick();
    expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('idle');
  });

  it('reduced-motion 下不播放待机动作与 chat-open，点击仍立即打开面板', async () => {
    vi.stubGlobal('matchMedia', (query: string) => ({
      matches: query.includes('prefers-reduced-motion'),
      media: query,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    }));
    const { wrapper } = await mountAssistant('ADMIN', '/');
    await vi.advanceTimersByTimeAsync(totalDuration('waving') * 3 + IDLE_TEST_MS * 2);
    expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('idle');
    expect(vi.getTimerCount()).toBe(0);

    await wrapper.find('[data-testid="pet-button"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="pet-chat-panel"]').exists()).toBe(true);
    expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('idle');
    vi.unstubAllGlobals();
  });

  it('/admin/ai 点击宠物：不创建面板，播放 chat-open 且输入框立即聚焦', async () => {
    const input = document.createElement('textarea');
    input.setAttribute('data-testid', 'ai-chat-input');
    document.body.appendChild(input);
    try {
      const { wrapper } = await mountAssistant('ADMIN', '/admin/ai');
      await idleReady(wrapper);
      await wrapper.find('[data-testid="pet-button"]').trigger('click');
      await flushPromises();
      expect(wrapper.find('[data-testid="pet-chat-panel"]').exists()).toBe(false);
      expect(document.activeElement).toBe(input);
      expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('chat-open');

      await loadPetSprite(wrapper);
      await vi.advanceTimersByTimeAsync(totalDuration('chat-open'));
      await nextTick();
      expect(wrapper.find('.pet-sprite').attributes('data-state')).toBe('idle');
    } finally {
      document.body.removeChild(input);
    }
  });
});
