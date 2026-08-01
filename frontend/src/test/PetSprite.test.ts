import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { nextTick } from 'vue'
import { enableAutoUnmount, mount } from '@vue/test-utils'
import PetSprite from '../components/admin-pet/PetSprite.vue'
import {
  ATLAS, PET_ANIMATIONS, LOOK_DIRECTIONS, lookCell, lookDirectionIndex, totalDuration,
} from '../components/admin-pet/petAnimations'

enableAutoUnmount(afterEach)

describe('petAnimations 元数据（以 animation-rows.md 为准）', () => {
  it('图集为 8×11、192×208 单元格，整图 1536×2288', () => {
    expect(ATLAS.columns).toBe(8)
    expect(ATLAS.rows).toBe(11)
    expect(ATLAS.cellWidth).toBe(192)
    expect(ATLAS.cellHeight).toBe(208)
    expect(ATLAS.width).toBe(1536)
    expect(ATLAS.height).toBe(2288)
    expect(ATLAS.spriteUrl).toBe('/pets/xinn/spritesheet.webp')
  })

  it('九个标准状态的行号/有效帧数/逐帧时长与规范一致', () => {
    expect(PET_ANIMATIONS.idle).toMatchObject({ row: 0, frames: 6, durations: [280, 110, 110, 140, 140, 320], loop: true })
    expect(PET_ANIMATIONS['running-right']).toMatchObject({ row: 1, frames: 8, durations: [120, 120, 120, 120, 120, 120, 120, 220], loop: true })
    expect(PET_ANIMATIONS['running-left']).toMatchObject({ row: 2, frames: 8, durations: [120, 120, 120, 120, 120, 120, 120, 220], loop: true })
    expect(PET_ANIMATIONS.waving).toMatchObject({ row: 3, frames: 4, durations: [140, 140, 140, 280], loop: false })
    expect(PET_ANIMATIONS.jumping).toMatchObject({ row: 4, frames: 5, durations: [140, 140, 140, 140, 280], loop: true })
    expect(PET_ANIMATIONS.failed).toMatchObject({ row: 5, frames: 8, durations: [140, 140, 140, 140, 140, 140, 140, 240], loop: false })
    expect(PET_ANIMATIONS.waiting).toMatchObject({ row: 6, frames: 6, durations: [150, 150, 150, 150, 150, 260], loop: true })
    expect(PET_ANIMATIONS.running).toMatchObject({ row: 7, frames: 6, durations: [120, 120, 120, 120, 120, 220], loop: true })
    expect(PET_ANIMATIONS.review).toMatchObject({ row: 8, frames: 6, durations: [150, 150, 150, 150, 150, 280], loop: false })
  })

  it('所有标准状态帧数 ≤ 8 且未用列不参与动画', () => {
    for (const spec of Object.values(PET_ANIMATIONS)) {
      expect(spec.frames).toBeLessThanOrEqual(ATLAS.columns)
      expect(spec.durations).toHaveLength(spec.frames)
      expect(spec.row).toBeLessThan(9)
    }
  })

  it('16 个视线方向：row 9 = 0°-157.5°，row 10 = 180°-337.5°，顺时针 22.5° 步进', () => {
    expect(LOOK_DIRECTIONS).toHaveLength(16)
    expect(LOOK_DIRECTIONS[0]).toMatchObject({ row: 9, col: 0, degrees: 0 })
    expect(LOOK_DIRECTIONS[7]).toMatchObject({ row: 9, col: 7, degrees: 157.5 })
    expect(LOOK_DIRECTIONS[8]).toMatchObject({ row: 10, col: 0, degrees: 180 })
    expect(LOOK_DIRECTIONS[15]).toMatchObject({ row: 10, col: 7, degrees: 337.5 })
    LOOK_DIRECTIONS.forEach((spec, index) => {
      expect(spec.degrees).toBeCloseTo(index * 22.5)
    })
  })

  it('lookCell 把角度量化为 16 档（0°=上，顺时针）', () => {
    expect(lookCell(0)).toEqual({ row: 9, col: 0 })
    expect(lookCell(22.5)).toEqual({ row: 9, col: 1 })
    expect(lookCell(45)).toEqual({ row: 9, col: 2 })
    expect(lookCell(90)).toEqual({ row: 9, col: 4 })
    expect(lookCell(180)).toEqual({ row: 10, col: 0 })
    expect(lookCell(270)).toEqual({ row: 10, col: 4 })
    expect(lookCell(337.5)).toEqual({ row: 10, col: 7 })
    expect(lookCell(360)).toEqual({ row: 9, col: 0 })
    expect(lookCell(-45)).toEqual({ row: 10, col: 6 })
    expect(lookDirectionIndex(11)).toBe(0)
    expect(lookDirectionIndex(12)).toBe(1)
  })

  it('totalDuration 为逐帧时长之和（one-shot 结束时机）', () => {
    expect(totalDuration('waving')).toBe(140 + 140 + 140 + 280)
    expect(totalDuration('failed')).toBe(140 * 7 + 240)
    expect(totalDuration('review')).toBe(150 * 5 + 280)
  })
})

describe('PetSprite 渲染与裁切', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  it('首帧即 state 对应行的第 0 格，图片按 192×208 缩放铺满容器', () => {
    const wrapper = mount(PetSprite, { props: { state: 'idle', size: 96 } })
    const sprite = wrapper.get('.pet-sprite')
    expect(sprite.attributes('data-state')).toBe('idle')
    expect(sprite.attributes('data-row')).toBe('0')
    expect(sprite.attributes('data-col')).toBe('0')
    expect(sprite.attributes('data-frame')).toBe('0')
    const img = wrapper.get('img')
    expect(img.attributes('src')).toBe('/pets/xinn/spritesheet.webp')
    expect(img.attributes('width')).toBe('1536')
    expect(img.attributes('height')).toBe('2288')
    // size=96 → 图集缩放 0.5 倍，容器 96×104
    expect(sprite.attributes('style')).toContain('width: 96px')
    expect(sprite.attributes('style')).toContain('height: 104px')
    expect(img.attributes('style')).toContain('width: 768px')
    expect(img.attributes('style')).toContain('height: 1144px')
    expect(img.attributes('style')).toContain('transform: translate(0px, 0px)')
  })

  it('idle 循环按帧时长推进并回绕，且裁切窗口随帧右移 192×scale', async () => {
    const wrapper = mount(PetSprite, { props: { state: 'idle', size: 96 } })
    const sprite = wrapper.get('.pet-sprite')
    const img = wrapper.get('img')

    await vi.advanceTimersByTimeAsync(280)
    await nextTick()
    expect(sprite.attributes('data-col')).toBe('1')
    expect(img.attributes('style')).toContain('translate(-96px, 0px)')

    await vi.advanceTimersByTimeAsync(110)
    await nextTick()
    expect(sprite.attributes('data-col')).toBe('2')

    await vi.advanceTimersByTimeAsync(110 + 140 + 140 + 320)
    await nextTick()
    // 循环回第 0 帧
    expect(sprite.attributes('data-col')).toBe('0')
  })

  it('一次性动画按累计时长精确结束并 emit finished，不提前不迟到', async () => {
    const wrapper = mount(PetSprite, { props: { state: 'waving', size: 96 } })

    await vi.advanceTimersByTimeAsync(totalDuration('waving') - 1)
    expect(wrapper.emitted('finished')).toBeUndefined()
    await vi.advanceTimersByTimeAsync(1)
    expect(wrapper.emitted('finished')).toHaveLength(1)
  })

  it('状态切换从新状态第 0 帧开始', async () => {
    const wrapper = mount(PetSprite, { props: { state: 'idle', size: 96 } })
    await vi.advanceTimersByTimeAsync(280)
    await nextTick()
    expect(wrapper.get('.pet-sprite').attributes('data-col')).toBe('1')

    await wrapper.setProps({ state: 'running' })
    const sprite = wrapper.get('.pet-sprite')
    expect(sprite.attributes('data-state')).toBe('running')
    expect(sprite.attributes('data-row')).toBe('7')
    expect(sprite.attributes('data-col')).toBe('0')
  })

  it('look 状态显示对应方向的静态格，不启动循环 timer', async () => {
    const wrapper = mount(PetSprite, { props: { state: 'look', lookDirection: 90, size: 96 } })
    const sprite = wrapper.get('.pet-sprite')
    expect(sprite.attributes('data-state')).toBe('look')
    expect(sprite.attributes('data-row')).toBe('9')
    expect(sprite.attributes('data-col')).toBe('4')

    vi.advanceTimersByTime(5000)
    expect(sprite.attributes('data-col')).toBe('4')
  })

  it('卸载时清理 timer，不泄漏', () => {
    const wrapper = mount(PetSprite, { props: { state: 'idle', size: 96 } })
    wrapper.unmount()
    vi.advanceTimersByTime(10000)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('页面 hidden 时暂停动画，恢复后从当前帧继续', async () => {
    const wrapper = mount(PetSprite, { props: { state: 'idle', size: 96 } })
    Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true })
    document.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(10000)
    await nextTick()
    expect(wrapper.get('.pet-sprite').attributes('data-col')).toBe('0')

    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true })
    document.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(280)
    await nextTick()
    expect(wrapper.get('.pet-sprite').attributes('data-col')).toBe('1')
  })

  it('reduced-motion 下只显示稳定首帧，不启动循环 timer 或 finished', async () => {
    vi.stubGlobal('matchMedia', (query: string) => ({
      matches: query.includes('prefers-reduced-motion'),
      media: query,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    }))

    const wrapper = mount(PetSprite, { props: { state: 'waving', size: 96 } })
    const sprite = wrapper.get('.pet-sprite')
    expect(sprite.attributes('data-col')).toBe('0')

    vi.advanceTimersByTime(5000)
    expect(sprite.attributes('data-col')).toBe('0')
    expect(vi.getTimerCount()).toBe(0)
    vi.unstubAllGlobals()
  })
})
