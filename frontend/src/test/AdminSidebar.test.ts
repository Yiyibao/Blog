import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import AdminSidebar from '../components/AdminSidebar.vue'
import AdminDashboard from '../components/AdminDashboard.vue'

vi.mock('../api/admin', async (importOriginal) => {
  const mod = await importOriginal<typeof import('../api/admin')>()
  return {
    ...mod,
    hasValidAdminSession: () => true,
    getAdminSessionName: () => 'TestAdmin',
    fetchAdminPosts: vi.fn().mockResolvedValue({ items: [], totalElements: 0, totalPages: 1 }),
    fetchAdminDishes: vi.fn().mockResolvedValue({ items: [], totalElements: 0, totalPages: 1 }),
    fetchNotes: vi.fn().mockResolvedValue({ items: [], totalElements: 0, totalPages: 1 }),
    fetchAdminStats: vi.fn().mockResolvedValue({ posts: 5, dishes: 3, notes: 12 }),
  }
})

async function mountSidebarAt(path: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div>Home</div>' } },
      { path: '/admin', name: 'admin', component: { template: '<div>Dashboard</div>' } },
      { path: '/admin/notes', name: 'admin-notes', component: { template: '<div>Notes</div>' } },
      { path: '/admin/ai', name: 'admin-ai', component: { template: '<div>AI</div>' } },
    ],
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(AdminSidebar, {
    props: { postTotal: 5, dishTotal: 3, noteTotal: 12 },
    global: { plugins: [router] },
  })
  return { wrapper, router }
}

async function mountDashboardAt(path: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div>Home</div>' } },
      { path: '/admin', name: 'admin', component: AdminDashboard },
      { path: '/admin/notes', name: 'admin-notes', component: { template: '<div>Notes</div>' } },
      { path: '/admin/ai', name: 'admin-ai', component: { template: '<div>AI</div>' } },
    ],
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(AdminDashboard, {
    global: { plugins: [router] },
  })
  return { wrapper, router }
}

describe('AdminSidebar Navigation & Highlighting', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('highlights overview on /admin path with no section query', async () => {
    const { wrapper } = await mountSidebarAt('/admin')
    const activeLinks = wrapper.findAll('nav a.active')
    expect(activeLinks.length).toBe(1)
    expect(activeLinks[0].text()).toContain('总览')
  })

  it('highlights posts management on /admin?section=posts', async () => {
    const { wrapper } = await mountSidebarAt('/admin?section=posts')
    const activeLinks = wrapper.findAll('nav a.active')
    expect(activeLinks.length).toBe(1)
    expect(activeLinks[0].text()).toContain('文章管理')
  })

  it('highlights dishes management on /admin?section=dishes', async () => {
    const { wrapper } = await mountSidebarAt('/admin?section=dishes')
    const activeLinks = wrapper.findAll('nav a.active')
    expect(activeLinks.length).toBe(1)
    expect(activeLinks[0].text()).toContain('菜品管理')
  })

  it('highlights learning notes on /admin/notes', async () => {
    const { wrapper } = await mountSidebarAt('/admin/notes')
    const activeLinks = wrapper.findAll('nav a.active')
    expect(activeLinks.length).toBe(1)
    expect(activeLinks[0].text()).toContain('学习笔记')
  })

  it('highlights AI assistant on /admin/ai', async () => {
    const { wrapper } = await mountSidebarAt('/admin/ai')
    const activeLinks = wrapper.findAll('nav a.active')
    expect(activeLinks.length).toBe(1)
    expect(activeLinks[0].text()).toContain('AI 助手')
  })

  it('syncs dashboard tab with route section query and back/forward navigation', async () => {
    const { wrapper, router } = await mountDashboardAt('/admin?section=dishes')
    await flushPromises()

    // Active sidebar link should be dishes
    const activeLink = wrapper.find('.admin-sidebar nav a.active')
    expect(activeLink.text()).toContain('菜品管理')

    // Click posts tab inside dashboard
    const postsTabBtn = wrapper.findAll('.admin-content-section .admin-tabs button')[0]
    await postsTabBtn.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query.section).toBe('posts')
    expect(wrapper.find('.admin-sidebar nav a.active').text()).toContain('文章管理')
  })
})
