// @vitest-environment node
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import {
  createCdpClient,
  describeConnectionFailure,
  describePostOpenFailure,
  redactWsUrl,
  DISCONNECT_SETTLE_MS,
  DEFAULT_SEND_TIMEOUT_MS,
  type CdpDisconnectDiagnostics,
} from '../../scripts/lib/cdp-client.mjs';

/** EventTarget 模拟 WebSocket：可手动派发 message/error/close。 */
function createMockWs() {
  const target = new EventTarget();
  const sent: Record<string, unknown>[] = [];
  const ws = {
    readyState: WebSocket.OPEN as number,
    addEventListener: (type: string, callback: EventListenerOrEventListenerObject) =>
      target.addEventListener(type, callback as EventListener),
    removeEventListener: (type: string, callback: EventListenerOrEventListenerObject) =>
      target.removeEventListener(type, callback as EventListener),
    send: (data: string) => {
      sent.push(JSON.parse(data) as Record<string, unknown>);
    },
    dispatch: (event: Event) => target.dispatchEvent(event),
    sent,
  };
  return ws;
}

function reply(ws: ReturnType<typeof createMockWs>, id: number, payload: Record<string, unknown>) {
  ws.dispatch(new MessageEvent('message', { data: JSON.stringify({ id, ...payload }) }));
}

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

describe('createCdpClient', () => {
  it('正常响应 resolve 结果', async () => {
    const ws = createMockWs();
    const client = createCdpClient({ ws });
    const promise = client.send('Page.enable');
    expect(ws.sent[0]).toEqual({ id: 1, method: 'Page.enable', params: {} });
    reply(ws, 1, { result: { ok: true } });
    await expect(promise).resolves.toEqual({ ok: true });
  });

  it('浏览器级命令与扁平化页面会话使用正确 sessionId', async () => {
    const ws = createMockWs();
    const client = createCdpClient({ ws });

    client.setSessionId('SESSION-1');
    expect(client.getSessionId()).toBe('SESSION-1');

    const pagePromise = client.send('Page.enable');
    expect(ws.sent[0]).toEqual({
      id: 1,
      method: 'Page.enable',
      params: {},
      sessionId: 'SESSION-1',
    });
    reply(ws, 1, { result: {} });
    await expect(pagePromise).resolves.toEqual({});

    const browserPromise = client.send('Browser.getVersion', {}, { sessionId: null });
    expect(ws.sent[1]).toEqual({ id: 2, method: 'Browser.getVersion', params: {} });
    reply(ws, 2, { result: { product: 'Mock/1.0' } });
    await expect(browserPromise).resolves.toEqual({ product: 'Mock/1.0' });
  });

  it('错误响应 reject 且错误信息包含 method', async () => {
    const ws = createMockWs();
    const client = createCdpClient({ ws });
    const promise = client.send('Runtime.evaluate');
    reply(ws, 1, { error: { message: 'boom' } });
    await expect(promise).rejects.toThrow('Runtime.evaluate: boom');
  });

  it('请求超时（15s 有界）且错误信息包含 method', async () => {
    const ws = createMockWs();
    const client = createCdpClient({ ws });
    const promise = client.send('Page.navigate');
    const assertion = expect(promise).rejects.toThrow(
      `CDP send 超时（${DEFAULT_SEND_TIMEOUT_MS}ms）: Page.navigate`,
    );
    await vi.advanceTimersByTimeAsync(DEFAULT_SEND_TIMEOUT_MS);
    await assertion;
  });

  it('error 事件拒绝并清空所有 pending', async () => {
    const ws = createMockWs();
    const client = createCdpClient({ ws });
    const p1 = client.send('Page.enable');
    const p2 = client.send('Runtime.enable');
    ws.dispatch(new Event('error'));
    await expect(p1).rejects.toThrow('CDP 连接中断（error）');
    await expect(p2).rejects.toThrow('CDP 连接中断（error）');
    expect(client.pendingCount()).toBe(0);
  });

  it('close 事件拒绝所有 pending（错误信息含 code/reason）', async () => {
    const ws = createMockWs();
    const client = createCdpClient({ ws });
    const promise = client.send('Page.navigate');
    const closeEvent = Object.assign(new Event('close'), { code: 1006, reason: 'abnormal' });
    ws.dispatch(closeEvent);
    await expect(promise).rejects.toThrow('code=1006');
    await expect(promise).rejects.toThrow('abnormal');
    expect(client.pendingCount()).toBe(0);
  });

  it('WS 未打开时 send 立即失败且不占用 pending', async () => {
    const ws = createMockWs();
    const client = createCdpClient({ ws });
    ws.readyState = WebSocket.CLOSED;
    await expect(client.send('Page.enable')).rejects.toThrow('WS 未打开');
    expect(client.pendingCount()).toBe(0);
  });

  it('send 抛出异常时以错误形式返回且清理 pending', async () => {
    const ws = createMockWs();
    ws.send = () => {
      throw new Error('socket dead');
    };
    const client = createCdpClient({ ws });
    await expect(client.send('Page.enable')).rejects.toThrow('socket dead');
    expect(client.pendingCount()).toBe(0);
  });

  it('close 后新 send 立即失败（不挂起）', async () => {
    const ws = createMockWs();
    const client = createCdpClient({ ws });
    ws.readyState = WebSocket.CLOSED;
    await expect(client.send('Runtime.evaluate')).rejects.toThrow('WS 未打开');
  });
});

describe('describeConnectionFailure 诊断（脱敏）', () => {
  it('包含 targetId、URL 安全摘要与浏览器退出码', () => {
    const report = describeConnectionFailure({
      targetId: 'ABC1234567890DEF',
      wsUrl: 'ws://127.0.0.1:9223/devtools/page/ABC1234567890DEF',
      browserExited: true,
      exitCode: 1,
      exitSignal: 'SIGTERM',
      closeInfo: 'code=1006 reason=""',
    });
    expect(report).toContain('targetId=ABC1234567890DEF');
    expect(report).toContain('ws://127.0.0.1:9223/devtools');
    expect(report).toContain('浏览器已提前退出: exitCode=1 signal=SIGTERM');
    expect(report).toContain('close 事件: code=1006');
  });

  it('浏览器存活时不谎报退出', () => {
    const report = describeConnectionFailure({
      targetId: 'T1',
      wsUrl: 'ws://127.0.0.1:9223/devtools/page/T1',
      browserExited: false,
      errorInfo: 'error 事件（无附加详情）',
    });
    expect(report).toContain('浏览器进程存活');
    expect(report).toContain('error 事件');
  });
});

describe('P1 post-open 断开（open 后 error/close）', () => {
  function createMockWs() {
    const target = new EventTarget();
    const sent: Record<string, unknown>[] = [];
    const ws = {
      readyState: WebSocket.OPEN as number,
      addEventListener: (type: string, callback: EventListenerOrEventListenerObject) =>
        target.addEventListener(type, callback as EventListener),
      removeEventListener: (type: string, callback: EventListenerOrEventListenerObject) =>
        target.removeEventListener(type, callback as EventListener),
      send: (data: string) => {
        sent.push(JSON.parse(data) as Record<string, unknown>);
      },
      dispatch: (event: Event) => target.dispatchEvent(event),
      sent,
    };
    return ws;
  }

  it('open 后首个 CDP 请求期间触发 error：pending 被拒绝且诊断包含 method 与阶段', async () => {
    const ws = createMockWs();
    const diagnostics: CdpDisconnectDiagnostics[] = [];
    const client = createCdpClient({ ws, phase: 'post-open', onDisconnect: (d) => diagnostics.push(d) });
    const promise = client.send('Page.enable');
    ws.dispatch(new Event('error'));
    await expect(promise).rejects.toThrow('Page.enable');
    expect(client.pendingCount()).toBe(0);
    await vi.advanceTimersByTimeAsync(DISCONNECT_SETTLE_MS);
    expect(diagnostics).toHaveLength(1);
    expect(diagnostics[0].phase).toBe('post-open');
    expect(diagnostics[0].method).toBe('Page.enable');
    expect(diagnostics[0].errorInfo).toContain('error');
  });

  it('error 后紧接 close：诊断收集 close code/reason', async () => {
    const ws = createMockWs();
    const diagnostics: CdpDisconnectDiagnostics[] = [];
    const client = createCdpClient({ ws, onDisconnect: (d) => diagnostics.push(d) });
    const promise = client.send('Page.enable');
    ws.dispatch(new Event('error'));
    ws.dispatch(Object.assign(new Event('close'), { code: 1006, reason: 'abnormal', wasClean: false }));
    await expect(promise).rejects.toThrow();
    await vi.advanceTimersByTimeAsync(DISCONNECT_SETTLE_MS);
    expect(diagnostics[0].closeInfo).toContain('code=1006');
    expect(diagnostics[0].closeInfo).toContain('abnormal');
  });

  it('只有 error、没有 close：在有界等待（DISCONNECT_SETTLE_MS）后结束，不挂起', async () => {
    const ws = createMockWs();
    const diagnostics: CdpDisconnectDiagnostics[] = [];
    const client = createCdpClient({ ws, onDisconnect: (d) => diagnostics.push(d) });
    const promise = client.send('Runtime.evaluate');
    ws.dispatch(new Event('error'));
    await expect(promise).rejects.toThrow();
    await vi.advanceTimersByTimeAsync(DISCONNECT_SETTLE_MS - 1);
    expect(diagnostics).toHaveLength(0);
    await vi.advanceTimersByTimeAsync(1);
    expect(diagnostics).toHaveLength(1);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('客户端主动 close（clean、无 error）不触发断开诊断', async () => {
    const ws = createMockWs();
    const diagnostics: CdpDisconnectDiagnostics[] = [];
    const client = createCdpClient({ ws, onDisconnect: (d) => diagnostics.push(d) });
    ws.dispatch(Object.assign(new Event('close'), { code: 1000, wasClean: true }));
    await vi.advanceTimersByTimeAsync(DISCONNECT_SETTLE_MS * 2);
    expect(diagnostics).toHaveLength(0);
    expect(client.pendingCount()).toBe(0);
  });

  it('close(1006) 且无 error 同样触发断开诊断', async () => {
    const ws = createMockWs();
    const diagnostics: CdpDisconnectDiagnostics[] = [];
    const client = createCdpClient({ ws, onDisconnect: (d) => diagnostics.push(d) });
    const promise = client.send('Page.navigate');
    ws.dispatch(Object.assign(new Event('close'), { code: 1006, wasClean: false }));
    await expect(promise).rejects.toThrow('Page.navigate');
    await vi.advanceTimersByTimeAsync(DISCONNECT_SETTLE_MS);
    expect(diagnostics[0].closeInfo).toContain('code=1006');
    expect(client.pendingCount()).toBe(0);
  });

  it('describePostOpenFailure 诊断含 method/阶段且不泄露完整 URL 或请求内容', () => {
    const report = describePostOpenFailure({
      phase: 'post-open',
      method: 'Page.enable',
      targetId: 'TARGET-1234567890',
      wsUrl: 'ws://127.0.0.1:9420/devtools/page/TARGET-1234567890?token=SECRET-QUERY',
      readyState: 3,
      errorInfo: 'error 事件',
      closeInfo: 'code=1006',
      browserExited: false,
      targetExists: true,
    });
    expect(report).toContain('阶段=post-open');
    expect(report).toContain('method=Page.enable');
    expect(report).toContain('targetId=TARGET-1234567890');
    expect(report).toContain('code=1006');
    // 脱敏：不泄露查询参数
    expect(report).not.toContain('SECRET-QUERY');
    expect(report).not.toContain('token=');
  });

  it('redactWsUrl 去除查询参数并截断长 path', () => {
    const url = 'ws://127.0.0.1:9420/devtools/page/ABCDEFGHIJKLMNOPQRST?token=secret';
    const safe = redactWsUrl(url);
    expect(safe).toContain('ws://127.0.0.1:9420/devtools');
    expect(safe).not.toContain('token');
    expect(safe).not.toContain('secret');
    expect(redactWsUrl(null)).toBe('unknown');
  });
});
