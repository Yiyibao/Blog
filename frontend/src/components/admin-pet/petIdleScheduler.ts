import { IDLE_ACTIONS, randomIdleAction } from './petAnimations';
import type { IdleActionId } from './petAnimations';

export type { IdleActionId } from './petAnimations';

/**
 * 宠物待机调度器（纯逻辑，不依赖 DOM）。
 *
 * 状态机：disabled → counting → playing-idle-action → counting
 * - counting：单一 setTimeout 计时；到点均匀随机选择一组待机动作。
 * - playing：动作播放期间不存在第二个计时器；由父级在动画 finished 后调用
 *   handleActionFinished 回到 counting。
 * - 触发间隔由 nextIntervalMs 注入（默认 12-36 秒均匀随机，比固定 30 秒稍频繁），
 *   每次重新计时都生成新的随机间隔。
 * - 任意打断（hover / 点击 / 拖动 / 面板 / 流式 / 页面 hidden / 登出 / 卸载）调用
 *   stop() 或 restart()：清零计时；播放中的动作经 onCancel 通知父级取消。
 * - 随机函数依赖注入：生产 Math.random，测试注入固定值。
 */
export interface PetIdleSchedulerOptions {
  /** 返回下一次待机触发的等待毫秒数（每次重新计时时调用）。 */
  nextIntervalMs: (random: () => number) => number;
  random?: () => number;
  onStart: (action: IdleActionId) => void;
  onFinish: (action: IdleActionId) => void;
  onCancel: (action: IdleActionId) => void;
}

export type IdleSchedulerPhase = 'disabled' | 'counting' | 'playing';

export class PetIdleScheduler {
  private _phase: IdleSchedulerPhase = 'disabled';
  private timer: ReturnType<typeof setTimeout> | undefined;
  private currentAction: IdleActionId | null = null;
  private readonly options: PetIdleSchedulerOptions;

  constructor(options: PetIdleSchedulerOptions) {
    this.options = options;
  }

  get phase(): IdleSchedulerPhase {
    return this._phase;
  }

  get playingAction(): IdleActionId | null {
    return this.currentAction;
  }

  /** 从零开始计时（已处于 counting 也重新归零）；播放中先取消动作。 */
  restart() {
    this.cancelPlaying();
    this.clearTimer();
    this._phase = 'counting';
    this.startTimer();
  }

  /** 停止并清零（播放中的动作会被取消）；条件恢复后由 restart 重新计时。 */
  stop() {
    this.cancelPlaying();
    this.clearTimer();
    this._phase = 'disabled';
  }

  /** 待机动作完整播放结束：回到 counting 并从零重新计时。 */
  handleActionFinished(action: IdleActionId) {
    if (this._phase !== 'playing' || this.currentAction !== action) return;
    this.currentAction = null;
    this._phase = 'counting';
    this.options.onFinish(action);
    this.startTimer();
  }

  /** 幂等销毁：等价 stop()，供组件卸载调用。 */
  dispose() {
    this.stop();
  }

  private startTimer() {
    if (this._phase !== 'counting') return;
    this.clearTimer();
    const random = this.options.random ?? Math.random;
    const delay = Math.max(1, Math.round(this.options.nextIntervalMs(random)));
    this.timer = setTimeout(() => {
      this.timer = undefined;
      if (this._phase !== 'counting') return;
      const action = randomIdleAction(this.options.random);
      this.currentAction = action;
      this._phase = 'playing';
      this.options.onStart(action);
    }, delay);
  }

  private cancelPlaying() {
    if (this._phase === 'playing' && this.currentAction) {
      const action = this.currentAction;
      this.currentAction = null;
      this._phase = 'disabled';
      this.options.onCancel(action);
    }
  }

  private clearTimer() {
    if (this.timer !== undefined) {
      clearTimeout(this.timer);
      this.timer = undefined;
    }
  }
}

/** 默认触发间隔：12-36 秒均匀随机（平均 24 秒，比固定 30 秒稍频繁）。 */
export const IDLE_INTERVAL_RANGE: readonly [number, number] = [12_000, 36_000];

function defaultNextInterval(random: () => number): number {
  const [min, max] = IDLE_INTERVAL_RANGE;
  return min + random() * (max - min);
}

/** 生产入口：随机间隔待机，Math.random 均匀随机。 */
export function createIdleScheduler(
  options: Partial<Omit<PetIdleSchedulerOptions, 'onStart' | 'onFinish' | 'onCancel'>> &
    Pick<PetIdleSchedulerOptions, 'onStart' | 'onFinish' | 'onCancel'>,
): PetIdleScheduler {
  return new PetIdleScheduler({
    nextIntervalMs: options.nextIntervalMs ?? defaultNextInterval,
    random: options.random ?? Math.random,
    onStart: options.onStart,
    onFinish: options.onFinish,
    onCancel: options.onCancel,
  });
}

export { IDLE_ACTIONS };
