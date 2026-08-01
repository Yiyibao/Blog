/**
 * PARTNER 真实登录验收脚本。
 * 依赖：后端 8080 + 前端 dev 5173（strictPort 锁定）。
 * 流程：
 *  1. 打开 /admin/login，读取 backend/.env.properties 的 APP_PARTNER_* 凭据（仅内存，绝不输出）。
 *  2. 真实表单提交 + HumanVerifyModal（前端完成 PoW，不绕过不注入）。
 *  3. 断言 role=PARTNER。
 *  4. 断言宠物显示（pet-button）并能打开面板。
 *  5. 权限一致：管理接口 /api/v1/admin/ai/providers 200 且可读（与 ADMIN 同权）。
 *  6. 输出逐项 PASS/FAIL，任一失败非零退出。
 */
import { readFileSync, existsSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { cleanupStaleProfiles } from './lib/browser-session.mjs'

const ROOT = fileURLToPath(new URL('..', import.meta.url))
const FRONTEND_URL = 'http://127.0.0.1:5173'
const SEND_TIMEOUT_MS = 15000
const WAIT_TIMEOUT_MS = 60000
const WATCHDOG_MS = 240000

const BROWSERS = [
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
]

function log(...args) { console.log('[real-partner]', ...args) }

function fail(message) {
  console.error('[real-partner] FAIL:', message)
  process.exitCode = 1
  throw new Error(message)
}

function readPartnerCredentials() {
  const path = join(ROOT, '..', 'backend', '.env.properties')
  const values = {}
  for (const line of readFileSync(path, 'utf8').split(/\r?\n/)) {
    const match = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$/)
    if (match) values[match[1]] = match[2]
  }
  const username = values.APP_PARTNER_USERNAME
  const password = values.APP_PARTNER_PASSWORD
  if (!username || !password) fail('backend/.env.properties 缺少 APP_PARTNER_USERNAME/PASSWORD（环境无 PARTNER 凭据）')
  return { username, password }
}

async function main() {
  const credentials = readPartnerCredentials()
  const browserPath = BROWSERS.find((path) => existsSync(path))
  if (!browserPath) fail('未找到 Edge/Chrome')

  const watchdog = setTimeout(() => {
    console.error('[real-partner] FAIL: 整体执行超时（' + WATCHDOG_MS + 'ms），强制退出')
    process.exit(1)
  }, WATCHDOG_MS)
  watchdog.unref?.()

  let session = null
  const userDataDir = join(tmpdir(), `pet-layout-check-${process.pid}`)

  const checks = []
  const check = (name, ok, detail = '') => {
    checks.push({ name, ok })
    log(`检查 ${name}: ${ok ? 'PASS' : 'FAIL'}${detail ? `（${detail}）` : ''}`)
    if (!ok) fail(`检查失败: ${name}`)
  }

  try {
    const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))
    const { initBrowserSession } = await import('./lib/browser-session.mjs')
    session = await initBrowserSession({
      browserPath, profileDir: userDataDir, injectScript: null,
      sendTimeoutMs: SEND_TIMEOUT_MS, log,
    })
    const send = session.client.send
    log('CDP 就绪: target', session.targetId, '@', session.cdpPort)

    const evaluate = async (expression) => {
      const result = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
      if (result.exceptionDetails) throw new Error(`evaluate 失败: ${result.exceptionDetails.text}`)
      return result.result.value
    }
    const waitFor = async (expression, timeoutMs = WAIT_TIMEOUT_MS, label = expression) => {
      const deadline = Date.now() + timeoutMs
      while (Date.now() < deadline) {
        try {
          const value = await evaluate(expression)
          if (value) return value
        } catch (error) {
          if (String(error).includes('WS 未打开') || String(error).includes('连接中断')) throw error
        }
        await sleep(300)
      }
      throw new Error(`等待超时（${timeoutMs}ms）: ${label}`)
    }

    // ---- 1. PARTNER 真实登录（CAPTCHA 前端完成）----
    await send('Page.navigate', { url: `${FRONTEND_URL}/admin/login` })
    await waitFor(`document.readyState === 'complete'`)
    await waitFor(`!!document.querySelector('form.admin-login-card')`, WAIT_TIMEOUT_MS, '登录表单')
    const loginForm = await evaluate(`(() => {
      const u = document.querySelector('input[autocomplete="username"]')
      const p = document.querySelector('input[type="password"]')
      return { hasUser: !!u, hasPass: !!p }
    })()`)
    check('登录页表单就绪', loginForm.hasUser && loginForm.hasPass)

    await evaluate(`(() => {
      const set = (el, value) => { el.value = value; el.dispatchEvent(new Event('input', { bubbles: true })) }
      set(document.querySelector('input[autocomplete="username"]'), ${JSON.stringify(credentials.username)})
      set(document.querySelector('input[type="password"]'), ${JSON.stringify(credentials.password)})
      document.querySelector('form.admin-login-card').requestSubmit()
      return true
    })()`)
    await waitFor(`!!document.querySelector('.verify-modal')`, 15000, '人机验证弹窗')
    log('人机验证弹窗已出现（CAPTCHA 由前端自动完成，不绕过）')
    await evaluate(`document.querySelector('.verify-start').click()`)
    await waitFor(`sessionStorage.getItem('yubai-admin-token') !== null`, WAIT_TIMEOUT_MS, '登录会话')
    const role = await evaluate(`sessionStorage.getItem('yubai-admin-role')`)
    check('PARTNER 真实登录成功（CAPTCHA 通过）', role === 'PARTNER', `role=${role}`)

    // ---- 2. 宠物显示与面板 ----
    await send('Page.navigate', { url: `${FRONTEND_URL}/` })
    await waitFor(`document.readyState === 'complete'`)
    await waitFor(`!!document.querySelector('[data-testid="pet-button"]')`, WAIT_TIMEOUT_MS, '宠物按钮')
    check('PARTNER 显示宠物', true, 'pet-button 存在')
    await evaluate(`document.querySelector('[data-testid="pet-button"]').click()`)
    await waitFor(`!!document.querySelector('[data-testid="pet-chat-panel"]')`, WAIT_TIMEOUT_MS, '聊天面板')
    check('PARTNER 可打开聊天面板', true)

    // ---- 3. 权限一致：管理接口可达 ----
    const listResult = await evaluate(`fetch('/api/v1/admin/ai/providers', { headers: { Authorization: 'Bearer ' + sessionStorage.getItem('yubai-admin-token') } }).then(async r => ({ status: r.status, body: await r.json() }))`)
    const list = await listResult.body
    log(`PARTNER 接口响应 status=${listResult.status} keys=${Object.keys(list).join(',')} dataType=${Array.isArray(list.data) ? 'array' : typeof list.data}`)
    check('PARTNER 访问管理接口权限一致（200 可读）', listResult.status === 200 && Array.isArray(list.data), `status=${listResult.status}`)
    log(`PARTNER 供应商注册表: ${(list.data ?? []).length} 条`)

    const failed = checks.filter((entry) => !entry.ok)
    log(`全部检查 ${checks.length} 项，失败 ${failed.length} 项`)
    if (failed.length) fail(`存在失败项: ${JSON.stringify(failed)}`)
    console.log('[real-partner] ALL PASS')
  } catch (error) {
    console.error('[real-partner] ERROR:', error instanceof Error ? error.message : error)
    process.exitCode = 1
  } finally {
    try { await session?.close() } catch { /* ignore */ }
    try { await cleanupStaleProfiles() } catch { /* ignore */ }
    clearTimeout(watchdog)
  }
}

main().catch((error) => {
  console.error('[real-partner] ERROR:', error instanceof Error ? error.message : error)
  process.exit(1)
})
