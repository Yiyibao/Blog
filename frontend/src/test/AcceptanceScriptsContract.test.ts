// @vitest-environment node
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const scriptsDir = resolve(process.cwd(), 'scripts');
const acceptance = readFileSync(resolve(scriptsDir, 'acceptance-run.mjs'), 'utf8');
const realChat = readFileSync(resolve(scriptsDir, 'real-chat-check.mjs'), 'utf8');
const layout = readFileSync(resolve(scriptsDir, 'pet-panel-layout-check.mjs'), 'utf8');
const assetCheck = readFileSync(resolve(scriptsDir, 'pet-animation-asset-check.mjs'), 'utf8');

describe('真实验收脚本安全契约', () => {
  it('不终止启动前已经存在的端口监听进程', () => {
    expect(acceptance).toContain('拒绝终止既有进程');
    expect(acceptance).not.toContain('started.push({ pid: listener.pid');
    expect(acceptance).not.toContain("taskkill.exe', ['/IM'");
  });

  it('只清理仍存活的本轮 child，避免退出后的 PID 被复用', () => {
    expect(acceptance).toContain('entry.child?.exitCode === null');
    expect(acceptance).toContain('entry.child?.signalCode === null');
    expect(acceptance).toContain('started.push({ pid: child.pid, name, child })');
  });

  it('本地健康探针单次结算并清除超时定时器', () => {
    expect(acceptance).toContain('if (settled) return');
    expect(acceptance).toContain('if (timer) clearTimeout(timer)');
    expect(acceptance).toContain("code: 'ETIMEDOUT'");
  });

  it('固定端口、严格模式和绝对工具路径均保留', () => {
    expect(acceptance).toContain("const MAVEN_CMD = 'C:\\\\Program Files\\\\apache-maven");
    expect(acceptance).toContain("const NPM_CMD = 'C:\\\\Program Files\\\\nodejs\\\\npm.cmd'");
    expect(acceptance).toContain('--strictPort');
  });

  it('OpenCode 本地验收密码每轮随机生成且只经子进程环境变量传递', () => {
    const legacyFixedPassword = ['local', 'verify', 'pass'].join('-');
    expect(acceptance).toContain('randomBytes(24)');
    expect(acceptance).toContain('REAL_CHAT_OPENCODE_PASSWORD: OPENCODE_PASS');
    expect(acceptance).not.toContain(legacyFixedPassword);
    expect(realChat).toContain('process.env.REAL_CHAT_OPENCODE_PASSWORD');
    expect(realChat).not.toContain(legacyFixedPassword);
  });

  it('真实聊天与布局检查先激活页面，并严格验证实体焦点环', () => {
    for (const source of [realChat, layout]) {
      expect(source).toContain("send('Page.bringToFront')");
      expect(source).toContain("outlineStyle !== 'none'");
      expect(source).toContain('parseFloat(');
    }
    expect(realChat).toContain('document.activeElement === el');
  });

  it('素材结构检查：像素级契约、15 行元数据与安全清理齐全', () => {
    // 用与 petAnimations.ts 同源的 15 行元数据做双向校验（键可能带引号或不带）
    for (const rowId of [
      'idle',
      'running-right',
      'running-left',
      'waving',
      'jumping',
      'failed',
      'waiting',
      'running',
      'review',
      'look-row-9',
      'look-row-10',
      'idle-curious',
      'idle-sleeve',
      'idle-sway',
      'chat-open',
    ]) {
      expect(assetCheck).toMatch(new RegExp(`['"]?${rowId}['"]?\\s*:`));
    }
    expect(assetCheck).toContain('3072');
    expect(assetCheck).toContain('416');
    expect(assetCheck).toContain('SAFE_MARGIN');
    expect(assetCheck).toContain('getImageData');
    expect(assetCheck).toContain('verifyNativeHdProvenance');
    expect(assetCheck).toContain('manifest.placeholder !== false');
    expect(assetCheck).toContain('minimumSourcePoseHeight < 384');
    expect(assetCheck).toContain('pet-hd-placeholder-assets.py');
    // 生命周期契约：统一清理浏览器会话与残留 profile，不终止既有进程
    expect(assetCheck).toContain('initBrowserSession');
    expect(assetCheck).toContain('cleanupStaleProfiles');
    expect(assetCheck).not.toContain('taskkill.exe');
    expect(assetCheck).toContain('process.exitCode = 1');
  });
});
