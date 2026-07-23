import { describe, it, expect, vi, beforeEach } from 'vitest'
import { buildPayload, clearPreviewUrls, replacePreviewUrls, replaceCanonicalUrls } from '../utils/noteHelpers'
import type { NoteAttachment } from '../api/admin'

const makeAttachment = (id: number, publicId: string): NoteAttachment => ({
  id,
  publicId,
  noteId: 1,
  fileName: `${publicId}.png`,
  mediaType: 'image/png',
  byteSize: 1024,
  url: `/api/v1/note-assets/${publicId}`,
  createdAt: '2026-01-01T00:00:00Z',
})

describe('buildPayload', () => {
  const baseForm = { title: 'Test', markdownContent: 'Hello', folder: 'work', status: 'DRAFT' as const, tags: [], version: 1 }

  it('replaces blob URLs with canonical attachment URLs', () => {
    const attachments = [makeAttachment(1, 'abc123')]
    const previews = { 1: 'blob:mock-1' }
    const form = { ...baseForm, markdownContent: 'See ![img](blob:mock-1) here' }
    const result = buildPayload(form, attachments, previews, '')
    expect(result.markdownContent).toBe('See ![img](/api/v1/note-assets/abc123) here')
    expect(result.markdownContent).not.toContain('blob:')
  })

  it('handles multiple attachments and multiple occurrences', () => {
    const attachments = [makeAttachment(1, 'a1'), makeAttachment(2, 'b2')]
    const previews = { 1: 'blob:a', 2: 'blob:b' }
    const form = { ...baseForm, markdownContent: '![a](blob:a) ![b](blob:b) ![a](blob:a)' }
    const result = buildPayload(form, attachments, previews, '')
    expect(result.markdownContent).toBe('![a](/api/v1/note-assets/a1) ![b](/api/v1/note-assets/b2) ![a](/api/v1/note-assets/a1)')
  })

  it('ignores attachments without preview URLs', () => {
    const attachments = [makeAttachment(1, 'c3')]
    const form = { ...baseForm, markdownContent: '![x](blob:ghost) text' }
    const result = buildPayload(form, attachments, {}, '')
    expect(result.markdownContent).toBe('![x](blob:ghost) text')
  })

  it('trims title and falls back to default', () => {
    const form = { ...baseForm, title: '  ', markdownContent: 'body', folder: 'x', status: 'DRAFT' as const, tags: [], version: 0 }
    const result = buildPayload(form, [], {}, '')
    expect(result.title).toBe('未命名笔记')
  })

  it('parses comma- and Chinese-comma-separated tags', () => {
    const result = buildPayload(baseForm, [], {}, 'vue, 测试,react，typescript')
    expect(result.tags).toEqual(['vue', '测试', 'react', 'typescript'])
  })

  it('preserves all other form fields', () => {
    const form = { title: 'Title', markdownContent: 'Content', folder: '随笔', status: 'PUBLISHED' as const, tags: ['a'], version: 5 }
    const result = buildPayload(form, [], {}, 'x, y')
    expect(result.folder).toBe('随笔')
    expect(result.status).toBe('PUBLISHED')
    expect(result.version).toBe(5)
    expect(result.tags).toEqual(['x', 'y'])
  })
})

describe('clearPreviewUrls', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('revokes all object URLs and returns empty object', () => {
    const urls = { 1: 'blob:mock-1', 2: 'blob:mock-2' }
    const result = clearPreviewUrls(urls)
    expect(URL.revokeObjectURL).toHaveBeenCalledTimes(2)
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:mock-1')
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:mock-2')
    expect(result).toEqual({})
  })

  it('handles empty input', () => {
    const result = clearPreviewUrls({})
    expect(result).toEqual({})
  })
})

describe('replacePreviewUrls', () => {
  it('replaces canonical URLs with preview URLs', () => {
    const attachments = [makeAttachment(1, 'def456')]
    const previews = { 1: 'blob:mock-1' }
    const result = replacePreviewUrls('![img](/api/v1/note-assets/def456)', attachments, previews)
    expect(result).toBe('![img](blob:mock-1)')
  })
})

describe('replaceCanonicalUrls', () => {
  it('replaces preview URLs with canonical URLs (single attachment)', () => {
    const attachments = [makeAttachment(1, 'ghi789')]
    const previews = { 1: 'blob:mock-1' }
    const result = replaceCanonicalUrls('![img](blob:mock-1)', attachments, previews)
    expect(result).toBe('![img](/api/v1/note-assets/ghi789)')
  })
})
