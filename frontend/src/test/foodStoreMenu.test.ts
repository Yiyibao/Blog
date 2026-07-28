import { describe, it, expect, beforeEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useFoodStore, todayISO } from '../stores/foodStore'
import { useAuthStore } from '../stores/auth'
import type { DailyMenu } from '../api/kitchen'

const mockFetchDailyMenu = vi.fn()
const mockAppendMenuItem = vi.fn()
const mockPutDailyMenu = vi.fn()
const mockDeleteMenuItem = vi.fn()

vi.mock('../api/kitchen', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/kitchen')>()
  return {
    ...actual,
    fetchDailyMenu: (...args: unknown[]) => mockFetchDailyMenu(...args),
    appendMenuItem: (...args: unknown[]) => mockAppendMenuItem(...args),
    putDailyMenu: (...args: unknown[]) => mockPutDailyMenu(...args),
    deleteMenuItem: (...args: unknown[]) => mockDeleteMenuItem(...args),
  }
})

function menuOf(items: Partial<DailyMenu['items'][number]>[], overrides: Partial<DailyMenu> = {}): DailyMenu {
  return {
    exists: true,
    date: todayISO(),
    status: 'DRAFT',
    note: '',
    version: 0,
    updatedBy: 1,
    updatedAt: new Date().toISOString(),
    items: items.map((item, index) => ({
      id: index + 1,
      dishId: null,
      dishSlug: null,
      title: `菜${index + 1}`,
      mealSlot: 'DINNER',
      note: '',
      sortOrder: index,
      authorId: 1,
      authorName: '站长',
      createdAt: '2026-07-27T10:00:00Z',
      ...item,
    })),
    ...overrides,
  }
}

function axios409() {
  return Object.assign(new Error('conflict'), {
    isAxiosError: true,
    response: { status: 409, data: { message: '菜单刚被对方更新过，请刷新后再提交' }, headers: {} },
  })
}

beforeEach(() => {
  sessionStorage.clear()
  localStorage.clear()
  setActivePinia(createPinia())
  useAuthStore().saveSession({
    token: 't', tokenType: 'Bearer', username: 'gxynf',
    expiresAt: '2099-12-31T23:59:59Z', role: 'ADMIN', displayName: '站长',
  })
  mockFetchDailyMenu.mockReset()
  mockAppendMenuItem.mockReset()
  mockPutDailyMenu.mockReset()
  mockDeleteMenuItem.mockReset()
})

describe('FD-12 foodStore 菜单分片', () => {
  it('todayISO 是本地时区日期（sv-SE 格式）', () => {
    expect(todayISO()).toMatch(/^\d{4}-\d{2}-\d{2}$/)
    expect(todayISO()).toBe(new Date().toLocaleDateString('sv-SE'))
  })

  it('loadMenu 加载并标记已读', async () => {
    mockFetchDailyMenu.mockResolvedValue(menuOf([{}]))
    const store = useFoodStore()
    await store.loadMenu()
    expect(store.menu?.items).toHaveLength(1)
    expect(store.loading).toBe(false)
    expect(sessionStorage.getItem(`yubai:food:menu-seen:${todayISO()}`)).not.toBeNull()
  })

  it('对方在上次看过之后加的菜被标为 arrivals（到达动画数据源）', async () => {
    const store = useFoodStore()
    sessionStorage.setItem(`yubai:food:menu-seen:${todayISO()}`, '2026-07-27T09:00:00Z')
    mockFetchDailyMenu.mockResolvedValue(menuOf([
      { id: 1, authorName: '站长', createdAt: '2026-07-27T08:00:00Z' },
      { id: 2, authorName: '小伙伴', createdAt: '2026-07-27T10:30:00Z' },
      { id: 3, authorName: '站长', createdAt: '2026-07-27T10:40:00Z' },
    ]))
    await store.loadMenu()
    // 只有"对方"在 09:00 之后加的才算到达（自己加的不庆祝自己）
    expect(store.arrivals).toEqual([2])
  })

  it('append 乐观插入临时项，成功后以服务端响应整单替换', async () => {
    const store = useFoodStore()
    mockFetchDailyMenu.mockResolvedValue(menuOf([{}]))
    await store.loadMenu()
    let resolve!: (m: DailyMenu) => void
    mockAppendMenuItem.mockReturnValue(new Promise((r) => { resolve = r }))
    const pending = store.append({ title: '新菜', mealSlot: 'DINNER' })
    expect(store.menu?.items).toHaveLength(2)
    expect(store.menu?.items[1].title).toBe('新菜')
    expect(store.menu?.items[1].id).toBeLessThan(0)
    resolve(menuOf([{}, { id: 9, title: '新菜' }]))
    await pending
    expect(store.menu?.items[1].id).toBe(9)
  })

  it('append 失败回滚乐观项并抛分类错误', async () => {
    const store = useFoodStore()
    mockFetchDailyMenu.mockResolvedValue(menuOf([{}]))
    await store.loadMenu()
    mockAppendMenuItem.mockRejectedValue(Object.assign(new Error('429'), {
      isAxiosError: true, response: { status: 429, data: {}, headers: {} },
    }))
    await expect(store.append({ title: '手速菜', mealSlot: 'DINNER' }))
      .rejects.toMatchObject({ kind: 'rate-limited' })
    expect(store.menu?.items).toHaveLength(1)
  })

  it('submitMenu 遇 409 自动刷新最新菜单再抛冲突', async () => {
    const store = useFoodStore()
    mockFetchDailyMenu.mockResolvedValue(menuOf([{}]))
    await store.loadMenu()
    mockPutDailyMenu.mockRejectedValue(axios409())
    mockFetchDailyMenu.mockResolvedValue(menuOf([{}, { id: 5, title: '对方加的' }], { version: 2 }))
    await expect(store.submitMenu({ status: 'CONFIRMED', note: '', expectedVersion: 0, items: [] }))
      .rejects.toMatchObject({ kind: 'conflict' })
    expect(store.menu?.version).toBe(2)
    expect(store.menu?.items).toHaveLength(2)
  })

  it('轮询只在页面可见且非保存中时拉取，停止后不再拉', async () => {
    const store = useFoodStore()
    mockFetchDailyMenu.mockResolvedValue(menuOf([{}]))
    await store.loadMenu()
    mockFetchDailyMenu.mockClear()
    store.startMenuPolling()
    await vi.advanceTimersByTimeAsync(30_000)
    expect(mockFetchDailyMenu).toHaveBeenCalledTimes(1)
    store.stopMenuPolling()
    await vi.advanceTimersByTimeAsync(60_000)
    expect(mockFetchDailyMenu).toHaveBeenCalledTimes(1)
  })

  it('切日期期间的迟到响应被丢弃（竞态守卫）', async () => {
    const store = useFoodStore()
    let resolveSlow!: (m: DailyMenu) => void
    mockFetchDailyMenu.mockReturnValueOnce(new Promise((r) => { resolveSlow = r }))
    const slow = store.loadMenu('2026-08-01')
    mockFetchDailyMenu.mockResolvedValueOnce(menuOf([{ id: 7, title: '今天的菜' }]))
    await store.loadMenu('2026-08-02')
    resolveSlow(menuOf([{ id: 6, title: '过期的菜' }], { date: '2026-08-01' }))
    await slow
    expect(store.menu?.items[0].id).toBe(7)
    expect(store.menuDate).toBe('2026-08-02')
  })

  it('stale same-date read discarded after mutation (generation guard)', async () => {
    const store = useFoodStore()
    mockFetchDailyMenu.mockResolvedValue(menuOf([{ id: 1, title: '初始菜' }]))
    await store.loadMenu()
    expect(store.menu?.items).toHaveLength(1)
    let resolveStale!: (m: DailyMenu) => void
    mockFetchDailyMenu.mockReturnValueOnce(new Promise((r) => { resolveStale = r }))
    const staleRead = store.loadMenu()
    await vi.advanceTimersByTimeAsync(0)
    mockAppendMenuItem.mockResolvedValue(menuOf([{ id: 1, title: '初始菜' }, { id: 2, title: '新加的菜' }]))
    await store.append({ title: '新加的菜', mealSlot: 'DINNER' })
    resolveStale(menuOf([{ id: 1, title: '初始菜' }]))
    await staleRead
    expect(store.menu?.items).toHaveLength(2)
    expect(store.menu?.items[1].title).toBe('新加的菜')
  })

  it('cross-midnight switches to today when followingToday is true', async () => {
    const store = useFoodStore()
    const yesterday = '2026-07-27'
    const today = '2026-07-28'
    mockFetchDailyMenu.mockResolvedValue(menuOf([{ id: 1, title: '昨天的菜' }], { date: yesterday }))
    await store.loadMenu(yesterday)
    expect(store.menuDate).toBe(yesterday)
    mockFetchDailyMenu.mockClear()
    mockFetchDailyMenu.mockResolvedValue(menuOf([{ id: 2, title: '今天的菜' }], { date: today }))
    store['followingToday'] = true
    store.onVisibilityChange()
    await vi.advanceTimersByTimeAsync(0)
    expect(store.menuDate).toBe(today)
    expect(store.menu?.items[0].title).toBe('今天的菜')
  })

  it('cross-midnight keeps historical date when not followingToday', async () => {
    const store = useFoodStore()
    const historical = '2026-07-01'
    mockFetchDailyMenu.mockResolvedValue(menuOf([{ id: 1, title: '历史菜' }], { date: historical }))
    await store.loadMenu(historical)
    expect(store.menuDate).toBe(historical)
    mockFetchDailyMenu.mockClear()
    mockFetchDailyMenu.mockResolvedValue(menuOf([{ id: 2, title: '新菜' }], { date: historical }))
    store.onVisibilityChange()
    await vi.advanceTimersByTimeAsync(0)
    expect(store.menuDate).toBe(historical)
  })
})
