import { describe, it, expect } from 'vitest'
import { Capabilities, getCapabilities, hasCapability } from '../utils/capabilities'

describe('capabilities', () => {
  it('ADMIN has every capability', () => {
    const caps = getCapabilities('ADMIN')
    for (const value of Object.values(Capabilities)) {
      expect(caps.has(value)).toBe(true)
    }
  })

  it('PARTNER has only account and kitchen access', () => {
    const caps = getCapabilities('PARTNER')
    expect(caps.has(Capabilities.KITCHEN_ACCESS)).toBe(true)
    expect(caps.has(Capabilities.ACCOUNT_ACCESS)).toBe(true)
    expect(caps.has(Capabilities.KITCHEN_DELETE_ANY)).toBe(false)
    expect(caps.size).toBe(2)
  })

  it('unknown role returns empty set (fail-closed)', () => {
    expect(getCapabilities('UNKNOWN').size).toBe(0)
    expect(getCapabilities(null).size).toBe(0)
    expect(getCapabilities(undefined).size).toBe(0)
    expect(getCapabilities('').size).toBe(0)
  })

  it('hasCapability works correctly', () => {
    expect(hasCapability('ADMIN', Capabilities.CONTENT_MANAGE)).toBe(true)
    expect(hasCapability('PARTNER', Capabilities.CONTENT_MANAGE)).toBe(false)
    expect(hasCapability(null, Capabilities.CONTENT_MANAGE)).toBe(false)
    expect(hasCapability('BOGUS', Capabilities.KITCHEN_ACCESS)).toBe(false)
  })
})
