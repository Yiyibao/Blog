export const PROFILE_PREFIX: string
export const MAX_INIT_RETRIES: number
export const CDP_PORT_MIN: number
export const CDP_PORT_MAX: number
export const OWNER_FILE: string
export const DIAGNOSTIC_SETTLE_MS: number
export const LOCAL_HTTP_TIMEOUT_MS: number
export const READY_STABLE_PROBES: number
export const DEFAULT_EDGE_ARGS: readonly string[]
export const TARGET_CREATE_FORMATS: readonly {
  method: string
  path: string | ((port: number) => string)
  body?: string
}[]

export function classifyExit(options: {
  code: number | null
  signal?: string | null
  stderr?: string
}): { hex: string | null; signal: string | null; findings: string[] }

import type { LocalHttpResponse } from './local-http.mjs'

export interface BrowserSessionDeps {
  spawn: (...args: unknown[]) => unknown
  sleep: (ms: number) => Promise<void>
  killProcessTree: (pid: number) => Promise<void>
  listBrowserProcesses: () => Promise<{ pid: number; commandLine: string }[]>
  listProfileCandidates: () => Promise<string[]>
  removeDir: (dir: string) => Promise<void>
  readOwnerFile: (dir: string) => Promise<{ pid: number; createdAt?: number } | null>
  /** 读取 DevToolsActivePort（不存在/不可读返回 null）。 */
  readDevToolsActivePort: (dir: string) => Promise<string | null>
  getListenerPid: (port: number) => Promise<number | null>
  isPidAlive: (pid: number) => Promise<boolean>
  now: () => number
  /** 可选注入的端口选择器（默认真实空闲端口探测）。 */
  findFreePort?: () => Promise<number>
  /** 本地 CDP HTTP 客户端（node:http，agent:false + Connection:close；测试可注入 mock）。 */
  localHttpRequest: (options: {
    port: number
    method?: string
    path: string
    body?: string | null
    timeoutMs?: number
    signal?: AbortSignal | null
  }) => Promise<LocalHttpResponse>
  /** WebSocket 构造器：返回 EventTarget 风格对象（真实实现包装 ws 库；测试可注入 mock）。 */
  WebSocket: (url: string) => {
    readyState: number
    addEventListener(type: string, callback: (event: Event) => void): void
    removeEventListener(type: string, callback: (event: Event) => void): void
    send(data: string): void
    close(): void
  }
}

export function makeDeps(overrides?: Partial<BrowserSessionDeps>): BrowserSessionDeps

export interface OwnedProfileInspection {
  ok: boolean
  pathValid: boolean
  expired: boolean
  owner: { pid: number; createdAt?: number } | null
  ownerAlive: boolean | null
  ownerKind: string | null
  inUseByBrowser: boolean | null
  reason: string | null
}

/** 统一安全断言：profile 必须是 resolve(tmpdir()) 的直接子目录 + 前缀 + 受限后缀。 */
export function assertOwnedProfileDir(profileDir: string, options?: { prefix?: string }): string
export function extractUserDataDir(commandLine: string): string | null
export function isAllowedBrowserProcess(commandLine: string): boolean
export function pathEquals(a: string, b: string): boolean
export function isProfileInUseByBrowser(profileDir: string, deps?: BrowserSessionDeps): Promise<boolean | null>
export function inspectOwnedProfile(profileDir: string, deps?: BrowserSessionDeps): Promise<OwnedProfileInspection>
export function cleanupStaleProfiles(deps?: BrowserSessionDeps): Promise<number>
export function killResidualBrowsers(deps?: BrowserSessionDeps): Promise<number>
export function findFreePort(deps?: BrowserSessionDeps, preferPort?: number, min?: number, max?: number): Promise<number>
export function summarizeStderr(stderr: string, limit?: number): string
export function waitCdpReady(options: {
  deps?: BrowserSessionDeps
  cdpPort: number
  child: { pid: number }
  ownedProfile: string
  browserExitedRef?: () => { code: number | null; signal: string | null } | null
  log?: (...args: unknown[]) => void
  timeoutMs?: number
  stableProbes?: number
}): Promise<{ browser: string; listenerPid: number }>
export function createPageTarget(options: {
  deps?: BrowserSessionDeps
  cdpPort: number
  attempt?: number
  maxAttempts?: number
  log?: (...args: unknown[]) => void
  childPid?: number | null
  childExited?: { code: number | null; signal: string | null } | null
}): Promise<{ id: string; type: string; webSocketDebuggerUrl: string }>

export interface BrowserSession {
  child: unknown
  ws: unknown
  client: { send(method: string, params?: unknown): Promise<unknown> }
  cdpPort: number
  targetId: string
  timeline?: { state: string; at: number; detail: string }[]
  runId?: string | null
  close(): Promise<void>
}

export function connectOnce(options: {
  deps?: BrowserSessionDeps
  browserPath: string
  profileDir: string
  cdpPort?: number | null
  injectScript?: string | null
  sendTimeoutMs?: number
  log?: (...args: unknown[]) => void
  attempt?: number
  maxAttempts?: number
  edgeArgs?: readonly string[]
  runId?: string | null
}): Promise<BrowserSession>

export function initBrowserSession(options: {
  deps?: BrowserSessionDeps
  browserPath: string
  profileDir: string
  injectScript?: string | null
  sendTimeoutMs?: number
  log?: (...args: unknown[]) => void
  maxRetries?: number
  edgeArgs?: readonly string[]
}): Promise<BrowserSession>

export function waitChildExit(child: unknown, deps?: BrowserSessionDeps, ownedProfile?: string | null): Promise<void>
