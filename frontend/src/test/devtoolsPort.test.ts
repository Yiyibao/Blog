// @vitest-environment node
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import {
  parseDevToolsActivePort, waitForDevToolsActivePort, verifyDevToolsOwnership,
} from '../../scripts/lib/devtools-port.mjs'

const VALID = '9400\n/devtools/browser/4c85ff47-b360-4b78-b870-cc596e3b3475'

function activeDeps(_opts: Record<string, unknown> = {}) {
  return {
    readDevToolsActivePort: async () => null,
    sleep: () => Promise.resolve(),
    localHttpRequest: async (options: { port: number; path: string }) => {
      if (options.path.startsWith('/json/version')) {
        return {
          status: 200, contentType: 'application/json', text: '{}',
          json: { Browser: 'Mock/1.0', webSocketDebuggerUrl: `ws://127.0.0.1:${options.port}/devtools/browser/mock` },
        }
      }
      throw new Error('unexpected ' + options.path)
    },
  }
}

beforeEach(() => {
  vi.useFakeTimers()
})

afterEach(() => {
  vi.useRealTimers()
})

describe('parseDevToolsActivePort', () => {
  it('合法内容（LF）', () => {
    expect(parseDevToolsActivePort(VALID)).toEqual({
      port: 9400,
      browserPath: '/devtools/browser/4c85ff47-b360-4b78-b870-cc596e3b3475',
    })
  })

  it('CRLF 与尾随空行', () => {
    const parsed = parseDevToolsActivePort('9400\r\n/devtools/browser/x\r\n')
    expect(parsed.port).toBe(9400)
  })

  it('BOM 前缀容忍', () => {
    expect(parseDevToolsActivePort('\uFEFF' + VALID).port).toBe(9400)
  })

  it('空文件', () => {
    expect(() => parseDevToolsActivePort('')).toThrow('ACTIVE_PORT_INVALID')
  })

  it('非数字端口', () => {
    expect(() => parseDevToolsActivePort('abc\n/devtools/browser/x')).toThrow('ACTIVE_PORT_INVALID')
  })

  it('端口越界（0/负数/65536）', () => {
    expect(() => parseDevToolsActivePort('0\n/devtools/browser/x')).toThrow('ACTIVE_PORT_INVALID')
    expect(() => parseDevToolsActivePort('-1\n/devtools/browser/x')).toThrow('ACTIVE_PORT_INVALID')
    expect(() => parseDevToolsActivePort('65536\n/devtools/browser/x')).toThrow('ACTIVE_PORT_INVALID')
  })

  it('缺少 browser endpoint', () => {
    expect(() => parseDevToolsActivePort('9400')).toThrow('ACTIVE_PORT_INVALID')
  })

  it('endpoint 非 /devtools/browser/', () => {
    expect(() => parseDevToolsActivePort('9400\n/devtools/page/x')).toThrow('ACTIVE_PORT_INVALID')
  })

  it('外部 URL / 控制字符 / .. / 额外行', () => {
    expect(() => parseDevToolsActivePort('9400\nhttp://evil/devtools/browser/x')).toThrow('ACTIVE_PORT_INVALID')
    expect(() => parseDevToolsActivePort('9400\n/devtools/browser/..')).toThrow('ACTIVE_PORT_INVALID')
    expect(() => parseDevToolsActivePort('9400\n/devtools/browser/x\u0000')).toThrow('ACTIVE_PORT_INVALID')
    expect(() => parseDevToolsActivePort('9400\n/devtools/browser/x\nextra')).toThrow('ACTIVE_PORT_INVALID')
  })
})

describe('waitForDevToolsActivePort', () => {
  it('文件尚未生成后成功', async () => {
    let reads = 0
    const deps = {
      readDevToolsActivePort: async () => {
        reads += 1
        return reads > 2 ? VALID : null
      },
      sleep: () => Promise.resolve(),
    }
    const result = await waitForDevToolsActivePort({
      deps, profileDir: 'C:/tmp/pet-layout-check-x', child: { pid: 1 },
      browserExitedRef: () => null, maxProbes: 10,
    })
    expect(result.port).toBe(9400)
    expect(reads).toBeGreaterThanOrEqual(3)
  })

  it('child 等待期间退出且无后代：PROCESS_EXITED', async () => {
    const deps = {
      readDevToolsActivePort: async () => null,
      sleep: () => Promise.resolve(),
    }
    await expect(waitForDevToolsActivePort({
      deps, profileDir: 'C:/tmp/pet-layout-check-x', child: { pid: 1 },
      browserExitedRef: () => ({ code: 0x80000003, signal: null }),
      isProfileInUse: async () => false,
      maxProbes: 5,
    })).rejects.toThrow('PROCESS_EXITED')
  })

  it('合法后代存活时继续等待直到文件生成', async () => {
    let reads = 0
    const deps = {
      readDevToolsActivePort: async () => {
        reads += 1
        return reads > 2 ? VALID : null
      },
      sleep: () => Promise.resolve(),
    }
    const result = await waitForDevToolsActivePort({
      deps, profileDir: 'C:/tmp/pet-layout-check-x', child: { pid: 1 },
      browserExitedRef: () => ({ code: 1, signal: null }),
      isProfileInUse: async () => true, // 后代存活
      maxProbes: 10,
    })
    expect(result.port).toBe(9400)
  })

  it('超时有界（maxProbes 上限）', async () => {
    const deps = {
      readDevToolsActivePort: async () => null,
      sleep: () => Promise.resolve(),
    }
    await expect(waitForDevToolsActivePort({
      deps, profileDir: 'C:/tmp/pet-layout-check-x', child: { pid: 1 },
      browserExitedRef: () => null, maxProbes: 5,
    })).rejects.toThrow('ACTIVE_PORT_TIMEOUT')
  })
})

describe('verifyDevToolsOwnership', () => {
  const base = {
    port: 9400,
    browserPath: '/devtools/browser/mock',
    profileDir: 'C:/tmp/pet-layout-check-x',
    child: { pid: 4000 },
    stableProbes: 3,
  }

  it('listener PID 查询返回 null：成功且 listenerUnknown=true', async () => {
    const result = await verifyDevToolsOwnership({
      deps: activeDeps({}),
      ...base,
      browserExitedRef: () => null,
      getListenerPid: async () => null,
      maxProbes: 10,
    })
    expect(result.listenerUnknown).toBe(true)
    expect(result.browser).toBe('Mock/1.0')
  })

  it('listener PID 查询抛错（权限失败）：成功', async () => {
    const result = await verifyDevToolsOwnership({
      deps: activeDeps({}),
      ...base,
      browserExitedRef: () => null,
      getListenerPid: async () => { throw new Error('Access denied') },
      maxProbes: 10,
    })
    expect(result.listenerUnknown).toBe(true)
  })

  it('listener PID 明确属于无关进程：OWNERSHIP_MISMATCH 拒绝', async () => {
    await expect(verifyDevToolsOwnership({
      deps: activeDeps({}),
      ...base,
      browserExitedRef: () => null,
      getListenerPid: async () => 999999,
      isProfileInUse: async () => false,
      maxProbes: 10,
    })).rejects.toThrow('OWNERSHIP_MISMATCH')
  })

  it('version webSocketDebuggerUrl 端口不匹配：OWNERSHIP_MISMATCH', async () => {
    const deps = {
      ...activeDeps({}),
      localHttpRequest: async () => ({
        status: 200, contentType: 'application/json', text: '{}',
        json: { Browser: 'Mock/1.0', webSocketDebuggerUrl: 'ws://127.0.0.1:9999/devtools/browser/mock' },
      }),
    }
    await expect(verifyDevToolsOwnership({
      deps, ...base,
      browserExitedRef: () => null,
      getListenerPid: async () => null,
      maxProbes: 5,
    })).rejects.toThrow('OWNERSHIP_MISMATCH')
  })

  it('version endpoint path 不匹配：OWNERSHIP_MISMATCH', async () => {
    const deps = {
      ...activeDeps({}),
      localHttpRequest: async () => ({
        status: 200, contentType: 'application/json', text: '{}',
        json: { Browser: 'Mock/1.0', webSocketDebuggerUrl: 'ws://127.0.0.1:9400/devtools/browser/other' },
      }),
    }
    await expect(verifyDevToolsOwnership({
      deps, ...base,
      browserExitedRef: () => null,
      getListenerPid: async () => null,
      maxProbes: 5,
    })).rejects.toThrow('OWNERSHIP_MISMATCH')
  })

  it('连续三次稳定探测成功', async () => {
    const result = await verifyDevToolsOwnership({
      deps: activeDeps({}),
      ...base,
      browserExitedRef: () => null,
      getListenerPid: async () => 4000,
      maxProbes: 10,
    })
    expect(result.listenerPid).toBe(4000)
    expect(result.browser).toBe('Mock/1.0')
  })

  it('中间一次不稳定（version 失败）后重新计数', async () => {
    let calls = 0
    const deps = {
      ...activeDeps({}),
      localHttpRequest: async (options: { port: number; path: string }) => {
        calls += 1
        if (calls === 2) throw new Error('ECONNRESET')
        return {
          status: 200, contentType: 'application/json', text: '{}',
          json: { Browser: 'Mock/1.0', webSocketDebuggerUrl: `ws://127.0.0.1:${options.port}/devtools/browser/mock` },
        }
      },
    }
    const result = await verifyDevToolsOwnership({
      deps, ...base,
      browserExitedRef: () => null,
      getListenerPid: async () => null,
      maxProbes: 20,
    })
    expect(result.browser).toBe('Mock/1.0')
    expect(calls).toBeGreaterThanOrEqual(4)
  })

  it('Browser 字段变化后重新计数', async () => {
    let calls = 0
    const deps = {
      ...activeDeps({}),
      localHttpRequest: async (options: { port: number }) => {
        calls += 1
        return {
          status: 200, contentType: 'application/json', text: '{}',
          json: {
            Browser: calls === 1 ? 'Old/1.0' : 'Mock/1.0',
            webSocketDebuggerUrl: `ws://127.0.0.1:${options.port}/devtools/browser/mock`,
          },
        }
      },
    }
    const result = await verifyDevToolsOwnership({
      deps, ...base,
      browserExitedRef: () => null,
      getListenerPid: async () => null,
      maxProbes: 20,
    })
    expect(result.browser).toBe('Mock/1.0')
  })

  it('端点/错误消息不泄露完整 profile 路径', async () => {
    const error = await verifyDevToolsOwnership({
      deps: activeDeps({}),
      port: 9400,
      browserPath: '/devtools/browser/mock',
      profileDir: 'C:/Users/Hfff/AppData/Local/Temp/pet-layout-check-secret-a1',
      child: { pid: 4000 },
      browserExitedRef: () => null,
      getListenerPid: async () => 999999,
      isProfileInUse: async () => false,
      maxProbes: 3,
    }).catch((cause) => cause)
    expect(String(error)).not.toContain('pet-layout-check-secret-a1')
    expect(String(error)).toContain('OWNERSHIP_MISMATCH')
  })
})
