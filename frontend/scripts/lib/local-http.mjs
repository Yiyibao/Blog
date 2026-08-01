/**
 * 本地 CDP HTTP 客户端（node:http 实现，仅 127.0.0.1，不访问外部网络）。
 *
 * 资源生命周期保证：
 * - 单一 settle/finalize：resolve/reject 只发生一次；
 * - 成功 response end、request error、response error、timeout、abort 后统一清理
 *   setTimeout 与 AbortSignal listener；
 * - 已完成请求在 5 秒后不会再次 destroy；
 * - timeout 错误带 code=ETIMEDOUT；abort 错误带 code=ABORT_ERR，两者可区分；
 * - agent:false + Connection: close：每个请求独立连接，响应后由系统关闭，无连接复用状态依赖。
 */

import { request as nodeHttpRequest } from 'node:http'

export function createLocalHttpRequest(httpImpl = nodeHttpRequest) {
  return function localHttpRequest({
    port,
    method = 'GET',
    path,
    body = null,
    timeoutMs = 5000,
    signal = null,
  }) {
    return new Promise((resolve, reject) => {
      let settled = false
      let timer = null
      let req = null

      const cleanup = () => {
        if (timer !== null) {
          clearTimeout(timer)
          timer = null
        }
        if (signal) {
          signal.removeEventListener('abort', onAbort)
        }
      }

      const finalize = (fn, value) => {
        if (settled) return
        settled = true
        cleanup()
        fn(value)
      }

      const onAbort = () => {
        const error = Object.assign(new Error('aborted'), { code: 'ABORT_ERR' })
        finalize(reject, error)
        try { req?.destroy(error) } catch { /* ignore */ }
      }

      const headers = { Connection: 'close' }
      if (body !== null && body !== undefined) {
        headers['Content-Type'] = 'application/json'
        headers['Content-Length'] = Buffer.byteLength(body)
      }

      req = httpImpl({
        host: '127.0.0.1',
        port,
        method,
        path,
        headers,
        agent: false, // 每个请求独立连接，避免 keep-alive 状态依赖
      }, (res) => {
        const chunks = []
        res.on('data', (chunk) => chunks.push(chunk))
        res.on('error', (error) => finalize(reject, error))
        res.on('end', () => {
          const text = Buffer.concat(chunks).toString('utf8')
          let json = null
          try {
            json = text ? JSON.parse(text) : null
          } catch {
            json = null
          }
          finalize(resolve, {
            status: res.statusCode ?? 0,
            contentType: res.headers['content-type'] ?? null,
            text,
            json,
          })
        })
      })

      timer = setTimeout(() => {
        const error = Object.assign(new Error('local HTTP 请求超时'), { code: 'ETIMEDOUT' })
        finalize(reject, error)
        try { req?.destroy(error) } catch { /* ignore */ }
      }, timeoutMs)
      timer.unref?.()

      if (signal) {
        if (signal.aborted) onAbort()
        else signal.addEventListener('abort', onAbort, { once: true })
      }

      req.on('error', (error) => finalize(reject, error))

      if (body !== null && body !== undefined) req.write(body)
      req.end()
    })
  }
}

export const localHttpRequest = createLocalHttpRequest()
