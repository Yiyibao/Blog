/**
 * 宠物图集动画元数据（Codex v2：8 列 × 11 行，每格 192 × 208，整图 1536 × 2288）。
 * 行号、有效帧数与逐帧时长以 hatch-pet 的 animation-rows.md 为准，
 * 透明未使用单元格不算动画帧。组件内部不再散落魔法数字。
 */
export const ATLAS = {
  spriteUrl: '/pets/xinn/spritesheet.webp',
  columns: 8,
  rows: 11,
  cellWidth: 192,
  cellHeight: 208,
  width: 1536,
  height: 2288,
} as const

export type PetState =
  | 'idle'
  | 'running-right'
  | 'running-left'
  | 'waving'
  | 'jumping'
  | 'failed'
  | 'waiting'
  | 'running'
  | 'review'

export interface AnimationSpec {
  row: number
  frames: number
  /** 逐帧停留毫秒；末帧结束后循环回第 0 帧（loop）或结束（one-shot）。 */
  durations: readonly number[]
  loop: boolean
}

export const PET_ANIMATIONS: Record<PetState, AnimationSpec> = {
  // row 0: cols 0-5, 280,110,110,140,140,320 —— 呼吸/眨眼循环
  idle: { row: 0, frames: 6, durations: [280, 110, 110, 140, 140, 320], loop: true },
  // row 1: cols 0-7，前 7 帧 120ms，末帧 220ms
  'running-right': { row: 1, frames: 8, durations: [120, 120, 120, 120, 120, 120, 120, 220], loop: true },
  // row 2: 同上（左向）
  'running-left': { row: 2, frames: 8, durations: [120, 120, 120, 120, 120, 120, 120, 220], loop: true },
  // row 3: cols 0-3, 140,140,140,280 —— 挥手一轮
  waving: { row: 3, frames: 4, durations: [140, 140, 140, 280], loop: false },
  // row 4: cols 0-4, 140,140,140,140,280
  jumping: { row: 4, frames: 5, durations: [140, 140, 140, 140, 280], loop: true },
  // row 5: cols 0-7，前 7 帧 140ms，末帧 240ms
  failed: { row: 5, frames: 8, durations: [140, 140, 140, 140, 140, 140, 140, 240], loop: false },
  // row 6: cols 0-5，前 5 帧 150ms，末帧 260ms
  waiting: { row: 6, frames: 6, durations: [150, 150, 150, 150, 150, 260], loop: true },
  // row 7: cols 0-5，前 5 帧 120ms，末帧 220ms
  running: { row: 7, frames: 6, durations: [120, 120, 120, 120, 120, 220], loop: true },
  // row 8: cols 0-5，前 5 帧 150ms，末帧 280ms
  review: { row: 8, frames: 6, durations: [150, 150, 150, 150, 150, 280], loop: false },
}

export interface CellPosition {
  row: number
  col: number
}

export interface LookDirectionSpec {
  row: number
  col: number
  degrees: number
}

/** 16 个顺时针视线方向：000° = 向上（12 点方向）；row 9 = 0°-157.5°，row 10 = 180°-337.5°。 */
export const LOOK_DIRECTIONS: readonly LookDirectionSpec[] = Array.from({ length: 16 }, (_, index) => {
  const degrees = index * 22.5
  return { degrees, row: index < 8 ? 9 : 10, col: index % 8 }
})

/** 把角度量化为 16 档视线单元格（0°=上，顺时针）。 */
export function lookDirectionIndex(degrees: number): number {
  const normalized = ((degrees % 360) + 360) % 360
  return Math.round(normalized / 22.5) % 16
}

export function lookCell(degrees: number): CellPosition {
  const spec = LOOK_DIRECTIONS[lookDirectionIndex(degrees)]
  return { row: spec.row, col: spec.col }
}

/** 一次动画从第 0 帧到最后一帧的累计时长（ms），one-shot 结束时机以此为准。 */
export function totalDuration(state: PetState): number {
  return PET_ANIMATIONS[state].durations.reduce((sum, value) => sum + value, 0)
}
