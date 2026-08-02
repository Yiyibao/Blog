import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { nextTick } from 'vue'
import { enableAutoUnmount, mount, type VueWrapper } from '@vue/test-utils'
import PetSprite from '../components/admin-pet/PetSprite.vue'
import { totalDuration } from '../components/admin-pet/petAnimations'

enableAutoUnmount(afterEach)

/** jsdom 不加载真实图片：手动派发 load 表示当前行图已加载完成。 */
async function loadSprite(wrapper: VueWrapper) {
  await wrapper.get('img').trigger('load')
  await nextTick()
}

describe('PetSprite 高清渲染与裁切（size=307 显示尺寸不变）', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  it('size=307 时裁切窗口仍为 307×332.6 CSS px，图片按 384 源像素单格缩小', () => {
    const wrapper = mount(PetSprite, { props: { state: 'idle', size: 307 } })
    const sprite = wrapper.get('.pet-sprite')
    const img = wrapper.get('img')
    expect(sprite.attributes('data-state')).toBe('idle')
    expect(sprite.attributes('data-row')).toBe('0')
    expect(sprite.attributes('data-col')).toBe('0')
    expect(sprite.attributes('data-src')).toBe('idle')
    // 显示尺寸：307 × (416/384) = 332.583…
    expect(sprite.attributes('style')).toContain('width: 307px')
    expect(sprite.attributes('style')).toContain('height: 332.58')
    // 高清行图：src 指向独立行文件，img 尺寸按 3072×416 计算
    expect(img.attributes('src')).toBe('/pets/xinn/hd/idle.webp')
    expect(img.attributes('width')).toBe('3072')
    expect(img.attributes('height')).toBe('416')
    expect(img.attributes('style')).toContain('width: 2456px')
    expect(img.attributes('style')).toContain('height: 332.58')
    expect(img.attributes('style')).toContain('translate(0px, 0px)')
  })

  it('资源未加载前不推进 timer；加载完成后从第 0 帧开始', async () => {
    const wrapper = mount(PetSprite, { props: { state: 'idle', size: 96 } })
    await vi.advanceTimersByTimeAsync(5000)
    await nextTick()
    expect(wrapper.get('.pet-sprite').attributes('data-col')).toBe('0')
    expect(vi.getTimerCount()).toBe(0)

    await loadSprite(wrapper)
    await vi.advanceTimersByTimeAsync(280)
    await nextTick()
    expect(wrapper.get('.pet-sprite').attributes('data-col')).toBe('1')
  })

  it('idle 循环按帧时长推进并回绕，裁切窗口随帧右移 384×scale', async () => {
    const wrapper = mount(PetSprite, { props: { state: 'idle', size: 96 } })
    await loadSprite(wrapper)
    const sprite = wrapper.get('.pet-sprite')
    const img = wrapper.get('img')

    await vi.advanceTimersByTimeAsync(280)
    await nextTick()
    expect(sprite.attributes('data-col')).toBe('1')
    // scale = 96/384 = 0.25 → 每帧右移 96 CSS px
    expect(img.attributes('style')).toContain('translate(-96px, 0px)')

    await vi.advanceTimersByTimeAsync(110)
    await nextTick()
    expect(sprite.attributes('data-col')).toBe('2')

    await vi.advanceTimersByTimeAsync(110 + 140 + 140 + 320)
    await nextTick()
    expect(sprite.attributes('data-col')).toBe('0')
  })

  it('一次性动画按累计时长精确结束并 emit 带动画 id 的 finished，不提前不迟到', async () => {
    const wrapper = mount(PetSprite, { props: { state: 'waving', size: 96 } })
    await loadSprite(wrapper)

    await vi.advanceTimersByTimeAsync(totalDuration('waving') - 1)
    expect(wrapper.emitted('finished')).toBeUndefined()
    await vi.advanceTimersByTimeAsync(1)
    expect(wrapper.emitted('finished')).toHaveLength(1)
    expect(wrapper.emitted('finished')![0]).toEqual(['waving'])
  })

  it('状态切换从新状态第 0 帧开始，且新行图需要重新加载', async () => {
    const wrapper = mount(PetSprite, { props: { state: 'idle', size: 96 } })
    await loadSprite(wrapper)
    await vi.advanceTimersByTimeAsync(280)
    await nextTick()
    expect(wrapper.get('.pet-sprite').attributes('data-col')).toBe('1')

    await wrapper.setProps({ state: 'running' })
    const sprite = wrapper.get('.pet-sprite')
    expect(sprite.attributes('data-state')).toBe('running')
    expect(sprite.attributes('data-row')).toBe('7')
    expect(sprite.attributes('data-col')).toBe('0')
    expect(wrapper.get('img').attributes('src')).toBe('/pets/xinn/hd/running.webp')

    // 新行图未加载前不推进
    await vi.advanceTimersByTimeAsync(5000)
    await nextTick()
    expect(sprite.attributes('data-col')).toBe('0')

    await loadSprite(wrapper)
    await vi.advanceTimersByTimeAsync(120)
    await nextTick()
    expect(sprite.attributes('data-col')).toBe('1')
  })

  it('快速切换两个资源时，旧资源的迟到 load 不得启动旧状态', async () => {
    const wrapper = mount(PetSprite, { props: { state: 'idle', size: 96 } })
    // idle 尚未加载完成就切到 running：旧 pending（idle）作废
    await wrapper.setProps({ state: 'running' })
    await nextTick()
    expect(wrapper.get('img').attributes('src')).toBe('/pets/xinn/hd/running.webp')

    // 派发 load 时元素 src 已是 running → 只有 running 的播放令牌生效
    await loadSprite(wrapper)
    await vi.advanceTimersByTimeAsync(120)
    await nextTick()
    const sprite = wrapper.get('.pet-sprite')
    expect(sprite.attributes('data-src')).toBe('running')
    expect(sprite.attributes('data-col')).toBe('1')

    // 若旧 idle 令牌被误启动，data-src 会是 idle 且帧序为 idle 的
    expect(sprite.attributes('data-src')).not.toBe('idle')
  })

  it('旧行已缓存时也不能把它误判为新行已就绪', async () => {
    const wrapper = mount(PetSprite, { props: { state: 'idle', size: 96 } })
    const oldImage = wrapper.get('img').element as HTMLImageElement
    Object.defineProperty(oldImage, 'complete', { value: true, configurable: true })
    Object.defineProperty(oldImage, 'naturalWidth', { value: 3072, configurable: true })

    await wrapper.setProps({ state: 'running' })
    await nextTick()
    expect(wrapper.get('img').attributes('src')).toBe('/pets/xinn/hd/running.webp')
    await vi.advanceTimersByTimeAsync(5000)
    expect(wrapper.get('.pet-sprite').attributes('data-col')).toBe('0')

    await loadSprite(wrapper)
    await vi.advanceTimersByTimeAsync(120)
    expect(wrapper.get('.pet-sprite').attributes('data-col')).toBe('1')
  })

  it('状态切换打断 one-shot：旧动画不得再 emit finished', async () => {
    const wrapper = mount(PetSprite, { props: { state: 'waving', size: 96 } })
    await loadSprite(wrapper)
    await vi.advanceTimersByTimeAsync(totalDuration('waving') / 2)

    await wrapper.setProps({ state: 'idle' })
    await loadSprite(wrapper)
    await vi.advanceTimersByTimeAsync(totalDuration('waving') * 2)
    await nextTick()
    expect(wrapper.emitted('finished')).toBeUndefined()
  })

  it('高清加载失败回退旧 8×11 图集对应行并继续播放', async () => {
    const wrapper = mount(PetSprite, { props: { state: 'running', size: 96 } })
    await wrapper.get('img').trigger('error')
    await nextTick()
    const img = wrapper.get('img')
    expect(img.attributes('src')).toBe('/pets/xinn/spritesheet.webp')
    // 回退行 data-row 保持旧图集身份行号
    expect(wrapper.get('.pet-sprite').attributes('data-row')).toBe('7')

    await loadSprite(wrapper)
    await vi.advanceTimersByTimeAsync(120)
    await nextTick()
    expect(wrapper.get('.pet-sprite').attributes('data-col')).toBe('1')
  })

  it('回退同样失败时 one-shot 仍按名义时长 emit finished（不卡死父级状态机）', async () => {
    const wrapper = mount(PetSprite, { props: { state: 'waving', size: 96 } })
    await wrapper.get('img').trigger('error')
    await wrapper.get('img').trigger('error')
    await nextTick()

    await vi.advanceTimersByTimeAsync(totalDuration('waving') - 1)
    expect(wrapper.emitted('finished')).toBeUndefined()
    await vi.advanceTimersByTimeAsync(1)
    expect(wrapper.emitted('finished')).toEqual([['waving']])
  })

  it('look 状态显示对应高清视线行的静态格，不启动循环 timer，也不等待加载', async () => {
    const wrapper = mount(PetSprite, { props: { state: 'look', lookDirection: 90, size: 96 } })
    const sprite = wrapper.get('.pet-sprite')
    expect(sprite.attributes('data-state')).toBe('look')
    expect(sprite.attributes('data-row')).toBe('9')
    expect(sprite.attributes('data-col')).toBe('4')
    expect(sprite.attributes('data-src')).toBe('look-row-9')
    expect(wrapper.get('img').attributes('src')).toBe('/pets/xinn/hd/look-row-9.webp')

    vi.advanceTimersByTime(5000)
    expect(sprite.attributes('data-col')).toBe('4')
  })

  it('卸载时清理 timer，不泄漏', async () => {
    const wrapper = mount(PetSprite, { props: { state: 'idle', size: 96 } })
    await loadSprite(wrapper)
    wrapper.unmount()
    vi.advanceTimersByTime(10000)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('页面 hidden 时暂停动画，恢复后从当前帧继续', async () => {
    const wrapper = mount(PetSprite, { props: { state: 'idle', size: 96 } })
    await loadSprite(wrapper)
    await vi.advanceTimersByTimeAsync(280)
    await nextTick()
    expect(wrapper.get('.pet-sprite').attributes('data-col')).toBe('1')

    Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true })
    document.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(10000)
    await nextTick()
    expect(wrapper.get('.pet-sprite').attributes('data-col')).toBe('1')

    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true })
    document.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(110)
    await nextTick()
    expect(wrapper.get('.pet-sprite').attributes('data-col')).toBe('2')
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

  it('新动作行加载后按各自逐帧时长推进并 emit 对应 id', async () => {
    const wrapper = mount(PetSprite, { props: { state: 'idle-curious', size: 96 } })
    expect(wrapper.get('img').attributes('src')).toBe('/pets/xinn/hd/idle-curious.webp')
    await loadSprite(wrapper)

    await vi.advanceTimersByTimeAsync(totalDuration('idle-curious') - 1)
    expect(wrapper.emitted('finished')).toBeUndefined()
    await vi.advanceTimersByTimeAsync(1)
    expect(wrapper.emitted('finished')).toEqual([['idle-curious']])
  })
})
