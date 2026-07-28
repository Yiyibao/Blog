package com.yubai.blog.auth;

import java.io.IOException;
import java.time.Instant;
import java.util.Set;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * FD-9：JWT 无状态且不可撤销——本过滤器以 admin_users.sessions_valid_from 为"会话有效起点"，
 * iat 早于它的 token 一律 401。这是改密（FD-25）/踢下线的止损阀。
 * 只校验当前需要登录的 API：公开读与认证入口不查库，每请求最多一次按唯一索引的点查。
 * 刻意不加 @Component：@WebMvcTest 切片会捡起 Filter 类型组件导致装配失败，
 * 且 Filter bean 会被 Boot 自动注册为全局 servlet filter 造成双跑——由 SecurityConfiguration 显式建 bean 并禁用自动注册。
 */
public class SessionValidityFilter extends OncePerRequestFilter {
    private static final Set<String> PUBLIC_AUTH_PATHS = Set.of(
        "/api/v1/auth/challenge",
        "/api/v1/auth/login",
        "/api/v1/auth/refresh",
        "/api/v1/auth/logout",
        "/api/v1/auth/totp/verify"
    );

    private final AdminUserRepository repository;

    public SessionValidityFilter(AdminUserRepository repository) {
        this.repository = repository;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        var path = request.getRequestURI();
        var protectedAuthPath = path.startsWith("/api/v1/auth/") && !PUBLIC_AUTH_PATHS.contains(path);
        return !(path.startsWith("/api/v1/kitchen/")
            || path.startsWith("/api/v1/admin/")
            || path.equals("/api/v1/notes")
            || path.startsWith("/api/v1/notes/")
            || path.startsWith("/api/v1/note-assets/")
            || protectedAuthPath);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwtAuth) {
            var jwt = jwtAuth.getToken();
            var user = repository.findByUsername(jwt.getSubject()).orElse(null);
            var raw = jwt.getClaim("svf");
            var svf = raw instanceof Number ? ((Number) raw).longValue() : null;
            // FD-25：每个 token 携带签发时的 sessionsValidFrom（毫秒精度 svf）。
            // 改密/踢下线后 DB 的 sessionsValidFrom 推进；token 的 svf 早于它则 401。
            // 毫秒精度消除了同一秒内新/旧 token 的时序竞争。
            if (user == null || !user.isEnabled() || svf == null
                || svf < user.getSessionsValidFrom().toEpochMilli()) {
                reject(response);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        // 与 GlobalExceptionHandler 的错误信封保持同构 {status, message, timestamp}
        response.getWriter().write("{\"status\":401,\"message\":\"登录已失效，请重新登录\",\"timestamp\":\""
            + Instant.now() + "\"}");
    }
}
