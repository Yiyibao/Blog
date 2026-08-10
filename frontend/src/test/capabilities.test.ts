import { describe, it, expect } from 'vitest';
import { Capabilities, getCapabilities, hasCapability } from '../utils/capabilities';

describe('capabilities', () => {
  it('ADMIN has every capability', () => {
    const caps = getCapabilities('ADMIN');
    for (const value of Object.values(Capabilities)) {
      expect(caps.has(value)).toBe(true);
    }
  });

  it('PARTNER has the same capability set as ADMIN (FD-29)', () => {
    const partner = getCapabilities('PARTNER');
    const admin = getCapabilities('ADMIN');
    for (const value of Object.values(Capabilities)) {
      expect(partner.has(value)).toBe(true);
    }
    expect(partner.size).toBe(admin.size);
    expect(partner.size).toBe(Object.values(Capabilities).length);
  });

  it('unknown role returns empty set (fail-closed)', () => {
    expect(getCapabilities('UNKNOWN').size).toBe(0);
    expect(getCapabilities(null).size).toBe(0);
    expect(getCapabilities(undefined).size).toBe(0);
    expect(getCapabilities('').size).toBe(0);
  });

  it('hasCapability works correctly', () => {
    expect(hasCapability('ADMIN', Capabilities.CONTENT_MANAGE)).toBe(true);
    // FD-29：PARTNER 与 ADMIN 同权
    expect(hasCapability('PARTNER', Capabilities.CONTENT_MANAGE)).toBe(true);
    expect(hasCapability('PARTNER', Capabilities.AI_MANAGE)).toBe(true);
    expect(hasCapability('PARTNER', Capabilities.KITCHEN_DELETE_ANY)).toBe(true);
    expect(hasCapability(null, Capabilities.CONTENT_MANAGE)).toBe(false);
    expect(hasCapability('BOGUS', Capabilities.KITCHEN_ACCESS)).toBe(false);
  });
});
