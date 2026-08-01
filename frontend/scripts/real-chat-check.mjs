/**
 * 真实聊天验收脚本：本地完整环境（后端 8080 + 前端 dev 5173 + opencode serve 4096）。
 *
 * 流程：
 *  1. 打开 /admin/login，读取 backend/.env.properties 的账号（仅内存使用，绝不输出），
 *     提交表单并通过 HumanVerifyModal（PoW/图形 CAPTCHA 由前端完成，不绕过不削弱）。
 *  2. 供应商注册表：空则创建 OPENCODE_SERVER 类型供应商（连本地 opencode serve，
 *     Basic 凭据来自后端 env），执行“测试连通”。
 *  3. 宠物 compact 面板真实发送一条消息并等待助手回复（校验无错误条）。
 *  4. /admin/ai 完整页真实发送一条消息并等待助手回复。
 *  5. 深色主题下读取聊天容器/气泡/输入框/焦点环的计算样式。
 *  6. 输出逐项 PASS/FAIL，任一失败非零退出。
 *
 * 依赖服务（需先启动）：后端 8080（含 APP_AI_OPENCODE_USERNAME/PASSWORD 与
 * APP_AI_ALLOW_LOCAL_ENDPOINTS=true）、前端 dev 5173、opencode serve 4096。
 * 运行：node scripts/real-chat-check.mjs
 */
import { readFileSync, existsSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, normalize } from 'node:path'
import { fileURLToPath } from 'node:url'
import { cleanupStaleProfiles } from './lib/browser-session.mjs'

const ROOT = fileURLToPath(new URL('..', import.meta.url))
const FRONTEND_URL = process.env.REAL_CHAT_FRONTEND_URL || 'http://127.0.0.1:5173'
const OPENCODE_URL = 'http://127.0.0.1:4096'
const OPENCODE_USER = process.env.REAL_CHAT_OPENCODE_USERNAME || 'opencode'
const OPENCODE_PASS = process.env.REAL_CHAT_OPENCODE_PASSWORD || ''
const SEND_TIMEOUT_MS = 15000
const WAIT_TIMEOUT_MS = 60000
const WATCHDOG_MS = 420000
const MESSAGE = '你好，请用一句中文介绍你自己。'

const BROWSERS = [
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
]

function log(...args) {
  console.log('[real-chat]', ...args)
}

function fail(message) {
  console.error('[real-chat] FAIL:', message)
  process.exitCode = 1
  throw new Error(message)
}

function readAdminCredentials() {
  // 从 backend/.env.properties 读取（内存使用；绝不打印账号密码）
  const path = join(ROOT, '..', 'backend', '.env.properties')
  const values = {}
  for (const line of readFileSync(path, 'utf8').split(/\r?\n/)) {
    const match = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$/)
    if (match) values[match[1]] = match[2]
  }
  const username = values.APP_ADMIN_USERNAME
  const password = values.APP_ADMIN_PASSWORD
  if (!username || !password) fail('backend/.env.properties 缺少 APP_ADMIN_USERNAME/PASSWORD')
  return { username, password }
}

async function main() {
  if (!OPENCODE_PASS) fail('缺少 REAL_CHAT_OPENCODE_PASSWORD；请通过 acceptance-run.mjs 运行或显式设置本地验收凭据')
  const credentials = readAdminCredentials()
  const browserPath = BROWSERS.find((path) => existsSync(path))
  if (!browserPath) fail('未找到 Edge/Chrome')

  const watchdog = setTimeout(() => {
    console.error('[real-chat] FAIL: 整体执行超时（' + WATCHDOG_MS + 'ms），强制退出')
    process.exit(1)
  }, WATCHDOG_MS)
  watchdog.unref?.()

  let session = null
  // 统一使用 pet-layout-check- 特征前缀（initBrowserSession 校验 + 残留清理共用）
  const userDataDir = join(tmpdir(), `pet-layout-check-${process.pid}`)

  const checks = []
  const check = (name, ok, detail = '') => {
    checks.push({ name, ok })
    log(`检查 ${name}: ${ok ? 'PASS' : 'FAIL'}${detail ? `（${detail}）` : ''}`)
    if (!ok) fail(`检查失败: ${name}`)
  }

  try {
    const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))
    // ---- 浏览器会话整体初始化（清理残留 → 随机 CDP 端口 → 启动 → target → WS → enable；失败整体重试）----
    const { initBrowserSession } = await import('./lib/browser-session.mjs')
    session = await initBrowserSession({
      browserPath,
      profileDir: userDataDir,
      injectScript: null,
      sendTimeoutMs: SEND_TIMEOUT_MS,
      log,
    })
    const send = session.client.send
    log('CDP 就绪: target', session.targetId, '@', session.cdpPort)

    const evaluate = async (expression) => {
      const result = await send('Runtime.evaluate', {
        expression, returnByValue: true, awaitPromise: true,
      })
      if (result.exceptionDetails) {
        throw new Error(`evaluate 失败: ${result.exceptionDetails.text}`)
      }
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

    // ---- 1. 登录（CAPTCHA 由前端完成）----
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
      const set = (el, value) => {
        el.value = value
        el.dispatchEvent(new Event('input', { bubbles: true }))
      }
      set(document.querySelector('input[autocomplete="username"]'), ${JSON.stringify(credentials.username)})
      set(document.querySelector('input[type="password"]'), ${JSON.stringify(credentials.password)})
      document.querySelector('form.admin-login-card').requestSubmit()
      return true
    })()`)
    await waitFor(`!!document.querySelector('.verify-modal')`, 15000, '人机验证弹窗')
    log('人机验证弹窗已出现（CAPTCHA 由前端自动完成，不绕过）')
    await evaluate(`document.querySelector('.verify-start').click()`)
    // 等待登录完成：会话落地
    await waitFor(`sessionStorage.getItem('yubai-admin-token') !== null`, WAIT_TIMEOUT_MS, '登录会话')
    const role = await evaluate(`sessionStorage.getItem('yubai-admin-role')`)
    check('真实登录成功（CAPTCHA 通过）', role === 'ADMIN' || role === 'PARTNER', `role=${role}`)

    // ---- 2. 供应商注册表 + 连通测试 ----
    const listResult = await evaluate(`fetch('/api/v1/admin/ai/providers', { headers: { Authorization: 'Bearer ' + sessionStorage.getItem('yubai-admin-token') } }).then(r => r.json())`)
    const providers = listResult.data ?? []
    log(`供应商注册表: ${providers.length} 条`)
    let providerId = providers.find((p) => p.enabled)?.id ?? null

    if (!providerId) {
      // 从 opencode serve 拉取 opencode-go 模型列表
      const b64 = Buffer.from(`${OPENCODE_USER}:${OPENCODE_PASS}`).toString('base64')
      const ocRes = await fetch(`${OPENCODE_URL}/provider`, {
        headers: { Authorization: `Basic ${b64}` },
      })
      if (ocRes.status !== 200) fail(`opencode serve /provider 返回 ${ocRes.status}`)
      const oc = await ocRes.json()
      const go = oc.all.find((p) => p.id === 'opencode-go')
      const models = go ? Object.keys(go.models ?? {}) : []
      if (!models.includes('deepseek-v4-flash')) fail('opencode-go 无 deepseek-v4-flash 模型')
      const createResult = await evaluate(`fetch('/api/v1/admin/ai/providers', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + sessionStorage.getItem('yubai-admin-token') },
        body: JSON.stringify({
          name: '本地opencode',
          baseUrl: 'http://127.0.0.1:4096',
          providerType: 'OPENCODE_SERVER',
          models: ${JSON.stringify(models)},
          defaultModel: 'deepseek-v4-flash',
          enabled: true,
          dailyRequestLimit: 200,
          dailyTokenLimit: 200000,
        }),
      }).then(r => r.json())`)
      if (!createResult.data?.id) fail(`创建 OPENCODE_SERVER 供应商失败: ${createResult.message ?? '未知错误'}`)
      providerId = createResult.data.id
      check('创建 OPENCODE_SERVER 供应商（本地 opencode serve）', true, `id=${providerId}`)
    } else {
      log('复用现有已启用供应商 id=' + providerId)
    }

    const testResult = await evaluate(`fetch('/api/v1/admin/ai/providers/${providerId}/test', {
      method: 'POST',
      headers: { Authorization: 'Bearer ' + sessionStorage.getItem('yubai-admin-token') },
    }).then(r => r.json())`)
    const testData = testResult.data ?? {}
    log(`供应商连通测试: ok=${testData.ok} message=${testData.message} models=${(testData.models ?? []).slice(0, 5).join(',')}`)
    check('供应商“测试连通”成功', testData.ok === true, testData.message)

    // ---- 3. compact 面板真实发消息 ----
    await send('Page.navigate', { url: `${FRONTEND_URL}/` })
    await waitFor(`document.readyState === 'complete'`)
    await waitFor(`!!document.querySelector('[data-testid="pet-button"]')`, WAIT_TIMEOUT_MS, '宠物按钮')
    await sleep(600)
    await evaluate(`document.querySelector('[data-testid="pet-button"]').click()`)
    await waitFor(`!!document.querySelector('[data-testid="pet-chat-panel"]')`, WAIT_TIMEOUT_MS, '面板')
    await waitFor(`!!document.querySelector('[data-testid="ai-chat-input"]')`, WAIT_TIMEOUT_MS, '输入框')
    await sleep(500)
    await evaluate(`(() => {
      const input = document.querySelector('[data-testid="ai-chat-input"]')
      input.value = ${JSON.stringify(MESSAGE)}
      input.dispatchEvent(new Event('input', { bubbles: true }))
      return true
    })()`)
    await evaluate(`document.querySelector('button.send-btn').click()`)
    // 动画状态：发送后应为 running
    await sleep(800)
    const runningState = await evaluate(`document.querySelector('.pet-sprite')?.getAttribute('data-state')`)
    check('compact 发送中宠物为 running 状态', runningState === 'running', `state=${runningState}`)
    // 等待助手回复（无错误条；排除「思考中…」loading 占位）
    const compactResult = await waitFor(`(() => {
      const bubbles = [...document.querySelectorAll('.ai-chat-compact-root .chat-bubble-wrap.assistant .bubble-content')]
      const last = bubbles[bubbles.length - 1]
      return last && !last.classList.contains('loading-indicator') && last.textContent.trim().length > 0
        ? last.textContent.trim() : ''
    })()`, WAIT_TIMEOUT_MS, 'compact 助手回复')
    const compactError = await evaluate(`!!document.querySelector('.ai-chat-compact-root .chat-error-bar')`)
    check('compact 无错误条', !compactError)
    check('compact 收到助手回复', compactResult.length > 0, compactResult.slice(0, 60))
    // 等 review 一轮播完，确认成功后状态回到 waiting/idle（review 总时长 1030ms）
    await sleep(2200)
    const afterState = await evaluate(`document.querySelector('.pet-sprite')?.getAttribute('data-state')`)
    check('compact 完成后宠物回 waiting/idle', ['waiting', 'idle'].includes(afterState), `state=${afterState}`)

    // ---- 4a. 深色主题：compact 关键计算样式（页面仍停留在首页、面板仍打开）----
    await evaluate(`document.documentElement.classList.add('dark'); true`)
    await sleep(300)
    const darkCompact = await evaluate(`(() => {
      const pick = (selector) => {
        const el = document.querySelector(selector)
        if (!el) return { bg: '', color: '' }
        const s = getComputedStyle(el)
        return { bg: s.backgroundColor, color: s.color }
      }
      return {
        container: pick('.ai-chat-compact-root .ai-chat-container'),
        assistantBubble: pick('.ai-chat-compact-root .chat-bubble-wrap.assistant .bubble-content'),
        inputArea: pick('.ai-chat-compact-root .chat-input-area'),
        textarea: pick('.ai-chat-compact-root [data-testid="ai-chat-input"]'),
      }
    })()`)
    const notWhite = (c) => c && c !== 'rgba(0, 0, 0, 0)' && !/rgba?\(2\d\d,\s*2\d\d,\s*2\d\d/.test(c)
    check('深色 compact 容器非纯白', notWhite(darkCompact.container.bg), darkCompact.container.bg)
    check('深色 compact 助手气泡非纯白', notWhite(darkCompact.assistantBubble.bg), darkCompact.assistantBubble.bg)
    check('深色 compact 输入区非纯白', notWhite(darkCompact.inputArea.bg), darkCompact.inputArea.bg)
    check('深色 compact 输入框非纯白', notWhite(darkCompact.textarea.bg), darkCompact.textarea.bg)
    await evaluate(`document.documentElement.classList.remove('dark'); true`)

    // ---- 4. /admin/ai 完整页真实发消息 ----
    await send('Page.navigate', { url: `${FRONTEND_URL}/admin/ai` })
    await waitFor(`document.readyState === 'complete'`)
    await waitFor(`!!document.querySelector('.ai-chat-console [data-testid="ai-chat-input"]')`, WAIT_TIMEOUT_MS, '完整页输入框')
    await sleep(800)
    check('/admin/ai 无 compact 实例', !(await evaluate(`!!document.querySelector('.ai-chat-compact-root')`)))
    await evaluate(`(() => {
      const input = document.querySelector('.ai-chat-console [data-testid="ai-chat-input"]')
      input.value = ${JSON.stringify(MESSAGE)}
      input.dispatchEvent(new Event('input', { bubbles: true }))
      return true
    })()`)
    await evaluate(`document.querySelector('button.send-btn').click()`)
    const fullResult = await waitFor(`(() => {
      const bubbles = [...document.querySelectorAll('.ai-chat-console .chat-bubble-wrap.assistant .bubble-content')]
      const last = bubbles[bubbles.length - 1]
      return last && !last.classList.contains('loading-indicator') && last.textContent.trim().length > 0
        ? last.textContent.trim() : ''
    })()`, WAIT_TIMEOUT_MS, '完整页助手回复')
    const fullError = await evaluate(`!!document.querySelector('.ai-chat-console .chat-error-bar')`)
    check('/admin/ai 无错误条', !fullError)
    check('/admin/ai 收到助手回复', fullResult.length > 0, fullResult.slice(0, 60))

    // ---- 5. 深色主题：完整页容器/输入框与焦点环 ----
    await evaluate(`document.documentElement.classList.add('dark'); true`)
    await sleep(300)
    const darkFull = await evaluate(`(() => {
      const pick = (selector) => {
        const el = document.querySelector(selector)
        if (!el) return { bg: '', color: '' }
        const s = getComputedStyle(el)
        return { bg: s.backgroundColor, color: s.color }
      }
      return {
        container: pick('.ai-chat-console .ai-chat-container'),
        assistantBubble: pick('.ai-chat-console .chat-bubble-wrap.assistant .bubble-content'),
        inputArea: pick('.ai-chat-console .chat-input-area'),
        textarea: pick('.ai-chat-console [data-testid="ai-chat-input"]'),
      }
    })()`)
    check('深色完整页容器非纯白', notWhite(darkFull.container.bg), darkFull.container.bg)
    check('深色完整页助手气泡非纯白', notWhite(darkFull.assistantBubble.bg), darkFull.assistantBubble.bg)
    check('深色完整页输入区非纯白', notWhite(darkFull.inputArea.bg), darkFull.inputArea.bg)
    check('深色完整页输入框非纯白', notWhite(darkFull.textarea.bg), darkFull.textarea.bg)

    // 焦点环：聚焦输入框读取 box-shadow
    await send('Page.bringToFront')
    await evaluate(`document.querySelector('.ai-chat-console [data-testid="ai-chat-input"]').focus()`)
    await sleep(200)
    const focusRing = await evaluate(`(() => {
      const el = document.querySelector('.ai-chat-console [data-testid="ai-chat-input"]')
      const s = getComputedStyle(el)
      return {
        active: document.activeElement === el,
        className: el?.className ?? '',
        shadow: s.boxShadow,
        outline: s.outline,
        outlineStyle: s.outlineStyle,
        outlineWidth: s.outlineWidth,
        border: s.borderColor,
      }
    })()`)
    const hasShadow = Boolean(focusRing.shadow && focusRing.shadow !== 'none')
    const hasOutline = focusRing.outlineStyle !== 'none' && parseFloat(focusRing.outlineWidth) > 0
    check('完整页输入框获得焦点且聚焦环可见',
      focusRing.active && (hasShadow || hasOutline),
      `active=${focusRing.active} class=${focusRing.className} shadow=${focusRing.shadow} outline=${focusRing.outline}`)
    await evaluate(`document.documentElement.classList.remove('dark'); true`)

    const failed = checks.filter((entry) => !entry.ok)
    log(`全部检查 ${checks.length} 项，失败 ${failed.length} 项`)
    if (failed.length) fail(`存在失败项: ${JSON.stringify(failed)}`)
    console.log('[real-chat] ALL PASS')
  } catch (error) {
    console.error('[real-chat] ERROR:', error instanceof Error ? error.message : error)
    process.exitCode = 1
  } finally {
    try { await session?.close() } catch { /* ignore */ }
    try { await cleanupStaleProfiles() } catch { /* ignore */ }
    clearTimeout(watchdog)
  }
}

main().catch((error) => {
  console.error('[real-chat] ERROR:', error instanceof Error ? error.message : error)
  process.exit(1)
})
