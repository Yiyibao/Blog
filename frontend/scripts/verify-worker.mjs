import { readFile } from 'node:fs/promises'
import { join } from 'node:path'
import worker from '../dist/server/index.js'

const contentType = (pathname) => {
  if (pathname.endsWith('.html')) return 'text/html'
  if (pathname.endsWith('.js')) return 'text/javascript'
  if (pathname.endsWith('.css')) return 'text/css'
  return 'application/octet-stream'
}

const env = {
  ASSETS: {
    async fetch(request) {
      let pathname = new URL(request.url).pathname
      if (pathname === '/') pathname = '/index.html'
      try {
        const body = await readFile(join(process.cwd(), 'dist/client', pathname))
        return new Response(body, { status: 200, headers: { 'content-type': contentType(pathname) } })
      } catch {
        return new Response('Not found', { status: 404 })
      }
    },
  },
}

for (const pathname of ['/', '/articles']) {
  const response = await worker.fetch(
    new Request(`http://localhost${pathname}`, { headers: { accept: 'text/html' } }),
    env,
  )
  const body = await response.text()
  if (response.status !== 200 || !body.includes('<div id="app"></div>')) {
    throw new Error(`${pathname} failed: ${response.status}`)
  }
}

console.log('Worker verification passed: root and SPA fallback return the Vue app.')
