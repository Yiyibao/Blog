package com.yubai.blog.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RolePermissionsTest {

    @Test
    void adminHasAllPermissions() {
        var permissions = RolePermissions.forRole(AdminUserRole.ADMIN);
        assertThat(permissions)
            .containsExactlyInAnyOrder(
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
    }

    @Test
    void partnerHasOnlyAccountAndKitchenAccess() {
        var permissions = RolePermissions.forRole(AdminUserRole.PARTNER);
        assertThat(permissions)
            .containsExactlyInAnyOrder(Permissions.ACCOUNT_ACCESS, Permissions.KITCHEN_ACCESS);
    }

    @Test
    void unknownRoleReturnsEmptyPermissions() {
        var permissions = RolePermissions.forRole(null);
        assertThat(permissions).isEmpty();
    }
}
