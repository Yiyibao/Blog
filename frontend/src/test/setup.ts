import { vi, beforeEach, afterEach } from 'vitest'

let objectUrlCounter = 0
const revokeObjectURLMock = vi.fn()

const origURL = globalThis.URL
vi.stubGlobal('URL', new Proxy(origURL, {
  construct(target, args) {
    return new target(...args as ConstructorParameters<typeof URL>)
  },
  get(target, prop, receiver) {
    if (prop === 'createObjectURL') {
      return (_blob: Blob) => `blob:mock-${++objectUrlCounter}`
    }
    if (prop === 'revokeObjectURL') {
      return revokeObjectURLMock
    }
    return Reflect.get(target, prop, receiver)
  },
}))

const mockStorage: Record<string, string> = {}
vi.stubGlobal('sessionStorage', {
  getItem(key: string) { return mockStorage[key] ?? null },
  setItem(key: string, value: string) { mockStorage[key] = value },
  removeItem(key: string) { delete mockStorage[key] },
  clear() { Object.keys(mockStorage).forEach(k => delete mockStorage[k]) },
  get length() { return Object.keys(mockStorage).length },
  key(_index: number) { return null },
  _data: mockStorage,
})

beforeEach(() => {
  objectUrlCounter = 0
  revokeObjectURLMock.mockClear()
  Object.keys(mockStorage).forEach(k => delete mockStorage[k])
  mockStorage['yubai-admin-token'] = 'fake-token'
  mockStorage['yubai-admin-expiry'] = '2099-12-31T23:59:59Z'
  vi.useFakeTimers({ shouldAdvanceTime: false })
})

afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})
