import { describe, it, expect } from 'vitest'
import {
  ATLAS, HD_ROW, HD_ROWS, PET_HD_ANIMATIONS, PET_LEGACY_ROWS,
  LOOK_DIRECTIONS, lookCell, lookDirectionIndex, lookHdRow, totalDuration,
  randomIdleAction, IDLE_ACTIONS,
} from '../components/admin-pet/petAnimations'

describe('petAnimations 高清行元数据（15 个动画 id）', () => {
  it('旧 8×11 图集基线不变：192×208 单格，1536×2288 整图', () => {
    expect(ATLAS.columns).toBe(8)
    expect(ATLAS.rows).toBe(11)
    expect(ATLAS.cellWidth).toBe(192)
    expect(ATLAS.cellHeight).toBe(208)
    expect(ATLAS.width).toBe(1536)
    expect(ATLAS.height).toBe(2288)
    expect(ATLAS.spriteUrl).toBe('/pets/xinn/spritesheet.webp')
  })

  it('高清行几何：3072×416，单格 384×416，一行 8 格', () => {
    expect(HD_ROW.columns).toBe(8)
    expect(HD_ROW.rows).toBe(1)
    expect(HD_ROW.cellWidth).toBe(384)
    expect(HD_ROW.cellHeight).toBe(416)
    expect(HD_ROW.width).toBe(3072)
    expect(HD_ROW.height).toBe(416)
  })

  it('恰好 15 个高清行 id：9 标准 + 2 视线 + 3 待机 + 1 点击', () => {
    const ids = Object.keys(HD_ROWS)
    expect(ids).toHaveLength(15)
    for (const id of ['idle', 'running-right', 'running-left', 'waving', 'jumping', 'failed',
      'waiting', 'running', 'review', 'look-row-9', 'look-row-10',
      'idle-curious', 'idle-sleeve', 'idle-sway', 'chat-open']) {
      expect(ids).toContain(id)
    }
  })

  it('每个高清行引用独立文件 /pets/xinn/hd/<id>.webp，且单行 8 格', () => {
    for (const [id, meta] of Object.entries(HD_ROWS)) {
      expect(HD_SOURCE_URL(id)).toBe(`/pets/xinn/hd/${id}.webp`)
      expect(meta.frames).toBeGreaterThan(0)
      expect(meta.frames).toBeLessThanOrEqual(8)
      expect(meta.legacyRow).toBeGreaterThanOrEqual(0)
      expect(meta.legacyRow).toBeLessThanOrEqual(10)
    }
  })

  it('九个标准状态的行号/有效帧数/逐帧时长/kind 与规范一致', () => {
    const standard: Record<string, { frames: number; durations: number[]; loop: boolean; legacyRow: number }> = {
      idle: { frames: 6, durations: [280, 110, 110, 140, 140, 320], loop: true, legacyRow: 0 },
      'running-right': { frames: 8, durations: [120, 120, 120, 120, 120, 120, 120, 220], loop: true, legacyRow: 1 },
      'running-left': { frames: 8, durations: [120, 120, 120, 120, 120, 120, 120, 220], loop: true, legacyRow: 2 },
      waving: { frames: 4, durations: [140, 140, 140, 280], loop: false, legacyRow: 3 },
      jumping: { frames: 5, durations: [140, 140, 140, 140, 280], loop: true, legacyRow: 4 },
      failed: { frames: 8, durations: [140, 140, 140, 140, 140, 140, 140, 240], loop: false, legacyRow: 5 },
      waiting: { frames: 6, durations: [150, 150, 150, 150, 150, 260], loop: true, legacyRow: 6 },
      running: { frames: 6, durations: [120, 120, 120, 120, 120, 220], loop: true, legacyRow: 7 },
      review: { frames: 6, durations: [150, 150, 150, 150, 150, 280], loop: false, legacyRow: 8 },
    }
    for (const [id, spec] of Object.entries(standard)) {
      const meta = HD_ROWS[id as keyof typeof HD_ROWS]
      expect(meta.frames).toBe(spec.frames)
      expect([...meta.durations!]).toEqual(spec.durations)
      expect(meta.loop).toBe(spec.loop)
      expect(meta.kind).toBe('standard')
      expect(meta.legacyRow).toBe(spec.legacyRow)
      const anim = PET_HD_ANIMATIONS[id as keyof typeof PET_HD_ANIMATIONS]
      expect(anim.frames).toBe(spec.frames)
      expect(anim.loop).toBe(spec.loop)
      expect(anim.source.url).toBe(`/pets/xinn/hd/${id}.webp`)
      expect(anim.source.cellWidth).toBe(384)
    }
  })

  it('三组待机动作：8 帧、逐帧时长、loop=false、kind=idle-action', () => {
    const expected = {
      'idle-curious': [180, 140, 140, 220, 180, 180, 240, 320],
      'idle-sleeve': [200, 180, 180, 220, 200, 180, 260, 360],
      'idle-sway': [160, 160, 180, 180, 180, 180, 220, 340],
    }
    for (const [id, durations] of Object.entries(expected)) {
      const meta = HD_ROWS[id as keyof typeof HD_ROWS]
      expect(meta.frames).toBe(8)
      expect([...meta.durations!]).toEqual(durations)
      expect(meta.loop).toBe(false)
      expect(meta.kind).toBe('idle-action')
    }
  })

  it('chat-open：8 帧、逐帧时长、loop=false、kind=interaction', () => {
    const meta = HD_ROWS['chat-open']
    expect(meta.frames).toBe(8)
    expect([...meta.durations!]).toEqual([90, 90, 110, 130, 150, 160, 180, 220])
    expect(meta.loop).toBe(false)
    expect(meta.kind).toBe('interaction')
  })

  it('视线行：look-row-9/10 各 8 格，无逐帧时长（静态格）', () => {
    for (const id of ['look-row-9', 'look-row-10'] as const) {
      expect(HD_ROWS[id].frames).toBe(8)
      expect(HD_ROWS[id].durations).toBeNull()
      expect(HD_ROWS[id].loop).toBe(false)
      expect(HD_ROWS[id].kind).toBe('standard')
    }
  })

  it('所有动画状态帧数 ≤ 8 且时长数组长度与帧数一致', () => {
    for (const spec of Object.values(PET_HD_ANIMATIONS)) {
      expect(spec.frames).toBeLessThanOrEqual(HD_ROW.columns)
      expect(spec.durations).toHaveLength(spec.frames)
    }
  })

  it('PET_LEGACY_ROWS 与高清标准行同源（回退契约）', () => {
    for (const [id, legacy] of Object.entries(PET_LEGACY_ROWS)) {
      const hd = HD_ROWS[id as keyof typeof HD_ROWS]
      expect(legacy!.row).toBe(hd.legacyRow)
      expect(legacy!.frames).toBe(hd.frames)
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

  it('lookHdRow 把 16 档角度映射到两个高清视线行文件', () => {
    for (let index = 0; index < 16; index += 1) {
      const degrees = index * 22.5
      expect(lookHdRow(degrees)).toBe(index < 8 ? 'look-row-9' : 'look-row-10')
    }
  })

  it('totalDuration 为逐帧时长之和（one-shot 结束时机），新旧动作均正确', () => {
    expect(totalDuration('waving')).toBe(140 + 140 + 140 + 280)
    expect(totalDuration('failed')).toBe(140 * 7 + 240)
    expect(totalDuration('review')).toBe(150 * 5 + 280)
    expect(totalDuration('idle-curious')).toBe(1600)
    expect(totalDuration('idle-sleeve')).toBe(1780)
    expect(totalDuration('idle-sway')).toBe(1600)
    expect(totalDuration('chat-open')).toBe(1130)
  })

  it('三组随机区间分别映射到三个待机动作，允许连续重复', () => {
    // [0, 1/3) → idle-curious；[1/3, 2/3) → idle-sleeve；[2/3, 1) → idle-sway
    expect(randomIdleAction(() => 0)).toBe('idle-curious')
    expect(randomIdleAction(() => 0.333)).toBe('idle-curious')
    expect(randomIdleAction(() => 0.334)).toBe('idle-sleeve')
    expect(randomIdleAction(() => 0.666)).toBe('idle-sleeve')
    expect(randomIdleAction(() => 0.667)).toBe('idle-sway')
    expect(randomIdleAction(() => 0.999)).toBe('idle-sway')
    // 连续两次相同随机值允许连续重复同一组
    expect(randomIdleAction(() => 0.5)).toBe('idle-sleeve')
    expect(randomIdleAction(() => 0.5)).toBe('idle-sleeve')
    // IDLE_ACTIONS 顺序与区间一致
    expect(IDLE_ACTIONS).toEqual(['idle-curious', 'idle-sleeve', 'idle-sway'])
  })
})

function HD_SOURCE_URL(id: string): string {
  return `/pets/xinn/hd/${id}.webp`
}
