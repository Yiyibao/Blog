package com.yubai.blog.auth;

/**
 * FD-6：账号角色。取值集合与 V17 迁移的 admin_users_role_check 约束必须同步——
 * 改这里必须同时新增迁移改约束，否则写库直接违反 check。
 * 刻意不做派生角色（如 KITCHEN）：授权处用 hasAnyRole("ADMIN","PARTNER")，
 * 存量 ADMIN token 的 roles 天然通过，部署后无 403 空窗。
 */
public enum AdminUserRole {
    /** 站长：全部能力。 */
    ADMIN,
    /** 伴侣：kitchen（今日菜单/打卡）读写，不可触碰 /admin/**。 */
    PARTNER
}
