import { request as nodeHttpRequest } from 'node:http'

export type LocalHttpRequestOptions = {
  port: number
  method?: string
  path: string
  body?: string | null
  timeoutMs?: number
  signal?: AbortSignal | null
}

export interface LocalHttpResponse {
  status: number
  contentType: string | null
  text: string
  json: unknown
}

export function createLocalHttpRequest(
  httpImpl?: typeof nodeHttpRequest,
): (options: LocalHttpRequestOptions) => Promise<LocalHttpResponse>

export const localHttpRequest: (options: LocalHttpRequestOptions) => Promise<LocalHttpResponse>
