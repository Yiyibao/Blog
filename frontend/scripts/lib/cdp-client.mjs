/**
 * CDP 客户端辅助（供 pet-panel-layout-check.mjs 等脚本与单元测试复用）。
 * - 每个 send() 有有界超时（错误信息含 method）；
 * - WebSocket error/close 时拒绝并清空所有 pending；
 * - WS 未打开时发送立即失败；
 * - error 后等待 close（有界），通过 onDisconnect 回调提供 post-open 脱敏诊断。
 */

export const DEFAULT_SEND_TIMEOUT_MS = 15000
/** error 后等待 close 事件的严格上界（毫秒），避免脚本挂起。 */
export const DISCONNECT_SETTLE_MS = 1500

/** 把 wsUrl 转成不含查询参数、截断 path 的安全摘要。 */
export function redactWsUrl(wsUrl) {
  const raw = String(wsUrl ?? '')
  if (!raw) return 'unknown'
  const withoutQuery = raw.split('?')[0]
  return withoutQuery.replace(/^ws:\/\/([^/]+)\/(.+)$/, (_, host, rest) => {
    const short = rest.length > 16 ? `${rest.slice(0, 8)}…${rest.slice(-8)}` : rest
    return `ws://${host}/${short}`
  })
}

/**
 * 创建 CDP 客户端。
 * @param {object} options
 * @param {object} options.ws            WebSocket 或等价的 mock（readyState/addEventListener/send）
 * @param {number} [options.sendTimeoutMs]
 * @param {string} [options.phase]       当前阶段：'post-open' | 'command-response'（用于诊断）
 * @param {(diagnostics: object) => void} [options.onDisconnect]
 *       连接在 open 后断开时调用；参数包含 method/phase/readyState/error/close/browser 信息，
 *       全部脱敏。onDisconnect 内部不得抛异常。
 */
export function createCdpClient({
  ws,
  sendTimeoutMs = DEFAULT_SEND_TIMEOUT_MS,
  phase = 'command-response',
  onDisconnect,
  sessionId: initialSessionId = null,
}) {
  let nextId = 0
  const pending = new Map()
  let settled = false
  let hadError = false
  let lastErrorInfo = null
  let lastCloseInfo = null
  let lastFailedMethods = []
  let settleTimer = null
  let defaultSessionId = initialSessionId

  const rejectAllPending = (reason) => {
    const entries = [...pending.entries()]
    pending.clear()
    lastFailedMethods = [...new Set(entries.map(([, entry]) => entry.method).filter(Boolean))]
    for (const [, entry] of entries) {
      clearTimeout(entry.timer)
      entry.reject(new Error(`CDP 连接中断（${reason}），等待中的请求全部失败: ${entry.method}`))
    }
  }

  const emitDiagnostics = () => {
    if (settled || !onDisconnect) return
    settled = true
    if (settleTimer !== null) {
      clearTimeout(settleTimer)
      settleTimer = null
    }
    try {
      onDisconnect({
        phase,
        method: lastFailedMethods.length ? lastFailedMethods.join(',') : null,
        pendingCount: lastFailedMethods.length,
        readyState: typeof ws.readyState === 'number' ? ws.readyState : 'unknown',
        errorInfo: lastErrorInfo,
        closeInfo: lastCloseInfo,
      })
    } catch {
      // 诊断回调失败不得影响控制流
    }
  }

  // error 可能先于 close；等待有界的 close 信息后统一上报，避免挂起。
  // 客户端主动 close()（clean close 且无 error）属正常关闭，不触发诊断。
  const scheduleSettle = () => {
    if (settled || settleTimer !== null) return
    settleTimer = setTimeout(() => {
      settleTimer = null
      emitDiagnostics()
    }, DISCONNECT_SETTLE_MS)
  }

  ws.addEventListener('message', (event) => {
    let message
    try {
      message = JSON.parse(String(event.data))
    } catch {
      return
    }
    if (!message || typeof message.id !== 'number' || !pending.has(message.id)) return
    const entry = pending.get(message.id)
    pending.delete(message.id)
    clearTimeout(entry.timer)
    if (message.error) entry.reject(new Error(`${entry.method}: ${message.error.message}`))
    else entry.resolve(message.result)
  })
  ws.addEventListener('error', (event) => {
    hadError = true
    lastErrorInfo = event && event.message ? String(event.message) : 'error 事件（无附加详情）'
    // 断开信号立即拒绝 pending，不等 close（close 信息仅用于诊断收集）
    rejectAllPending('error')
    scheduleSettle()
  })
  ws.addEventListener('close', (event) => {
    lastCloseInfo = event && typeof event.code === 'number'
      ? `code=${event.code}${event.reason ? ` reason=${JSON.stringify(String(event.reason))}` : ''}`
      : 'close 事件'
    const clean = event && typeof event.wasClean === 'boolean'
      ? event.wasClean
      : (event && typeof event.code === 'number' ? event.code === 1000 : false)
    if (!hadError && clean) return
    rejectAllPending(lastCloseInfo ?? 'close')
    scheduleSettle()
  })

  function send(method, params = {}, options = {}) {
    return new Promise((resolve, reject) => {
      if (typeof ws.readyState === 'number' && ws.readyState !== WebSocket.OPEN) {
        reject(new Error(`CDP 发送失败（WS 未打开，readyState=${ws.readyState}）: ${method}`))
        return
      }
      const id = ++nextId
      const routedSessionId = options.sessionId !== undefined
        ? options.sessionId
        : defaultSessionId
      const timer = setTimeout(() => {
        pending.delete(id)
        reject(new Error(`CDP send 超时（${sendTimeoutMs}ms）: ${method}${routedSessionId ? ' (session)' : ' (browser)'}`))
      }, sendTimeoutMs)
      pending.set(id, { method, sessionId: routedSessionId, timer, resolve, reject })
      try {
        const message = { id, method, params }
        if (routedSessionId) message.sessionId = routedSessionId
        ws.send(JSON.stringify(message))
      } catch (cause) {
        clearTimeout(timer)
        pending.delete(id)
        reject(new Error(`CDP send 抛出异常: ${method}: ${cause instanceof Error ? cause.message : String(cause)}`))
      }
    })
  }

  function setSessionId(sessionId) {
    defaultSessionId = sessionId || null
  }

  return {
    send,
    setSessionId,
    getSessionId: () => defaultSessionId,
    rejectAllPending,
    pendingCount: () => pending.size,
  }
}

/** WebSocket 建连失败（open 之前）的脱敏诊断。 */
export function describeConnectionFailure({
  targetId,
  wsUrl,
  browserExited,
  exitCode,
  exitSignal,
  closeInfo,
  errorInfo,
  readyState,
}) {
  const lines = ['CDP WebSocket 建连失败，诊断信息：']
  lines.push(`  targetId=${targetId ?? 'unknown'}`)
  lines.push(`  wsUrl=${redactWsUrl(wsUrl)}`)
  lines.push(`  readyState=${readyState ?? 'unknown'}`)
  if (browserExited) {
    lines.push(`  浏览器已提前退出: exitCode=${exitCode ?? 'unknown'} signal=${exitSignal ?? 'none'}`)
  } else {
    lines.push('  浏览器进程存活')
  }
  if (closeInfo) lines.push(`  close 事件: ${closeInfo}`)
  if (errorInfo) lines.push(`  error 事件: ${errorInfo}`)
  return lines.join('\n')
}

/** WebSocket open 之后断开的脱敏诊断（P1-2，生产路径调用）。 */
export function describePostOpenFailure({
  phase,
  method,
  targetId,
  wsUrl,
  readyState,
  errorInfo,
  closeInfo,
  browserExited,
  exitCode,
  exitSignal,
  targetExists,
  childPid,
  listenerPid,
  cdpAlive,
  profileId,
  attempt,
  maxAttempts,
}) {
  const lines = ['CDP 连接在 open 后断开，诊断信息：']
  lines.push(`  阶段=${phase ?? 'unknown'}（initial-connect=建连前，post-open=建连后首个命令，command-response=响应阶段）`)
  if (method) lines.push(`  失败请求 method=${method}`)
  if (attempt != null) lines.push(`  初始化尝试 attempt=${attempt}/${maxAttempts ?? '?'}`)
  lines.push(`  targetId=${targetId ?? 'unknown'}`)
  lines.push(`  wsUrl=${redactWsUrl(wsUrl)}`)
  lines.push(`  readyState=${readyState ?? 'unknown'}`)
  if (childPid != null) lines.push(`  本轮 Edge 主进程 pid=${childPid}`)
  if (listenerPid != null) lines.push(`  CDP 端口监听 pid=${listenerPid}`)
  if (cdpAlive != null) lines.push(`  CDP HTTP /json/version 可响应=${cdpAlive}`)
  if (profileId) lines.push(`  profile=${profileId}`)
  if (errorInfo) lines.push(`  error 事件: ${errorInfo}`)
  if (closeInfo) lines.push(`  close 事件: ${closeInfo}`)
  if (browserExited) {
    lines.push(`  浏览器已提前退出: exitCode=${exitCode ?? 'unknown'} signal=${exitSignal ?? 'none'}`)
  } else {
    lines.push('  浏览器进程存活')
  }
  lines.push(`  target 仍存在=${targetExists ?? 'unknown'}`)
  return lines.join('\n')
}
