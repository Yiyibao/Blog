/** 统一日志脱敏：先脱敏、再截断、再输出。 */
export function redact(input: unknown): string
export function redactAndTruncate(input: unknown, limit?: number): string
export function redactErrorChain(error: unknown, limit?: number): string
export function safeJsonSummary(value: unknown, limit?: number): string
export function safeResponseBody(
  response: { text?: () => string | Promise<string> } | string | null | undefined,
  limit?: number,
): Promise<string>
