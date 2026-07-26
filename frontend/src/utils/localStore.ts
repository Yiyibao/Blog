/**
 * FD-3：美食模块 localStorage 统一入口。
 * 键一律加 `yubai:food:` 前缀防命名分裂；隐私模式下 localStorage 可能抛异常，全部 try/catch 静默降级。
 */
const PREFIX = 'yubai:food:'

export function readLocal<T>(key: string, fallback: T): T {
  try {
    const raw = window.localStorage.getItem(PREFIX + key)
    return raw === null ? fallback : (JSON.parse(raw) as T)
  } catch {
    return fallback
  }
}

export function writeLocal(key: string, value: unknown): void {
  try {
    window.localStorage.setItem(PREFIX + key, JSON.stringify(value))
  } catch {
    // 存不进就算了，功能降级为不记忆
  }
}

export function removeLocal(key: string): void {
  try {
    window.localStorage.removeItem(PREFIX + key)
  } catch {
    // 忽略
  }
}

/** 清理历史遗留的全名键（前缀约定之前写下的），仅用于一次性迁移清扫。 */
export function removeLegacyKey(fullKey: string): void {
  try {
    window.localStorage.removeItem(fullKey)
  } catch {
    // 忽略
  }
}
