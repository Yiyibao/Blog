package com.yubai.blog.auth;

import java.util.Map;
import java.util.Set;

public final class RolePermissions {
    private static final Map<AdminUserRole, Set<String>> MAPPING = Map.of(
        AdminUserRole.ADMIN, Set.of(
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
        ),
        AdminUserRole.PARTNER, Set.of(
            Permissions.ACCOUNT_ACCESS,
            Permissions.KITCHEN_ACCESS
        )
    );

    public static Set<String> forRole(AdminUserRole role) {
        if (role == null) return Set.of();
        var permissions = MAPPING.get(role);
        return permissions != null ? permissions : Set.of();
    }

    private RolePermissions() {}
}
