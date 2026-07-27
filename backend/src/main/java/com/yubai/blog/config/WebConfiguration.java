package com.yubai.blog.config;

import java.time.Duration;
import java.util.Arrays;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.HandlerInterceptor;

import com.yubai.blog.common.CurrentUser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Configuration
public class WebConfiguration implements WebMvcConfigurer {
    private final String[] allowedOrigins;

    public WebConfiguration(@Value("${app.cors.allowed-origins}") String allowedOrigins) {
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
            .map(String::trim)
            .filter(origin -> !origin.isBlank())
            .toArray(String[]::new);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
            .allowedOrigins(allowedOrigins)
            .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .allowCredentials(true);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if ("GET".equals(request.getMethod())) {
                    String path = request.getRequestURI();
                    // 5C：图谱响应按身份变化——添加 Vary: Authorization，登录用户走 private 缓存
                    if (path.contains("/api/v1/graph/")) {
                        String vary = response.getHeader("Vary");
                        String newVary = "Authorization";
                        if (vary != null && !vary.isBlank() && !vary.contains("Authorization")) {
                            newVary = vary + ", Authorization";
                        } else if (vary != null && vary.contains("Authorization")) {
                            newVary = vary;
                        }
                        response.setHeader("Vary", newVary);
                        boolean authed = CurrentUser.isAuthenticated();
                        response.setHeader("Cache-Control", (authed
                            ? CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate()
                            : CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic()).getHeaderValue());
                    } else if (isCounterEndpoint(path)) {
                        response.setHeader("Cache-Control", CacheControl.noCache().getHeaderValue());
                    } else {
                        response.setHeader("Cache-Control",
                            CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic().getHeaderValue());
                    }
                }
                return true;
            }
        // NB-7：/graph、/quotes 纳入可缓存列表，与 P1-5 服务端 Caffeine 5 分钟 TTL 对齐
        }).addPathPatterns("/api/v1/posts/**", "/api/v1/dishes/**", "/api/v1/notes/**", "/api/v1/categories/**", "/api/v1/search", "/api/v1/music/**", "/api/v1/graph/**", "/api/v1/quotes/**");

        // FD-11：kitchen（今日菜单/打卡）是两人私有生活数据——一律 no-store，
        // 任何共享缓存/磁盘副本都不许落（与 NB-7 计数端点的 no-cache 可再验证语义刻意区分）
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                response.setHeader("Cache-Control", CacheControl.noStore().getHeaderValue());
                return true;
            }
        }).addPathPatterns("/api/v1/kitchen/**");
    }

    private static boolean isCounterEndpoint(String path) {
        return path.endsWith("/stats") || path.equals("/api/v1/dishes/favorites");
    }
}
