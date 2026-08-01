/**
 * 浏览器 CDP 会话初始化辅助（供布局/真实聊天验收脚本复用，依赖注入以便单元测试）。
 *
 * 设计要点：
 * - 严格 profile 路径校验 assertOwnedProfileDir：resolve 规范化 + 组件级判断；
 * - 统一所有权判定 inspectOwnedProfile：owner.json 的 owner PID 存活 + 是否有 Edge/Chrome
 *   进程正在使用相同 user-data-dir（Windows 大小写不敏感）双重判定；仅两者都确认退出才可清理；
 * - 本地 CDP HTTP 客户端基于 node:http（每请求独立超时/Abort、Connection: close 防连接复用异常），
 *   避免 Node fetch 对本地动态端口的不稳定；不使用外部网络；
 * - 创建 page target：先探活 /json/version，逐格式尝试；CDP 死亡时立即停止其余格式并整体重试；
 *   失败输出完整脱敏诊断（cause chain、child/listener/存活/CDP 复查/版本号/阶段时序）；
 * - 每 attempt 全新 profile/随机端口/进程/target/socket；重试前等待诊断、进程退出并安全清理；
 * - post-open 断连输出 describePostOpenFailure 完整脱敏诊断（生产路径真实调用）。
 */

import { spawn as nodeSpawn } from 'node:child_process'
import {
  existsSync, rmSync, readdirSync, readFileSync, writeFileSync, mkdirSync,
} from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve, dirname, basename, normalize } from 'node:path'
import WsImpl from 'ws'
import { redactErrorChain, safeJsonSummary, redactAndTruncate } from './redact.mjs'
import { createLocalHttpRequest } from './local-http.mjs'

export const PROFILE_PREFIX = 'pet-layout-check-'
export const MAX_INIT_RETRIES = 3
export const CDP_PORT_MIN = 9300
export const CDP_PORT_MAX = 9800
export const OWNER_FILE = 'owner.json'
export const DIAGNOSTIC_SETTLE_MS = 2500
export const LOCAL_HTTP_TIMEOUT_MS = 5000
export const READY_STABLE_PROBES = 3
/** /json/new 各候选请求格式（有界兼容不同 Edge/Chromium 版本）。 */
export const TARGET_CREATE_FORMATS = [
  { method: 'PUT', path: () => `/json/new?about%3Ablank` },
  { method: 'PUT', path: () => `/json/new?about:blank` },
  { method: 'GET', path: () => `/json/new?about%3Ablank` },
  { method: 'PUT', path: () => `/json/new`, body: '{"url":"about:blank"}' },
]

/** profile 后缀允许的字符集（字母数字与连字符，2–64 位）。 */
const SUFFIX_RE = /^[A-Za-z0-9-]{2,64}$/

/**
 * Edge 启动参数（每条对应验收环境的崩溃证据）：
 * - --disable-gpu：GPU 硬件加速路径（证据：GPU process exited unexpectedly）
 * - --disable-gpu-compositing：GPU 合成路径
 * - --in-process-gpu：GPU 进程并入主进程，消除独立 GPU 子进程崩溃（0xC0000022）
 * - --disable-features=SkiaGraphite：禁用 Graphite 渲染后端（证据：GPUPersistentCache\DawnGraphiteCache）
 * - --disable-extensions / --disable-component-extensions-with-background-pages：
 *   扩展后台加载（可能写缓存/占句柄）
 * - --disable-background-networking / --disable-default-apps / --disable-sync /
 *   --metrics-recording-only：禁用导入、后台更新、首启行为
 * - --no-first-run / --no-default-browser-check：禁用首启引导
 * - --no-sandbox：仅用于本脚本创建的临时 headless 验收实例；受限 Windows 环境中
 *   browser target 可用但 renderer sandbox 无法启动时，Page.enable 会永久无响应。
 */
export const DEFAULT_EDGE_ARGS = [
  '--headless=new',
  '--disable-gpu',
  '--disable-gpu-compositing',
  '--in-process-gpu',
  '--disable-features=SkiaGraphite',
  '--disable-extensions',
  '--disable-component-extensions-with-background-pages',
  '--disable-background-networking',
  '--disable-default-apps',
  '--disable-sync',
  '--metrics-recording-only',
  '--no-first-run',
  '--no-default-browser-check',
  '--no-sandbox',
]

/** Windows 退出码分类（仅诊断；崩溃不得视为可接受退出）。 */
export function classifyExit({ code, signal = null, stderr = '' }) {
  const findings = []
  const hex = typeof code === 'number'
    ? `0x${(code >>> 0).toString(16).toUpperCase().padStart(8, '0')}`
    : null
  if (hex === '0x80000003') {
    findings.push('STATUS_BREAKPOINT（0x80000003）：主进程崩溃退出')
  }
  if (hex === '0xC0000022' || code === -1073741790) {
    findings.push('STATUS_ACCESS_DENIED（0xC0000022）：权限受限，常见于 GPU 子进程')
  }
  if (/GPU process exited unexpectedly/i.test(stderr)) {
    findings.push('GPU 子进程意外退出')
  }
  if (/GPUPersistentCache|DawnGraphiteCache/i.test(stderr)) {
    findings.push('GPU 持久化缓存（GPUPersistentCache/DawnGraphiteCache）被引用')
  }
  if (/另一个程序正在使用此文件|0x20\b|sharing violation/i.test(stderr)) {
    findings.push('缓存/文件共享冲突（0x20 另一个程序正在使用此文件）')
  }
  return { hex, signal, findings }
}

/** Edge stderr 关键行过滤（DevTools/profile/singleton/sandbox/crash/port/shutdown）。 */
const STDERR_FILTER = /DevTools|remote debugging|user-data-dir|profile|singleton|sandbox|crash|bind|shutdown|ERROR|error/i

const DEFAULT_DEPS = {
  spawn: nodeSpawn,
  sleep: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
  killProcessTree: (pid) => new Promise((resolve) => {
    if (process.platform === 'win32') {
      const killer = nodeSpawn('taskkill', ['/PID', String(pid), '/T', '/F'], { stdio: 'ignore' })
      killer.on('exit', () => resolve())
      killer.on('error', () => resolve())
    } else {
      try { process.kill(pid, 'SIGKILL') } catch { /* 可能已退出 */ }
      resolve()
    }
  }),
  listBrowserProcesses: () => new Promise((resolvePromise) => {
    if (process.platform !== 'win32') { resolvePromise([]); return }
    const ps = nodeSpawn('powershell', [
      '-NoProfile', '-NonInteractive', '-Command',
      "Get-CimInstance Win32_Process -Filter \"Name='msedge.exe' or Name='chrome.exe'\" | " +
      "ForEach-Object { \"$($_.ProcessId)|$($_.CommandLine)\" }",
    ], { stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true })
    let out = ''
    ps.stdout.on('data', (chunk) => { out += chunk.toString() })
    ps.on('error', () => resolvePromise([]))
    ps.on('close', () => {
      const rows = []
      for (const line of out.split(/\r?\n/)) {
        const idx = line.indexOf('|')
        if (idx <= 0) continue
        rows.push({ pid: Number(line.slice(0, idx)), commandLine: line.slice(idx + 1) })
      }
      resolvePromise(rows)
    })
  }),
  listProfileCandidates: () => new Promise((resolvePromise) => {
    const tmpRoot = resolve(tmpdir())
    let entries = []
    try {
      entries = readdirSync(tmpRoot, { withFileTypes: true })
    } catch {
      resolvePromise([])
      return
    }
    resolvePromise(
      entries.filter((entry) => entry.isDirectory()).map((entry) => join(tmpRoot, entry.name)))
  }),
  removeDir: (dir) => {
    rmSync(dir, { recursive: true, force: true })
    return Promise.resolve()
  },
  readOwnerFile: async (dir) => {
    try {
      const raw = readFileSync(join(dir, OWNER_FILE), 'utf8')
      const parsed = JSON.parse(raw)
      return parsed && typeof parsed.pid === 'number' ? parsed : null
    } catch {
      return null
    }
  },
  /** 读取 DevToolsActivePort（不存在/不可读返回 null）。 */
  readDevToolsActivePort: async (dir) => {
    try {
      return readFileSync(join(dir, 'DevToolsActivePort'), 'utf8')
    } catch {
      return null
    }
  },
  getListenerPid: (port) => new Promise((resolvePromise) => {
    if (process.platform !== 'win32') { resolvePromise(null); return }
    const ps = nodeSpawn('powershell', [
      '-NoProfile', '-NonInteractive', '-Command',
      `$c = Get-NetTCPConnection -LocalPort ${Number(port)} -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1; if ($c) { $c.OwningProcess }`,
    ], { stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true })
    let out = ''
    ps.stdout.on('data', (chunk) => { out += chunk.toString() })
    ps.on('error', () => resolvePromise(null))
    ps.on('close', () => {
      const pid = Number(out.trim())
      resolvePromise(Number.isInteger(pid) && pid > 0 ? pid : null)
    })
  }),
  isPidAlive: (pid) => new Promise((resolvePromise) => {
    if (!Number.isInteger(pid) || pid <= 0) { resolvePromise(false); return }
    try {
      process.kill(pid, 0)
      resolvePromise(true)
    } catch {
      resolvePromise(false)
    }
  }),
  now: () => Date.now(),
  /** 本地 CDP HTTP 客户端（node:http，agent:false + Connection:close，独立连接）。 */
  localHttpRequest: createLocalHttpRequest(),
  /** WebSocket 构造：返回 EventTarget 风格对象（cdp-client 与测试 mock 均兼容）。 */
  WebSocket: (url) => wsToEventTarget(new WsImpl(url)),
}

function wsToEventTarget(ws) {
  const target = new EventTarget()
  ws.on('message', (data, isBinary) => {
    const text = isBinary ? Buffer.from(data).toString('utf8') : String(data)
    target.dispatchEvent(new MessageEvent('message', { data: text }))
  })
  ws.on('open', () => target.dispatchEvent(new Event('open')))
  ws.on('error', (error) => {
    const event = new Event('error')
    event.message = error && error.message ? String(error.message) : ''
    target.dispatchEvent(event)
  })
  ws.on('close', (code, reason) => {
    target.dispatchEvent(Object.assign(new Event('close'), {
      code: typeof code === 'number' ? code : 1006,
      reason: String(reason ?? ''),
      wasClean: code === 1000,
    }))
  })
  return {
    get readyState() { return ws.readyState },
    addEventListener: (type, callback) => target.addEventListener(type, callback),
    removeEventListener: (type, callback) => target.removeEventListener(type, callback),
    send: (data) => ws.send(data),
    close: () => ws.close(),
  }
}

/** Windows 路径比较（大小写不敏感）；其他平台区分大小写。 */
export function pathEquals(a, b) {
  const left = resolve(a)
  const right = resolve(b)
  return process.platform === 'win32'
    ? left.toLowerCase() === right.toLowerCase()
    : left === right
}

/**
 * 统一安全断言：profile 必须是 resolve(tmpdir()) 的直接子目录，
 * basename 以 PROFILE_PREFIX 开头且后缀为受限格式。
 * @returns {string} 规范化后的绝对路径
 */
export function assertOwnedProfileDir(profileDir, { prefix = PROFILE_PREFIX } = {}) {
  if (typeof profileDir !== 'string' || !profileDir) {
    throw new Error('拒绝：profileDir 为空')
  }
  const resolved = resolve(profileDir)
  const tmpRoot = resolve(tmpdir())
  if (!pathEquals(dirname(resolved), tmpRoot)) {
    throw new Error(`拒绝：profile 必须是临时目录的直接子目录（${tmpRoot}），实际 ${resolved}`)
  }
  const name = basename(resolved)
  const nameMatches = process.platform === 'win32'
    ? name.toLowerCase().startsWith(prefix.toLowerCase())
    : name.startsWith(prefix)
  if (!nameMatches) {
    throw new Error(`拒绝：profile 名称必须以 ${prefix} 开头，实际 ${name}`)
  }
  const suffix = name.slice(prefix.length)
  if (!SUFFIX_RE.test(suffix)) {
    throw new Error(`拒绝：profile 后缀格式非法（${suffix || '(空)'}）`)
  }
  if (normalize(resolved) !== resolved) {
    throw new Error(`拒绝：profile 路径未规范化（${resolved}）`)
  }
  return resolved
}

/** 从命令行解析 --user-data-dir 的真实值（支持引号包裹）；解析失败返回 null。 */
export function extractUserDataDir(commandLine) {
  if (typeof commandLine !== 'string') return null
  const match = commandLine.match(/(?:^|\s)--user-data-dir=(?:"([^"]*)"|(\S+))/)
  if (!match) return null
  return match[1] !== undefined ? match[1] : match[2]
}

/** 可执行文件名必须是允许的 Edge/Chrome（引号内路径含空格也能正确解析）。 */
export function isAllowedBrowserProcess(commandLine) {
  if (typeof commandLine !== 'string' || !commandLine.trim()) return false
  const match = commandLine.trim().match(/^"([^"]+)"|^(\S+)/)
  const exePath = match ? (match[1] ?? match[2]) : ''
  const name = basename(exePath.replace(/^"|"$/g, '')).toLowerCase()
  return name === 'msedge.exe' || name === 'chrome.exe'
}

function writeOwnerFile(profileDir, pid, now) {
  const owned = assertOwnedProfileDir(profileDir)
  mkdirSync(owned, { recursive: true })
  writeFileSync(join(owned, OWNER_FILE), JSON.stringify({ pid, createdAt: now }))
}

/**
 * 是否有允许的 Edge/Chrome 进程正在使用完全相同（规范化、大小写不敏感）的 user-data-dir。
 * excludePid：排除自身（killResidualBrowsers 遍历目标），避免"自己使用自己"的死循环。
 * 枚举失败返回 null（未知 → 保守视为"可能在使用"）。
 */
export async function isProfileInUseByBrowser(profileDir, deps = DEFAULT_DEPS, { excludePid = null } = {}) {
  let owned
  try {
    owned = assertOwnedProfileDir(profileDir)
  } catch {
    return false
  }
  let processes
  try {
    processes = await deps.listBrowserProcesses()
  } catch {
    return null
  }
  if (!Array.isArray(processes)) return null
  for (const entry of processes) {
    if (!entry || entry.pid === excludePid) continue
    if (!isAllowedBrowserProcess(entry.commandLine)) continue
    const dir = extractUserDataDir(entry.commandLine)
    if (!dir) continue
    try {
      if (pathEquals(assertOwnedProfileDir(dir), owned)) return true
    } catch {
      // 非脚本 profile，跳过
    }
  }
  return false
}

/**
 * 统一所有权与过期判定（owner PID + 浏览器使用双重判定）。
 * excludePid：遍历到的目标进程自身（见 isProfileInUseByBrowser）。
 * @returns {{ ok: boolean, pathValid: boolean, expired: boolean, owner: object|null,
 *             ownerAlive: boolean|null, ownerKind: string|null,
 *             inUseByBrowser: boolean|null, reason: string|null }}
 */
export async function inspectOwnedProfile(profileDir, deps = DEFAULT_DEPS, { excludePid = null } = {}) {
  let owned
  try {
    owned = assertOwnedProfileDir(profileDir)
  } catch (error) {
    return {
      ok: false, pathValid: false, expired: false,
      owner: null, ownerAlive: null, ownerKind: null, inUseByBrowser: null,
      reason: error instanceof Error ? error.message : String(error),
    }
  }
  let owner = null
  try {
    owner = await deps.readOwnerFile(owned)
  } catch {
    owner = null
  }
  if (!owner || !Number.isInteger(owner.pid)) {
    return {
      ok: false, pathValid: true, expired: false,
      owner: null, ownerAlive: null, ownerKind: null, inUseByBrowser: null,
      reason: 'owner.json 缺失或损坏（归属不明，不清理）',
    }
  }
  const ownerAlive = await deps.isPidAlive(owner.pid)
  if (ownerAlive) {
    return {
      ok: false, pathValid: true, expired: false,
      owner, ownerAlive, ownerKind: 'browser-child', inUseByBrowser: true,
      reason: 'owner 进程仍存活（活跃实例）',
    }
  }
  // owner 已退出：检查是否仍有浏览器使用该 profile（含本轮进程树后代）
  const inUseByBrowser = await isProfileInUseByBrowser(owned, deps, { excludePid })
  if (inUseByBrowser !== false) {
    return {
      ok: false, pathValid: true, expired: false,
      owner, ownerAlive, ownerKind: 'browser-child', inUseByBrowser,
      reason: inUseByBrowser === null
        ? '浏览器进程枚举失败（安全失败，不清理）'
        : '仍有 Edge/Chrome 进程使用该 profile（含活跃后代）',
    }
  }
  return {
    ok: true, pathValid: true, expired: true,
    owner, ownerAlive, ownerKind: 'browser-child', inUseByBrowser: false,
    reason: null,
  }
}

/**
 * 终止"确认过期"的本脚本浏览器实例：
 * 进程名合法 + --user-data-dir 通过 assertOwnedProfileDir + inspectOwnedProfile 判定过期。
 * 活跃实例（owner 存活或浏览器仍使用 profile）与归属不明实例一律不杀。
 */
export async function killResidualBrowsers(deps = DEFAULT_DEPS) {
  let processes
  try {
    processes = await deps.listBrowserProcesses()
  } catch {
    return 0 // 查询失败：安全失败，不扩大清理范围
  }
  if (!Array.isArray(processes)) return 0
  const killed = []
  for (const entry of processes) {
    if (!entry || !isAllowedBrowserProcess(entry.commandLine)) continue
    const dir = extractUserDataDir(entry.commandLine)
    if (!dir) continue
    // excludePid=自身：僵尸（owner 已退出且无其他浏览器使用）可杀；
    // 并发活跃实例（owner 存活）与"仍有其他浏览器使用"的实例一律不杀
    const info = await inspectOwnedProfile(dir, deps, { excludePid: entry.pid })
    if (!info.expired) continue
    await deps.killProcessTree(entry.pid)
    killed.push(entry.pid)
  }
  if (killed.length) {
    await deps.sleep(800)
  }
  return killed.length
}

/**
 * 删除本脚本残留 profile：仅删除 inspectOwnedProfile 判定过期（owner 已退出且无浏览器使用）
 * 且删除前再次通过安全断言的目录；删除前最后一刻复查 owner 与浏览器使用，变为活跃则中止。
 */
export async function cleanupStaleProfiles(deps = DEFAULT_DEPS) {
  let candidates
  try {
    candidates = await deps.listProfileCandidates()
  } catch {
    return 0
  }
  if (!Array.isArray(candidates)) return 0
  let removed = 0
  let failed = 0
  for (const dir of candidates) {
    const info = await inspectOwnedProfile(dir, deps)
    if (!info.expired) continue
    // 删除前最后一刻复查：若此刻变为活跃，中止删除
    const recheck = await inspectOwnedProfile(dir, deps)
    if (!recheck.expired) continue
    let owned
    try {
      owned = assertOwnedProfileDir(dir)
    } catch {
      continue
    }
    try {
      await deps.removeDir(owned)
      removed += 1
    } catch {
      failed += 1
    }
  }
  if (failed > 0) {
    throw new Error(`cleanupStaleProfiles: ${failed} 个 profile 删除失败`)
  }
  return removed
}

/** 探测空闲端口（首选 preferPort；否则在 [min,max] 随机探测）。 */
export async function findFreePort(deps = DEFAULT_DEPS, preferPort, min = CDP_PORT_MIN, max = CDP_PORT_MAX) {
  const { createServer } = await import('node:net')
  const tryPort = (port) => new Promise((resolvePromise) => {
    const probe = createServer()
    probe.once('error', () => resolvePromise(false))
    probe.once('listening', () => probe.close(() => resolvePromise(true)))
    probe.listen(port, '127.0.0.1')
  })
  if (preferPort && (await tryPort(preferPort))) return preferPort
  const candidates = []
  for (let port = min; port <= max; port += 1) candidates.push(port)
  for (let i = candidates.length - 1; i > 0; i -= 1) {
    const j = Math.floor(Math.random() * (i + 1))
    ;[candidates[i], candidates[j]] = [candidates[j], candidates[i]]
  }
  for (const port of candidates) {
    if (await tryPort(port)) return port
  }
  throw new Error('无法找到空闲端口')
}

/** 用注入或默认的本地 HTTP 客户端发请求（异常统一转为带 cause 信息的 Error）。 */
async function localGet(deps, port, path, timeoutMs = LOCAL_HTTP_TIMEOUT_MS) {
  return deps.localHttpRequest({ port, method: 'GET', path, timeoutMs })
}

/** Edge stderr 关键行脱敏摘要（DevTools/profile/singleton/sandbox/crash/port/shutdown）。 */
export function summarizeStderr(stderr, limit = 2000) {
  if (!stderr) return ''
  const lines = stderr.split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line && STDERR_FILTER.test(line))
  return redactAndTruncate(lines.join('\n'), limit)
}

/** 判断 listener PID 是否属于本轮进程树（child 本身或使用同 profile 的合法后代）。 */
async function isListenerOwned(deps, listener, child, ownedProfile) {
  if (listener === child.pid) return true
  let processes
  try {
    processes = await deps.listBrowserProcesses()
  } catch {
    return false
  }
  if (!Array.isArray(processes)) return false
  return processes.some((entry) => {
    if (!entry || entry.pid !== listener) return false
    const dir = extractUserDataDir(entry.commandLine)
    if (!dir) return false
    try {
      return pathEquals(assertOwnedProfileDir(dir), ownedProfile)
    } catch {
      return false
    }
  })
}

/**
 * CDP 就绪判定（readiness gate）：
 * - 观测到有效 /json/version（2xx 且 Browser 字段非空）；
 * - listener PID 非空且属于本轮进程树（允许转移到合法后代，重新校验归属）；
 * - child/合法后代仍存活（browserExitedRef 实时读取，非静态快照）；
 * - 连续 READY_STABLE_PROBES 次稳定探测（Browser 字段一致、listener 一致或可解释转移）。
 * @returns {Promise<{ browser: string, listenerPid: number }>}
 */
export async function waitCdpReady({
  deps = DEFAULT_DEPS,
  cdpPort,
  child,
  ownedProfile,
  browserExitedRef,
  log = () => {},
  timeoutMs = 60000,
  stableProbes = READY_STABLE_PROBES,
  maxProbes = Math.ceil(60000 / 250) + 1,
}) {
  const deadline = Date.now() + timeoutMs
  let lastBrowser = null
  let lastListener = null
  let stable = 0
  let probeCount = 0
  while (probeCount < maxProbes && Date.now() < deadline) {
    probeCount += 1
    const exited = typeof browserExitedRef === 'function' ? browserExitedRef() : null
    if (exited) {
      // 原始 child 退出：若仍有使用同 profile 的合法后代存活，则继续验证后代；
      // 没有任何存活后代才判定为崩溃退出
      const descendantsAlive = await isProfileInUseByBrowser(ownedProfile, deps)
      if (descendantsAlive !== true) {
        throw new Error(
          `浏览器崩溃退出: exitCode=${exited.code} signal=${exited.signal}`)
      }
    }
    try {
      const response = await localGet(deps, cdpPort, '/json/version', 4000)
      if (response.status >= 200 && response.status < 300
        && response.json && typeof response.json.Browser === 'string') {
        const listener = await deps.getListenerPid(cdpPort)
        if (listener === null) {
          stable = 0
          await deps.sleep(250)
          continue
        }
        if (!(await isListenerOwned(deps, listener, child, ownedProfile))) {
          throw new Error(
            `CDP 端口 ${cdpPort} 监听 PID=${listener} 不属于本轮进程树（child.pid=${child.pid}），拒绝连接`)
        }
        if (lastBrowser !== null && lastBrowser !== response.json.Browser) {
          throw new Error(
            `/json/version Browser 字段变化（${lastBrowser} → ${response.json.Browser}），CDP 实例不稳定`)
        }
        if (lastListener !== null && lastListener !== listener) {
          // listener 转移到合法后代：重新校验归属
          if (!(await isListenerOwned(deps, listener, child, ownedProfile))) {
            throw new Error(`CDP listener 转移到未授权 PID ${listener}`)
          }
          log(`  CDP listener 由 ${lastListener} 转移到合法后代 ${listener}`)
        }
        lastBrowser = response.json.Browser
        lastListener = listener
        stable += 1
        if (stable >= stableProbes) {
          return { browser: response.json.Browser, listenerPid: listener }
        }
      } else {
        stable = 0
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      if (message.includes('不属于本轮进程树') || message.includes('Browser 字段变化') || message.includes('未授权')) {
        throw error
      }
      stable = 0
    }
    await deps.sleep(250)
  }
  throw new Error(`CDP 未稳定就绪（${timeoutMs}ms 内未达到连续 ${stableProbes} 次稳定探测）`)
}

async function targetInList(deps, cdpPort, id) {
  try {
    const response = await localGet(deps, cdpPort, '/json/list')
    if (response.status < 200 || response.status >= 300) return false
    const list = response.json
    return Array.isArray(list) && list.some((item) => item && item.id === id)
  } catch {
    return false
  }
}

/** 收集 CDP 现场证据（版本/存活/监听 PID），失败以 'unknown' 表示。 */
async function collectCdpEvidence(deps, cdpPort) {
  const evidence = {}
  try {
    const response = await localGet(deps, cdpPort, '/json/version', 3000)
    evidence.versionStatus = response.status
    evidence.versionBrowser = response.json && response.json.Browser
      ? redactAndTruncate(String(response.json.Browser), 80) : null
    evidence.cdpAlive = response.status >= 200 && response.status < 300
  } catch (error) {
    evidence.versionStatus = 'error'
    evidence.versionError = redactErrorChain(error, 200)
    evidence.cdpAlive = false
  }
  try {
    evidence.listenerPid = await deps.getListenerPid(cdpPort)
  } catch {
    evidence.listenerPid = 'unknown'
  }
  return evidence
}

/**
 * 创建自有 page target：
 * 1) 先探活 /json/version（失败 → CDP 已死 → 立即抛错整体重试，不尝试任何格式）；
 * 2) 逐格式尝试（有界），每格式失败记录安全诊断（method/脱敏 path/status/Content-Type/摘要/cause chain）；
 * 3) 连接层失败或异常状态时复查 CDP 存活；CDP 已死 → 停止其余格式；
 * 4) 成功后校验 type/id/webSocketDebuggerUrl 且 target 出现在 /json/list（绝不盲选第一项）。
 */
export async function createPageTarget({
  deps = DEFAULT_DEPS,
  cdpPort,
  attempt = 1,
  maxAttempts = MAX_INIT_RETRIES,
  log = () => {},
  childPid = null,
  childExited = null,
}) {
  const failures = []
  const startedAt = Date.now()

  const probe = await collectCdpEvidence(deps, cdpPort)
  if (!probe.cdpAlive) {
    log(`  CDP 探活失败（/json/version ${probe.versionStatus}${probe.versionError ? `，${probe.versionError}` : ''}，listenerPid=${probe.listenerPid}），整体重试`)
    throw new Error(`CDP HTTP 服务不可达（/json/version 失败：${probe.versionStatus}）`)
  }
  log(`  CDP 探活成功：/json/version ${probe.versionStatus}（${probe.versionBrowser ?? '?'}），listenerPid=${probe.listenerPid}`)

  for (const format of TARGET_CREATE_FORMATS) {
    const path = typeof format.path === 'function' ? format.path(cdpPort) : format.path
    const entry = {
      method: format.method,
      path: redactAndTruncate(path, 120), // 脱敏 path（不输出查询参数内容之外的信息）
      at: Date.now() - startedAt,
    }
    let response
    try {
      response = await deps.localHttpRequest({
        port: cdpPort,
        method: format.method,
        path,
        body: format.body,
      })
      entry.status = response.status
      entry.contentType = response.contentType ?? 'unknown'
      if (response.status < 200 || response.status >= 300) {
        entry.body = redactAndTruncate(response.text ?? '', 300)
        failures.push(entry)
      } else {
        const json = response.json
        if (!json || typeof json !== 'object' || json.type !== 'page'
          || typeof json.id !== 'string' || !json.id
          || typeof json.webSocketDebuggerUrl !== 'string' || !json.webSocketDebuggerUrl) {
          entry.summary = safeJsonSummary(json)
          failures.push(entry)
        } else if (!(await targetInList(deps, cdpPort, json.id))) {
          entry.summary = `target 创建后未出现在 /json/list（id=${redactAndTruncate(json.id, 40)}）`
          failures.push(entry)
        } else {
          return json
        }
      }
    } catch (error) {
      entry.error = redactErrorChain(error, 300)
      failures.push(entry)
    }
    // 非预期失败后复查 CDP：已死 → 停止其余格式（立即整体重试）
    if (response === undefined || response.status === 0 || response.status >= 500 || entry.error) {
      const alive = await collectCdpEvidence(deps, cdpPort)
      if (!alive.cdpAlive) {
        entry.cdpDied = true
        log(`  CDP 在 target 创建期间死亡（${entry.method} ${entry.path}，${alive.versionError ?? `version=${alive.versionStatus}`}，listenerPid=${alive.listenerPid}），停止其余格式并整体重试`)
        break
      }
    }
  }

  log(`  创建 page target 失败（attempt ${attempt}/${maxAttempts}，尝试 ${failures.length} 种请求格式）：`)
  for (const failure of failures) {
    log(`    - [${failure.at}ms] ${failure.method} ${failure.path} status=${failure.status ?? '?'} contentType=${failure.contentType ?? '?'}${failure.error ? ` error=${failure.error}` : ''}${failure.summary ? ` summary=${failure.summary}` : ''}${failure.body ? ` body=${failure.body}` : ''}${failure.cdpDied ? ' [CDP 已死亡]' : ''}`)
  }
  // 汇总现场证据
  const evidence = await collectCdpEvidence(deps, cdpPort)
  log(`  CDP 现场：version=${evidence.versionStatus}${evidence.versionError ? `（${evidence.versionError}）` : ''} listenerPid=${evidence.listenerPid} childPid=${childPid ?? '?'} childExited=${childExited ? `exitCode=${childExited.code} signal=${childExited.signal}` : '否'}`)
  throw new Error(`无法创建本脚本的 page target（${failures.length} 次尝试均失败，见上方诊断）`)
}

/**
 * 完整诊断（生产路径调用 describePostOpenFailure）：
 * 断连后异步补查 target 存在性、CDP 存活、监听 PID，然后输出脱敏报告。
 */
async function emitPostOpenDiagnostics({
  deps, cdpPort, targetId, wsUrl, profileId, childPid, attempt, maxAttempts, diag,
}) {
  const extra = {}
  const evidence = await collectCdpEvidence(deps, cdpPort)
  extra.cdpAlive = evidence.cdpAlive
  extra.listenerPid = evidence.listenerPid
  try {
    const response = await localGet(deps, cdpPort, '/json/list', 3000)
    const targets = response.json
    extra.targetExists = Array.isArray(targets) && targets.some((t) => t && t.id === targetId)
  } catch {
    extra.targetExists = 'unknown'
  }
  const { describePostOpenFailure } = await import('./cdp-client.mjs')
  const report = describePostOpenFailure({
    phase: diag.phase,
    method: diag.method,
    targetId,
    wsUrl,
    readyState: diag.readyState,
    errorInfo: diag.errorInfo,
    closeInfo: diag.closeInfo,
    browserExited: diag.browserExited,
    exitCode: diag.exitCode,
    exitSignal: diag.exitSignal,
    targetExists: extra.targetExists,
    childPid,
    listenerPid: extra.listenerPid,
    cdpAlive: extra.cdpAlive,
    profileId,
    attempt,
    maxAttempts,
  })
  console.error(report)
}

/**
 * 建立 WebSocket（EventTarget 风格；ws 库或注入实现）。
 * 建连失败含诊断，同 URL 重试一次；仍失败返回 null。
 */
async function connectSocket(deps, url, { targetId, childPid, browserExited }) {
  const { describeConnectionFailure } = await import('./cdp-client.mjs')
  const socket = deps.WebSocket(url)
  const outcome = await new Promise((resolvePromise) => {
    let settled = false
    const done = (result) => {
      if (settled) return
      settled = true
      resolvePromise(result)
    }
    socket.addEventListener('open', () => done({ ok: true }))
    socket.addEventListener('error', (event) => done({
      ok: false,
      errorInfo: event && event.message ? String(event.message) : 'error 事件（无附加详情）',
    }))
    socket.addEventListener('close', (event) => done({
      ok: false,
      closeInfo: `code=${event.code} reason=${JSON.stringify(event.reason ?? '')}`,
    }))
  })
  if (!outcome.ok) {
    console.error(describeConnectionFailure({
      targetId,
      wsUrl: url,
      readyState: socket.readyState,
      closeInfo: outcome.closeInfo,
      errorInfo: outcome.errorInfo,
      browserExited: Boolean(browserExited),
      exitCode: browserExited?.code,
      exitSignal: browserExited?.signal,
    }))
    try { socket.close() } catch { /* ignore */ }
    return null
  }
  return socket
}

/** 终止浏览器并等待其真正退出（原始 child + 使用同 profile 的整个后代进程树，有界）。 */
export async function waitChildExit(child, deps = DEFAULT_DEPS, ownedProfile = null) {
  if (!child || child.exitCode !== null) return
  try { child.kill() } catch { /* ignore */ }
  const treeGone = async () => {
    const childGone = child.exitCode !== null || child.signalCode !== null
    if (!childGone) return false
    if (!ownedProfile) return true
    // 原始 child 已退出：仍须确认没有使用同 profile 的后代进程持有句柄
    const inUse = await isProfileInUseByBrowser(ownedProfile, deps)
    return inUse === false
  }
  for (let attempt = 0; attempt < 40; attempt += 1) {
    if (await treeGone()) return
    await deps.sleep(250)
  }
  if (child.pid) {
    await deps.killProcessTree(child.pid)
  }
  for (let attempt = 0; attempt < 20; attempt += 1) {
    if (await treeGone()) return
    await deps.sleep(250)
  }
}

/**
 * 单次初始化事务（不重试）。
 * 完整流程：spawn（捕获 stderr）→ owner.json → waitCdpReady（连续稳定探测 + listener 归属 +
 * child 存活实时判定）→ owner 更新为真实拥有者 → 创建自有 target → WebSocket →
 * Page.enable → Runtime.enable → 可选注入。
 */
export async function connectOnce({
  deps = DEFAULT_DEPS,
  browserPath,
  profileDir,
  cdpPort = null,
  injectScript = null,
  sendTimeoutMs = 15000,
  log = () => {},
  attempt = 1,
  maxAttempts = MAX_INIT_RETRIES,
  edgeArgs = DEFAULT_EDGE_ARGS,
  runId = null,
}) {
  const ownedProfile = assertOwnedProfileDir(profileDir)
  // 捕获本轮 Edge stderr 到有界内存缓冲（失败时输出脱敏关键行）
  let stderrBuffer = ''
  // 使用 --remote-debugging-port=0：Edge 自选端口并写入 DevToolsActivePort，
  // 避免"先探测空闲端口再启动"的 TOCTOU（端口由独占 profile 的唯一实例独占）
  const child = deps.spawn(browserPath, [
    ...edgeArgs,
    '--remote-debugging-port=0',
    '--remote-allow-origins=*',
    '--user-data-dir=' + ownedProfile,
    'about:blank',
  ], { stdio: ['ignore', 'pipe', 'pipe'] })
  let browserExited = null
  child.on?.('error', () => {})
  child.on?.('exit', (code, signal) => { browserExited = { code, signal } })
  child.stderr?.on?.('data', (chunk) => {
    stderrBuffer = (stderrBuffer + chunk.toString()).slice(-65536)
  })
  try {
    writeOwnerFile(ownedProfile, child.pid, deps.now())
  } catch (cause) {
    throw new Error(`无法写入 profile 归属文件: ${cause instanceof Error ? cause.message : cause}`)
  }

  const startedAt = Date.now()
  const timeline = []
  const mark = (state, detail = '') => timeline.push({ state, at: Date.now() - startedAt, detail })
  mark('SPAWNED', `childPid=${child.pid}`)

  let ws = null
  let client = null
  let diagnosisPromise = null
  const close = async () => {
    try { ws?.close() } catch { /* ignore */ }
    await waitChildExit(child, deps, ownedProfile)
  }

  try {
    // ---- ACTIVE_PORT_DISCOVERED：等待独占 profile 的 DevToolsActivePort ----
    const { waitForDevToolsActivePort, verifyDevToolsOwnership } = await import('./devtools-port.mjs')
    const activePort = await waitForDevToolsActivePort({
      deps,
      profileDir: ownedProfile,
      child,
      browserExitedRef: () => browserExited,
      isProfileInUse: (dir) => isProfileInUseByBrowser(dir, deps),
      timeoutMs: 15000,
      pollMs: 150,
    })
    mark('ACTIVE_PORT_DISCOVERED',
      `port=${activePort.port} endpoint=${redactAndTruncate(activePort.browserPath, 40)}`)

    // ---- VERSION_VERIFIED + STABLE：连续稳定探测 + ActivePort/version 三方匹配 ----
    // listener PID 查询为可选增强：null/无权限 → listenerUnknown，不否决；明确无关 PID → 拒绝
    const verified = await verifyDevToolsOwnership({
      deps,
      port: activePort.port,
      browserPath: activePort.browserPath,
      profileDir: ownedProfile,
      child,
      browserExitedRef: () => browserExited,
      isProfileInUse: (dir) => isProfileInUseByBrowser(dir, deps),
      getListenerPid: (port) => deps.getListenerPid(port),
      stableProbes: READY_STABLE_PROBES,
      timeoutMs: 15000,
      pollMs: 250,
    })
    mark('VERSION_VERIFIED',
      `browser=${redactAndTruncate(verified.browser, 40)} listener=${verified.listenerUnknown ? 'unknown' : String(verified.listenerPid)}`)
    mark('STABLE')
    log(`  CDP 稳定就绪：/json/version 200（${verified.browser}），port=${verified.port}，listener=${verified.listenerUnknown ? 'unknown' : String(verified.listenerPid)}，childPid=${child.pid}`)

    // owner.json 更新为真实 owner（listener 或 child）
    try {
      writeOwnerFile(ownedProfile, verified.listenerPid ?? child.pid, deps.now())
    } catch {
      // owner 更新失败不影响本次连接（保守：保留原 owner）
    }

    // ---- TARGET_CREATED：创建自有 page target（先探活、逐格式、CDP 死亡即停、完整诊断）----
    const target = await createPageTarget({
      deps, cdpPort: activePort.port, attempt, maxAttempts, log,
      childPid: child.pid, childExited: browserExited,
    })
    mark('TARGET_CREATED', `target=${redactAndTruncate(target.id, 16)}`)

    // ---- WS_READY：browser endpoint + flattened target session ----
    // Some managed Edge environments accept a page-target WebSocket handshake
    // but never answer domain commands. The browser endpoint is authored by
    // Edge in DevToolsActivePort and supports explicit flattened sessions.
    const browserWebSocketUrl = `ws://127.0.0.1:${activePort.port}${activePort.browserPath}`
    ws = await connectSocket(deps, browserWebSocketUrl, {
      targetId: target.id,
      childPid: child.pid,
      browserExited,
    })
    if (!ws) {
      await deps.sleep(800)
      ws = await connectSocket(deps, browserWebSocketUrl, {
        targetId: target.id,
        childPid: child.pid,
        browserExited,
      })
      if (!ws) throw Object.assign(new Error('CDP WebSocket 建连失败（已重试一次），见上方诊断'), { stage: 'WS_FAILED' })
    }
    mark('WS_CONNECTED')

    const { createCdpClient } = await import('./cdp-client.mjs')
    const diagContext = {
      deps, cdpPort: activePort.port, targetId: target.id, wsUrl: browserWebSocketUrl,
      profileId: basename(ownedProfile), childPid: child.pid,
      attempt, maxAttempts,
    }
    client = createCdpClient({
      ws,
      sendTimeoutMs,
      phase: 'post-open',
      onDisconnect: (diag) => {
        // 生产路径真实输出完整 post-open 诊断（异步补查，不影响 pending 失败）
        diagnosisPromise = emitPostOpenDiagnostics({
          ...diagContext,
          diag: {
            ...diag,
            browserExited: Boolean(browserExited),
            exitCode: browserExited?.code,
            exitSignal: browserExited?.signal,
          },
        }).catch(() => {})
      },
    })
    await client.send('Browser.getVersion', {}, { sessionId: null })
    const attached = await client.send('Target.attachToTarget', {
      targetId: target.id,
      flatten: true,
    }, { sessionId: null })
    if (!attached || typeof attached.sessionId !== 'string' || !attached.sessionId) {
      throw Object.assign(new Error('Target.attachToTarget 未返回 sessionId'), { stage: 'WS_FAILED' })
    }
    client.setSessionId(attached.sessionId)
    await client.send('Page.enable')
    await client.send('Runtime.enable')
    if (injectScript) {
      await client.send('Page.addScriptToEvaluateOnNewDocument', { source: injectScript })
    }
    mark('WS_READY')
    return {
      child, ws, client, cdpPort: activePort.port, targetId: target.id,
      close, browserPath, profileDir: ownedProfile, timeline, runId,
    }
  } catch (cause) {
    // 失败路径：状态时间线 + Edge stderr 脱敏关键行 + 崩溃分类
    const stage = cause && typeof cause.stage === 'string' ? cause.stage : 'UNKNOWN'
    mark(stage, cause instanceof Error ? cause.message : String(cause))
    console.error(`  readiness 时间线: ${timeline.map((entry) => `[${entry.at}ms]${entry.state}${entry.detail ? `(${redactAndTruncate(entry.detail, 120)})` : ''}`).join(' → ')}`)
    const summary = summarizeStderr(stderrBuffer)
    if (summary) {
      console.error(`  Edge stderr 关键行（脱敏）:\n${summary}`)
    }
    if (browserExited) {
      const classification = classifyExit({ ...browserExited, stderr: stderrBuffer })
      console.error(`  Edge 退出分类: ${classification.hex ?? '?'}${classification.signal ? ` signal=${classification.signal}` : ''}${classification.findings.length ? `（${classification.findings.join('；')}）` : ''}`)
    }
    // 有界等待诊断收集完成，再关闭并抛原始错误
    if (diagnosisPromise) {
      await Promise.race([diagnosisPromise, deps.sleep(DIAGNOSTIC_SETTLE_MS)])
    }
    await close()
    throw cause
  }
}

/** 安全删除一个 attempt 的 profile（仅当 inspectOwnedProfile 判定过期且断言通过）。 */
async function cleanupAttemptProfile(profileDir, deps) {
  const info = await inspectOwnedProfile(profileDir, deps)
  if (!info.expired) {
    return { ok: false, reason: info.reason ?? '未过期' }
  }
  let owned
  try {
    owned = assertOwnedProfileDir(profileDir)
  } catch (error) {
    return { ok: false, reason: error instanceof Error ? error.message : String(error) }
  }
  try {
    await deps.removeDir(owned)
    return { ok: true }
  } catch (error) {
    return { ok: false, reason: `删除失败: ${error instanceof Error ? error.message : String(error)}` }
  }
}

/**
 * 整体初始化（含重试）：清理残留 → 每 attempt 全新 profile/随机端口 → 启动 → target → WS → enable。
 * 每次重试使用全新 profile（pet-layout-check-<runId>-aN）、全新端口、进程、target、socket；
 * 重试前等待上一轮诊断收集完成、进程树退出并安全清理上一轮 profile；
 * 最终错误汇总每轮失败摘要。
 */
export async function initBrowserSession({
  deps = DEFAULT_DEPS,
  browserPath,
  profileDir,
  injectScript = null,
  sendTimeoutMs = 15000,
  log = () => {},
  maxRetries = MAX_INIT_RETRIES,
  edgeArgs = DEFAULT_EDGE_ARGS,
}) {
  // 校验基准路径（同时验证 tmpdir 可解析）
  assertOwnedProfileDir(profileDir)
  const tmpRoot = resolve(tmpdir())
  const runId = `${process.pid}-${deps.now().toString(36)}`

  // 0) 启动前清理历史残留（仅确认过期实例；活跃实例不受影响）
  const killed = await killResidualBrowsers(deps)
  const removedProfiles = await cleanupStaleProfiles(deps)
  if (killed || removedProfiles) {
    log(`启动前清理：终止过期实例 ${killed} 个，清理残留 profile ${removedProfiles} 个`)
  }

  const errors = []
  for (let attempt = 1; attempt <= maxRetries; attempt += 1) {
    // 每 attempt 独立全新 profile：pet-layout-check-<runId>-a<attempt>
    const attemptProfile = join(tmpRoot, `${PROFILE_PREFIX}${runId}-a${attempt}`)
    assertOwnedProfileDir(attemptProfile)
    try {
      if (attempt > 1) log(`初始化重试（第 ${attempt}/${maxRetries} 次，新 profile ${basename(attemptProfile)}）…`)
      // 端口由 Edge 动态选择（--remote-debugging-port=0 + DevToolsActivePort），
      // 每个 attempt 全新实例天然获得全新端口
      return await connectOnce({
        deps, browserPath, profileDir: attemptProfile,
        injectScript, sendTimeoutMs, log, attempt, maxAttempts: maxRetries,
        edgeArgs, runId,
      })
    } catch (cause) {
      const message = cause instanceof Error ? cause.message : String(cause)
      errors.push({ attempt, message })
      log(`初始化失败（第 ${attempt}/${maxRetries} 次）: ${message}`)
      // 安全清理上一轮 profile（失败必须明确报告，不伪装成功）
      const cleanup = await cleanupAttemptProfile(attemptProfile, deps)
      if (!cleanup.ok) {
        log(`  上一轮 profile 清理未完成: ${cleanup.reason}`)
      }
      if (attempt < maxRetries) {
        await deps.sleep(600)
      }
    }
  }
  const summary = errors.map((entry) => `[第${entry.attempt}次] ${entry.message}`).join('；')
  throw new Error(`浏览器初始化重试耗尽（${maxRetries} 次均失败）：${summary}`)
}

export function makeDeps(overrides) {
  return { ...DEFAULT_DEPS, ...overrides }
}
