package com.yubai.blog.auth;

import java.util.Set;

public final class Permissions {
    public static final String ACCOUNT_ACCESS = "account:access";
    public static final String CONTENT_MANAGE = "content:manage";
    public static final String AI_MANAGE = "ai:manage";
    public static final String AI_USAGE = "ai:usage";
    public static final String KITCHEN_ACCESS = "kitchen:access";
    public static final String KITCHEN_DELETE_ANY = "kitchen:delete_any";
    public static final String DASHBOARD_VIEW = "dashboard:view";
    public static final String ATTACHMENTS_MANAGE = "attachments:manage";
    public static final String LIBRARY_MANAGE = "library:manage";
    public static final String METRICS_VIEW = "metrics:view";

    public static final Set<String> ALL = Set.of(
        ACCOUNT_ACCESS, CONTENT_MANAGE, AI_MANAGE, AI_USAGE, KITCHEN_ACCESS,
        KITCHEN_DELETE_ANY, DASHBOARD_VIEW, ATTACHMENTS_MANAGE, LIBRARY_MANAGE,
        METRICS_VIEW
    );

    private Permissions() {}
}
