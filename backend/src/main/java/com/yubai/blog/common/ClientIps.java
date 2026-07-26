package com.yubai.blog.common;

import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 解析客户端真实 IP。
 * FD-0：转发头只有当请求确实来自可信反代（生产为本机 nginx，proxy_pass 自 127.0.0.1）时才可信；
 * 直连后端的请求可任意伪造 X-Forwarded-For/X-Real-IP，采信即等于全站按 IP 的限流与冷却失效。
 * 优先 X-Real-IP：nginx 以 $remote_addr 覆写，单值不可拼接；
 * X-Forwarded-For 取末位：首位可被客户端预填，末位才是本机 nginx 追加的真实来源。
 */
public final class ClientIps {
    private static final Set<String> TRUSTED_PROXIES = Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1");

    private ClientIps() {
    }

    public static String resolve(HttpServletRequest request) {
        var remoteAddr = request.getRemoteAddr();
        if (!isTrustedProxy(remoteAddr)) {
            return remoteAddr;
        }
        var realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        var forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            var entries = forwarded.split(",");
            return entries[entries.length - 1].trim();
        }
        return remoteAddr;
    }

    private static boolean isTrustedProxy(String remoteAddr) {
        return remoteAddr != null && TRUSTED_PROXIES.contains(remoteAddr);
    }
}
