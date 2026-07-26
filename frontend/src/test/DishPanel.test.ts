import { describe, it, expect, afterEach } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import DishPanel from '../components/food/DishPanel.vue'
import type { Dish } from '../data'

function makeDish(overrides: Partial<Dish> = {}): Dish {
  return {
    id: 7,
    slug: 'test-dish',
    name: '测试红烧肉',
    summary: '一道用于测试的菜',
    category: '家常菜',
    imageUrl: '/food/test.jpg',
    imageAlt: '测试图片',
    imageCredit: '测试摄影师',
    imageSourceUrl: 'https://example.com/source',
    prepMinutes: 15,
    difficulty: '简单',
    rating: 4.5,
    featured: false,
    published: true,
    displayOrder: 0,
    ingredients: ['五花肉 200 克', '盐 适量'],
    steps: ['切块焯水', '小火慢炖'],
    createdAt: '2026-06-01T00:00:00Z',
    updatedAt: '2026-06-01T00:00:00Z',
    ...overrides,
  }
}

let wrapper: VueWrapper | null = null

async function mountPanel(dish: Dish | null = makeDish()) {
  wrapper = mount(DishPanel, { props: { dish }, attachTo: document.body })
  await flushPromises()
  return wrapper
}

const panelEl = () => document.body.querySelector('.dish-panel')
const backdropEl = () => document.body.querySelector<HTMLElement>('.dish-backdrop')

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  document.body.style.removeProperty('overflow')
  document.body.innerHTML = ''
})

describe('DishPanel', () => {
  it('renders dish details into the teleported drawer', async () => {
    await mountPanel()
    const panel = panelEl()
    expect(panel).not.toBeNull()
    const text = panel!.textContent ?? ''
    expect(text).toContain('测试红烧肉')
    expect(text).toContain('五花肉 200 克')
    expect(text).toContain('切块焯水')
    expect(text).toContain('测试摄影师')
  })

  it('renders nothing when dish is null', async () => {
    await mountPanel(null)
    expect(panelEl()).toBeNull()
  })

  it('exposes dialog semantics for assistive tech', async () => {
    await mountPanel()
    const panel = panelEl()!
    expect(panel.getAttribute('role')).toBe('dialog')
    expect(panel.getAttribute('aria-modal')).toBe('true')
    const labelledBy = panel.getAttribute('aria-labelledby')
    expect(labelledBy).toBeTruthy()
    expect(document.getElementById(labelledBy!)?.textContent).toContain('测试红烧肉')
  })

  it('locks body scroll while open and restores it when closed', async () => {
    const w = await mountPanel()
    expect(document.body.style.overflow).toBe('hidden')
    await w.setProps({ dish: null })
    await flushPromises()
    expect(document.body.style.overflow).toBe('')
  })

  it('moves focus to the close button when opened', async () => {
    await mountPanel()
    const close = document.body.querySelector<HTMLButtonElement>('.dish-panel-media button')
    expect(document.activeElement).toBe(close)
  })

  it('emits close when Escape is pressed anywhere in the window', async () => {
    const w = await mountPanel()
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    expect(w.emitted('close')).toHaveLength(1)
  })

  it('does not emit close on Escape when already closed', async () => {
    const w = await mountPanel(null)
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    expect(w.emitted('close')).toBeUndefined()
  })

  it('emits close when clicking the backdrop but not the panel itself', async () => {
    const w = await mountPanel()
    panelEl()!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    expect(w.emitted('close')).toBeUndefined()
    backdropEl()!.dispatchEvent(new MouseEvent('click'))
    expect(w.emitted('close')).toHaveLength(1)
  })

  it('emits close from the close button', async () => {
    const w = await mountPanel()
    document.body.querySelector<HTMLButtonElement>('.dish-panel-media button')!.click()
    expect(w.emitted('close')).toHaveLength(1)
  })

  it('scales ingredient amounts against the base servings of 2', async () => {
    await mountPanel()
    const increase = document.body.querySelector<HTMLButtonElement>('button[aria-label="增加份数"]')!
    increase.click()
    await flushPromises()
    const text = panelEl()!.textContent ?? ''
    expect(text).toContain('3 人份')
    expect(text).toContain('300 克')
    expect(text).toContain('盐 适量')
  })

  it('clamps servings between 1 and 20', async () => {
    await mountPanel()
    const decrease = document.body.querySelector<HTMLButtonElement>('button[aria-label="减少份数"]')!
    decrease.click()
    await flushPromises()
    expect(panelEl()!.textContent).toContain('1 人份')
    expect(decrease.disabled).toBe(true)
  })

  it('resets servings when a different dish is shown', async () => {
    const w = await mountPanel()
    document.body.querySelector<HTMLButtonElement>('button[aria-label="增加份数"]')!.click()
    await flushPromises()
    expect(panelEl()!.textContent).toContain('3 人份')
    await w.setProps({ dish: makeDish({ id: 8, slug: 'another', name: '另一道菜' }) })
    await flushPromises()
    expect(panelEl()!.textContent).toContain('2 人份')
  })

  it('traps Tab focus inside the drawer', async () => {
    await mountPanel()
    const panel = panelEl()!
    const focusable = panel.querySelectorAll<HTMLElement>('a[href], button:not([disabled])')
    const last = focusable[focusable.length - 1]
    last.focus()
    const event = new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true })
    window.dispatchEvent(event)
    expect(event.defaultPrevented).toBe(true)
    expect(document.activeElement).toBe(focusable[0])
  })

  it('meets the 44px touch target contract on drawer controls', async () => {
    await mountPanel()
    // jsdom 无布局引擎，尺寸靠 .tap-44 约定类做源码级断言，最终以人工验收为准
    const close = document.body.querySelector<HTMLButtonElement>('.dish-panel-media button')!
    expect(close.classList.contains('tap-44')).toBe(true)
    document.body.querySelectorAll<HTMLButtonElement>('.servings-bar button').forEach((btn) => {
      expect(btn.classList.contains('tap-44')).toBe(true)
    })
  })
})
