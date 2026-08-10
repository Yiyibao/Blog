import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import {
  PetIdleScheduler,
  createIdleScheduler,
  IDLE_INTERVAL_RANGE,
} from '../components/admin-pet/petIdleScheduler';
import type { IdleActionId } from '../components/admin-pet/petIdleScheduler';

/** 测试默认：固定 30 秒间隔，便于 29_999/30_000 边界验证。 */
const FIXED_MS = 30_000;

function makeScheduler(
  random: () => number = Math.random,
  nextIntervalMs: (random: () => number) => number = () => FIXED_MS,
) {
  const started: IdleActionId[] = [];
  const finished: IdleActionId[] = [];
  const cancelled: IdleActionId[] = [];
  const scheduler = new PetIdleScheduler({
    nextIntervalMs,
    random,
    onStart: (action) => started.push(action),
    onFinish: (action) => finished.push(action),
    onCancel: (action) => cancelled.push(action),
  });
  return { scheduler, started, finished, cancelled };
}

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

describe('PetIdleScheduler 随机间隔待机调度', () => {
  it('29_999ms 不触发，30_000ms 恰好触发一次', () => {
    const { scheduler, started } = makeScheduler(() => 0);
    scheduler.restart();
    expect(scheduler.phase).toBe('counting');

    vi.advanceTimersByTime(FIXED_MS - 1);
    expect(started).toHaveLength(0);
    expect(scheduler.phase).toBe('counting');

    vi.advanceTimersByTime(1);
    expect(started).toEqual(['idle-curious']);
    expect(scheduler.phase).toBe('playing');
  });

  it('固定随机值 0 / 0.34 / 0.67 分别选择三组动作', () => {
    for (const [value, expected] of [
      [0, 'idle-curious'],
      [0.34, 'idle-sleeve'],
      [0.67, 'idle-sway'],
    ] as const) {
      const { scheduler, started } = makeScheduler(() => value);
      scheduler.restart();
      vi.advanceTimersByTime(FIXED_MS);
      expect(started).toEqual([expected]);
    }
  });

  it('动作播放期间不再创建第二个 timer（同一时刻最多 1 个）', () => {
    const { scheduler, started } = makeScheduler(() => 0);
    scheduler.restart();
    expect(vi.getTimerCount()).toBe(1);
    vi.advanceTimersByTime(FIXED_MS);
    expect(started).toHaveLength(1);
    // playing 阶段无计时器
    expect(vi.getTimerCount()).toBe(0);

    // 计时器已在动作开始时清掉：继续等待也不会重复触发
    vi.advanceTimersByTime(FIXED_MS * 3);
    expect(started).toHaveLength(1);
  });

  it('finished 后从零计时：完成后 29_999ms 不触发，下一毫秒触发', () => {
    const { scheduler, started, finished } = makeScheduler(() => 0);
    scheduler.restart();
    vi.advanceTimersByTime(FIXED_MS);
    expect(started).toHaveLength(1);

    scheduler.handleActionFinished('idle-curious');
    expect(scheduler.phase).toBe('counting');
    expect(finished).toEqual(['idle-curious']);
    expect(vi.getTimerCount()).toBe(1);

    vi.advanceTimersByTime(FIXED_MS - 1);
    expect(started).toHaveLength(1);
    vi.advanceTimersByTime(1);
    expect(started).toHaveLength(2);
    expect(scheduler.phase).toBe('playing');
  });

  it('触发间隔由 nextIntervalMs 注入，每次重新计时都重新生成', () => {
    const calls: number[] = [];
    const nextIntervalMs = (random: () => number) => {
      const value = 10_000 + random() * 20_000;
      calls.push(value);
      return value;
    };
    const { scheduler, started } = makeScheduler(() => 0.5, nextIntervalMs);
    scheduler.restart();
    // 10_000 + 0.5 * 20_000 = 20_000；random 0.5 → idle-sleeve
    vi.advanceTimersByTime(19_999);
    expect(started).toHaveLength(0);
    vi.advanceTimersByTime(1);
    expect(started).toEqual(['idle-sleeve']);
    expect(calls).toHaveLength(1);

    // 动作完成 → 重新计时 → 生成新的随机间隔（注入 random 恒定 0.5 → 仍是 20s）
    scheduler.handleActionFinished('idle-sleeve');
    expect(calls).toHaveLength(2);
    vi.advanceTimersByTime(20_000);
    expect(started).toHaveLength(2);
  });

  it('createIdleScheduler 默认间隔落在 12-36 秒随机区间且使用 Math.random', () => {
    const started: IdleActionId[] = [];
    const scheduler = createIdleScheduler({
      onStart: (action) => started.push(action),
      onFinish: () => {},
      onCancel: () => {},
    });
    scheduler.restart();
    const [min, max] = IDLE_INTERVAL_RANGE;
    expect(max).toBeGreaterThan(min);
    vi.advanceTimersByTime(min - 1);
    expect(started).toHaveLength(0);
    // 随机间隔 ≤ 36 秒：36 秒内必触发
    vi.advanceTimersByTime(max - min + 1);
    expect(started).toHaveLength(1);
  });

  it('hover（stop 清零）长时间不触发；leave（restart）后重新完整等待', () => {
    const { scheduler, started, cancelled } = makeScheduler(() => 0);
    scheduler.restart();
    vi.advanceTimersByTime(20_000);

    // pointerenter：stop 清零计时
    scheduler.stop();
    expect(vi.getTimerCount()).toBe(0);
    vi.advanceTimersByTime(60_000);
    expect(started).toHaveLength(0);
    expect(cancelled).toHaveLength(0);

    // pointerleave：restart 从零重新完整等待 → 完整 30 秒后才触发
    scheduler.restart();
    vi.advanceTimersByTime(FIXED_MS - 1);
    expect(started).toHaveLength(0);
    vi.advanceTimersByTime(1);
    expect(started).toEqual(['idle-curious']);
  });

  it('hover 能立即取消正在播放的待机动作', () => {
    const { scheduler, started, cancelled } = makeScheduler(() => 0);
    scheduler.restart();
    vi.advanceTimersByTime(FIXED_MS);
    expect(started).toEqual(['idle-curious']);

    // 动作播放 5 秒后被 reset 打断 → onCancel
    vi.advanceTimersByTime(5_000);
    scheduler.restart();
    expect(cancelled).toEqual(['idle-curious']);
    expect(scheduler.phase).toBe('counting');
    expect(started).toHaveLength(1);
  });

  it('stop 清理计时器与动作；restart 从零恢复，不沿用暂停前剩余时间', () => {
    const { scheduler, started, cancelled } = makeScheduler(() => 0);
    scheduler.restart();
    vi.advanceTimersByTime(20_000);
    scheduler.stop();
    expect(vi.getTimerCount()).toBe(0);
    expect(scheduler.phase).toBe('disabled');

    // 若 20 秒剩余时间被沿用，则 10 秒后即触发；实际必须再等完整 30 秒
    scheduler.restart();
    vi.advanceTimersByTime(20_000);
    expect(started).toHaveLength(0);
    vi.advanceTimersByTime(10_000);
    expect(started).toEqual(['idle-curious']);
    expect(cancelled).toHaveLength(0);
  });

  it('点击/拖动/面板/流式等任意打断统一走 stop，条件恢复后从零开始', () => {
    const { scheduler, started } = makeScheduler(() => 0);
    for (let index = 0; index < 5; index += 1) {
      scheduler.restart();
      vi.advanceTimersByTime(10_000);
      scheduler.stop();
      expect(vi.getTimerCount()).toBe(0);
    }
    // 全部重置后，最后一个 restart 仍然要求完整等待
    scheduler.restart();
    vi.advanceTimersByTime(FIXED_MS - 1);
    expect(started).toHaveLength(0);
    vi.advanceTimersByTime(1);
    expect(started).toHaveLength(1);
  });

  it('任意事件序列下待机 timer 不超过 1', () => {
    const { scheduler } = makeScheduler(() => 0.5);
    scheduler.restart();
    expect(vi.getTimerCount()).toBe(1);
    vi.advanceTimersByTime(5000);
    scheduler.stop();
    scheduler.restart();
    expect(vi.getTimerCount()).toBe(1);
    vi.advanceTimersByTime(FIXED_MS);
    expect(vi.getTimerCount()).toBe(0);
    scheduler.handleActionFinished('idle-sleeve');
    expect(vi.getTimerCount()).toBe(1);
    vi.advanceTimersByTime(FIXED_MS);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('dispose 幂等清理', () => {
    const { scheduler } = makeScheduler(() => 0);
    scheduler.restart();
    vi.advanceTimersByTime(FIXED_MS);
    scheduler.dispose();
    scheduler.dispose();
    expect(vi.getTimerCount()).toBe(0);
    expect(scheduler.phase).toBe('disabled');
  });

  it('不匹配的 finished 事件被忽略', () => {
    const { scheduler, finished } = makeScheduler(() => 0);
    scheduler.restart();
    vi.advanceTimersByTime(FIXED_MS);
    scheduler.handleActionFinished('idle-sway');
    expect(finished).toHaveLength(0);
    expect(scheduler.phase).toBe('playing');
  });
});
