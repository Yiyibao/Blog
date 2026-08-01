/** WebSocket 的最小结构类型（真实 WebSocket 与测试 mock 均满足）。 */
export interface CdpSocketLike {
  readyState: number
  addEventListener(type: string, callback: EventListenerOrEventListenerObject): void
  removeEventListener?(type: string, callback: EventListenerOrEventListenerObject): void
  send(data: string): void
}

export interface CdpClient {
  send(
    method: string,
    params?: Record<string, unknown>,
    options?: { sessionId?: string | null },
  ): Promise<Record<string, unknown>>
  setSessionId(sessionId: string | null): void
  getSessionId(): string | null
  rejectAllPending(reason: string): void
  pendingCount(): number
}

export const DEFAULT_SEND_TIMEOUT_MS: number
export const DISCONNECT_SETTLE_MS: number

/** WebSocket URL 脱敏摘要（去除查询参数、截断长 path）。 */
export function redactWsUrl(wsUrl: string | null | undefined): string

export interface CdpDisconnectDiagnostics {
  phase: string
  method: string | null
  pendingCount: number
  readyState: number | string
  errorInfo: string | null
  closeInfo: string | null
}

export function createCdpClient(options: {
  ws: CdpSocketLike
  sendTimeoutMs?: number
  phase?: string
  sessionId?: string | null
  onDisconnect?: (diagnostics: CdpDisconnectDiagnostics) => void
}): CdpClient

export function describePostOpenFailure(options: {
  phase?: string | null
  method?: string | null
  targetId?: string | null
  wsUrl?: string | null
  readyState?: number | string | null
  errorInfo?: string | null
  closeInfo?: string | null
  browserExited?: boolean
  exitCode?: number | null
  exitSignal?: string | null
  targetExists?: boolean | string | null
  childPid?: number | null
  listenerPid?: number | string | null
  cdpAlive?: boolean | null
  profileId?: string | null
  attempt?: number | null
  maxAttempts?: number | null
}): string

export function describeConnectionFailure(options: {
  targetId?: string | null
  wsUrl?: string | null
  browserExited?: boolean
  exitCode?: number | null
  exitSignal?: string | null
  closeInfo?: string | null
  errorInfo?: string | null
  readyState?: number | null
}): string
