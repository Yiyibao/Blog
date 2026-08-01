/**
 * 宠物面板真实浏览器验收检查（几何边界 + 交互回归 + /admin/ai 单实例与聚焦 + 深浅主题 + 游客无图集）。
 *
 * jsdom 无法计算布局与 CSS 变量，本脚本用本机 Edge/Chrome headless + CDP 在真实渲染引擎验证。
 * 可靠性设计（P1 修复）：
 * - 启动前终止本脚本历史残留浏览器进程并清理残留 profile（防止连到上一轮"半死"实例，
 *   open 后立即断连的根因）；绝不触碰用户正在运行的浏览器；
 * - CDP 端口与静态服务端口随机选取，避免与任何残留监听冲突；
 * - 初始化（启动 → 等 CDP → 自有 target → WebSocket → Page.enable → Runtime.enable）
 *   整体重试，次数严格有界；每次重试使用全新 target/socket，重试前关闭旧 socket 并终止旧浏览器；
 * - 每个 CDP send() 有 15s 有界超时；WebSocket error/close 拒绝并清空全部 pending；
 *   error 后有界等待 close 信息，输出脱敏诊断（阶段、method、target、URL 摘要、浏览器退出码）；
 * - try/finally 统一清理 WS、静态服务、浏览器子进程（等待真正退出）、本脚本创建的临时 profile。
 *
 * 前置：先运行 `npm run build`。运行：`node scripts/pet-panel-layout-check.mjs`（Node >= 22）。
 * 登录态经 Page.addScriptToEvaluateOnNewDocument 注入 sessionStorage 模拟；聊天请求不伪造。
 */
import { createServer } from 'node:http'
import { readFile } from 'node:fs/promises'
import { existsSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, extname, normalize } from 'node:path'
import { fileURLToPath } from 'node:url'
import { initBrowserSession, cleanupStaleProfiles } from './lib/browser-session.mjs'

const ROOT = fileURLToPath(new URL('..', import.meta.url))
const DIST = join(ROOT, 'dist', 'client')
const SEND_TIMEOUT_MS = 15000
const WAIT_TIMEOUT_MS = 25000
const WATCHDOG_MS = 240000

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript',
  '.css': 'text/css',
  '.webp': 'image/webp',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.json': 'application/json',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
}

const BROWSERS = [
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
]

const SESSION_INJECT = `
  try {
    // ?guest=1 用于游客场景（不注入任何会话）；已有 role 时不覆盖（PARTNER 场景）
    if (!location.search.includes('guest=1') && !sessionStorage.getItem('yubai-admin-role')) {
      sessionStorage.setItem('yubai-admin-token', 'layout-check-token')
      sessionStorage.setItem('yubai-admin-name', 'admin')
      sessionStorage.setItem('yubai-admin-expiry', '2099-12-31T23:59:59Z')
      sessionStorage.setItem('yubai-admin-role', 'ADMIN')
      sessionStorage.setItem('yubai-admin-display', '站长')
    }
  } catch (e) {}
`

function log(...args) {
  console.log('[layout-check]', ...args)
}

function fail(message) {
  console.error('[layout-check] FAIL:', message)
  process.exitCode = 1
  throw new Error(message)
}

function isWhitelike(color) {
  const match = color.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)/)
  if (!match) return false
  return Number(match[1]) > 200 && Number(match[2]) > 200 && Number(match[3]) > 200
}

function isDarklike(color) {
  const match = color.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)/)
  if (!match) return false
  return Number(match[1]) < 90 && Number(match[2]) < 90 && Number(match[3]) < 90
}

async function main() {
  if (!existsSync(join(DIST, 'index.html'))) {
    fail(`dist 不存在，请先运行 npm run build（查找 ${DIST}）`)
  }
  const browserPath = BROWSERS.find((path) => existsSync(path))
  if (!browserPath) {
    fail('未找到 Edge/Chrome，无法执行浏览器检查（可手工验收）')
  }
  log('使用浏览器:', browserPath)

  // 整体看门狗：确保脚本有界结束（清理仍由 finally 兜底）
  const watchdog = setTimeout(() => {
    console.error('[layout-check] FAIL: 整体执行超时（' + WATCHDOG_MS + 'ms），强制退出')
    process.exit(1)
  }, WATCHDOG_MS)
  watchdog.unref?.()

  let server
  let session = null
  let send = null
  let evaluate = null
  let waitFor = null
  let sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))
  const userDataDir = join(tmpdir(), `pet-layout-check-${process.pid}`)

  try {
    // ---- 1. 静态服务（SPA 回退：无扩展名 → index.html；资源缺失 → 404；随机端口）----
    const { findFreePort } = await import('./lib/browser-session.mjs')
    const PORT = await findFreePort()
    server = createServer(async (req, res) => {
      try {
        let path = decodeURIComponent(new URL(req.url, 'http://127.0.0.1').pathname)
        const isAsset = /\.[a-z0-9]+$/i.test(path)
        const file = normalize(join(DIST, isAsset ? path : '/index.html'))
        if (!file.startsWith(normalize(DIST))) {
          res.writeHead(403).end()
          return
        }
        const body = await readFile(file)
        res.writeHead(200, { 'Content-Type': MIME[extname(file)] ?? 'application/octet-stream' })
        res.end(body)
      } catch {
        res.writeHead(404).end()
      }
    })
    await new Promise((resolve) => server.listen(PORT, '127.0.0.1', resolve))
    log(`静态服务 http://127.0.0.1:${PORT}（SPA 回退已启用）`)

    // ---- 2. 浏览器会话整体初始化（清理残留 → 随机 CDP 端口 → 启动 → target → WS → enable；失败整体重试）----
    session = await initBrowserSession({
      browserPath,
      profileDir: userDataDir,
      injectScript: SESSION_INJECT,
      sendTimeoutMs: SEND_TIMEOUT_MS,
      log,
    })
    send = session.client.send
    const cdpPort = session.cdpPort
    log('CDP 就绪:', `Edg/Chrome @ ${cdpPort}`, 'target:', session.targetId)

    evaluate = async (expression) => {
      const result = await send('Runtime.evaluate', {
        expression,
        returnByValue: true,
        awaitPromise: true,
      })
      if (result.exceptionDetails) {
        throw new Error(`evaluate 失败: ${result.exceptionDetails.text}`)
      }
      return result.result.value
    }
    waitFor = async (expression, timeoutMs = WAIT_TIMEOUT_MS, label = expression) => {
      const deadline = Date.now() + timeoutMs
      while (Date.now() < deadline) {
        try {
          const value = await evaluate(expression)
          if (value) return value
        } catch (error) {
          if (String(error).includes('WS 未打开') || String(error).includes('连接中断')) throw error
        }
        await sleep(150)
      }
      throw new Error(`等待超时（${timeoutMs}ms）: ${label}`)
    }

    const checks = []

    async function check(name, ok, detail = '') {
      checks.push({ name, ok })
      log(`检查 ${name}: ${ok ? 'PASS' : 'FAIL'}${detail ? `（${detail}）` : ''}`)
      if (!ok) fail(`检查失败: ${name}`)
    }

    async function openHome() {
      await send('Page.navigate', { url: `http://127.0.0.1:${PORT}/` })
      await waitFor(`document.readyState === 'complete'`)
      await waitFor(`!!document.querySelector('[data-testid="pet-button"]')`, WAIT_TIMEOUT_MS, '宠物按钮')
      await sleep(400)
    }

    // ---- 5. 几何：ADMIN 390 / 360 ----
    for (const width of [390, 360]) {
      await send('Emulation.setDeviceMetricsOverride', {
        width, height: 844, deviceScaleFactor: 1, mobile: true,
      })
      await openHome()
      await evaluate(`document.querySelector('[data-testid="pet-button"]').click()`)
      await waitFor(`!!document.querySelector('[data-testid="pet-chat-panel"]')`, WAIT_TIMEOUT_MS, '面板')
      await sleep(300)
      const measure = await evaluate(`(() => {
        const rect = document.querySelector('[data-testid="pet-chat-panel"]').getBoundingClientRect()
        const petRect = document.querySelector('[data-testid="pet-button"]').getBoundingClientRect()
        return {
          innerWidth: window.innerWidth, innerHeight: window.innerHeight,
          left: rect.left, right: rect.right, petBottom: petRect.bottom,
          scrollWidth: document.documentElement.scrollWidth,
          horizontalScrollbar: document.documentElement.scrollWidth > window.innerWidth,
        }
      })()`)
      const ok = measure.left >= 0 && measure.right <= measure.innerWidth
        && measure.right > measure.left && !measure.horizontalScrollbar
        && measure.petBottom <= measure.innerHeight
      check(`几何 ${width}px（ADMIN）`, ok,
        `panel=[${measure.left}, ${measure.right}] scrollWidth=${measure.scrollWidth} hScroll=${measure.horizontalScrollbar}`)
      await evaluate(`document.querySelector('.pet-chat-close').click()`)
      await sleep(200)
    }

    // ---- 6. 几何：PARTNER 390 ----
    await send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true })
    await evaluate(`sessionStorage.setItem('yubai-admin-role', 'PARTNER'); sessionStorage.setItem('yubai-admin-display', '小伙伴'); location.reload(); true`)
    await waitFor(`document.readyState === 'complete'`)
    await waitFor(`!!document.querySelector('[data-testid="pet-button"]')`, WAIT_TIMEOUT_MS, '宠物按钮(PARTNER)')
    await sleep(400)
    const role = await evaluate(`sessionStorage.getItem('yubai-admin-role')`)
    check('PARTNER 会话生效', role === 'PARTNER', `role=${role}`)
    await send('Page.bringToFront')
    await evaluate(`document.querySelector('[data-testid="pet-button"]').click()`)
    await waitFor(`!!document.querySelector('[data-testid="pet-chat-panel"]')`, WAIT_TIMEOUT_MS, '面板(PARTNER)')
    await sleep(300)
    const partnerMeasure = await evaluate(`(() => {
      const rect = document.querySelector('[data-testid="pet-chat-panel"]').getBoundingClientRect()
      return { left: rect.left, right: rect.right, innerWidth: window.innerWidth,
        horizontalScrollbar: document.documentElement.scrollWidth > window.innerWidth }
    })()`)
    check('几何 390px（PARTNER）',
      partnerMeasure.left >= 0 && partnerMeasure.right <= partnerMeasure.innerWidth
      && !partnerMeasure.horizontalScrollbar,
      `panel=[${partnerMeasure.left}, ${partnerMeasure.right}]`)
    await evaluate(`document.querySelector('.pet-chat-close').click()`)
    await sleep(200)

    // ---- 7. 交互回归（ADMIN 桌面）----
    await evaluate(`sessionStorage.setItem('yubai-admin-role', 'ADMIN'); sessionStorage.setItem('yubai-admin-display', '站长'); location.reload(); true`)
    await waitFor(`document.readyState === 'complete'`)
    await waitFor(`!!document.querySelector('[data-testid="pet-button"]')`, WAIT_TIMEOUT_MS, '宠物按钮')
    await sleep(400)
    await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false })

    await evaluate(`document.querySelector('[data-testid="pet-button"]').click()`)
    await waitFor(`!!document.querySelector('[data-testid="pet-chat-panel"]')`, WAIT_TIMEOUT_MS, '面板')
    check('打开面板', true)
    await evaluate(`document.querySelector('.pet-chat-close').click()`)
    await waitFor(`!document.querySelector('[data-testid="pet-chat-panel"]')`, WAIT_TIMEOUT_MS, '面板关闭')
    check('关闭面板后宠物保留', await evaluate(`!!document.querySelector('[data-testid="pet-button"]')`))
    await evaluate(`document.querySelector('[data-testid="pet-hide-button"]').click()`)
    await waitFor(`!document.querySelector('[data-testid="admin-pet-assistant"]')`, WAIT_TIMEOUT_MS, '宠物隐藏')
    check('隐藏宠物写 sessionStorage', (await evaluate(`sessionStorage.getItem('yubai-admin-pet-hidden')`)) === '1')
    await evaluate(`window.dispatchEvent(new KeyboardEvent('keydown', { key: 'a', ctrlKey: true, shiftKey: true }))`)
    await waitFor(`!!document.querySelector('[data-testid="pet-chat-panel"]')`, WAIT_TIMEOUT_MS, '快捷键恢复')
    check('Ctrl+Shift+A 恢复并打开', true)
    check('恢复后隐藏键清除', (await evaluate(`sessionStorage.getItem('yubai-admin-pet-hidden')`)) === null)
    await evaluate(`document.querySelector('.pet-chat-close').click()`)
    await sleep(200)

    // ---- 8. 主题：compact 面板浅色/深色计算样式 ----
    await evaluate(`document.querySelector('[data-testid="pet-button"]').click()`)
    await waitFor(`!!document.querySelector('[data-testid="pet-chat-panel"]')`, WAIT_TIMEOUT_MS, '面板(主题)')
    await sleep(200)
    const themeProbe = `(() => {
      const pick = (selector) => {
        const el = document.querySelector(selector)
        const style = getComputedStyle(el)
        return { bg: style.backgroundColor, color: style.color }
      }
      return {
        container: pick('.pet-chat-panel .ai-chat-container'),
        textarea: pick('[data-testid="ai-chat-input"]'),
      }
    })()`
    const light = await evaluate(themeProbe)
    check('浅色主题：compact 聊天容器为浅色背景', light.container.bg.includes('255'), light.container.bg)
    check('浅色主题：输入框文字为深色', !isWhitelike(light.textarea.color), light.textarea.color)

    await evaluate(`document.documentElement.classList.add('dark'); true`)
    await sleep(200)
    const dark = await evaluate(themeProbe)
    check('深色主题：compact 聊天容器不再为纯白背景', !isWhitelike(dark.container.bg) && dark.container.bg !== 'rgba(0, 0, 0, 0)', dark.container.bg)
    check('深色主题：compact 输入框不再为纯白背景', !isWhitelike(dark.textarea.bg) && dark.textarea.bg !== 'rgba(0, 0, 0, 0)', dark.textarea.bg)
    check('深色主题：compact 输入框文字为亮色', isWhitelike(dark.textarea.color) || !isDarklike(dark.textarea.color), dark.textarea.color)
    await evaluate(`document.documentElement.classList.remove('dark'); true`)
    await evaluate(`document.querySelector('.pet-chat-close').click()`)
    await sleep(200)

    // ---- 9. /admin/ai：compact 销毁、单实例、首次点击聚焦、full 形态主题 ----
    await send('Page.navigate', { url: `http://127.0.0.1:${PORT}/` })
    await waitFor(`document.readyState === 'complete'`)
    await waitFor(`!!document.querySelector('[data-testid="pet-button"]')`, WAIT_TIMEOUT_MS, '宠物按钮')
    await sleep(300)
    await evaluate(`document.querySelector('[data-testid="pet-button"]').click()`)
    await waitFor(`!!document.querySelector('[data-testid="pet-chat-panel"]')`, WAIT_TIMEOUT_MS, '面板')
    check('面板打开后导航前 compact 存在', await evaluate(`!!document.querySelector('.ai-chat-compact-root')`))

    await send('Page.navigate', { url: `http://127.0.0.1:${PORT}/admin/ai` })
    await waitFor(`document.readyState === 'complete'`)
    await waitFor(`!!document.querySelector('textarea[data-testid="ai-chat-input"]')`, WAIT_TIMEOUT_MS, '/admin/ai 完整页输入框')
    await sleep(400)
    check('/admin/ai 下 compact 面板已销毁', !(await evaluate(`!!document.querySelector('[data-testid="pet-chat-panel"]')`)))
    check('/admin/ai 下无 compact 聊天实例', !(await evaluate(`!!document.querySelector('.ai-chat-compact-root')`)))
    check('/admin/ai 下完整页聊天存在', await evaluate(`!!document.querySelector('.ai-chat-console')`))

    await evaluate(`document.querySelector('[data-testid="pet-button"]').click()`)
    await sleep(300)
    check('/admin/ai 首次点击宠物聚焦完整页输入框',
      (await evaluate(`document.activeElement === document.querySelector('textarea[data-testid="ai-chat-input"]')`)))

    const fullFocus = await evaluate(`(() => {
      const el = document.querySelector('textarea[data-testid="ai-chat-input"]')
      const style = getComputedStyle(el)
      const selectors = []
      const visit = (rules) => {
        for (const rule of Array.from(rules ?? [])) {
          if (rule.cssRules) visit(rule.cssRules)
          if (rule.selectorText?.includes('chat-textarea') && el.matches(rule.selectorText)) {
            selectors.push(rule.cssText)
          }
        }
      }
      for (const sheet of Array.from(document.styleSheets)) {
        try { visit(sheet.cssRules) } catch { /* local styles only; ignore inaccessible sheets */ }
      }
      return {
        active: document.activeElement === el,
        shadow: style.boxShadow,
        outline: style.outline,
        outlineStyle: style.outlineStyle,
        outlineWidth: style.outlineWidth,
        matchingRules: selectors.join(' | '),
      }
    })()`)
    const fullHasShadow = Boolean(fullFocus.shadow && fullFocus.shadow !== 'none')
    const fullHasOutline = fullFocus.outlineStyle !== 'none' && parseFloat(fullFocus.outlineWidth) > 0
    check('/admin/ai 输入框聚焦环可见',
      fullFocus.active && (fullHasShadow || fullHasOutline),
      `shadow=${fullFocus.shadow} outline=${fullFocus.outline} rules=${fullFocus.matchingRules}`)

    const fullThemeLight = await evaluate(`(() => {
      const container = getComputedStyle(document.querySelector('.ai-chat-container'))
      const textarea = getComputedStyle(document.querySelector('[data-testid="ai-chat-input"]'))
      return { containerBg: container.backgroundColor, textareaColor: textarea.color }
    })()`)
    check('/admin/ai 浅色容器为浅色背景', fullThemeLight.containerBg.includes('255'), fullThemeLight.containerBg)
    await evaluate(`document.documentElement.classList.add('dark'); true`)
    await sleep(200)
    const fullThemeDark = await evaluate(`(() => {
      const container = getComputedStyle(document.querySelector('.ai-chat-container'))
      const textarea = getComputedStyle(document.querySelector('[data-testid="ai-chat-input"]'))
      return { containerBg: container.backgroundColor, textareaColor: textarea.color, textareaBg: textarea.backgroundColor }
    })()`)
    check('/admin/ai 深色容器不再为纯白背景', !isWhitelike(fullThemeDark.containerBg), fullThemeDark.containerBg)
    check('/admin/ai 深色输入框不再为纯白背景', !isWhitelike(fullThemeDark.textareaBg), fullThemeDark.textareaBg)
    check('/admin/ai 深色输入框文字为亮色', isWhitelike(fullThemeDark.textareaColor) || !isDarklike(fullThemeDark.textareaColor), fullThemeDark.textareaColor)
    await evaluate(`document.documentElement.classList.remove('dark'); true`)

    // ---- 10. 游客：不渲染宠物、不请求图集 ----
    await evaluate(`sessionStorage.clear(); true`)
    await send('Page.navigate', { url: `http://127.0.0.1:${PORT}/?guest=1` })
    await waitFor(`document.readyState === 'complete'`)
    await sleep(800)
    const guest = await evaluate(`({
      petMounted: !!document.querySelector('[data-testid="admin-pet-assistant"]'),
      petAssetRequested: performance.getEntriesByType('resource')
        .some((entry) => entry.name.includes('spritesheet.webp')),
    })`)
    check('游客不渲染宠物', !guest.petMounted)
    check('游客不请求宠物图集', !guest.petAssetRequested)

    // ---- 11. 汇总 ----
    const failed = checks.filter((entry) => !entry.ok)
    log(`全部检查 ${checks.length} 项，失败 ${failed.length} 项`)
    if (failed.length) {
      fail(`存在失败项: ${JSON.stringify(failed)}`)
    }
    console.log('[layout-check] ALL PASS')
  } catch (error) {
    console.error('[layout-check] ERROR:', error instanceof Error ? error.message : error)
    process.exitCode = 1
  } finally {
    // ---- 统一清理：WS / 静态服务 / 浏览器子进程（等待退出）/ 本脚本创建的临时 profile ----
    try { await session?.close() } catch { /* ignore */ }
    await new Promise((resolve) => server?.close?.(resolve))
    // 残留清理兜底：终止本脚本特征进程 + 删除 profile（仅限系统临时目录、前缀匹配）
    try { await cleanupStaleProfiles() } catch { /* ignore */ }
    clearTimeout(watchdog)
  }
}

main().catch((error) => {
  console.error('[layout-check] ERROR:', error instanceof Error ? error.message : error)
  process.exit(1)
})
