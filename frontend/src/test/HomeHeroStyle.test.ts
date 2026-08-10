import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const source = readFileSync(resolve('src/base.css'), 'utf8');

function rule(selector: string) {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const match = source.match(new RegExp(`${escaped}\\s*\\{([\\s\\S]*?)\\}`));
  if (!match) throw new Error(`Missing CSS rule: ${selector}`);
  return match[1];
}

describe('首页首屏视觉回归', () => {
  it('恢复旧版无玻璃大容器的沉浸式标题布局', () => {
    const heroCopy = rule('.site-shell:not(.admin-mode) .hero-stage .hero-copy');
    expect(heroCopy).toContain('width: min(1280px, calc(100% - 48px))');
    expect(heroCopy).not.toMatch(/background:|backdrop-filter:|border-radius:|box-shadow:/);
  });

  it('滚动提示使用轻量文字与细线，不再显示白色胶囊', () => {
    const scrollCue = rule('.site-shell:not(.admin-mode) .hero-stage .hero-scroll');
    expect(scrollCue).toContain('color: rgba(255, 255, 255, 0.76)');
    expect(scrollCue).not.toMatch(/background:|border-radius:\s*999px/);

    const scrollLine = rule('.site-shell:not(.admin-mode) .hero-stage .hero-scroll i');
    expect(scrollLine).toContain('height: 32px');
  });
});
