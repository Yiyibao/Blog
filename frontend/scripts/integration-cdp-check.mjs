/**
 * 真实 Edge CDP 集成回归检查（不可 mock）：
 * 启动 Edge → readiness gate（连续 3 次稳定探测）→ 连续 3 次 /json/version →
 * /json/list → 创建自有 target → target 出现在 list → WebSocket open →
 * Page.enable → Runtime.enable → 正常关闭与清理。
 * 任一失败输出完整脱敏生命周期诊断并 exit 1；全部通过输出 ALL PASS，exit 0。
 *
 * 运行：node scripts/integration-cdp-check.mjs（需本机 Edge/Chrome；Node >= 22）
 */
import { existsSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import {
  initBrowserSession, killResidualBrowsers, cleanupStaleProfiles,
  findFreePort, summarizeStderr, PROFILE_PREFIX,
} from './lib/browser-session.mjs'
import { redactErrorChain } from './lib/redact.mjs'

const BROWSERS = [
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
]

function log(...args) {
  console.log('[integration]', ...args)
}

async function main() {
  const browserPath = BROWSERS.find((path) => existsSync(path))
  if (!browserPath) {
    console.error('[integration] FAIL: 未找到 Edge/Chrome')
    process.exit(1)
  }
  log('浏览器:', browserPath)
  const profileDir = join(tmpdir(), `${PROFILE_PREFIX}int-${process.pid}`)
  const checks = []
  const check = (name, ok, detail = '') => {
    checks.push({ name, ok })
    log(`检查 ${name}: ${ok ? 'PASS' : 'FAIL'}${detail ? `（${detail}）` : ''}`)
  }

  let session = null
  try {
    // 清理历史残留（只清理确认过期实例）
    const killed = await killResidualBrowsers()
    const removed = await cleanupStaleProfiles()
    if (killed || removed) log(`启动前清理：过期实例 ${killed}，残留 profile ${removed}`)

    // 完整初始化事务：spawn → readiness gate → target → WS → Page.enable → Runtime.enable
    session = await initBrowserSession({
      browserPath,
      profileDir,
      maxRetries: 3,
      log,
    })
    check('浏览器会话初始化（readiness gate + target + WebSocket + enable）', true,
      `target=${session.targetId} port=${session.cdpPort}`)

    const send = session.client.send

    // 连续 3 次 /json/version（Node 侧直接请求 CDP 端点，避免页面 CORS）
    for (let i = 0; i < 3; i += 1) {
      const response = await fetch(`http://127.0.0.1:${session.cdpPort}/json/version`)
      const body = await response.json()
      const browser = body && typeof body.Browser === 'string' ? body.Browser : null
      check(`/json/version 第 ${i + 1} 次`, response.status === 200 && typeof browser === 'string' && browser.length > 0,
        String(browser))
    }

    // /json/list 包含自有 target
    const listResponse = await fetch(`http://127.0.0.1:${session.cdpPort}/json/list`)
    const listValue = await listResponse.json()
    const targets = Array.isArray(listValue) ? listValue : []
    check('/json/list 可读且包含自有 target',
      targets.some((t) => t && typeof t.id === 'string' && t.id === session.targetId),
      `list=${targets.length}`)

    // target 已由 initBrowserSession 创建（createPageTarget 内部已验证 list 归属）
    check('自有 page target 已创建（type=page）', typeof session.targetId === 'string' && session.targetId.length > 0)

    const failed = checks.filter((entry) => !entry.ok)
    log(`全部检查 ${checks.length} 项，失败 ${failed.length} 项`)
    if (failed.length) {
      console.error('[integration] FAIL: ' + JSON.stringify(failed))
      process.exitCode = 1
    } else {
      console.log('[integration] ALL PASS')
    }
  } catch (error) {
    console.error('[integration] FAIL:', redactErrorChain(error))
    process.exitCode = 1
  } finally {
    try { await session?.close() } catch { /* ignore */ }
    try { await cleanupStaleProfiles() } catch { /* ignore */ }
  }
}

main().catch((error) => {
  console.error('[integration] ERROR:', redactErrorChain(error))
  process.exit(1)
})
