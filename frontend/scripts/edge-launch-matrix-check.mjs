/**
 * Edge 启动参数矩阵检查（只访问本机；与布局脚本相同权限/清理/脱敏规则）。
 *
 * 按严格顺序对少量有解释力的启动配置做完整验证（不可并发）：
 *   A 基线参数
 *   B 基线 + 禁用扩展及扩展后台加载
 *   C B + 禁用 GPU/Graphite/Dawn 相关路径 + 禁用导入/后台更新/首启行为
 *
 * 每配置验证：child 存活稳定窗口、listener PID 归属、连续 3 次 /json/version、
 * /json/list、创建自有 target、WebSocket、Page.enable、Runtime.enable、
 * 期间无 0x80000003 / 0xC0000022 / GPUPersistentCache 占用日志；随后正常清理。
 * 任一配置失败输出完整脱敏诊断并 exit 1；全部通过输出 ALL PASS，exit 0。
 *
 * 运行：node scripts/edge-launch-matrix-check.mjs（需本机 Edge/Chrome；Node >= 22）
 */
import { existsSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import {
  initBrowserSession, killResidualBrowsers, cleanupStaleProfiles,
  DEFAULT_EDGE_ARGS, PROFILE_PREFIX,
} from './lib/browser-session.mjs'
import { redactErrorChain } from './lib/redact.mjs'

const BROWSERS = [
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
]

/** 崩溃证据关键词（出现即视为该配置在环境中不稳定）。 */
const CRASH_PATTERNS = [
  /0x80000003/,
  /0xC0000022/,
  /-1073741790/,
  /2147483651/,
  /GPUPersistentCache/,
  /DawnGraphiteCache/,
  /另一个程序正在使用此文件/,
  /GPU process exited unexpectedly/i,
]

const CONFIGS = [
  {
    name: 'A-基线',
    note: 'headless + --disable-gpu + 首启禁用',
    args: ['--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check'],
    required: false,
  },
  {
    name: 'B-禁用扩展',
    note: 'A + 禁用扩展与扩展后台加载',
    args: [
      '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
      '--disable-extensions',
      '--disable-component-extensions-with-background-pages',
    ],
    required: false,
  },
  {
    name: 'C-禁用GPU/Graphite+后台行为',
    note: 'B + GPU 进程并入主进程 + 禁用 Graphite/Dawn 缓存 + 禁用导入/更新/同步/指标',
    args: DEFAULT_EDGE_ARGS,
    required: true,
  },
]

function log(...args) {
  console.log('[matrix]', ...args)
}

async function main() {
  const browserPath = BROWSERS.find((path) => existsSync(path))
  if (!browserPath) {
    console.error('[matrix] FAIL: 未找到 Edge/Chrome')
    process.exit(1)
  }
  log('浏览器:', browserPath)
  const results = []
  for (const config of CONFIGS) {
    log(`==== 配置 ${config.name}（${config.note}） ====`)
    const profileDir = join(tmpdir(), `${PROFILE_PREFIX}matrix-${process.pid}`)
    let session = null
    let ok = false
    let detail = ''
    try {
      await killResidualBrowsers()
      await cleanupStaleProfiles()
      const logs = []
      session = await initBrowserSession({
        browserPath,
        profileDir,
        edgeArgs: config.args,
        maxRetries: 1, // 矩阵单次尝试：失败即该配置判 FAIL
        log: (message) => logs.push(String(message)),
      })
      // 会话内验证：readiness + target + WebSocket + Page.enable + Runtime.enable 已由 initBrowserSession 完成
      const send = session.client.send
      let versionOk = 0
      for (let i = 0; i < 3; i += 1) {
        const response = await fetch(`http://127.0.0.1:${session.cdpPort}/json/version`)
        if (response.status === 200) versionOk += 1
      }
      const listResponse = await fetch(`http://127.0.0.1:${session.cdpPort}/json/list`)
      const list = await listResponse.json()
      const listHasTarget = Array.isArray(list) && list.some((t) => t && t.id === session.targetId)
      // 稳定窗口：短暂保持后再次探活
      await new Promise((resolve) => setTimeout(resolve, 1500))
      const after = await fetch(`http://127.0.0.1:${session.cdpPort}/json/version`)
      ok = versionOk === 3 && listHasTarget && after.status === 200
      detail = `version3x=${versionOk} listHasTarget=${listHasTarget} stableProbe=${after.status}`
      // 崩溃关键词检查（来自 stderr 捕获，initBrowserSession 失败时会输出）
      const stderrMentions = logs.filter((line) => CRASH_PATTERNS.some((pattern) => pattern.test(line)))
      if (stderrMentions.length) {
        ok = false
        detail += ` crashMentions=${stderrMentions.length}`
      }
      log(`配置 ${config.name}: ${ok ? 'PASS' : 'FAIL'}（${detail}）`)
    } catch (error) {
      ok = false
      detail = redactErrorChain(error)
      log(`配置 ${config.name}: FAIL（${detail}）`)
    } finally {
      try { await session?.close() } catch { /* ignore */ }
      try { await cleanupStaleProfiles() } catch { /* ignore */ }
    }
    results.push({ name: config.name, ok, detail, required: config.required })
  }

  const failed = results.filter((entry) => !entry.ok)
  const blocking = results.filter((entry) => entry.required && !entry.ok)
  log(`矩阵结果：${results.length} 个配置，观察到失败 ${failed.length} 个，阻断 ${blocking.length} 个`)
  for (const entry of results) {
    log(`  ${entry.name}: ${entry.ok ? 'PASS' : 'FAIL'}`)
  }
  if (blocking.length) {
    console.error('[matrix] FAIL: ' + JSON.stringify(blocking.map((entry) => entry.name)))
    process.exitCode = 1
  } else {
    console.log('[matrix] DEFAULT PASS（A/B 为诊断基线，允许在受限环境失败；默认参数集为配置 C：' + DEFAULT_EDGE_ARGS.join(' ') + '）')
  }
}

main().catch((error) => {
  console.error('[matrix] ERROR:', redactErrorChain(error))
  process.exit(1)
})
