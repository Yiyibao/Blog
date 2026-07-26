import { sha256Hex } from './sha256'

/** L-7：穷举 nonce 使 SHA-256(salt + nonce) 的十六进制摘要满足难度前缀（difficulty 个前导 '0'）。 */
export function solvePowSync(salt: string, difficulty: number): string {
  const prefix = '0'.repeat(difficulty)
  for (let nonce = 0; ; nonce++) {
    const candidate = String(nonce)
    if (sha256Hex(salt + candidate).startsWith(prefix)) {
      return candidate
    }
  }
}

/** 优先在 Web Worker 中求解（不阻塞 UI）；无 Worker 环境（如测试）降级为同步计算。 */
export function solvePow(salt: string, difficulty: number): Promise<string> {
  if (typeof Worker === 'undefined') {
    return Promise.resolve(solvePowSync(salt, difficulty))
  }
  return new Promise((resolve) => {
    const worker = new Worker(new URL('../workers/powWorker.ts', import.meta.url), { type: 'module' })
    worker.onmessage = (event: MessageEvent<string>) => {
      worker.terminate()
      resolve(event.data)
    }
    worker.onerror = () => {
      worker.terminate()
      resolve(solvePowSync(salt, difficulty))
    }
    worker.postMessage({ salt, difficulty })
  })
}
