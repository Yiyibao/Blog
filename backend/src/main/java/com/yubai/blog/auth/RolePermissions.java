package com.yubai.blog.auth;

import java.util.Map;
import java.util.Set;

public final class RolePermissions {
    // FD-29：ADMIN 与 PARTNER 能力完全一致（角色身份仍各自保留）。
    // 同一实例使两角色的集合恒等，杜绝“新增能力只加一边”的漂移。
    private static final Set<String> ADMIN_PERMISSIONS = Set.of(
        Permissions.ACCOUNT_ACCESS,
        Permissions.CONTENT_MANAGE,
        Permissions.AI_MANAGE,
        Permissions.AI_USAGE,
        Permissions.KITCHEN_ACCESS,
        Permissions.KITCHEN_DELETE_ANY,
        Permissions.DASHBOARD_VIEW,
        Permissions.ATTACHMENTS_MANAGE,
        Permissions.LIBRARY_MANAGE,
        Permissions.METRICS_VIEW
    );

    private static final Map<AdminUserRole, Set<String>> MAPPING = Map.of(
        AdminUserRole.ADMIN, ADMIN_PERMISSIONS,
        AdminUserRole.PARTNER, ADMIN_PERMISSIONS
    );

    public static Set<String> forRole(AdminUserRole role) {
        if (role == null) return Set.of();
        var permissions = MAPPING.get(role);
        return permissions != null ? permissions : Set.of();
    }

    private RolePermissions() {}
}
