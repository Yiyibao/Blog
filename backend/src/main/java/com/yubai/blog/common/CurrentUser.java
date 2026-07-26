package com.yubai.blog.common;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** L-16/D-17：游客收权的统一判定——公开端点据此决定是否剔除学习笔记内容。 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /** 持有效 JWT（任意角色）即为登录用户；匿名与无上下文一律视为游客。 */
    public static boolean isAuthenticated() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
            && authentication.isAuthenticated()
            && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
