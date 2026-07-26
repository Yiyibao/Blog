import { describe, it, expect, beforeEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { AiStreamHttpError, streamAiChat } from '../api/admin'
import { useAuthStore } from '../stores/auth'

/**
 * streamAiChat 的 SSE 解析器真实测试（组件测试全部 mock 掉了它，这里补上）。
 * 用 ReadableStream 模拟 fetch 响应体，覆盖事件分发、事件类型复位、连接释放。
 */

interface FakeStream {
  response: Response
  cancelled: () => boolean
}

function sseResponse(chunks: string[], { close = true } = {}): FakeStream {
  const encoder = new TextEncoder()
  let cancelled = false
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(encoder.encode(chunk))
      if (close) controller.close()
    },
    cancel() {
      cancelled = true
    },
  })
  const response = { ok: true, status: 200, body: stream } as unknown as Response
  return { response, cancelled: () => cancelled }
}

const MESSAGES = [{ role: 'user' as const, content: 'hi' }]

beforeEach(() => {
  setActivePinia(createPinia())
})

describe('streamAiChat SSE 解析器', () => {
  it('按事件类型分发 delta 与 done，跨 chunk 的行正确拼接', async () => {
    const { response } = sseResponse([
      'event:delta\ndata:{"content":"Hel"}\n\n',
      // 同一行被拆到两个网络 chunk
      'event:delta\ndata:{"con',
      'tent":"lo"}\n\n',
      'event:done\ndata:{"model":"m1","usage":{"promptTokens":1,"completionTokens":2,"totalTokens":3}}\n\n',
    ])
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response))

    const deltas: string[] = []
    let done: unknown = null
    await streamAiChat(MESSAGES, {
      onDelta: (text) => deltas.push(text),
      onDone: (info) => { done = info },
    })

    expect(deltas).toEqual(['Hel', 'lo'])
    expect(done).toMatchObject({ model: 'm1', usage: { totalTokens: 3 } })
  })

  it('事件类型在事件边界复位：裸 data 行不会被误判为上一个事件', async () => {
    const { response } = sseResponse([
      'event:delta\ndata:{"content":"A"}\n\n',
      // 无 event 前缀的裸 data 行（如注释后重放）不应再次触发 delta
      'data:{"content":"B"}\n\n',
    ])
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response))

    const deltas: string[] = []
    await streamAiChat(MESSAGES, { onDelta: (text) => deltas.push(text) })

    expect(deltas).toEqual(['A'])
  })

  it('error 事件抛出 AiStreamHttpError 并取消底层 reader', async () => {
    const fake = sseResponse([
      'event:delta\ndata:{"content":"部分"}\n\n',
      'event:error\ndata:{"status":502,"message":"AI service returned an error"}\n\n',
    ], { close: false })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(fake.response))

    const deltas: string[] = []
    await expect(streamAiChat(MESSAGES, { onDelta: (t) => deltas.push(t) }))
      .rejects.toMatchObject({ name: 'AiStreamHttpError', status: 502 })
    expect(deltas).toEqual(['部分'])
    expect(fake.cancelled()).toBe(true)
  })

  it('非 2xx 响应解析 JSON message 并抛出对应状态码', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 429,
      json: () => Promise.resolve({ status: 429, message: '请求过于频繁' }),
    } as unknown as Response))

    await expect(streamAiChat(MESSAGES, { onDelta: () => {} }))
      .rejects.toMatchObject({ status: 429, message: '请求过于频繁' })
  })

  it('401 清空登录态并抛出', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 401,
      json: () => Promise.resolve({}),
    } as unknown as Response))

    const auth = useAuthStore()
    expect(auth.token).toBe('fake-token')
    await expect(streamAiChat(MESSAGES, { onDelta: () => {} }))
      .rejects.toBeInstanceOf(AiStreamHttpError)
    expect(auth.token).toBeNull()
  })
})
