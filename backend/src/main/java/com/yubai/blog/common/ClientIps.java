package com.yubai.blog.common;

import jakarta.servlet.http.HttpServletRequest;

/** 解析客户端真实 IP：生产环境经 nginx 反代，优先取转发头。 */
public final class ClientIps {
    private ClientIps() {
    }

    public static String resolve(HttpServletRequest request) {
        var forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        var realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
