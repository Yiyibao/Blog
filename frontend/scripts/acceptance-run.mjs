/**
 * 真实聊天验收一键编排：预检 → 启动（可归属进程自动重建）→ 健康轮询 → 真实检查 → finally 清理。
 *
 * 用途：消除"手工启动三个服务"的编排依赖（最终报告剩余风险 1/2）。
 * 服务：
 *   - 后端 8080：mvn spring-boot:run，注入验收运行参数
 *     APP_AI_OPENCODE_USERNAME/PASSWORD、APP_AI_ALLOW_LOCAL_ENDPOINTS=true、
 *     AI_ENABLED=true、APP_AI_OPENCODE_AGENT=build（非生产默认值，仅本编排注入）。
 *   - 前端 5173：npm run dev -- --host 127.0.0.1 --port 5173 --strictPort（禁止降级 5174）。
 *   - opencode serve 4096：opencode.exe serve --port 4096，
 *     env OPENCODE_SERVER_USERNAME/PASSWORD 提供 Basic 鉴权。
 *   - 检查：node scripts/real-chat-check.mjs、node scripts/real-partner-check.mjs。
 *
 * 启动前要求三个固定端口均为空闲。即使命令行看起来属于本项目，也不能证明是本轮
 * 启动的进程，因此只报告 PID/脱敏命令行并失败，绝不终止既有进程。
 *
 * 无论成败，finally 中 taskkill /T /F 终止本轮启动的完整进程树并等待端口释放。
 * 运行：node scripts/acceptance-run.mjs
 */
import { spawn, execFileSync } from 'node:child_process'
import { randomBytes } from 'node:crypto'
import { request as nodeHttpRequest } from 'node:http'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { redact } from './lib/redact.mjs'

const ROOT = fileURLToPath(new URL('..', import.meta.url)) // = frontend/
const FRONTEND_DIR = ROOT
const BACKEND_DIR = join(ROOT, '..', 'backend')
const OPENCODE_EXE = 'D:\\Office\\nodejs\\node_global\\node_modules\\opencode-ai\\bin\\opencode.exe'
const MAVEN_CMD = 'C:\\Program Files\\apache-maven-3.9.16\\bin\\mvn.cmd'
const NPM_CMD = 'C:\\Program Files\\nodejs\\npm.cmd'
const POWERSHELL_EXE = 'C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe'
const OPENCODE_USER = 'opencode'
// 每次验收随机生成，仅在本轮父/子进程内传递，不写文件、不输出。
const OPENCODE_PASS = randomBytes(24).toString('base64url')
const START_TIMEOUT_MS = {
  opencode: 30000,
  backend: 150000,
  frontend: 60000,
}
const POLL_MS = 2000
const KILL_SETTLE_MS = 4000

const PORTS = {
  backend: 8080,
  frontend: 5173,
  opencode: 4096,
}

const started = []
let cleaningUp = false

function log(...args) { console.log('[acceptance-run]', ...args) }

function fail(message) {
  console.error('[acceptance-run] FAIL:', message)
  process.exitCode = 1
  throw new Error(message)
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

/** 本地 HTTP 探测（仅 127.0.0.1；支持自定义头与 JSON body；不访问外部网络） */
function localHttp(port, path, { method = 'GET', headers = {}, body = null, timeoutMs = 4000 } = {}) {
  return new Promise((resolve, reject) => {
    let settled = false
    let timer = null
    const finish = (callback, value) => {
      if (settled) return
      settled = true
      if (timer) clearTimeout(timer)
      callback(value)
    }
    const req = nodeHttpRequest({
      host: '127.0.0.1',
      port,
      method,
      path,
      headers: { Connection: 'close', ...headers },
      agent: false,
    }, (res) => {
      const chunks = []
      res.on('data', (chunk) => chunks.push(chunk))
      res.on('error', (error) => finish(reject, error))
      res.on('end', () => {
        const text = Buffer.concat(chunks).toString('utf8')
        let json = null
        try { json = text ? JSON.parse(text) : null } catch { json = null }
        finish(resolve, { status: res.statusCode ?? 0, text, json })
      })
    })
    req.on('error', (error) => finish(reject, error))
    timer = setTimeout(() => {
      const error = Object.assign(new Error('probe timeout'), { code: 'ETIMEDOUT' })
      req.destroy(error)
      finish(reject, error)
    }, timeoutMs)
    timer.unref?.()
    if (body !== null) req.write(body)
    req.end()
  })
}

/** 监听指定端口的进程（netstat + CIM 命令行）；未监听返回 null */
function findListener(port) {
  let pid = null
  try {
    const out = execFileSync('netstat.exe', ['-ano', '-p', 'TCP'], { encoding: 'utf8', timeout: 10000 })
    const line = out.split(/\r?\n/).find((l) => l.includes(`:${port} `) && /LISTENING/i.test(l))
    if (!line) return null
    pid = parseInt(line.trim().split(/\s+/).pop(), 10)
    if (!pid) return { pid: null, cmdline: null, inspectionError: 'netstat PID 无效' }
    const cmdline = execFileSync(
      'powershell.exe',
      ['-NoProfile', '-Command', `(Get-CimInstance Win32_Process -Filter 'ProcessId=${pid}').CommandLine`],
      { encoding: 'utf8', timeout: 15000 },
    ).trim()
    return { pid, cmdline, inspectionError: null }
  } catch (error) {
    // netstat 已确认监听后，CIM 权限失败不能降级成“端口空闲”。
    if (pid) {
      return {
        pid,
        cmdline: null,
        inspectionError: error instanceof Error ? error.message : String(error),
      }
    }
    throw error
  }
}

function killTree(pid) {
  try {
    execFileSync('taskkill.exe', ['/PID', String(pid), '/T', '/F'], { stdio: 'ignore', timeout: 15000 })
    return true
  } catch {
    return false
  }
}

function basicAuthHeader(user, pass) {
  return { Authorization: 'Basic ' + Buffer.from(`${user}:${pass}`).toString('base64') }
}

async function waitHealthy(port, probe, label, timeoutMs, child = null) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (child && (child.exitCode !== null || child.signalCode !== null)) {
      throw new Error(`${label} 在健康检查前退出 code=${child.exitCode} signal=${child.signalCode}`)
    }
    try {
      const result = await probe()
      if (result) return result
    } catch { /* retry */ }
    await sleep(POLL_MS)
  }
  return null
}

const probeBackend = async () => {
  const result = await localHttp(PORTS.backend, '/actuator/health')
  return result?.status === 200 && /"status":"UP"/.test(result.text) ? result : null
}

const probeFrontend = async () => {
  const result = await localHttp(PORTS.frontend, '/')
  return result?.status === 200 ? result : null
}

const probeOpencode = async () => {
  const result = await localHttp(PORTS.opencode, '/provider', { headers: basicAuthHeader(OPENCODE_USER, OPENCODE_PASS) })
  return result?.status === 200 ? result : null
}

/** 安全预检：任何既有监听都拒绝启动；本轮只清理由本进程亲自 spawn 的 child。 */
function preflight() {
  for (const [name, port] of Object.entries(PORTS)) {
    const listener = findListener(port)
    if (!listener) {
      log(`预检: ${name} ${port} 空闲`)
      continue
    }
    const detail = listener.cmdline
      ? `命令行: ${redact(listener.cmdline)}`
      : `命令行不可读取: ${redact(listener.inspectionError ?? 'unknown')}`
    fail(`预检: ${name} ${port} 已被 pid=${listener.pid ?? 'unknown'} 占用，拒绝终止既有进程。${detail}`)
  }
}

/** 终止本轮启动的进程树并等待端口释放 */
async function cleanupOwned() {
  cleaningUp = true
  for (const entry of started) {
    // child 仍存活才按本轮保存的 PID 清理，避免进程退出后 PID 被复用而误杀。
    if (entry.pid && entry.child?.exitCode === null && entry.child?.signalCode === null) {
      log(`清理: 终止本轮进程树 pid=${entry.pid}（${entry.name}）`)
      killTree(entry.pid)
    }
  }
  const deadline = Date.now() + 20000
  while (Date.now() < deadline) {
    const busy = Object.values(PORTS).filter((p) => findListener(p))
    if (!busy.length) break
    await sleep(KILL_SETTLE_MS)
  }
  for (const [name, port] of Object.entries(PORTS)) {
    const listener = findListener(port)
    log(`端口 ${name} ${port}: ${listener ? `仍被 pid=${listener.pid} 占用` : '已释放'}`)
  }
}

async function ensureService(name, port, probe, spawnFn, timeoutKey) {
  const healthy = await waitHealthy(port, probe, name, START_TIMEOUT_MS[timeoutKey])
  if (healthy) {
    log(`${name} ${port} 已健康（复用现有实例）`)
    return null
  }
  log(`启动 ${name} ...`)
  const child = spawnFn()
  started.push({ pid: child.pid, name, child })
  child.stdout?.on('data', (d) => log(`[${name}]`, redact(String(d))))
  child.stderr?.on('data', (d) => log(`[${name}]`, redact(String(d))))
  child.on('exit', (code, signal) => {
    if (!cleaningUp) log(`${name} 提前退出 code=${code} signal=${signal}`)
  })
  const ok = await waitHealthy(port, probe, name, START_TIMEOUT_MS[timeoutKey], child)
  if (!ok) fail(`${name} ${port} 未在时限内健康`)
  log(`${name} ${port} 健康（pid=${child.pid}）`)
  return child
}

async function main() {
  // ---- 0. 预检（含归属判定）----
  preflight()
  await sleep(3000)

  // ---- 1. opencode serve 4096 ----
  await ensureService('opencode serve', PORTS.opencode, probeOpencode, () => spawn(
    OPENCODE_EXE, ['serve', '--port', String(PORTS.opencode)],
    { cwd: FRONTEND_DIR, stdio: 'pipe', windowsHide: true,
      env: { ...process.env, OPENCODE_SERVER_USERNAME: OPENCODE_USER, OPENCODE_SERVER_PASSWORD: OPENCODE_PASS } },
  ), 'opencode')

  // ---- 2. 后端 8080 ----
  await ensureService('后端', PORTS.backend, probeBackend, () => spawn(
    POWERSHELL_EXE, ['-NoProfile', '-NonInteractive', '-Command', `& '${MAVEN_CMD}' spring-boot:run`],
    { cwd: BACKEND_DIR, stdio: 'pipe', windowsHide: true,
      env: {
        ...process.env,
        APP_AI_OPENCODE_USERNAME: OPENCODE_USER,
        APP_AI_OPENCODE_PASSWORD: OPENCODE_PASS,
        APP_AI_ALLOW_LOCAL_ENDPOINTS: 'true',
        AI_ENABLED: 'true',
        APP_AI_OPENCODE_AGENT: 'build',
      } },
  ), 'backend')

  // ---- 3. 前端 5173（strictPort 锁定，禁止降级 5174）----
  await ensureService('前端', PORTS.frontend, probeFrontend, () => spawn(
    POWERSHELL_EXE, ['-NoProfile', '-NonInteractive', '-Command', `& '${NPM_CMD}' run dev -- --port 5173 --strictPort`],
    { cwd: FRONTEND_DIR, stdio: 'pipe', windowsHide: true },
  ), 'frontend')
  const stray = findListener(5174)
  if (stray) fail(`strictPort 失效：发现 5174 被 pid=${stray.pid} 监听（前端不应降级）`)

  // ---- 4. 真实检查（失败立即非零退出）----
  const checkScripts = [
    { name: 'real-chat-check', path: join(ROOT, 'scripts', 'real-chat-check.mjs') },
    { name: 'real-partner-check', path: join(ROOT, 'scripts', 'real-partner-check.mjs') },
  ]
  for (const script of checkScripts) {
    log(`执行 ${script.name} ...`)
    const result = await new Promise((resolve) => {
      const child = spawn('node.exe', [script.path], {
        cwd: FRONTEND_DIR,
        stdio: 'inherit',
        env: {
          ...process.env,
          REAL_CHAT_OPENCODE_USERNAME: OPENCODE_USER,
          REAL_CHAT_OPENCODE_PASSWORD: OPENCODE_PASS,
        },
      })
      child.on('exit', (code, signal) => resolve({ code, signal }))
    })
    log(`${script.name}: exit=${result.code}${result.signal ? ` signal=${result.signal}` : ''}`)
    if (result.code !== 0) fail(`${script.name} 未通过（exit=${result.code}）`)
  }

  log('真实聊天验收全部通过')
}

main()
  .catch((error) => {
    console.error('[acceptance-run] ERROR:', error instanceof Error ? error.message : error)
    process.exitCode = 1
  })
  .finally(async () => {
    try {
      await cleanupOwned()
    } catch (error) {
      console.error('[acceptance-run] 清理异常:', error instanceof Error ? error.message : error)
      process.exitCode = 1
    }
  })
