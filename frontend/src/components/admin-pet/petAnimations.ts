/**
 * 宠物动画元数据。
 *
 * 两层素材：
 * - ATLAS / LEGACY_ROWS：Codex v2 标准 8×11 图集（192×208，1536×2288）——
 *   身份与动作语义基线，也是高清行加载失败时的回退源。
 * - HD_ROWS：网站专用 2× 高清行图（每行 3072×416，8 格，单格 384×416，
 *   目录 /pets/xinn/hd/）。行号、有效帧数与逐帧时长以 hatch-pet 的
 *   animation-rows.md 为准；透明未使用单元格不算动画帧。
 *
 * 组件对外的显示尺寸（307/243）与素材像素尺寸分离：PetSprite 依据
 * sourceCellWidth/sourceCellHeight 计算裁切与缩放，不再硬编码 192×208。
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

/** 2× 高清行图几何（每文件一行 8 格）。 */
export const HD_ROW = {
  columns: 8,
  rows: 1,
  cellWidth: 384,
  cellHeight: 416,
  width: 3072,
  height: 416,
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
  | 'idle-curious'
  | 'idle-sleeve'
  | 'idle-sway'
  | 'chat-open'

export type IdleActionId = 'idle-curious' | 'idle-sleeve' | 'idle-sway'

export type AnimationKind = 'standard' | 'idle-action' | 'interaction'

export interface SpriteSource {
  url: string
  cellWidth: number
  cellHeight: number
  columns: number
  rows: number
}

export interface AnimationSpec {
  source: SpriteSource
  frames: number
  /** 逐帧停留毫秒；末帧结束后循环回第 0 帧（loop）或结束（one-shot）。 */
  durations: readonly number[]
  loop: boolean
  kind: AnimationKind
}

export const HD_SOURCE = (rowId: string): SpriteSource => ({
  url: `/pets/xinn/hd/${rowId}.webp`,
  cellWidth: HD_ROW.cellWidth,
  cellHeight: HD_ROW.cellHeight,
  columns: HD_ROW.columns,
  rows: HD_ROW.rows,
})

export const LEGACY_SOURCE: SpriteSource = {
  url: ATLAS.spriteUrl,
  cellWidth: ATLAS.cellWidth,
  cellHeight: ATLAS.cellHeight,
  columns: ATLAS.columns,
  rows: ATLAS.rows,
}

/** 15 个高清行 id（9 标准 + 2 视线 + 3 待机 + 1 点击）。 */
export type HdRowId =
  | PetState
  | 'look-row-9'
  | 'look-row-10'

export interface HdRowMeta {
  id: HdRowId
  frames: number
  durations: readonly number[] | null
  loop: boolean
  kind: AnimationKind
  /** 对应标准图集行（身份基线 / 回退行）。 */
  legacyRow: number
}

const HD_STANDARD: Record<string, { legacyRow: number; frames: number; durations: readonly number[]; loop: boolean }> = {
  // row 0: cols 0-5, 280,110,110,140,140,320 —— 呼吸/眨眼循环
  idle: { legacyRow: 0, frames: 6, durations: [280, 110, 110, 140, 140, 320], loop: true },
  // row 1: cols 0-7，前 7 帧 120ms，末帧 220ms
  'running-right': { legacyRow: 1, frames: 8, durations: [120, 120, 120, 120, 120, 120, 120, 220], loop: true },
  // row 2: 同上（左向）
  'running-left': { legacyRow: 2, frames: 8, durations: [120, 120, 120, 120, 120, 120, 120, 220], loop: true },
  // row 3: cols 0-3, 140,140,140,280 —— 挥手一轮
  waving: { legacyRow: 3, frames: 4, durations: [140, 140, 140, 280], loop: false },
  // row 4: cols 0-4, 140,140,140,140,280
  jumping: { legacyRow: 4, frames: 5, durations: [140, 140, 140, 140, 280], loop: true },
  // row 5: cols 0-7，前 7 帧 140ms，末帧 240ms
  failed: { legacyRow: 5, frames: 8, durations: [140, 140, 140, 140, 140, 140, 140, 240], loop: false },
  // row 6: cols 0-5，前 5 帧 150ms，末帧 260ms
  waiting: { legacyRow: 6, frames: 6, durations: [150, 150, 150, 150, 150, 260], loop: true },
  // row 7: cols 0-5，前 5 帧 120ms，末帧 220ms
  running: { legacyRow: 7, frames: 6, durations: [120, 120, 120, 120, 120, 220], loop: true },
  // row 8: cols 0-5，前 5 帧 150ms，末帧 280ms
  review: { legacyRow: 8, frames: 6, durations: [150, 150, 150, 150, 150, 280], loop: false },
}

/** 待机动作（30 秒未悬浮时随机播放一轮）。 */
const HD_IDLE_ACTIONS: Record<IdleActionId, { frames: number; durations: readonly number[] }> = {
  // 好奇眨眼歪头：正常呼吸 → 轻眨眼 → 眼睛看向一侧 → 轻歪头 → 发饰流苏跟随 → 回弹 → 中立
  'idle-curious': { frames: 8, durations: [180, 140, 140, 220, 180, 180, 240, 320] },
  // 害羞整理袖口：视线下移 → 双手靠近 → 整理袖口/披帛 → 温柔笑意 → 放手 → 袖摆跟随 → 中立
  'idle-sleeve': { frames: 8, durations: [200, 180, 180, 220, 200, 180, 260, 360] },
  // 轻踮脚裙摆摇曳：重心轻移 → 脚跟微抬 → 轻摆 → 裙摆披帛迟滞跟随 → 回弹 → 站稳收束
  'idle-sway': { frames: 8, durations: [160, 160, 180, 180, 180, 180, 220, 340] },
}

/** 点击聊天面板：欣喜迎接小欠身（方向中性，衔接 waiting）。 */
const CHAT_OPEN: { frames: number; durations: readonly number[] } = {
  frames: 8,
  durations: [90, 90, 110, 130, 150, 160, 180, 220],
}

/** 15 个高清行元数据（id → 行图 + 帧时长 + 回退行）。 */
export const HD_ROWS: Record<HdRowId, HdRowMeta> = {
  ...Object.fromEntries(
    Object.entries(HD_STANDARD).map(([id, spec]) => [
      id,
      { id, frames: spec.frames, durations: spec.durations, loop: spec.loop, kind: 'standard' as const, legacyRow: spec.legacyRow },
    ]),
  ),
  'look-row-9': { id: 'look-row-9', frames: 8, durations: null, loop: false, kind: 'standard', legacyRow: 9 },
  'look-row-10': { id: 'look-row-10', frames: 8, durations: null, loop: false, kind: 'standard', legacyRow: 10 },
  'idle-curious': { id: 'idle-curious', frames: 8, durations: HD_IDLE_ACTIONS['idle-curious'].durations, loop: false, kind: 'idle-action', legacyRow: 0 },
  'idle-sleeve': { id: 'idle-sleeve', frames: 8, durations: HD_IDLE_ACTIONS['idle-sleeve'].durations, loop: false, kind: 'idle-action', legacyRow: 0 },
  'idle-sway': { id: 'idle-sway', frames: 8, durations: HD_IDLE_ACTIONS['idle-sway'].durations, loop: false, kind: 'idle-action', legacyRow: 0 },
  'chat-open': { id: 'chat-open', frames: 8, durations: CHAT_OPEN.durations, loop: false, kind: 'interaction', legacyRow: 0 },
} as Record<HdRowId, HdRowMeta>

/** 动画状态 → 高清行（look 不在其中，视线单独映射）。 */
export const PET_HD_ANIMATIONS: Record<PetState, AnimationSpec> = Object.fromEntries(
  (Object.keys(HD_ROWS) as HdRowId[])
    .filter((id) => id !== 'look-row-9' && id !== 'look-row-10')
    .map((id) => [id, {
      source: HD_SOURCE(id),
      frames: HD_ROWS[id].frames,
      durations: HD_ROWS[id].durations!,
      loop: HD_ROWS[id].loop,
      kind: HD_ROWS[id].kind,
    }]),
) as Record<PetState, AnimationSpec>

/** 旧 8×11 图集的标准行元数据（身份基线 / 高清失败时的回退源）。 */
export interface LegacyRowSpec {
  row: number
  frames: number
  durations: readonly number[]
  loop: boolean
}

export const PET_LEGACY_ROWS: Partial<Record<PetState, LegacyRowSpec>> = Object.fromEntries(
  Object.entries(HD_STANDARD).map(([id, spec]) => [
    id,
    { row: spec.legacyRow, frames: spec.frames, durations: spec.durations, loop: spec.loop },
  ]),
) as Partial<Record<PetState, LegacyRowSpec>>

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

/** 视线角度 → 高清视线行 id（row 9 = 0°-157.5°，row 10 = 180°-337.5°）。 */
export function lookHdRow(degrees: number): HdRowId {
  return LOOK_DIRECTIONS[lookDirectionIndex(degrees)].row === 9 ? 'look-row-9' : 'look-row-10'
}

/** 一次动画从第 0 帧到最后一帧的累计时长（ms），one-shot 结束时机以此为准。 */
export function totalDuration(state: PetState): number {
  return PET_HD_ANIMATIONS[state].durations.reduce((sum, value) => sum + value, 0)
}

/** 三组待机动作：每次独立均匀随机，允许连续重复。 */
export const IDLE_ACTIONS: readonly IdleActionId[] = ['idle-curious', 'idle-sleeve', 'idle-sway']

export function randomIdleAction(random: () => number = Math.random): IdleActionId {
  return IDLE_ACTIONS[Math.floor(random() * IDLE_ACTIONS.length)]
}
