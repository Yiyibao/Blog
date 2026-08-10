// @vitest-environment node
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { EventEmitter } from 'node:events';
import { createLocalHttpRequest } from '../../scripts/lib/local-http.mjs';

/** mock node:http request：返回可控 req/res EventEmitter。 */
interface MockRes extends EventEmitter {
  statusCode: number;
  headers: Record<string, string>;
}

interface MockReq extends EventEmitter {
  destroy: ReturnType<typeof vi.fn>;
  lastDestroy: Error | null;
  end: ReturnType<typeof vi.fn>;
  write: ReturnType<typeof vi.fn>;
}

function mockHttpImpl() {
  const calls: {
    options: Record<string, unknown>;
    req: MockReq;
    res: MockRes;
    respond(status: number, body: string): void;
    failRequest(err: Error): void;
    failResponse(err: Error): void;
  }[] = [];
  const impl = (options: Record<string, unknown>, callback: (res: MockRes) => void) => {
    const req = new EventEmitter() as MockReq;
    req.destroy = vi.fn((error?: Error) => {
      req.lastDestroy = error ?? null;
      req.emit('error', error ?? new Error('destroyed'));
    });
    req.end = vi.fn(() => {});
    req.write = vi.fn(() => {});
    req.lastDestroy = null;
    const res = new EventEmitter() as MockRes;
    res.statusCode = 200;
    res.headers = { 'content-type': 'application/json' };
    const entry = {
      options,
      req,
      res,
      respond(status = 200, body = '{"ok":true}') {
        res.statusCode = status;
        callback(res);
        process.nextTick(() => {
          res.emit('data', Buffer.from(body));
          res.emit('end');
        });
      },
      failRequest(err: Error) {
        process.nextTick(() => req.emit('error', err));
      },
      failResponse(err: Error) {
        callback(res);
        process.nextTick(() => res.emit('error', err));
      },
    };
    calls.push(entry);
    return req;
  };
  return { impl, calls };
}

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

describe('localHttpRequest 资源生命周期（单一 settle / 无泄漏）', () => {
  it('成功后推进超过超时时间：不会再次 destroy（timer 已清理）', async () => {
    const { impl, calls } = mockHttpImpl();
    const request = createLocalHttpRequest(impl as never);
    const promise = request({ port: 9400, path: '/json/version', timeoutMs: 5000 });
    calls[0].respond(200, '{"Browser":"x"}');
    const result = await promise;
    expect(result.status).toBe(200);
    expect((result.json as { Browser: string }).Browser).toBe('x');
    await vi.advanceTimersByTimeAsync(6000);
    expect(calls[0].req.destroy).not.toHaveBeenCalled();
    expect(vi.getTimerCount()).toBe(0);
  });

  it('request error（连接层 ECONNRESET）：reject 且清理 timer', async () => {
    const { impl, calls } = mockHttpImpl();
    const request = createLocalHttpRequest(impl as never);
    const promise = request({ port: 9400, path: '/json/version', timeoutMs: 5000 });
    const error = Object.assign(new Error('read ECONNRESET'), {
      code: 'ECONNRESET',
      errno: -4077,
      syscall: 'read',
    });
    calls[0].failRequest(error);
    await expect(promise).rejects.toMatchObject({ code: 'ECONNRESET', syscall: 'read' });
    await vi.advanceTimersByTimeAsync(6000);
    expect(calls[0].req.destroy).not.toHaveBeenCalled();
    expect(vi.getTimerCount()).toBe(0);
  });

  it('response error：reject 且清理 timer', async () => {
    const { impl, calls } = mockHttpImpl();
    const request = createLocalHttpRequest(impl as never);
    const promise = request({ port: 9400, path: '/json/version', timeoutMs: 5000 });
    calls[0].failResponse(Object.assign(new Error('socket hang up'), { code: 'ECONNRESET' }));
    await expect(promise).rejects.toMatchObject({ code: 'ECONNRESET' });
    await vi.advanceTimersByTimeAsync(6000);
    expect(calls[0].req.destroy).not.toHaveBeenCalled();
    expect(vi.getTimerCount()).toBe(0);
  });

  it('timeout：reject 带 ETIMEDOUT，destroy 一次且不再重复 reject', async () => {
    const { impl, calls } = mockHttpImpl();
    const request = createLocalHttpRequest(impl as never);
    const promise = request({ port: 9400, path: '/json/version', timeoutMs: 5000 });
    const assertion = expect(promise).rejects.toMatchObject({ code: 'ETIMEDOUT' });
    await vi.advanceTimersByTimeAsync(5000);
    await assertion;
    expect(calls[0].req.destroy).toHaveBeenCalledTimes(1);
    // 继续推进：destroy 已触发 error，但 promise 已 settle，不再有副作用
    await vi.advanceTimersByTimeAsync(10000);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('abort：reject 带 ABORT_ERR，之后不再 timeout', async () => {
    const { impl, calls } = mockHttpImpl();
    const request = createLocalHttpRequest(impl as never);
    const controller = new AbortController();
    const promise = request({
      port: 9400,
      path: '/json/version',
      timeoutMs: 5000,
      signal: controller.signal,
    });
    const assertion = expect(promise).rejects.toMatchObject({ code: 'ABORT_ERR' });
    controller.abort();
    await assertion;
    await vi.advanceTimersByTimeAsync(10000);
    expect(calls[0].req.destroy).toHaveBeenCalledTimes(1);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('同一请求只 settle 一次（timeout 后响应到达无影响）', async () => {
    const { impl, calls } = mockHttpImpl();
    const request = createLocalHttpRequest(impl as never);
    const promise = request({ port: 9400, path: '/json/version', timeoutMs: 5000 });
    const assertion = expect(promise).rejects.toMatchObject({ code: 'ETIMEDOUT' });
    await vi.advanceTimersByTimeAsync(5000);
    await assertion;
    // 超时后响应才到达：不再 resolve（已 settle）
    calls[0].respond(200, '{"late":true}');
    await vi.advanceTimersByTimeAsync(0);
    await expect(promise).rejects.toMatchObject({ code: 'ETIMEDOUT' });
  });

  it('AbortSignal listener 在 settle 后被移除', async () => {
    const { impl } = mockHttpImpl();
    const request = createLocalHttpRequest(impl as never);
    const controller = new AbortController();
    const removeSpy = vi.spyOn(controller.signal, 'removeEventListener');
    const promise = request({
      port: 9400,
      path: '/json/version',
      timeoutMs: 5000,
      signal: controller.signal,
    });
    controller.abort();
    await expect(promise).rejects.toMatchObject({ code: 'ABORT_ERR' });
    expect(removeSpy).toHaveBeenCalledWith('abort', expect.any(Function));
  });

  it('abort 发生在 signal 已 aborted 时立即失败', async () => {
    const { impl, calls } = mockHttpImpl();
    const request = createLocalHttpRequest(impl as never);
    const controller = new AbortController();
    controller.abort();
    const promise = request({
      port: 9400,
      path: '/json/version',
      timeoutMs: 5000,
      signal: controller.signal,
    });
    await expect(promise).rejects.toMatchObject({ code: 'ABORT_ERR' });
    expect(calls[0].req.destroy).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(6000);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('agent:false 与 Connection:close 已设置（独立连接、无复用）', async () => {
    const { impl, calls } = mockHttpImpl();
    const request = createLocalHttpRequest(impl as never);
    const promise = request({ port: 9400, path: '/json/list' });
    calls[0].respond(200, '[]');
    await promise;
    expect(calls[0].options.agent).toBe(false);
    expect((calls[0].options.headers as Record<string, string>).Connection).toBe('close');
  });
});
