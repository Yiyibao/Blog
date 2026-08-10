// @vitest-environment node
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { EventEmitter } from 'node:events';
import { tmpdir } from 'node:os';
import { join, resolve, basename } from 'node:path';
import {
  initBrowserSession,
  connectOnce,
  createPageTarget,
  killResidualBrowsers,
  cleanupStaleProfiles,
  assertOwnedProfileDir,
  extractUserDataDir,
  isAllowedBrowserProcess,
  inspectOwnedProfile,
  pathEquals,
  waitCdpReady,
  waitChildExit,
  summarizeStderr,
  classifyExit,
  PROFILE_PREFIX,
  MAX_INIT_RETRIES,
  DEFAULT_EDGE_ARGS,
  type BrowserSessionDeps,
} from '../../scripts/lib/browser-session.mjs';
import type { LocalHttpResponse } from '../../scripts/lib/local-http.mjs';

let childCounter = 0;
let targetCounter = 0;
let wsCounter = 0;

class MockWebSocket {
  static OPEN = 1;
  static CLOSED = 3;
  static instances: MockWebSocket[] = [];
  static onConstruct: ((ws: MockWebSocket) => void) | null = null;

  url: string;
  readyState: number;
  listeners: Record<string, ((event: Event) => void)[]>;
  sent: { id: number; method: string; params: unknown; sessionId?: string }[];
  instanceIndex: number;
  closed: boolean;
  failOnSend: ((msg: { id: number; method: string }) => void) | null = null;

  constructor(url: string) {
    this.url = url;
    this.readyState = MockWebSocket.OPEN;
    this.listeners = {};
    this.sent = [];
    this.instanceIndex = wsCounter++;
    this.closed = false;
    MockWebSocket.instances.push(this);
    MockWebSocket.onConstruct?.(this);
    queueMicrotask(() => this.emit('open', new Event('open')));
  }

  addEventListener(type: string, callback: (event: Event) => void) {
    (this.listeners[type] ??= []).push(callback);
  }

  removeEventListener(type: string, callback: (event: Event) => void) {
    this.listeners[type] = (this.listeners[type] ?? []).filter((cb) => cb !== callback);
  }

  send(data: string) {
    if (this.failOnSend) {
      this.failOnSend(JSON.parse(data) as { id: number; method: string });
      if (this.readyState === MockWebSocket.CLOSED) return;
    }
    this.sent.push(JSON.parse(data) as { id: number; method: string; params: unknown; sessionId?: string });
    const message = this.sent[this.sent.length - 1];
    const result =
      message.method === 'Target.attachToTarget' ? { sessionId: `SESSION-${this.instanceIndex + 1}` } : {};
    this.emit(
      'message',
      new MessageEvent('message', {
        data: JSON.stringify({ id: message.id, result }),
      }),
    );
  }

  close() {
    this.closed = true;
    this.readyState = MockWebSocket.CLOSED;
    this.emit('close', Object.assign(new Event('close'), { code: 1000, wasClean: true }));
  }

  emit(type: string, event: Event) {
    for (const callback of this.listeners[type] ?? []) callback(event);
  }
}

interface MockChild extends EventEmitter {
  pid: number;
  exitCode: number | null;
  signalCode: string | null;
  kill: ReturnType<typeof vi.fn>;
}

function createMockChild(): MockChild {
  const child = new EventEmitter() as MockChild;
  child.pid = 4000 + childCounter++;
  child.exitCode = null;
  child.signalCode = null;
  child.kill = vi.fn(() => {
    if (child.exitCode === null) {
      child.exitCode = 0;
      child.emit('exit', 0, null);
    }
  });
  return child;
}

type Mode =
  | 'ok'
  | 'all-500'
  | 'all-non-json'
  | 'all-no-id'
  | 'all-no-url'
  | 'all-not-page'
  | 'all-not-in-list'
  | 'all-throw'
  | 'first-call-fail'
  | 'version-then-dead'
  | 'new-500-then-dead'
  | 'new-500-then-alive'
  | 'econnrefused'
  | 'econnreset'
  | 'timeout';

interface LocalState {
  mode: Mode;
  createdTargets: string[];
  newTargetCalls: number;
  versionCalls: number;
  ports: number[];
  onVersionSuccess?: () => void;
}

function createState(mode: Mode = 'ok'): LocalState {
  return { mode, createdTargets: [], newTargetCalls: 0, versionCalls: 0, ports: [] };
}

function connError(code: string) {
  return Object.assign(new Error(`connect ${code} 127.0.0.1:9400`), {
    code,
    errno: -4078,
    syscall: 'connect',
    address: '127.0.0.1',
    port: 9400,
  });
}

function jsonResponse(json: unknown, status = 200): LocalHttpResponse {
  return { status, contentType: 'application/json', text: JSON.stringify(json), json };
}

function textResponse(status: number, text: string, contentType = 'text/plain'): LocalHttpResponse {
  return { status, contentType, text, json: null };
}

function mockLocalHttp(state: LocalState) {
  return async ({ port, path }: { port: number; path: string }): Promise<LocalHttpResponse> => {
    state.ports.push(port);
    if (path.startsWith('/json/version')) {
      state.versionCalls += 1;
      if (
        (state.mode === 'version-then-dead' || state.mode === 'new-500-then-dead') &&
        state.versionCalls > 4
      ) {
        // readiness(1-3) + createPageTarget 探活(4) 成功后，复查(5) 起失败
        throw connError('ECONNREFUSED');
      }
      if (state.mode === 'all-throw') throw connError('ECONNREFUSED');
      state.onVersionSuccess?.();
      return jsonResponse({ Browser: 'Mock/1.0' });
    }
    if (path.startsWith('/json/new')) {
      state.newTargetCalls += 1;
      const mode = state.mode;
      if (mode === 'version-then-dead' || mode === 'all-throw' || mode === 'econnrefused') {
        throw connError('ECONNREFUSED');
      }
      if (mode === 'econnreset') throw connError('ECONNRESET');
      if (mode === 'timeout') throw Object.assign(new Error('local HTTP 请求超时'), { code: 'ETIMEDOUT' });
      if (mode === 'first-call-fail' && state.newTargetCalls <= 4) {
        return textResponse(500, 'boom');
      }
      if (mode === 'all-500' || mode === 'new-500-then-dead' || mode === 'new-500-then-alive') {
        return textResponse(500, 'Internal Server Error');
      }
      if (mode === 'all-non-json') return textResponse(200, '<html>not json</html>', 'text/html');
      if (mode === 'all-no-id') return jsonResponse({ type: 'page', webSocketDebuggerUrl: 'ws://x' });
      if (mode === 'all-no-url') return jsonResponse({ id: 'T-1', type: 'page' });
      if (mode === 'all-not-page')
        return jsonResponse({ id: 'T-1', type: 'iframe', webSocketDebuggerUrl: 'ws://x' });
      if (mode === 'all-not-in-list') {
        const id = 'TARGET-' + ++targetCounter;
        return jsonResponse({ id, type: 'page', webSocketDebuggerUrl: `ws://127.0.0.1:9/${id}` });
      }
      const id = 'TARGET-' + ++targetCounter;
      state.createdTargets.push(id);
      return jsonResponse({ id, type: 'page', webSocketDebuggerUrl: `ws://127.0.0.1:9/${id}` });
    }
    if (path.startsWith('/json/list')) {
      return jsonResponse(state.createdTargets.map((id) => ({ id })));
    }
    throw new Error('unexpected path ' + path);
  };
}

function makeDeps(overrides: Partial<BrowserSessionDeps> = {}): BrowserSessionDeps {
  let latestChildPid: number | null = null;
  const userSpawn = overrides.spawn;
  // 包装 spawn：无论调用方是否覆盖，都跟踪最新 child pid 供 readiness gate 使用
  const spawn = (...args: Parameters<BrowserSessionDeps['spawn']>) => {
    const child = (userSpawn ?? ((..._innerArgs: unknown[]) => createMockChild()))(...args);
    const pid = (child as { pid?: unknown } | null)?.pid;
    if (typeof pid === 'number') latestChildPid = pid;
    return child;
  };
  const rest = { ...overrides };
  delete (rest as Partial<BrowserSessionDeps>).spawn;
  return {
    spawn,
    sleep: () => Promise.resolve(),
    killProcessTree: async () => {},
    listBrowserProcesses: async () => [],
    listProfileCandidates: async () => [],
    removeDir: async () => {},
    readOwnerFile: async () => null,
    // 默认视为不存在（mock child pid 可能与真实系统进程冲突，需确定性）
    isPidAlive: async () => false,
    // listener PID 可选增强：默认返回本轮 child（测试确定性）
    getListenerPid: overrides.getListenerPid ?? (async () => latestChildPid),
    // 默认 DevToolsActivePort：模拟 Edge 写入（端口 9400 + browser endpoint）
    readDevToolsActivePort: overrides.readDevToolsActivePort ?? (async () => '9400\n/devtools/browser/mock'),
    now: () => 1700000000000,
    localHttpRequest: mockLocalHttp(createState()),
    WebSocket: (url: string) =>
      new MockWebSocket(url) as unknown as ReturnType<BrowserSessionDeps['WebSocket']>,
    ...rest,
  };
}

function profileDir(name: string) {
  return join(tmpdir(), `${PROFILE_PREFIX}${name}`);
}

beforeEach(() => {
  childCounter = 0;
  targetCounter = 0;
  wsCounter = 0;
  MockWebSocket.instances = [];
  MockWebSocket.onConstruct = null;
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
  MockWebSocket.onConstruct = null;
});

describe('P1 会话初始化与重试', () => {
  it('默认 headless 验收参数包含受限 Windows renderer 所需的 --no-sandbox', () => {
    expect(DEFAULT_EDGE_ARGS).toContain('--no-sandbox');
  });

  it('正常初始化：owner.json 写入、Page.enable/Runtime.enable 成功、cleanup 一次', async () => {
    const children: MockChild[] = [];
    const state = createState();
    const deps = makeDeps({
      spawn: () => {
        const c = createMockChild();
        children.push(c);
        return c;
      },
      localHttpRequest: mockLocalHttp(state),
      getListenerPid: async (port: number) => (port === state.ports[0] ? children[0].pid : null),
    });
    const session = await initBrowserSession({
      deps,
      browserPath: 'C:/mock/msedge.exe',
      profileDir: profileDir('test-ok'),
      maxRetries: 2,
    });
    expect(session.targetId).toBe('TARGET-1');
    expect(children).toHaveLength(1);
    expect(MockWebSocket.instances[0].sent.map((m) => m.method)).toEqual([
      'Browser.getVersion',
      'Target.attachToTarget',
      'Page.enable',
      'Runtime.enable',
    ]);
    expect(MockWebSocket.instances[0].sent[2].sessionId).toBe('SESSION-1');
    expect(MockWebSocket.instances[0].sent[3].sessionId).toBe('SESSION-1');
    await session.close();
    expect(MockWebSocket.instances[0].closed).toBe(true);
    expect(children[0].kill).toHaveBeenCalledTimes(1);
  });

  it('Page.enable 失败后整轮重试：第二轮使用新 socket，第一次 socket 已关闭', async () => {
    const children: MockChild[] = [];
    MockWebSocket.onConstruct = (ws) => {
      if (ws.instanceIndex === 0) {
        ws.failOnSend = (msg) => {
          if (msg.method === 'Page.enable') {
            ws.emit('error', new Event('error'));
            ws.emit(
              'close',
              Object.assign(new Event('close'), { code: 1006, reason: 'abnormal', wasClean: false }),
            );
          }
        };
      }
    };
    const deps = makeDeps({
      spawn: () => {
        const c = createMockChild();
        children.push(c);
        return c;
      },
      localHttpRequest: mockLocalHttp(createState()),
    });
    const session = await initBrowserSession({
      deps,
      browserPath: 'C:/mock/msedge.exe',
      profileDir: profileDir('test-retry'),
      maxRetries: 3,
    });
    expect(session.targetId).toBe('TARGET-2');
    expect(children).toHaveLength(2);
    expect(MockWebSocket.instances[0].closed).toBe(true);
    expect(MockWebSocket.instances[1].closed).toBe(false);
  });

  it('重试耗尽后抛出非零错误，且每次重试都启动新浏览器', async () => {
    const children: MockChild[] = [];
    MockWebSocket.onConstruct = (ws) => {
      if (ws.instanceIndex < 2) {
        ws.failOnSend = (msg) => {
          if (msg.method === 'Page.enable') {
            ws.emit('error', new Event('error'));
            ws.emit('close', Object.assign(new Event('close'), { code: 1006, wasClean: false }));
          }
        };
      }
    };
    const deps = makeDeps({
      spawn: () => {
        const c = createMockChild();
        children.push(c);
        return c;
      },
      localHttpRequest: mockLocalHttp(createState()),
    });
    await expect(
      initBrowserSession({
        deps,
        browserPath: 'C:/mock/msedge.exe',
        profileDir: profileDir('test-exhaust'),
        maxRetries: 2,
      }),
    ).rejects.toThrow('重试耗尽');
    expect(children).toHaveLength(2);
  });

  it('浏览器在 CDP 就绪前提前退出：明确报出退出码', async () => {
    const children: MockChild[] = [];
    const deps = makeDeps({
      spawn: () => {
        const c = createMockChild();
        children.push(c);
        queueMicrotask(() => {
          c.exitCode = 1;
          c.emit('exit', 1, null);
        });
        return c;
      },
      localHttpRequest: mockLocalHttp(createState()),
    });
    await expect(
      connectOnce({
        deps,
        browserPath: 'C:/mock/msedge.exe',
        profileDir: profileDir('test-exit'),
        cdpPort: 9999,
      }),
    ).rejects.toThrow('PROCESS_EXITED exitCode=1');
    expect(children[0].exitCode).toBe(1);
  });

  it('version 成功后 child 退出：报出退出码（时间线证据）', async () => {
    const children: MockChild[] = [];
    const state = createState();
    state.onVersionSuccess = () => {
      const child = children[0];
      if (child && child.exitCode === null) {
        child.exitCode = 1;
        child.emit('exit', 1, null);
      }
    };
    const deps = makeDeps({
      spawn: () => {
        const c = createMockChild();
        children.push(c);
        return c;
      },
      localHttpRequest: mockLocalHttp(state),
    });
    await expect(
      connectOnce({
        deps,
        browserPath: 'C:/mock/msedge.exe',
        profileDir: profileDir('test-version-exit'),
        cdpPort: 9998,
      }),
    ).rejects.toThrow('PROCESS_EXITED exitCode=1');
    expect(state.versionCalls).toBeGreaterThanOrEqual(1);
  });

  it('CDP 监听 PID 为合法后代（使用同 profile）时允许连接', async () => {
    const children: MockChild[] = [];
    const state = createState();
    const attemptProfile = profileDir('test-descendant');
    const deps = makeDeps({
      spawn: () => {
        const c = createMockChild();
        children.push(c);
        return c;
      },
      localHttpRequest: mockLocalHttp(state),
      getListenerPid: async () => 7777,
      listBrowserProcesses: async () => [
        {
          pid: 7777,
          commandLine: `"C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe" --user-data-dir="${attemptProfile}"`,
        },
      ],
    });
    const session = await connectOnce({
      deps,
      browserPath: 'C:/mock/msedge.exe',
      profileDir: attemptProfile,
      cdpPort: 9997,
    });
    expect(session.targetId).toBe('TARGET-1');
  });

  it('CDP 监听 PID 被无关进程抢占：拒绝连接（TOCTOU 防护）', async () => {
    const children: MockChild[] = [];
    const deps = makeDeps({
      spawn: () => {
        const c = createMockChild();
        children.push(c);
        return c;
      },
      getListenerPid: async () => 8888,
      listBrowserProcesses: async () => [
        {
          pid: 8888,
          commandLine:
            '"C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe" --user-data-dir="C:\\Users\\me\\AppData\\Local\\Edge"',
        },
      ],
      localHttpRequest: mockLocalHttp(createState()),
    });
    await expect(
      connectOnce({
        deps,
        browserPath: 'C:/mock/msedge.exe',
        profileDir: profileDir('test-touctou'),
        cdpPort: 9996,
      }),
    ).rejects.toThrow('OWNERSHIP_MISMATCH');
    expect(children[0].kill).toHaveBeenCalled();
  });

  it('target 建连失败（target 失效）：两次建连尝试后失败并整体重试', async () => {
    const children: MockChild[] = [];
    MockWebSocket.onConstruct = (ws) => {
      if (ws.instanceIndex === 0 || ws.instanceIndex === 1) {
        queueMicrotask(() => {
          ws.emit('error', new Event('error'));
          ws.emit('close', Object.assign(new Event('close'), { code: 1006, wasClean: false }));
        });
      }
    };
    const deps = makeDeps({
      spawn: () => {
        const c = createMockChild();
        children.push(c);
        return c;
      },
      localHttpRequest: mockLocalHttp(createState()),
    });
    const session = await initBrowserSession({
      deps,
      browserPath: 'C:/mock/msedge.exe',
      profileDir: profileDir('test-target-gone'),
      maxRetries: 3,
    });
    expect(session.targetId).toBe('TARGET-2');
    expect(children).toHaveLength(2);
  });

  it('MAX_INIT_RETRIES 有界', () => {
    expect(MAX_INIT_RETRIES).toBeGreaterThan(0);
    expect(MAX_INIT_RETRIES).toBeLessThanOrEqual(3);
  });
});

describe('P1 /json/new page target 创建与诊断（node:http）', () => {
  function captureDeps(state: LocalState) {
    return makeDeps({
      spawn: () => createMockChild(),
      localHttpRequest: mockLocalHttp(state),
    });
  }

  it('首次调用失败、第二次成功（first-call-fail）', async () => {
    const state = createState('first-call-fail');
    const session = await initBrowserSession({
      deps: captureDeps(state),
      browserPath: 'C:/mock/msedge.exe',
      profileDir: profileDir('test-new-retry'),
      maxRetries: 2,
    });
    expect(session.targetId).toBe('TARGET-1');
    expect(state.newTargetCalls).toBeGreaterThanOrEqual(5);
  });

  it.each([
    ['all-500', 'status=500'],
    ['all-non-json', 'summary='],
    ['all-no-id', 'summary='],
    ['all-no-url', 'summary='],
    ['all-not-page', 'summary='],
    ['all-not-in-list', '/json/list'],
    ['econnrefused', 'ECONNREFUSED'],
    ['econnreset', 'ECONNRESET'],
    ['timeout', '请求超时'],
  ] as const)('%s：失败时输出安全诊断并抛错', async (mode, expectFragment) => {
    const logs: string[] = [];
    const state = createState(mode);
    await expect(
      connectOnce({
        deps: captureDeps(state),
        browserPath: 'C:/mock/msedge.exe',
        profileDir: profileDir('test-new-diag'),
        cdpPort: 9411,
        log: (message: unknown) => logs.push(String(message)),
      }),
    ).rejects.toThrow('无法创建本脚本的 page target');
    const joined = logs.join('\n');
    expect(joined).toContain('创建 page target 失败');
    expect(joined).toContain(expectFragment);
    // 诊断不泄露敏感内容
    expect(joined).not.toMatch(/token=|Authorization|password|api[_-]?key/i);
  });

  it('version 成功后 CDP 消失：立即停止其余格式并整体重试（时间线）', async () => {
    const logs: string[] = [];
    const state = createState('version-then-dead');
    await expect(
      connectOnce({
        deps: captureDeps(state),
        browserPath: 'C:/mock/msedge.exe',
        profileDir: profileDir('test-version-dead'),
        cdpPort: 9412,
        log: (message: unknown) => logs.push(String(message)),
      }),
    ).rejects.toThrow('无法创建本脚本的 page target');
    // 只尝试了第一个格式：CDP 死亡后不再机械执行其余格式
    expect(state.newTargetCalls).toBe(1);
    expect(logs.join('\n')).toContain('CDP 在 target 创建期间死亡');
  });

  it('CDP 已死亡（探活失败）时直接报错，不尝试任何格式', async () => {
    const logs: string[] = [];
    const state = createState('all-throw');
    await expect(
      createPageTarget({
        deps: captureDeps(state),
        cdpPort: 9413,
        log: (message: unknown) => logs.push(String(message)),
      }),
    ).rejects.toThrow('CDP HTTP 服务不可达');
    expect(state.newTargetCalls).toBe(0);
    expect(logs.join('\n')).toContain('CDP 探活失败');
  });

  it('CDP 仍存活时执行有界兼容格式（500 后继续尝试）', async () => {
    const state = createState('new-500-then-alive');
    await expect(
      connectOnce({
        deps: captureDeps(state),
        browserPath: 'C:/mock/msedge.exe',
        profileDir: profileDir('test-500-alive'),
        cdpPort: 9414,
      }),
    ).rejects.toThrow('无法创建本脚本的 page target');
    // 4 种格式全部尝试（CDP 存活）
    expect(state.newTargetCalls).toBe(4);
  });

  it('首次错误不会被后续格式覆盖（诊断保留每格式条目与时间顺序）', async () => {
    const logs: string[] = [];
    const state = createState('all-500');
    await connectOnce({
      deps: captureDeps(state),
      browserPath: 'C:/mock/msedge.exe',
      profileDir: profileDir('test-first-error'),
      cdpPort: 9415,
      log: (message: unknown) => logs.push(String(message)),
    }).catch(() => {});
    const lines = logs.filter((line) => line.includes('- [') && line.includes('PUT'));
    expect(lines.length).toBeGreaterThanOrEqual(2);
    const firstIndex = logs.findIndex((line) => line.includes('- [0ms]'));
    expect(firstIndex).toBeGreaterThanOrEqual(0);
    expect(logs[firstIndex]).toContain('status=500');
  });

  it('重试耗尽：最终错误汇总每轮失败摘要', async () => {
    const state = createState('all-500');
    await expect(
      initBrowserSession({
        deps: captureDeps(state),
        browserPath: 'C:/mock/msedge.exe',
        profileDir: profileDir('test-new-exhaust'),
        maxRetries: 2,
      }),
    ).rejects.toThrow(/重试耗尽.*\[第1次\].*\[第2次\]/);
  });
});

describe('P1 每 attempt 全新 profile/动态端口/进程/target/socket', () => {
  it('三次失败 attempt 的 profile、child PID、target、socket 全部不同，端口由 DevToolsActivePort 提供，且上一轮 profile 已安全清理', async () => {
    const children: MockChild[] = [];
    const state = createState();
    const removedDirs: string[] = [];
    const profileArgs: string[] = [];
    let activePortCalls = 0;
    MockWebSocket.onConstruct = (ws) => {
      if (ws.instanceIndex < 3) {
        ws.failOnSend = (msg) => {
          if (msg.method === 'Page.enable') {
            ws.emit('error', new Event('error'));
            ws.emit('close', Object.assign(new Event('close'), { code: 1006, wasClean: false }));
          }
        };
      }
    };
    const deps = makeDeps({
      spawn: (...args: unknown[]) => {
        const argv = args[1] as string[];
        const dirArg = argv.find((a) => a.startsWith('--user-data-dir='));
        if (dirArg) profileArgs.push(dirArg.slice('--user-data-dir='.length));
        // 每 attempt 使用 --remote-debugging-port=0（Edge 动态选端口）
        expect(argv).toContain('--remote-debugging-port=0');
        const c = createMockChild();
        children.push(c);
        return c;
      },
      localHttpRequest: mockLocalHttp(state),
      // 动态端口：每个 attempt 由 DevToolsActivePort 提供不同端口（模拟 Edge 选择）
      readDevToolsActivePort: async () => {
        activePortCalls += 1;
        const port = 9400 + activePortCalls;
        return `${port}\n/devtools/browser/mock`;
      },
      readOwnerFile: async () => ({ pid: 0 }),
      removeDir: async (dir: string) => {
        removedDirs.push(dir);
      },
    });
    await expect(
      initBrowserSession({
        deps,
        browserPath: 'C:/mock/msedge.exe',
        profileDir: profileDir('test-attempts'),
        maxRetries: 3,
      }),
    ).rejects.toThrow('重试耗尽');

    expect(children).toHaveLength(3);
    expect(new Set(profileArgs).size).toBe(3);
    // 动态端口（ActivePort 提供）每次 attempt 不同
    expect(new Set(state.ports)).toContain(9401);
    expect(new Set(state.ports)).toContain(9402);
    expect(new Set(state.ports)).toContain(9403);
    expect(activePortCalls).toBeGreaterThanOrEqual(3);
    expect(new Set(children.map((c) => c.pid)).size).toBe(3);
    expect(targetCounter).toBe(3);
    expect(MockWebSocket.instances).toHaveLength(3);
    expect(removedDirs.length).toBe(3);
    for (const dir of removedDirs) {
      expect(dir).toMatch(new RegExp(`pet-layout-check-.*-a[123]$`));
    }
    expect(children.length).toBeLessThanOrEqual(MAX_INIT_RETRIES);
  });
});

describe('P1 inspectOwnedProfile 与 cleanupStaleProfiles 活跃保护', () => {
  const tmpRoot = resolve(tmpdir());
  const activeDir = join(tmpRoot, PROFILE_PREFIX + 'active-a1');
  const staleDir = join(tmpRoot, PROFILE_PREFIX + 'stale-b1');

  it('活跃 owner 的 profile 不删除；owner 已退出且无浏览器使用才删除', async () => {
    const removed: string[] = [];
    const deps = makeDeps({
      readOwnerFile: async (dir: string) => ({ pid: dir === activeDir ? 100 : 200 }),
      isPidAlive: async (pid: number) => pid === 100,
      listBrowserProcesses: async () => [],
      removeDir: async (dir: string) => {
        removed.push(dir);
      },
      listProfileCandidates: async () => [activeDir, staleDir],
    });
    expect(await cleanupStaleProfiles(deps)).toBe(1);
    expect(removed).toEqual([staleDir]);
  });

  it('owner 已退出但浏览器仍使用该 profile：不删除', async () => {
    const removed: string[] = [];
    const deps = makeDeps({
      readOwnerFile: async () => ({ pid: 0 }),
      isPidAlive: async () => false,
      listBrowserProcesses: async () => [
        {
          pid: 55,
          commandLine: `"C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe" --user-data-dir="${activeDir}"`,
        },
      ],
      removeDir: async (dir: string) => {
        removed.push(dir);
      },
      listProfileCandidates: async () => [activeDir],
    });
    expect(await cleanupStaleProfiles(deps)).toBe(0);
    expect(removed).toEqual([]);
  });

  it('owner.json 缺失或损坏不删除', async () => {
    const removed: string[] = [];
    const deps = makeDeps({
      readOwnerFile: async () => null,
      listBrowserProcesses: async () => [],
      removeDir: async (dir: string) => {
        removed.push(dir);
      },
      listProfileCandidates: async () => [activeDir, staleDir],
    });
    expect(await cleanupStaleProfiles(deps)).toBe(0);
    expect(removed).toEqual([]);
  });

  it('删除前最后一刻出现浏览器使用：中止删除', async () => {
    const removed: string[] = [];
    let inspections = 0;
    const deps = makeDeps({
      readOwnerFile: async () => ({ pid: 0 }),
      isPidAlive: async () => false,
      listBrowserProcesses: async () => {
        inspections += 1;
        // 第一次（initial）无浏览器；第二次（recheck）出现浏览器使用 → 中止
        return inspections >= 2
          ? [
              {
                pid: 66,
                commandLine: `"C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe" --user-data-dir="${activeDir}"`,
              },
            ]
          : [];
      },
      removeDir: async (dir: string) => {
        removed.push(dir);
      },
      listProfileCandidates: async () => [activeDir],
    });
    expect(await cleanupStaleProfiles(deps)).toBe(0);
    expect(removed).toEqual([]);
  });

  it('进程枚举失败时不删除（安全失败）', async () => {
    const removed: string[] = [];
    const deps = makeDeps({
      readOwnerFile: async () => ({ pid: 0 }),
      isPidAlive: async () => false,
      listBrowserProcesses: async () => {
        throw new Error('CIM down');
      },
      removeDir: async (dir: string) => {
        removed.push(dir);
      },
      listProfileCandidates: async () => [activeDir],
    });
    expect(await cleanupStaleProfiles(deps)).toBe(0);
    expect(removed).toEqual([]);
  });

  it('并发两个会话互不影响：两个 owner 均存活时全部保留且进程不终止', async () => {
    const killed: number[] = [];
    const removed: string[] = [];
    const deps = makeDeps({
      listBrowserProcesses: async () => [
        {
          pid: 71,
          commandLine: `"C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe" --user-data-dir="${activeDir}"`,
        },
        {
          pid: 72,
          commandLine: `"C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe" --user-data-dir="${staleDir}"`,
        },
      ],
      listProfileCandidates: async () => [activeDir, staleDir],
      readOwnerFile: async (dir: string) => ({ pid: dir === activeDir ? 100 : 200 }),
      isPidAlive: async () => true,
      killProcessTree: async (pid: number) => {
        killed.push(pid);
      },
      removeDir: async (dir: string) => {
        removed.push(dir);
      },
    });
    expect(await killResidualBrowsers(deps)).toBe(0);
    expect(await cleanupStaleProfiles(deps)).toBe(0);
    expect(killed).toEqual([]);
    expect(removed).toEqual([]);
  });

  it('Windows 大小写不敏感的 profile 路径匹配（win32）', (context) => {
    if (process.platform !== 'win32') {
      context.skip();
      return;
    }
    const upper = resolve(profileDir('CASE-MIX-a1')).toUpperCase();
    const lower = resolve(profileDir('case-mix-a1')).toLowerCase();
    expect(pathEquals(join(upper, ''), join(lower, ''))).toBe(true);
    expect(pathEquals(profileDir('CaseMix-a1'), profileDir('casemix-a1'))).toBe(true);
  });

  it('大小写变体的 user-data-dir 匹配同 profile（win32，不删除）', async (context) => {
    if (process.platform !== 'win32') {
      context.skip();
      return;
    }
    const removed: string[] = [];
    const variant = resolve(profileDir('case-variant-b1')).toUpperCase();
    const deps = makeDeps({
      readOwnerFile: async () => ({ pid: 0 }),
      isPidAlive: async () => false,
      listBrowserProcesses: async () => [
        {
          pid: 77,
          commandLine: `"C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe" --user-data-dir="${variant}"`,
        },
      ],
      removeDir: async (dir: string) => {
        removed.push(dir);
      },
      listProfileCandidates: async () => [resolve(profileDir('case-variant-b1'))],
    });
    expect(await cleanupStaleProfiles(deps)).toBe(0);
    expect(removed).toEqual([]);
  });

  it('inspectOwnedProfile 四态（路径非法/归属不明/存活/过期）', async () => {
    const deps = makeDeps({
      readOwnerFile: async (dir: string) => (dir === staleDir ? { pid: 200 } : { pid: 100 }),
      isPidAlive: async (pid: number) => pid === 100,
      listBrowserProcesses: async () => [],
    });
    expect(
      (await inspectOwnedProfile(join(tmpRoot, 'Temp-sibling', PROFILE_PREFIX + 'x'), deps)).pathValid,
    ).toBe(false);
    expect((await inspectOwnedProfile(join(tmpRoot, 'unrelated'), deps)).expired).toBe(false);
    expect((await inspectOwnedProfile(activeDir, deps)).expired).toBe(false);
    expect((await inspectOwnedProfile(activeDir, deps)).ownerAlive).toBe(true);
    expect((await inspectOwnedProfile(staleDir, deps)).expired).toBe(true);
  });
});

describe('P1 waitCdpReady readiness gate', () => {
  const profile = profileDir('ready-a1');
  const child = { pid: 4000 };

  function readyDeps({
    versionCallsToFail = 0,
    listenerByCall,
    browserCalls = () => 'Mock/1.0',
  }: {
    versionCallsToFail?: number;
    listenerByCall?: (call: number) => number | null;
    browserCalls?: (call: number) => string;
  } = {}) {
    let versionCalls = 0;
    let listenerCalls = 0;
    return makeDeps({
      localHttpRequest: async ({ path }: { path: string }) => {
        if (path.startsWith('/json/version')) {
          versionCalls += 1;
          if (versionCalls <= versionCallsToFail)
            throw Object.assign(new Error('read ECONNRESET'), { code: 'ECONNRESET' });
          return {
            status: 200,
            contentType: 'application/json',
            text: '{}',
            json: { Browser: browserCalls(versionCalls) },
          };
        }
        throw new Error('unexpected ' + path);
      },
      getListenerPid: async () => {
        listenerCalls += 1;
        return listenerByCall ? listenerByCall(listenerCalls) : child.pid;
      },
      listBrowserProcesses: async () => [],
    });
  }

  it('listenerPid=null 时不得进入就绪（持续不稳定 → 超时失败）', async () => {
    const deps = readyDeps({ listenerByCall: () => null });
    await expect(
      waitCdpReady({
        deps,
        cdpPort: 9400,
        child,
        ownedProfile: profile,
        browserExitedRef: () => null,
        timeoutMs: 2000,
        stableProbes: 3,
      }),
    ).rejects.toThrow('未稳定就绪');
  });

  it('连续 3 次稳定探测（Browser 一致、listener 一致、child 存活）通过', async () => {
    const deps = readyDeps();
    const result = await waitCdpReady({
      deps,
      cdpPort: 9400,
      child,
      ownedProfile: profile,
      browserExitedRef: () => null,
      timeoutMs: 5000,
      stableProbes: 3,
    });
    expect(result.listenerPid).toBe(4000);
    expect(result.browser).toBe('Mock/1.0');
  });

  it('中断（ECONNRESET）后重新累计稳定次数；最终通过', async () => {
    const deps = readyDeps({ versionCallsToFail: 2 });
    const result = await waitCdpReady({
      deps,
      cdpPort: 9400,
      child,
      ownedProfile: profile,
      browserExitedRef: () => null,
      timeoutMs: 5000,
      stableProbes: 3,
    });
    expect(result.browser).toBe('Mock/1.0');
  });

  it('Browser 字段变化：立即失败', async () => {
    const deps = readyDeps({ browserCalls: (call) => (call === 1 ? 'A/1.0' : 'B/1.0') });
    await expect(
      waitCdpReady({
        deps,
        cdpPort: 9400,
        child,
        ownedProfile: profile,
        browserExitedRef: () => null,
        timeoutMs: 5000,
        stableProbes: 3,
      }),
    ).rejects.toThrow('Browser 字段变化');
  });

  it('child 提前退出（实时 getter）：立即失败并报退出码', async () => {
    const deps = readyDeps();
    let exited: { code: number | null; signal: string | null } | null = null;
    const promise = waitCdpReady({
      deps,
      cdpPort: 9400,
      child,
      ownedProfile: profile,
      browserExitedRef: () => exited,
      timeoutMs: 5000,
      stableProbes: 3,
    });
    exited = { code: 1, signal: null };
    await expect(promise).rejects.toThrow('浏览器崩溃退出: exitCode=1');
  });

  it('listener 转移到合法后代（使用同 profile）时通过并报告', async () => {
    const logs: string[] = [];
    const calls = { listener: 0 };
    const deps = makeDeps({
      localHttpRequest: async ({ path }: { path: string }) => {
        if (path.startsWith('/json/version')) {
          return { status: 200, contentType: 'application/json', text: '{}', json: { Browser: 'Mock/1.0' } };
        }
        throw new Error('unexpected ' + path);
      },
      getListenerPid: async () => {
        calls.listener += 1;
        return calls.listener === 1 ? 4000 : 7777;
      },
      listBrowserProcesses: async () => [
        {
          pid: 7777,
          commandLine: `"C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe" --user-data-dir="${profile}"`,
        },
      ],
    });
    const result = await waitCdpReady({
      deps,
      cdpPort: 9400,
      child,
      ownedProfile: profile,
      browserExitedRef: () => null,
      timeoutMs: 5000,
      stableProbes: 3,
      log: (message: unknown) => logs.push(String(message)),
    });
    expect(result.listenerPid).toBe(7777);
    expect(logs.some((line) => line.includes('合法后代'))).toBe(true);
  });

  it('listener 转移到未授权 PID：拒绝', async () => {
    const calls = { listener: 0 };
    const deps = makeDeps({
      localHttpRequest: async ({ path }: { path: string }) => {
        if (path.startsWith('/json/version')) {
          return { status: 200, contentType: 'application/json', text: '{}', json: { Browser: 'Mock/1.0' } };
        }
        throw new Error('unexpected ' + path);
      },
      getListenerPid: async () => {
        calls.listener += 1;
        return calls.listener === 1 ? 4000 : 8888;
      },
      listBrowserProcesses: async () => [],
    });
    await expect(
      waitCdpReady({
        deps,
        cdpPort: 9400,
        child,
        ownedProfile: profile,
        browserExitedRef: () => null,
        timeoutMs: 5000,
        stableProbes: 3,
      }),
    ).rejects.toThrow('不属于本轮进程树');
  });
});

describe('P1 崩溃分类与进程树生命周期', () => {
  it('主进程 0x80000003 分类', () => {
    const result = classifyExit({ code: 2147483651, stderr: 'DevTools listening on ws://127.0.0.1:9400' });
    expect(result.hex).toBe('0x80000003');
    expect(result.findings.join('|')).toContain('STATUS_BREAKPOINT');
  });

  it('GPU 子进程 0xC0000022 分类', () => {
    const result = classifyExit({
      code: null,
      stderr: 'GPU process exited unexpectedly: exit_code=-1073741790',
    });
    expect(result.findings.join('|')).toContain('GPU 子进程意外退出');
    const asCode = classifyExit({ code: -1073741790, stderr: '' });
    expect(asCode.findings.join('|')).toContain('STATUS_ACCESS_DENIED');
  });

  it('GPUPersistentCache 占用日志分类', () => {
    const result = classifyExit({
      code: null,
      stderr:
        'Failed to open persistent cache files in: C:\\Temp\\pet-layout-check-1\\GPUPersistentCache\\DawnGraphiteCache\\... 另一个程序正在使用此文件，进程无法访问 (0x20)',
    });
    expect(result.findings.join('|')).toContain('GPUPersistentCache');
    expect(result.findings.join('|')).toContain('共享冲突');
  });

  it('无崩溃特征时不产生误报分类', () => {
    const result = classifyExit({ code: 0, stderr: 'DevTools listening on ws://127.0.0.1:9400' });
    expect(result.findings).toEqual([]);
  });

  it('waitChildExit 等待使用同 profile 的后代进程退出后才返回', async () => {
    const profile = profileDir('tree-a1');
    const child = createMockChild();
    let descendants = [
      {
        pid: 5000,
        commandLine: `"C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe" --user-data-dir="${profile}"`,
      },
    ];
    let listCalls = 0;
    const deps = makeDeps({
      listBrowserProcesses: async () => {
        listCalls += 1;
        // 前 3 次返回仍有后代；之后后代退出
        if (listCalls > 3) return [];
        return descendants;
      },
      readOwnerFile: async () => ({ pid: 0 }),
      isPidAlive: async () => false,
    });
    await waitChildExit(child, deps, profile);
    expect(child.kill).toHaveBeenCalled();
    expect(listCalls).toBeGreaterThanOrEqual(4);
  });

  it('waitChildExit 在后代迟迟不退时走 taskkill 兜底', async () => {
    const profile = profileDir('tree-b1');
    const child = createMockChild();
    const deps = makeDeps({
      listBrowserProcesses: async () => [
        {
          pid: 5001,
          commandLine: `"C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe" --user-data-dir="${profile}"`,
        },
      ],
      killProcessTree: async () => {},
      readOwnerFile: async () => ({ pid: 0 }),
      isPidAlive: async () => false,
    });
    await waitChildExit(child, deps, profile);
    expect(child.kill).toHaveBeenCalled();
  });

  it('原始 child 退出但合法后代仍存活：readiness 继续而非崩溃', async () => {
    const profile = profileDir('desc-a1');
    const child = { pid: 4000 };
    let exited: { code: number | null; signal: string | null } | null = null;
    const deps = makeDeps({
      localHttpRequest: async ({ path }: { path: string }) => {
        if (path.startsWith('/json/version')) {
          return { status: 200, contentType: 'application/json', text: '{}', json: { Browser: 'Mock/1.0' } };
        }
        throw new Error('unexpected ' + path);
      },
      getListenerPid: async () => 5002,
      listBrowserProcesses: async () => [
        {
          pid: 5002,
          commandLine: `"C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe" --user-data-dir="${profile}"`,
        },
      ],
      readOwnerFile: async () => ({ pid: 0 }),
      isPidAlive: async () => false,
    });
    const result = await waitCdpReady({
      deps,
      cdpPort: 9400,
      child,
      ownedProfile: profile,
      browserExitedRef: () => exited,
      timeoutMs: 5000,
      stableProbes: 3,
    });
    exited = { code: 0x80000003, signal: null }; // 中途 child 崩溃
    // 后代仍存活 → readiness 继续并返回（listener 为合法后代）
    expect(result.listenerPid).toBe(5002);
  });

  it('原始 child 与全部后代均退出：判定崩溃并报退出码', async () => {
    const profile = profileDir('desc-b1');
    const child = { pid: 4000 };
    let exited: { code: number | null; signal: string | null } | null = null;
    const deps = makeDeps({
      localHttpRequest: async ({ path }: { path: string }) => {
        if (path.startsWith('/json/version')) {
          return { status: 200, contentType: 'application/json', text: '{}', json: { Browser: 'Mock/1.0' } };
        }
        throw new Error('unexpected ' + path);
      },
      getListenerPid: async () => 4000,
      listBrowserProcesses: async () => [],
      readOwnerFile: async () => ({ pid: 0 }),
      isPidAlive: async () => false,
    });
    exited = { code: 0x80000003, signal: null };
    await expect(
      waitCdpReady({
        deps,
        cdpPort: 9400,
        child,
        ownedProfile: profile,
        browserExitedRef: () => exited,
        timeoutMs: 2000,
        stableProbes: 3,
      }),
    ).rejects.toThrow('浏览器崩溃退出: exitCode=2147483651');
  });
});

describe('P1 summarizeStderr 脱敏', () => {
  it('只输出关键行且脱敏', () => {
    const stderr = [
      'DevTools listening on ws://127.0.0.1:9400/devtools/browser/x',
      '[INFO] some noise line',
      'ERROR: user-data-dir=C:\\Temp\\pet-layout-check-123 password=SUPER_SECRET_PASSWORD_1',
      'sandbox: something',
      'crash: boom',
    ].join('\n');
    const out = summarizeStderr(stderr);
    expect(out).toContain('DevTools listening');
    expect(out).toContain('sandbox');
    expect(out).toContain('crash');
    expect(out).not.toContain('SUPER_SECRET_PASSWORD_1');
    expect(out).not.toContain('some noise line');
  });
});

describe('P1 profile 路径安全（纯函数，不写盘）', () => {
  const tmpRoot = resolve(tmpdir());

  it('合法临时 profile 通过并返回规范化路径', () => {
    const dir = profileDir('abc123');
    expect(assertOwnedProfileDir(dir)).toBe(resolve(dir));
  });

  it('tmpdir 自身被拒绝', () => {
    expect(() => assertOwnedProfileDir(tmpRoot)).toThrow('直接子目录');
  });

  it('Temp-sibling 同名前缀兄弟目录被拒绝（纯函数，不写盘）', () => {
    const sibling = join(resolve(tmpdir()) + '-sibling', PROFILE_PREFIX + 'danger');
    expect(() => assertOwnedProfileDir(sibling)).toThrow('直接子目录');
  });

  it('.. 越界路径被拒绝', () => {
    const escape = join(tmpRoot, PROFILE_PREFIX + 'x', '..', '..', 'Users', 'pet-layout-check-y');
    expect(() => assertOwnedProfileDir(escape)).toThrow('直接子目录');
  });

  it('工作区、用户目录、根目录被拒绝', () => {
    for (const dir of ['C:/', 'C:/Users', 'C:/Users/Hfff', 'D:/Office/Study/code/BlogDemo']) {
      expect(() => assertOwnedProfileDir(dir)).toThrow();
    }
  });

  it('仅中间目录含前缀被拒绝（非直接子目录）', () => {
    expect(() => assertOwnedProfileDir(join(tmpRoot, 'sub', PROFILE_PREFIX + 'x'))).toThrow('直接子目录');
  });

  it('空后缀被拒绝', () => {
    expect(() => assertOwnedProfileDir(join(tmpRoot, PROFILE_PREFIX))).toThrow('后缀格式非法');
  });

  it('非法后缀（点号/斜杠）被拒绝', () => {
    expect(() => assertOwnedProfileDir(join(tmpRoot, PROFILE_PREFIX + '..'))).toThrow('后缀格式非法');
    expect(() => assertOwnedProfileDir(join(tmpRoot, PROFILE_PREFIX + 'a/b'))).toThrow('直接子目录');
  });

  it('Temp-sibling 危险路径从未进入删除函数（删除调用次数为 0）', async () => {
    const danger = join(resolve(tmpdir()) + '-sibling', PROFILE_PREFIX + 'danger');
    const removeDir = vi.fn(async () => {});
    const deps = makeDeps({
      listProfileCandidates: async () => [danger, join(tmpRoot, PROFILE_PREFIX + 'ok-a1')],
      readOwnerFile: async () => null,
      removeDir,
    });
    await cleanupStaleProfiles(deps);
    expect(removeDir).not.toHaveBeenCalled();
  });

  it('profile 名称以合法 runId 形式通过（pet-layout-check-<runId>-aN）', () => {
    const dir = join(tmpdir(), 'pet-layout-check-12345-m0abc-a1');
    expect(assertOwnedProfileDir(dir)).toBe(resolve(dir));
    expect(basename(dir).startsWith(PROFILE_PREFIX)).toBe(true);
  });
});

describe('P1 killResidualBrowsers 所有权与过期判定', () => {
  const cmd = (dir: string) =>
    `"C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe" --headless=new --user-data-dir="${dir}" about:blank`;

  it('用户浏览器（无脚本前缀）不杀', async () => {
    const killed: number[] = [];
    const deps = makeDeps({
      listBrowserProcesses: async () => [
        {
          pid: 11,
          commandLine:
            '"C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe" --user-data-dir="C:\\Users\\me\\AppData\\Local\\Edge" about:blank',
        },
      ],
      killProcessTree: async (pid: number) => {
        killed.push(pid);
      },
    });
    expect(await killResidualBrowsers(deps)).toBe(0);
    expect(killed).toEqual([]);
  });

  it('活跃脚本实例不杀；过期实例（owner 退出且无浏览器使用）才杀', async () => {
    const active = profileDir('active-a1');
    const stale = profileDir('stale-b1');
    const killed: number[] = [];
    let listCalls = 0;
    const deps = makeDeps({
      // 状态机：第一次（killResidual 遍历来源）返回两个实例；
      // 之后（inspect 复查）返回空 → stale 无浏览器使用者 → 过期可杀
      listBrowserProcesses: async () => {
        listCalls += 1;
        return listCalls === 1
          ? [
              { pid: 21, commandLine: cmd(active) },
              { pid: 22, commandLine: cmd(stale) },
            ]
          : [];
      },
      readOwnerFile: async (dir: string) => ({ pid: dir === active ? 999999 : 888888 }),
      isPidAlive: async (pid: number) => pid === 999999,
      killProcessTree: async (pid: number) => {
        killed.push(pid);
      },
    });
    expect(await killResidualBrowsers(deps)).toBe(1);
    expect(killed).toEqual([22]);
  });

  it('owner 退出但浏览器进程树仍使用该 profile 时：不杀（主进程+后代互相引用）', async () => {
    const active = profileDir('active-a1');
    const killed: number[] = [];
    const deps = makeDeps({
      listBrowserProcesses: async () => [
        { pid: 23, commandLine: cmd(active) },
        { pid: 24, commandLine: cmd(active) }, // 后代进程（GPU 等）使用同 profile
      ],
      readOwnerFile: async () => ({ pid: 0 }),
      isPidAlive: async () => false,
      killProcessTree: async (pid: number) => {
        killed.push(pid);
      },
    });
    expect(await killResidualBrowsers(deps)).toBe(0);
    expect(killed).toEqual([]);
  });

  it('owner.json 缺失（归属不明）不杀', async () => {
    const dir = profileDir('noowner-x1');
    const killed: number[] = [];
    const deps = makeDeps({
      listBrowserProcesses: async () => [{ pid: 31, commandLine: cmd(dir) }],
      readOwnerFile: async () => null,
      killProcessTree: async (pid: number) => {
        killed.push(pid);
      },
    });
    expect(await killResidualBrowsers(deps)).toBe(0);
    expect(killed).toEqual([]);
  });

  it('命令行仅在无关参数中含前缀不杀', async () => {
    const killed: number[] = [];
    const deps = makeDeps({
      listBrowserProcesses: async () => [
        {
          pid: 41,
          commandLine:
            '"C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe" --foo=pet-layout-check-x about:blank',
        },
      ],
      killProcessTree: async (pid: number) => {
        killed.push(pid);
      },
    });
    expect(await killResidualBrowsers(deps)).toBe(0);
    expect(killed).toEqual([]);
  });

  it('畸形或恶意 user-data-dir 不杀', async () => {
    const killed: number[] = [];
    const deps = makeDeps({
      listBrowserProcesses: async () => [
        {
          pid: 51,
          commandLine:
            '"C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe" --user-data-dir="C:\\Users\\me\\pet-layout-check-x"',
        },
        {
          pid: 52,
          commandLine:
            '"C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe" --user-data-dir="C:\\Temp-sibling\\pet-layout-check-danger"',
        },
      ],
      readOwnerFile: async () => ({ pid: 1 }),
      isPidAlive: async () => false,
      killProcessTree: async (pid: number) => {
        killed.push(pid);
      },
    });
    expect(await killResidualBrowsers(deps)).toBe(0);
    expect(killed).toEqual([]);
  });

  it('非 Edge/Chrome 可执行文件不杀', async () => {
    const killed: number[] = [];
    const deps = makeDeps({
      listBrowserProcesses: async () => [
        {
          pid: 61,
          commandLine:
            'notepad.exe --user-data-dir="C:\\Users\\Hfff\\AppData\\Local\\Temp\\pet-layout-check-x"',
        },
      ],
      killProcessTree: async (pid: number) => {
        killed.push(pid);
      },
    });
    expect(await killResidualBrowsers(deps)).toBe(0);
    expect(killed).toEqual([]);
  });

  it('CIM 查询失败时安全失败（返回 0，不扩大清理）', async () => {
    const deps = makeDeps({
      listBrowserProcesses: async () => {
        throw new Error('CIM down');
      },
      killProcessTree: async () => {
        throw new Error('should not be called');
      },
    });
    expect(await killResidualBrowsers(deps)).toBe(0);
  });

  it('extractUserDataDir 解析引号与非引号形式', () => {
    expect(extractUserDataDir('msedge.exe --user-data-dir="C:\\Temp\\pet-layout-check-a"')).toBe(
      'C:\\Temp\\pet-layout-check-a',
    );
    expect(extractUserDataDir('msedge.exe --user-data-dir=C:\\Temp\\pet-layout-check-b')).toBe(
      'C:\\Temp\\pet-layout-check-b',
    );
    expect(extractUserDataDir('msedge.exe about:blank')).toBeNull();
  });

  it('isAllowedBrowserProcess 只认 msedge/chrome（引号内空格安全）', () => {
    expect(
      isAllowedBrowserProcess(
        '"C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe" --headless',
      ),
    ).toBe(true);
    expect(isAllowedBrowserProcess('"C:\\...\\chrome.exe" --headless')).toBe(true);
    expect(isAllowedBrowserProcess('notepad.exe --headless')).toBe(false);
    expect(isAllowedBrowserProcess('')).toBe(false);
  });
});
