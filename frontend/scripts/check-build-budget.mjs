import { readdir, readFile, stat } from 'node:fs/promises'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('../dist/client/', import.meta.url))
const limits = { js: 650 * 1024, lazyEditorJs: 850 * 1024, css: 300 * 1024, totalJs: 1800 * 1024 }
const files = []
async function walk(dir) {
  for (const name of await readdir(dir)) {
    const path = join(dir, name)
    const info = await stat(path)
    if (info.isDirectory()) await walk(path)
    else files.push({ path, name, size: info.size })
  }
}
await walk(root)
const javascript = files.filter((file) => file.name.endsWith('.js'))
const oversized = files.filter(
  (file) =>
    (file.name.endsWith('.js') &&
      file.size > (file.name.startsWith('code-highlight-') ? limits.lazyEditorJs : limits.js)) ||
    (file.name.endsWith('.css') && file.size > limits.css),
)
const totalJs = javascript.reduce((sum, file) => sum + file.size, 0)
if (oversized.length || totalJs > limits.totalJs) {
  for (const file of oversized) console.error(`Budget exceeded: ${file.name} ${file.size} bytes`)
  if (totalJs > limits.totalJs) console.error(`Total JS budget exceeded: ${totalJs} bytes`)
  process.exit(1)
}
const sw = files.find((file) => file.name === 'sw.js')
if (sw && /\.(png|jpe?g|webp|gif)"/.test(await readFile(sw.path, 'utf8'))) {
  throw new Error('Bitmap assets must not be present in the PWA precache manifest')
}
console.log(`Build budget passed: ${javascript.length} JS files, ${totalJs} bytes total`)
