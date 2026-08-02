/**
 * 高清宠物行图素材结构检查（真实浏览器逐像素验证）。
 *
 * 覆盖（对 frontend/public/pets/xinn/hd/*.webp 逐文件）：
 * - 尺寸精确 3072×416；
 * - 有效格（元数据帧数）alpha 非空；未使用格 alpha 全零；
 * - 每格内容四周至少 8 源像素安全边距（不贴边、不跨格）；
 * - 有效格内无内部整行透明断层（内容被透明横带切断）；
 * - 内容边缘无色键残留（绿/品红高饱和边缘）；
 * - 单行 ≤ 1.5MB、全部 ≤ 20MB；配置引用全部存在，无孤儿配置。
 *
 * 元数据（帧数/回退行）与 petAnimations.ts 保持同源契约，此处独立列出以便双向校验。
 * 前置：本机 Edge/Chrome。运行：node scripts/pet-animation-asset-check.mjs（Node >= 22）。
 */
import { createServer } from 'node:http'
import { createHash } from 'node:crypto'
import { readFile, stat } from 'node:fs/promises'
import { existsSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, extname, normalize, relative } from 'node:path'
import { fileURLToPath } from 'node:url'
import { initBrowserSession, cleanupStaleProfiles } from './lib/browser-session.mjs'

const ROOT = fileURLToPath(new URL('..', import.meta.url))
const PUBLIC = join(ROOT, 'public')
const HD_DIR = join(PUBLIC, 'pets', 'xinn', 'hd')
const PROVENANCE_FILE = join(HD_DIR, 'provenance.json')
const SEND_TIMEOUT_MS = 15000
const WAIT_TIMEOUT_MS = 25000
const WATCHDOG_MS = 240000

const MIME = { '.webp': 'image/webp', '.html': 'text/html', '.js': 'text/javascript' }

const BROWSERS = [
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
]

/** 15 行高清素材元数据（与 petAnimations.ts 契约一致）。 */
const ROWS = {
  idle: { frames: 6, legacyRow: 0 },
  'running-right': { frames: 8, legacyRow: 1 },
  'running-left': { frames: 8, legacyRow: 2 },
  waving: { frames: 4, legacyRow: 3 },
  jumping: { frames: 5, legacyRow: 4 },
  failed: { frames: 8, legacyRow: 5 },
  waiting: { frames: 6, legacyRow: 6 },
  running: { frames: 6, legacyRow: 7 },
  review: { frames: 6, legacyRow: 8 },
  'look-row-9': { frames: 8, legacyRow: 9 },
  'look-row-10': { frames: 8, legacyRow: 10 },
  'idle-curious': { frames: 8, legacyRow: 0 },
  'idle-sleeve': { frames: 8, legacyRow: 0 },
  'idle-sway': { frames: 8, legacyRow: 0 },
  'chat-open': { frames: 8, legacyRow: 0 },
}

const ROW_W = 3072
const ROW_H = 416
const CELL_W = 384
const CELL_H = 416
const SAFE_MARGIN = 8 // 源像素
const MAX_ROW_BYTES = 1.5 * 1024 * 1024
const MAX_TOTAL_BYTES = 20 * 1024 * 1024

function log(...args) {
  console.log('[asset-check]', ...args)
}

function fail(message) {
  console.error('[asset-check] FAIL:', message)
  process.exitCode = 1
  throw new Error(message)
}

async function fileSha256(path) {
  return createHash('sha256').update(await readFile(path)).digest('hex')
}

async function verifyNativeHdProvenance() {
  if (!existsSync(PROVENANCE_FILE)) fail('Missing HD provenance.json')
  if (existsSync(join(HD_DIR, 'PLACEHOLDER-NOTICE.md'))
    || existsSync(join(ROOT, 'scripts', 'pet-hd-placeholder-assets.py'))) {
    fail('Placeholder HD generator/notice must not be present in final assets')
  }

  const manifest = JSON.parse(await readFile(PROVENANCE_FILE, 'utf8'))
  if (manifest.placeholder !== false || manifest.generatedBy !== 'built-in imagegen') {
    fail('HD provenance does not identify final imagegen assets')
  }
  if (manifest.cell?.[0] !== CELL_W || manifest.cell?.[1] !== CELL_H) {
    fail('HD provenance cell dimensions do not match 384x416')
  }

  const expectedIds = Object.keys(ROWS)
  const records = Array.isArray(manifest.rows) ? manifest.rows : []
  if (records.length !== expectedIds.length) fail(`HD provenance must contain ${expectedIds.length} rows`)
  const byId = new Map(records.map((record) => [record.id, record]))
  for (const rowId of expectedIds) {
    const record = byId.get(rowId)
    if (!record) fail(`HD provenance missing row: ${rowId}`)
    if (record.frames !== ROWS[rowId].frames) fail(`HD provenance frame mismatch: ${rowId}`)
    const output = join(HD_DIR, `${rowId}.webp`)
    if (record.outputSha256 !== await fileSha256(output)) fail(`HD output hash mismatch: ${rowId}`)

    if (rowId === 'running-left') {
      if (!String(record.derivation).includes('mirrored individually')) {
        fail('running-left must document the approved frame-by-frame mirror derivation')
      }
      continue
    }
    if (!String(record.derivation).startsWith('imagegen-native-strip')) {
      fail(`Non-native asset provenance: ${rowId}`)
    }
    // Generated source canvases are at least 700px tall (legacy 2x rows are only
    // 416px tall); the visible pose itself must also meet one native cell width.
    if (!Array.isArray(record.sourceDimensions) || record.sourceDimensions[1] < 700) {
      fail(`Source canvas is not native high resolution: ${rowId}`)
    }
    if (!Number.isFinite(record.minimumSourcePoseHeight) || record.minimumSourcePoseHeight < 384) {
      fail(`Source pose lacks native cell-scale detail: ${rowId}`)
    }
  }
  log('Native-HD provenance: PASS (15 final rows, no placeholder pipeline)')
}

/** 在浏览器内对单行图执行逐像素结构检查（返回可序列化报告）。 */
function makeAnalyzerExpression(rowId, baseUrl) {
  return `(async () => {
    const url = '${baseUrl}/pets/xinn/hd/${rowId}.webp'
    const img = new Image()
    img.src = url
    await new Promise((resolve, reject) => {
      img.onload = resolve
      img.onerror = () => reject(new Error('行图加载失败: ' + url))
    })
    if (img.naturalWidth !== ${ROW_W} || img.naturalHeight !== ${ROW_H}) {
      return { ok: false, error: '尺寸不符 ' + img.naturalWidth + 'x' + img.naturalHeight }
    }
    const canvas = document.createElement('canvas')
    canvas.width = ${ROW_W}; canvas.height = ${ROW_H}
    const ctx = canvas.getContext('2d', { willReadFrequently: true })
    ctx.drawImage(img, 0, 0)
    const data = ctx.getImageData(0, 0, ${ROW_W}, ${ROW_H}).data
    const frames = ${ROWS[rowId].frames}
    const report = { ok: true, cells: [], totals: { opaque: 0, transparent: 0 } }
    for (let col = 0; col < 8; col++) {
      const x0 = col * ${CELL_W}, x1 = x0 + ${CELL_W}
      let minX = ${CELL_W}, maxX = -1, minY = ${CELL_H}, maxY = -1, opaque = 0
      const colCoverage = new Array(${CELL_H}).fill(0)
      for (let y = 0; y < ${CELL_H}; y++) {
        for (let x = x0; x < x1; x++) {
          const a = data[(y * ${ROW_W} + x) * 4 + 3]
          if (a > 0) { opaque++; colCoverage[y]++; if (x - x0 < minX) minX = x - x0; if (x - x0 > maxX) maxX = x - x0; if (y < minY) minY = y; if (y > maxY) maxY = y }
        }
      }
      report.totals.opaque += opaque
      report.totals.transparent += ${CELL_W} * ${CELL_H} - opaque
      const used = col < frames
      const cell = { col, used, opaque }
      if (used) {
        cell.empty = opaque === 0
        cell.margins = { left: minX, right: ${CELL_W} - 1 - maxX, top: minY, bottom: ${CELL_H} - 1 - maxY }
        cell.marginOk = cell.margins.left >= ${SAFE_MARGIN} && cell.margins.right >= ${SAFE_MARGIN}
          && cell.margins.top >= ${SAFE_MARGIN} && cell.margins.bottom >= ${SAFE_MARGIN}
        // 内部整行透明断层：内容上下都有不透明行，中间存在 ≥8 连续全透明行
        let band = null
        let bandLen = 0
        cell.band = false
        for (let y = 0; y < ${CELL_H}; y++) {
          if (colCoverage[y] === 0) {
            if (band === null) band = y
            bandLen++
          } else {
            if (band !== null && bandLen >= 8 && band > 0 && colCoverage.slice(y).some(v => v > 0)) {
              cell.band = true
            }
            band = null; bandLen = 0
          }
        }
        // 色键残留：内容边缘（alpha>0 且四邻存在 alpha=0）的高饱和绿/品红像素
        let chroma = 0
        for (let y = 1; y < ${CELL_H} - 1; y++) {
          for (let x = x0 + 1; x < x1 - 1; x++) {
            const idx = (y * ${ROW_W} + x) * 4
            const a = data[idx + 3]
            // Ignore sub-13%-alpha resampling fringe; it is part of the soft
            // antialias matte and is visually transparent at the target size.
            if (a <= 32) continue
            const neighbor = [
              (y * ${ROW_W} + x - 1) * 4, (y * ${ROW_W} + x + 1) * 4,
              ((y - 1) * ${ROW_W} + x) * 4, ((y + 1) * ${ROW_W} + x) * 4,
            ].some(n => data[n + 3] === 0)
            if (!neighbor) continue
            const r = data[idx], g = data[idx + 1], b = data[idx + 2]
            const greenish = g > 120 && g > r * 1.5 && g > b * 1.5
            const magenta = r > 120 && b > 120 && g < r * 0.7
            if (greenish || magenta) chroma++
          }
        }
        cell.chromaEdge = chroma
        // 容忍 ≤2 个边缘色键像素（编码伪影量级）；真实色键残留通常数十像素以上
        cell.chromaOk = chroma <= 2
        cell.ok = !cell.empty && cell.marginOk && !cell.band && cell.chromaOk
        if (!cell.ok) report.ok = false
      } else {
        cell.empty = opaque === 0
        cell.ok = cell.empty
        if (!cell.ok) report.ok = false
      }
      report.cells.push(cell)
    }
    return report
  })()`
}

async function main() {
  if (!existsSync(HD_DIR)) {
    fail(`高清目录不存在: ${HD_DIR}（先运行占位生成或放入真实行图）`)
  }
  await verifyNativeHdProvenance()
  const browserPath = BROWSERS.find((path) => existsSync(path))
  if (!browserPath) {
    fail('未找到 Edge/Chrome，无法执行像素检查')
  }
  log('使用浏览器:', browserPath)

  const watchdog = setTimeout(() => {
    console.error('[asset-check] FAIL: 整体执行超时（' + WATCHDOG_MS + 'ms），强制退出')
    process.exit(1)
  }, WATCHDOG_MS)
  watchdog.unref?.()

  let server
  let session = null
  let send = null
  let evaluate = null
  let sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))
  const userDataDir = join(tmpdir(), `pet-layout-check-asset-${process.pid}`)

  try {
    const { findFreePort } = await import('./lib/browser-session.mjs')
    const PORT = await findFreePort()
    server = createServer(async (req, res) => {
      try {
        const path = decodeURIComponent(new URL(req.url, 'http://127.0.0.1').pathname)
        // 根路径提供真实 HTML 文档：错误页为 opaque origin，会让同源 canvas 被污染
        if (path === '/' || path === '') {
          res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
          res.end('<!doctype html><html><head><meta charset="utf-8"><title>asset-check</title></head><body></body></html>')
          return
        }
        const file = normalize(join(PUBLIC, path))
        if (!file.startsWith(normalize(PUBLIC))) {
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
    log(`静态服务 http://127.0.0.1:${PORT}（public 目录）`)

    session = await initBrowserSession({
      browserPath,
      profileDir: userDataDir,
      sendTimeoutMs: SEND_TIMEOUT_MS,
      log,
    })
    send = session.client.send
    await send('Page.navigate', { url: `http://127.0.0.1:${PORT}/` })
    await sleep(300)
    evaluate = async (expression) => {
      const result = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
      if (result.exceptionDetails) {
        const detail = result.exceptionDetails.exception?.description
          ?? result.exceptionDetails.text
        throw new Error(`evaluate 失败: ${detail}`)
      }
      return result.result.value
    }

    // ---- 文件体积与存在性 ----
    const sizeReport = []
    let totalBytes = 0
    for (const rowId of Object.keys(ROWS)) {
      const file = join(HD_DIR, `${rowId}.webp`)
      if (!existsSync(file)) fail(`缺少行图: ${relative(ROOT, file)}`)
      const info = await stat(file)
      totalBytes += info.size
      sizeReport.push({ rowId, bytes: info.size, over: info.size > MAX_ROW_BYTES })
      if (info.size > MAX_ROW_BYTES) fail(`行图超限(${info.size}B): ${rowId}`)
    }
    if (totalBytes > MAX_TOTAL_BYTES) fail(`全部行图总大小 ${totalBytes}B 超过 20MB`)
    log(`文件体积: 合计 ${totalBytes} bytes（目标 ≤ 20MB，单行 ≤ 1.5MB）`)

    // ---- 孤儿配置检查：目录中多余 webp ----
    const { readdir } = await import('node:fs/promises')
    const actual = (await readdir(HD_DIR)).filter((name) => name.endsWith('.webp'))
    const orphans = actual.filter((name) => !Object.hasOwn(ROWS, name.replace(/\.webp$/, '')))
    if (orphans.length) fail(`孤儿行图文件: ${orphans.join(', ')}`)

    // ---- 逐文件像素检查 ----
    const checks = []
    const baseUrl = `http://127.0.0.1:${PORT}`
    for (const rowId of Object.keys(ROWS)) {
      const report = await evaluate(makeAnalyzerExpression(rowId, baseUrl))
      if (!report.ok) {
        const detail = report.cells
          .filter((cell) => !cell.ok)
          .map((cell) => `col${cell.col} ${JSON.stringify({ empty: cell.empty, margins: cell.margins, band: cell.band, chroma: cell.chromaEdge })}`)
          .join('; ')
        fail(`行图结构失败 ${rowId}: ${detail}`)
      }
      checks.push({ rowId, cells: report.cells.map((cell) => ({ col: cell.col, used: cell.used, opaque: cell.opaque })) })
      log(`检查 ${rowId}: PASS（${report.totals.opaque} 不透明 / ${report.totals.transparent} 透明像素）`)
    }

    // ---- 输出结构化报告 ----
    const reportOut = join(ROOT, 'outputs', 'pet-animation-asset-check.json')
    const { mkdir, writeFile } = await import('node:fs/promises')
    await mkdir(join(ROOT, 'outputs'), { recursive: true })
    await writeFile(reportOut, JSON.stringify({ ok: true, rows: checks, sizes: sizeReport, totalBytes }, null, 2))
    console.log('[asset-check] 报告已写出:', reportOut)
    console.log('[asset-check] ALL PASS')
  } catch (error) {
    console.error('[asset-check] ERROR:', error instanceof Error ? error.message : error)
    process.exitCode = 1
  } finally {
    try { await session?.close() } catch { /* ignore */ }
    await new Promise((resolve) => server?.close?.(resolve))
    try { await cleanupStaleProfiles() } catch { /* ignore */ }
    clearTimeout(watchdog)
  }
}

main().catch((error) => {
  console.error('[asset-check] ERROR:', error instanceof Error ? error.message : error)
  process.exit(1)
})
