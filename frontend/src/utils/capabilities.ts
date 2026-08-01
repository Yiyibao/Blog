export const Capabilities = {
  ACCOUNT_ACCESS: 'account:access',
  CONTENT_MANAGE: 'content:manage',
  AI_MANAGE: 'ai:manage',
  AI_USAGE: 'ai:usage',
  KITCHEN_ACCESS: 'kitchen:access',
  KITCHEN_DELETE_ANY: 'kitchen:delete_any',
  DASHBOARD_VIEW: 'dashboard:view',
  ATTACHMENTS_MANAGE: 'attachments:manage',
  LIBRARY_MANAGE: 'library:manage',
  METRICS_VIEW: 'metrics:view',
} as const

export type Capability = typeof Capabilities[keyof typeof Capabilities]

const ROLE_CAPABILITIES: Record<string, ReadonlySet<Capability>> = {
  // FD-29：PARTNER 与 ADMIN 能力完全一致（角色值仍各自保留）
  ADMIN: new Set(Object.values(Capabilities)),
  PARTNER: new Set(Object.values(Capabilities)),
}

export function getCapabilities(role: string | undefined | null): ReadonlySet<Capability> {
  return ROLE_CAPABILITIES[role ?? ''] ?? new Set()
}

export function hasCapability(role: string | undefined | null, capability: Capability): boolean {
  return getCapabilities(role).has(capability)
}
