import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { defineComponent } from 'vue';
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';
import { createRouter, createMemoryHistory } from 'vue-router';
import { createPinia, setActivePinia } from 'pinia';
import AdminAiChat from '../components/AdminAiChat.vue';

enableAutoUnmount(afterEach);

const CHAT_SFC = readFileSync(resolve('src/components/AdminAiChat.vue'), 'utf8');
const ADMIN_CSS = readFileSync(resolve('src/admin.css'), 'utf8');
const TOKENS_CSS = readFileSync(resolve('src/tokens.css'), 'utf8');

function extractStyleBlock(source: string): string {
  const match = source.match(/<style scoped>([\s\S]*?)<\/style>/);
  if (!match) throw new Error('未找到 scoped style 块');
  return match[1];
}

const style = extractStyleBlock(CHAT_SFC);

/** 提取 token 定义：`--name: value;`（含 :root.dark 块内） */
function extractTokenValues(css: string, dark: boolean): Record<string, string> {
  const block = dark
    ? (css.match(/:root\.dark\s*\{([\s\S]*?)\}/)?.[1] ?? '')
    : (css.match(/:root\s*\{([\s\S]*?)\}/)?.[1] ?? '');
  const values: Record<string, string> = {};
  for (const match of block.matchAll(/(--[\w-]+)\s*:\s*([^;]+);/g)) {
    values[match[1]] = match[2].trim();
  }
  return values;
}

/** WCAG 相对亮度与对比度 */
function luminance(hex: string): number {
  const normalized = hex.replace('#', '');
  const channels = [0, 2, 4].map((offset) => {
    const value = parseInt(normalized.slice(offset, offset + 2), 16) / 255;
    return value <= 0.03928 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2];
}

function contrastRatio(a: string, b: string): number {
  const [lighter, darker] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (lighter + 0.05) / (darker + 0.05);
}

/** 找出所有硬编码浅色值（背景类）出现的位置，必须全部在 var() fallback 内 */
function hardcodedLightValues(css: string): string[] {
  const hits: string[] = [];
  for (const value of ['rgba(255, 255, 255', '#ffffff', '#faf8f5', '#20211e']) {
    const index = css.indexOf(value);
    if (index < 0) continue;
    const prefix = css.slice(Math.max(0, index - 60), index);
    // 允许的唯一形式：位于 var(--token, <fallback>) 内部
    const insideVarFallback = /var\(\s*--[\w-]+\s*,\s*$/.test(prefix);
    if (!insideVarFallback) hits.push(`${value} @ ${index}`);
  }
  return hits;
}

describe('P1 深色主题：聊天区域 token 化契约', () => {
  it('聊天 scoped style 中不存在硬编码浅色背景/文字（除 var() fallback）', () => {
    const hits = hardcodedLightValues(style);
    expect(hits, `硬编码浅色值: ${hits.join('; ')}`).toEqual([]);
  });

  it('关键区域全部改用项目主题 token', () => {
    expect(style).toMatch(/\.ai-chat-container \{[\s\S]*?background: var\(--surface-solid/);
    expect(style).toMatch(/\.assistant \.bubble-content \{[\s\S]*?background: var\(--surface-solid,/);
    expect(style).toMatch(/\.chat-input-area \{[\s\S]*?background: var\(--surface-solid,/);
    expect(style).toMatch(/\.chat-textarea \{[\s\S]*?background: var\(--surface,/);
    expect(style).toMatch(/\.chat-textarea \{[\s\S]*?color: var\(--ink,/);
    expect(style).toMatch(/\.user \.bubble-content \{[\s\S]*?background: var\(--ink,/);
    expect(style).toMatch(/\.user \.bubble-content \{[\s\S]*?color: var\(--paper,/);
    expect(style).toMatch(/\.chat-model-select \{[\s\S]*?background: var\(--surface,/);
    expect(style).toMatch(/\.chat-model-select \{[\s\S]*?color: var\(--ink,/);
    expect(style).toMatch(/\.send-btn \{[\s\S]*?background: var\(--ink,/);
    expect(style).toMatch(/\.send-btn \{[\s\S]*?color: var\(--paper,/);
    // 边框/焦点态
    expect(style).toMatch(/\.chat-textarea:focus \{[\s\S]*?border-color: var\(--accent,/);
    expect(style).toMatch(
      /\.chat-textarea:focus \{[\s\S]*?box-shadow: 0 0 0 3px color-mix\(in srgb, var\(--accent,/,
    );
    expect(style).toMatch(
      /\.chat-textarea:focus \{[\s\S]*?outline: 2px solid color-mix\(in srgb, var\(--accent,/,
    );
  });

  it('用到的 token 在 tokens.css 的 :root.dark 中都有定义（深色可计算）', () => {
    const darkTokens = extractTokenValues(TOKENS_CSS, true);
    for (const token of [
      '--surface',
      '--surface-solid',
      '--ink',
      '--muted',
      '--line',
      '--line-strong',
      '--accent',
      '--paper',
    ]) {
      expect(darkTokens[token], `缺少 ${token} 的深色定义`).toBeTruthy();
    }
  });

  it('admin.css 为 --console-* 提供 :root.dark 覆盖', () => {
    expect(ADMIN_CSS).toMatch(/:root\.dark \.admin-console \{[\s\S]*?--console-ink: #f2efe7/);
    expect(ADMIN_CSS).toMatch(/:root\.dark \.admin-console \{[\s\S]*?--console-muted:/);
    expect(ADMIN_CSS).toMatch(/:root\.dark \.admin-console \{[\s\S]*?--console-line:/);
  });
});

describe('P1 深色主题：token 对比度数学验证', () => {
  const lightTokens = extractTokenValues(TOKENS_CSS, false);
  const darkTokens = extractTokenValues(TOKENS_CSS, true);

  it('浅色主题：正文/次要文字与表面背景对比度达标', () => {
    expect(contrastRatio(lightTokens['--ink'], lightTokens['--surface-solid'])).toBeGreaterThanOrEqual(7);
    expect(contrastRatio(lightTokens['--muted'], lightTokens['--surface-solid'])).toBeGreaterThanOrEqual(4.5);
  });

  it('深色主题：正文/次要文字与表面背景对比度达标（非低可读组合）', () => {
    expect(contrastRatio(darkTokens['--ink'], darkTokens['--surface-solid'])).toBeGreaterThanOrEqual(7);
    expect(contrastRatio(darkTokens['--muted'], darkTokens['--surface-solid'])).toBeGreaterThanOrEqual(4.5);
  });

  it('深色主题：用户气泡（--ink 底 + --paper 字）与助手气泡（--surface-solid 底 + --ink 字）对比度达标', () => {
    expect(contrastRatio(darkTokens['--paper'], darkTokens['--ink'])).toBeGreaterThanOrEqual(7);
    expect(contrastRatio(darkTokens['--ink'], darkTokens['--surface-solid'])).toBeGreaterThanOrEqual(7);
  });

  it('深色主题：输入框文字与背景非同一颜色', () => {
    expect(darkTokens['--ink']).not.toBe(darkTokens['--surface-solid']);
    expect(darkTokens['--ink']).not.toBe(darkTokens['--surface']);
  });
});

describe('P1 双形态渲染结构（compact 与 full-page 共用同一套 token 规则）', () => {
  const ChatSidebarStub = defineComponent({
    name: 'AdminSidebar',
    template: '<div class="sidebar-stub" />',
  });

  function createTestRouter() {
    return createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'home', component: { template: '<div />' } },
        { path: '/admin/login', name: 'admin-login', component: { template: '<div />' } },
        { path: '/admin', name: 'admin', component: { template: '<div />' } },
      ],
    });
  }

  beforeEach(() => {
    setActivePinia(createPinia());
    window.sessionStorage.clear();
  });

  it('紧凑形态（宠物面板）与全屏形态渲染同一组关键区域选择器', async () => {
    const compact = mount(AdminAiChat, {
      props: { compact: true },
      global: { plugins: [createTestRouter()], stubs: { AdminSidebar: ChatSidebarStub } },
    });
    const full = mount(AdminAiChat, {
      props: { compact: false },
      global: { plugins: [createTestRouter()], stubs: { AdminSidebar: ChatSidebarStub } },
    });
    await flushPromises();

    for (const wrapper of [compact, full]) {
      expect(wrapper.find('.ai-chat-container').exists()).toBe(true);
      expect(wrapper.find('.chat-input-area').exists()).toBe(true);
      expect(wrapper.find('[data-testid="ai-chat-input"]').exists()).toBe(true);
    }
    // 两种形态由同一份 scoped style 驱动：顶层（非嵌套、非媒体查询内）的背景规则只定义一次
    const occurrences = (style.match(/\n\.ai-chat-container \{/g) ?? []).length;
    expect(occurrences).toBe(1);
    expect(style).toMatch(/\n\.chat-textarea \{[\s\S]*?background: var\(--surface,/);
    expect(style).not.toMatch(/\n\.chat-textarea \{[\s\S]*?background: #faf8f5/);
  });
});
