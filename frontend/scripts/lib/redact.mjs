/**
 * 统一日志脱敏：先脱敏、再截断、再输出。
 * 覆盖：Authorization/Proxy-Authorization/Cookie/Set-Cookie、Basic/Bearer 凭据、
 * password/passwd/pwd、token/access_token/refresh_token、apiKey/api_key/api-key、
 * secret/client_secret、JWT 形态、URL 查询参数值、大小写与分隔符变体。
 */

const SENSITIVE_PATTERNS = [
  // JWT 形态（最优先，避免被后续模式拆散）
  /\beyJ[a-zA-Z0-9_-]{10,}\.[a-zA-Z0-9_-]{10,}\.[a-zA-Z0-9_-]{10,}\b/g,
  // Basic 凭据（整体）
  /\bBasic\s+[A-Za-z0-9+/=]{8,}\b/g,
  // Bearer 凭据（整体）
  /\bBearer\s+[A-Za-z0-9._~+/=-]{8,}\b/g,
  // 凭据头与 Cookie（保留键名；容忍 JSON 引号）
  /(authorization|proxy-authorization|cookie|set-cookie)\s*"?\s*[:=]\s*"?[^\s,;&]+/gi,
  // 常见敏感键（保留键名；含驼峰/大小写变体；容忍 JSON 引号；值排除 & 防止吞掉相邻参数）
  /(password|passwd|pwd|token|access_token|refresh_token|api_key|apikey|api-key|apiKey|client_secret|clientsecret|clientSecret|secret|key)\s*"?\s*[:=]\s*"?[^\s,;&]+/gi,
  // URL 查询参数值（保留参数名）
  /([?&](?:token|access_token|refresh_token|api_key|apikey|api-key|secret|client_secret|password|key)=)[^&#\s]+/gi,
]

function redactMatch(match) {
  const keyMatch = match.match(/^([^:=]+)[:=]\s*/)
  if (keyMatch) {
    return `${keyMatch[1]}=<redacted>`
  }
  return '<redacted>'
}

/** 统一脱敏：先替换全部敏感模式，再输出（截断由调用方在脱敏后进行）。 */
export function redact(input) {
  if (input === null || input === undefined) return ''
  const text = typeof input === 'string' ? input : String(input)
  let out = text
  for (const pattern of SENSITIVE_PATTERNS) {
    out = out.replace(pattern, redactMatch)
  }
  return out
}

/** 脱敏后截断（先脱敏、再截断）。 */
export function redactAndTruncate(input, limit = 300) {
  const cleaned = redact(input)
  return cleaned.length > limit ? `${cleaned.slice(0, limit)}…` : cleaned
}

/** 展开并脱敏异常 cause chain（name/message/code/errno/syscall/address/port）。 */
export function redactErrorChain(error, limit = 500) {
  const parts = []
  let current = error
  let depth = 0
  while (current && depth < 5) {
    const name = typeof current.name === 'string' ? current.name : 'Error'
    const message = redact(
      typeof current.message === 'string' ? current.message : String(current))
    parts.push(`${name}: ${message}`)
    // 附加系统层错误信息（ECONNREFUSED 等）
    const code = current.code
    const errno = current.errno
    const syscall = current.syscall
    const address = current.address
    const port = current.port
    if (code || errno || syscall || port) {
      parts.push(`cause=${redact(JSON.stringify({ code, errno, syscall, address, port }))}`)
    }
    const cause = current.cause
    if (cause instanceof Error) {
      current = cause
      depth += 1
      continue
    }
    if (cause && typeof cause === 'object') {
      parts.push(`cause=${redact(JSON.stringify({
        code: cause.code,
        errno: cause.errno,
        syscall: cause.syscall,
        address: cause.address,
        port: cause.port,
      }))}`)
    }
    break
  }
  const joined = parts.join(' <- ')
  return joined.length > limit ? `${joined.slice(0, limit)}…` : joined
}

/** JSON 摘要：先脱敏、再截断（不输出敏感字段原文）。 */
export function safeJsonSummary(value, limit = 200) {
  try {
    return redactAndTruncate(JSON.stringify(value), limit)
  } catch {
    return '(JSON 序列化失败)'
  }
}

/** 响应体摘要：先脱敏、再截断。 */
export function safeResponseBody(response, limit = 300) {
  if (response && typeof response.text === 'function') {
    try {
      return response.text().then((text) => redactAndTruncate(text, limit))
    } catch {
      return Promise.resolve('(读取响应体失败)')
    }
  }
  return Promise.resolve(redactAndTruncate(String(response ?? ''), limit))
}
