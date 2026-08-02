// @vitest-environment node
import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const assetDir = resolve(process.cwd(), 'public/food/real')
const manifestPath = resolve(assetDir, 'manifest.json')
const migrationPath = resolve(
  process.cwd(),
  '../backend/src/main/resources/db/migration/V40__replace_dish_svg_images_with_real_photos.sql',
)

const manifest = JSON.parse(readFileSync(manifestPath, 'utf8')) as {
  count: number
  dimensions: number[]
  placeholder: boolean
  items: Array<{ slug: string; output: string; dimensions: number[]; bytes: number }>
}
const migration = readFileSync(migrationPath, 'utf8')

describe('food photo asset contract', () => {
  it('ships twenty normalized, non-placeholder WebP assets', () => {
    expect(manifest.count).toBe(20)
    expect(manifest.placeholder).toBe(false)
    expect(manifest.dimensions).toEqual([1200, 800])
    expect(manifest.items).toHaveLength(20)

    const slugs = new Set<string>()
    for (const item of manifest.items) {
      expect(item.output).toMatch(/^\/food\/real\/[a-z0-9-]+\.webp$/)
      expect(item.dimensions).toEqual([1200, 800])
      expect(item.bytes).toBeGreaterThan(10_000)
      expect(slugs.has(item.slug)).toBe(false)
      slugs.add(item.slug)
      expect(existsSync(resolve(assetDir, `${item.slug}.webp`))).toBe(true)
    }
  })

  it('updates every published dish to a unique real-photo path', () => {
    for (const item of manifest.items) {
      expect(migration).toContain(`'/food/real/${item.slug}.webp'`)
    }
    expect(migration).not.toContain('/food/generated/')
    expect(migration.match(/\/food\/real\/[^']+\.webp/g)).toHaveLength(20)
  })
})
