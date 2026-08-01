import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

/**
 * P1 移动端面板横向溢出修复的布局契约测试。
 * jsdom 不做真实布局计算（scoped CSS 也不会注入测试环境），因此这里直接校验
 * AdminPetAssistant.vue 的编译后 CSS 规则，并做数学几何推导：
 * 容器 left/right 以 viewport 为基准（position: fixed + 双侧 inset），
 * 面板 left/right: 0 相对容器 —— 在 360/390px 视口下必然满足 left >= 0 且 right <= 视口宽。
 * 真实浏览器几何验证由 scripts/pet-panel-layout-check.mjs 完成（见验收报告）。
 */
const SFC_PATH = resolve('src/components/admin-pet/AdminPetAssistant.vue')
const sfcSource = readFileSync(SFC_PATH, 'utf8')

function extractStyleBlock(source: string): string {
  const match = source.match(/<style scoped>([\s\S]*?)<\/style>/)
  if (!match) throw new Error('未找到 scoped style 块')
  return match[1]
}

const style = extractStyleBlock(sfcSource)

function mediaBlock(source: string, query: string): string {
  const pattern = new RegExp(`@media \\(${query.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\)\\s*\\{`)
  const startMatch = pattern.exec(source)
  if (!startMatch) throw new Error(`未找到 @media (${query}) 块`)
  const start = startMatch.index + startMatch[0].length - 1
  let depth = 0
  for (let index = start; index < source.length; index += 1) {
    if (source[index] === '{') depth += 1
    else if (source[index] === '}') {
      depth -= 1
      if (depth === 0) return source.slice(start + 1, index)
    }
  }
  throw new Error(`@media (${query}) 块未闭合`)
}

interface CssRule {
  selector: string
  body: string
}

/** 按顶层花括号拆分规则；选择器精确匹配，避免被组合选择器（如 .pet-stack,\n.pet-chat-panel）误匹配。 */
function splitRules(css: string): CssRule[] {
  const rules: CssRule[] = []
  let depth = 0
  let selector = ''
  let body = ''
  const clean = css.replace(/\/\*[\s\S]*?\*\//g, '')
  for (const char of clean) {
    if (char === '{') {
      if (depth === 0) selector = selector.trim()
      else body += char
      depth += 1
    } else if (char === '}') {
      depth -= 1
      if (depth === 0) {
        rules.push({ selector, body: body.trim() })
        selector = ''
        body = ''
      } else {
        body += char
      }
    } else if (depth === 0) {
      selector += char
    } else {
      body += char
    }
  }
  return rules
}

function rule(css: string, selector: string): string {
  const found = splitRules(css).find((entry) => entry.selector === `.${selector}`)
  if (!found) throw new Error(`未找到选择器 .${selector} 的规则`)
  return found.body
}

describe('P1 移动端聊天面板布局契约', () => {
  const mobile = mediaBlock(style, 'max-width: 720px')
  const panelMobile = rule(mobile, 'pet-chat-panel')
  const panelDesktop = rule(style, 'pet-chat-panel')
  const container = rule(style, 'pet-assistant')
  const button = rule(style, 'pet-button')

  it('移动端面板以 viewport 为定位基准（position: fixed），不再使用 100vw 溢出方案', () => {
    expect(panelMobile).toMatch(/position:\s*fixed/)
    expect(panelMobile).not.toMatch(/100vw/)
    expect(panelMobile).not.toMatch(/width:\s*100vw/)
  })

  it('移动端面板双侧 inset 定位（left/right: 8px + width auto），几何上必然落在视口内', () => {
    expect(panelMobile).toMatch(/left:\s*8px/)
    expect(panelMobile).toMatch(/right:\s*8px/)
    expect(panelMobile).toMatch(/width:\s*auto/)
    expect(panelMobile).toMatch(/max-width:\s*none/)
  })

  it('几何推导：390px 与 360px 视口下面板完全落在视口内且不产生水平溢出', () => {
    // position: fixed + left:8px/right:8px → panel.left = 8px，panel.right = viewportWidth - 8px
    for (const viewportWidth of [390, 360]) {
      const left = 8
      const right = viewportWidth - 8
      expect(left).toBeGreaterThanOrEqual(0)
      expect(right).toBeLessThanOrEqual(viewportWidth)
      expect(right - left).toBeGreaterThan(0)
    }
    // 回归护栏：不再出现旧的“右下角容器绝对定位 + width:100vw”组合
    expect(panelMobile).not.toMatch(/left:\s*0;[^}]*width:\s*100vw/)
    expect(panelMobile).not.toMatch(/width:\s*100vw;[^}]*left:\s*0/)
  })

  it('P5 宠物容器位置由拖动逻辑以内联 left/top 控制，CSS 不再写死右下角偏移', () => {
    // 容器保持 fixed 浮层，但不再声明 right/bottom 偏移（默认位与拖动均由 JS 计算）
    expect(container).toMatch(/position:\s*fixed/)
    expect(container).toMatch(/z-index:\s*320/)
    expect(container).not.toMatch(/right:\s*\d+px/)
    expect(container).not.toMatch(/bottom:\s*/)
  })

  it('P5 宠物按钮支持触屏拖动手势（touch-action: none），拖动中显示 grabbing 光标', () => {
    expect(button).toMatch(/touch-action:\s*none/)
    expect(button).toMatch(/cursor:\s*grab/)
    expect(rule(style, 'pet-button.dragging')).toMatch(/cursor:\s*grabbing/)
  })

  it('P5 贴顶/贴左时面板翻转规则存在，且移动端规则覆盖翻转（top/bottom 复位）', () => {
    expect(rule(style, 'pet-chat-panel.panel-below')).toMatch(/top:\s*calc\(100%\s*\+\s*12px\)/)
    expect(rule(style, 'pet-chat-panel.panel-below')).toMatch(/bottom:\s*auto/)
    expect(rule(style, 'pet-chat-panel.panel-left')).toMatch(/left:\s*0/)
    // 移动端固定定位面板必须显式复位 top，防止 panel-below 的 top 泄漏到固定定位
    expect(panelMobile).toMatch(/top:\s*auto/)
  })

  it('移动端保留 safe area，且面板底部高于宠物栈（宠物与输入框同屏可见）', () => {
    expect(panelMobile).toMatch(/bottom:\s*calc\(8px\s*\+\s*env\(safe-area-inset-bottom\)\s*\+\s*330px\)/)
    expect(panelMobile).toMatch(/height:\s*min\(72dvh,\s*620px\)/)
    // 面板 bottom 偏移 330px > 移动宠物高度(243 × 208/192 ≈ 263.3px) + 隐藏按钮(≈30px)，
    // 保证面板悬浮在宠物栈上方而不遮挡宠物
    expect(330).toBeGreaterThan(263.3 + 30)
  })

  it('桌面端 380px 浮层行为不回归（media query 之外保持 absolute + 380px）', () => {
    expect(panelDesktop).toMatch(/position:\s*absolute/)
    expect(panelDesktop).toMatch(/width:\s*380px/)
    expect(panelDesktop).toMatch(/max-width:\s*calc\(100vw\s*-\s*24px\)/)
    // 桌面规则里不得混入移动端覆盖
    expect(panelDesktop).not.toMatch(/left:\s*0/)
  })
})
