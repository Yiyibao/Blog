import { solvePowSync } from '../utils/pow'

/** L-7：PoW 求解 Worker——收到 {salt, difficulty} 后穷举并回传 nonce。 */
addEventListener('message', (event) => {
  const { salt, difficulty } = (event as MessageEvent<{ salt: string; difficulty: number }>).data
  ;(postMessage as (message: string) => void)(solvePowSync(salt, difficulty))
})
