export interface ActivePortInfo {
  port: number
  browserPath: string
}

export function parseDevToolsActivePort(content: string): ActivePortInfo

export function waitForDevToolsActivePort(options: {
  deps: { readDevToolsActivePort(dir: string): Promise<string | null>; sleep(ms: number): Promise<void> }
  profileDir: string
  child: { pid: number }
  browserExitedRef?: () => { code: number | null; signal: string | null } | null
  isProfileInUse?: (profileDir: string) => Promise<boolean | null>
  timeoutMs?: number
  pollMs?: number
  maxProbes?: number
}): Promise<ActivePortInfo>

export function verifyDevToolsOwnership(options: {
  deps: { localHttpRequest(options: {
    port: number
    method?: string
    path: string
    body?: string | null
    timeoutMs?: number
    signal?: AbortSignal | null
  }): Promise<{ status: number; contentType: string | null; text: string; json: unknown }> }
  port: number
  browserPath: string
  profileDir: string
  child: { pid: number }
  browserExitedRef?: () => { code: number | null; signal: string | null } | null
  isProfileInUse?: (profileDir: string) => Promise<boolean | null>
  getListenerPid?: (port: number) => Promise<number | null>
  stableProbes?: number
  timeoutMs?: number
  pollMs?: number
  maxProbes?: number
}): Promise<{
  browser: string
  listenerPid: number | null
  listenerUnknown: boolean
  port: number
  browserPath: string
}>
