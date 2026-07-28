package com.yubai.blog.config;

import java.time.Clock;
import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.yubai.blog.auth.AdminUserRole;
import com.yubai.blog.auth.Permissions;
import com.yubai.blog.auth.RolePermissions;

@Configuration
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationConverter jwtConverter,
                                            com.yubai.blog.auth.SessionValidityFilter sessionValidityFilter) throws Exception {
        return http
            // FD-9：认证/授权之后校验 sessions_valid_from（改密/踢下线止损阀）
            .addFilterAfter(sessionValidityFilter, org.springframework.security.web.access.intercept.AuthorizationFilter.class)
            .csrf(csrf -> csrf.disable())
            .cors(Customizer.withDefaults())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // 6A：Prometheus 指标仅 ADMIN 可读，置于 permitAll 规则之前——顺序敏感
                .requestMatchers("/actuator/prometheus").hasAuthority(Permissions.METRICS_VIEW)
                .requestMatchers("/actuator/health", "/actuator/info", "/api/v1/auth/login", "/api/v1/auth/challenge", "/api/v1/auth/refresh", "/api/v1/auth/logout", "/api/v1/auth/totp/verify", "/sitemap.xml", "/rss.xml", "/robots.txt", "/error").permitAll()
                // P2-3：文档路径放行但功能默认关闭（SPRINGDOC_ENABLED=false 时如实 404），生产不暴露内容
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                // L-16/D-17：/notes 与 /note-assets 移出公开白名单——学习笔记对游客真隐藏（落入下方 /api/** authenticated）
                .requestMatchers(HttpMethod.GET, "/api/v1/posts/**", "/api/v1/categories", "/api/v1/categories/**", "/api/v1/dish-categories", "/api/v1/dish-categories/**", "/api/v1/dishes/**", "/api/v1/search", "/api/v1/music/**", "/api/v1/graph/**", "/api/v1/quotes/**", "/api/v1/series/**", "/api/v1/tags/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/dishes/*/favorite", "/api/v1/posts/*/like", "/api/v1/search").permitAll()
                // FD-7：kitchen（今日菜单/打卡）为两人私有空间——必须 kitchen:access 权限；
                // 规则须在 /api/** 通配之前，顺序敏感
                .requestMatchers("/api/v1/kitchen/**").hasAuthority(Permissions.KITCHEN_ACCESS)
                .requestMatchers("/api/v1/admin/ai/providers/**").hasAuthority(Permissions.AI_MANAGE)
                .requestMatchers("/api/v1/admin/ai/**").hasAuthority(Permissions.AI_USAGE)
                .requestMatchers("/api/v1/admin/stats").hasAuthority(Permissions.DASHBOARD_VIEW)
                .requestMatchers("/api/v1/admin/attachments/**", "/api/v1/admin/notes/*/attachments/**")
                    .hasAuthority(Permissions.ATTACHMENTS_MANAGE)
                .requestMatchers("/api/v1/admin/library/**").hasAuthority(Permissions.LIBRARY_MANAGE)
                .requestMatchers("/api/v1/admin/**").hasAuthority(Permissions.CONTENT_MANAGE)
                .requestMatchers("/api/**").hasAuthority(Permissions.ACCOUNT_ACCESS)
                // P0-1：兜底 denyAll——新增路由必须显式加入白名单，避免默认公开
                .anyRequest().denyAll()
            )
            .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter)))
            .build();
    }

    // FD-9：显式建 bean（类上无 @Component，防 @WebMvcTest 切片捡起）；
    // 禁用 servlet 容器自动注册，确保只在安全链里跑一次
    @Bean
    com.yubai.blog.auth.SessionValidityFilter sessionValidityFilter(com.yubai.blog.auth.AdminUserRepository repository) {
        return new com.yubai.blog.auth.SessionValidityFilter(repository);
    }

    @Bean
    org.springframework.boot.web.servlet.FilterRegistrationBean<com.yubai.blog.auth.SessionValidityFilter> sessionValidityFilterRegistration(
        com.yubai.blog.auth.SessionValidityFilter filter) {
        var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    SecretKey jwtSecretKey(@Value("${app.jwt.secret}") String secret) {
        if (secret.length() < 32) {
            throw new IllegalStateException("APP_JWT_SECRET must contain at least 32 characters");
        }
        // NB-3：.env.example 的占位符本身 ≥32 字符，能通过长度校验；
        // 照抄模板等于用公开已知密钥上线，启动时直接拒绝。
        if (secret.startsWith("replace_with")) {
            throw new IllegalStateException(
                "APP_JWT_SECRET 仍是 .env.example 的占位符，请改为随机生成的密钥（如 openssl rand -base64 48）");
        }
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey key) {
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey key) {
        var jwk = new OctetSequenceKey.Builder(key).build();
        JWKSource<SecurityContext> source = (selector, context) -> selector.select(new JWKSet(jwk));
        return new NimbusJwtEncoder(source);
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            var authorityClaim = jwt.getClaimAsStringList("authorities");
            if (authorityClaim != null) {
                return authorityClaim.stream()
                    .filter(Permissions.ALL::contains)
                    .distinct()
                    .map(SimpleGrantedAuthority::new)
                    .collect(java.util.stream.Collectors.toList());
            }
            // Rolling-deploy compatibility for access tokens issued before 6C-2.
            var roleClaim = jwt.getClaimAsStringList("roles");
            if (roleClaim == null || roleClaim.isEmpty()) return java.util.List.of();
            return roleClaim.stream().flatMap(roleName -> {
                try {
                    return RolePermissions.forRole(AdminUserRole.valueOf(roleName)).stream();
                } catch (IllegalArgumentException e) {
                    return java.util.stream.Stream.<String>empty();
                }
            })
                .distinct()
                    .map(SimpleGrantedAuthority::new)
                    .collect(java.util.stream.Collectors.toList());
        });
        return converter;
    }
}
