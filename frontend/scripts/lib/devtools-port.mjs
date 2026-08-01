/**
 * DevToolsActivePort 所有权机制（Chromium/Edge 自带）：
 * 使用 --remote-debugging-port=0 让 Edge 自选端口并写入
 * <profile>/DevToolsActivePort（第一行端口、第二行 /devtools/browser/<id>），
 * 通过独占 profile + ActivePort + /json/version 三方匹配建立 CDP 所有权，
 * 不再把系统 listener PID 查询作为硬条件（受限环境可返回 null/无权限）。
 */

/** 解析 DevToolsActivePort 内容（纯函数；失败抛错并带原因）。 */
export function parseDevToolsActivePort(content) {
  const MAX_BYTES = 4096
  if (typeof content !== 'string') throw new Error('ACTIVE_PORT_INVALID：内容为空')
  if (Buffer.byteLength(content, 'utf8') > MAX_BYTES) throw new Error('ACTIVE_PORT_INVALID：文件超过 4KB')
  // 去 BOM、统一 CRLF/LF
  const text = content.replace(/^\uFEFF/, '')
  const lines = text.split(/\r?\n/).map((line) => line.trim())
  // 去尾部空行；其余行必须恰好 2 行
  while (lines.length && lines[lines.length - 1] === '') lines.pop()
  if (lines.length !== 2) throw new Error(`ACTIVE_PORT_INVALID：应有恰好 2 行，实际 ${lines.length} 行`)
  // 控制字符检查
  if (/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/.test(text)) {
    throw new Error('ACTIVE_PORT_INVALID：包含控制字符')
  }
  const portLine = lines[0]
  if (!/^\d{1,5}$/.test(portLine)) throw new Error('ACTIVE_PORT_INVALID：端口非数字')
  const port = Number(portLine)
  if (!Number.isInteger(port) || port <= 0 || port > 65535) {
    throw new Error('ACTIVE_PORT_INVALID：端口越界（0/负数/>65535）')
  }
  const browserPath = lines[1]
  if (!/^\/devtools\/browser\/[A-Za-z0-9_-]+$/.test(browserPath)) {
    throw new Error('ACTIVE_PORT_INVALID：第二行不是 /devtools/browser/<id> 形式')
  }
  if (/\.\./.test(browserPath) || /^[a-z]+:\/\//i.test(browserPath)) {
    throw new Error('ACTIVE_PORT_INVALID：endpoint 含外部 URL 或 ..')
  }
  return { port, browserPath }
}

/**
 * 有界等待 DevToolsActivePort 生成：
 * - 文件暂未生成时短轮询；
 * - child 或全部合法后代退出时立即失败（PROCESS_EXITED）；
 * - 超时上限 timeoutMs（建议 10–15s）；
 * - 半写/非法文件继续轮询，超时抛 ACTIVE_PORT_TIMEOUT。
 */
export async function waitForDevToolsActivePort({
  deps,
  profileDir,
  child,
  browserExitedRef = () => null,
  isProfileInUse = async () => false,
  timeoutMs = 15000,
  pollMs = 150,
  maxProbes = Math.ceil(15000 / 150) + 1,
}) {
  const deadline = Date.now() + timeoutMs
  let probeCount = 0
  while (probeCount < maxProbes && Date.now() < deadline) {
    probeCount += 1
    const exited = typeof browserExitedRef === 'function' ? browserExitedRef() : null
    if (exited) {
      const descendantsAlive = await isProfileInUse(profileDir)
      if (descendantsAlive !== true) {
        throw Object.assign(new Error(`PROCESS_EXITED exitCode=${exited.code} signal=${exited.signal}`), {
          stage: 'PROCESS_EXITED',
        })
      }
    }
    let raw = null
    try {
      raw = await deps.readDevToolsActivePort(profileDir)
    } catch {
      raw = null
    }
    if (raw !== null) {
      try {
        return parseDevToolsActivePort(raw)
      } catch {
        // 半写/损坏文件：继续短轮询（超时统一抛 ACTIVE_PORT_TIMEOUT）
      }
    }
    await deps.sleep(pollMs)
  }
  throw Object.assign(
    new Error(`ACTIVE_PORT_TIMEOUT：DevToolsActivePort 未在 ${timeoutMs}ms 内生成`),
    { stage: 'ACTIVE_PORT_TIMEOUT' },
  )
}

/**
 * 验证 CDP 所有权并等待稳定（连续 stableProbes 次）：
 * - /json/version 2xx + Browser 非空；
 * - webSocketDebuggerUrl 端口与 ActivePort 第一行一致、path 与第二行一致；
 * - Browser 字段与 browser endpoint 连续一致；
 * - child/合法后代存活；
 * - listener PID 查询为可选增强：成功且属于无关 PID 才拒绝（OWNERSHIP_MISMATCH）；
 *   null/未知记录 listenerUnknown=true，不否决 ActivePort 已证明的所有权。
 */
export async function verifyDevToolsOwnership({
  deps,
  port,
  browserPath,
  profileDir,
  child,
  browserExitedRef = () => null,
  isProfileInUse = async () => false,
  getListenerPid = async () => null,
  stableProbes = 3,
  timeoutMs = 15000,
  pollMs = 250,
  maxProbes = Math.ceil(15000 / 250) + 1,
}) {
  const deadline = Date.now() + timeoutMs
  let stable = 0
  let lastBrowser = null
  let listenerUnknown = false
  let probeCount = 0
  while (probeCount < maxProbes && Date.now() < deadline) {
    probeCount += 1
    const exited = typeof browserExitedRef === 'function' ? browserExitedRef() : null
    if (exited) {
      const descendantsAlive = await isProfileInUse(profileDir)
      if (descendantsAlive !== true) {
        throw Object.assign(new Error(`PROCESS_EXITED exitCode=${exited.code} signal=${exited.signal}`), {
          stage: 'PROCESS_EXITED',
        })
      }
    }
    let response
    try {
      response = await deps.localHttpRequest({ port, method: 'GET', path: '/json/version', timeoutMs: 4000 })
    } catch (error) {
      stable = 0
      await deps.sleep(pollMs)
      continue
    }
    if (response.status < 200 || response.status >= 300
      || !response.json || typeof response.json.Browser !== 'string') {
      stable = 0
      await deps.sleep(pollMs)
      continue
    }
    const version = response.json
    if (lastBrowser !== null && lastBrowser !== version.Browser) {
      // Browser 字段变化：不稳定，重新计数
      stable = 0
      lastBrowser = version.Browser
      await deps.sleep(pollMs)
      continue
    }
    lastBrowser = version.Browser
    // webSocketDebuggerUrl 端口与 path 校验（可选字段：缺失时仅 Browser 一致）
    let endpointMatch = true
    if (typeof version.webSocketDebuggerUrl === 'string') {
      let parsed = null
      try {
        parsed = new URL(version.webSocketDebuggerUrl)
      } catch {
        parsed = null
      }
      if (!parsed || parsed.protocol !== 'ws:'
        || Number(parsed.port || 80) !== port
        || parsed.pathname !== browserPath) {
        endpointMatch = false
      }
    }
    if (!endpointMatch) {
      throw Object.assign(new Error('OWNERSHIP_MISMATCH：/json/version 的 webSocketDebuggerUrl 与 DevToolsActivePort 不一致'), {
        stage: 'OWNERSHIP_MISMATCH',
      })
    }
    // 可选增强：listener PID 归属
    let listenerPid = null
    try {
      listenerPid = await getListenerPid(port)
    } catch {
      listenerPid = null
    }
    if (listenerPid !== null && listenerPid !== child.pid) {
      const inUse = await isProfileInUse(profileDir)
      const owned = inUse === true
      if (!owned) {
        throw Object.assign(new Error(`OWNERSHIP_MISMATCH：listener PID ${listenerPid} 与本轮 profile 无关`), {
          stage: 'OWNERSHIP_MISMATCH',
        })
      }
    } else if (listenerPid === null) {
      listenerUnknown = true
    }
    stable += 1
    if (stable >= stableProbes) {
      return { browser: version.Browser, listenerPid, listenerUnknown, port, browserPath }
    }
    await deps.sleep(pollMs)
  }
  throw Object.assign(
    new Error(`VERSION_UNREACHABLE：${timeoutMs}ms 内未达到连续 ${stableProbes} 次稳定探测`),
    { stage: 'VERSION_UNREACHABLE' },
  )
}
