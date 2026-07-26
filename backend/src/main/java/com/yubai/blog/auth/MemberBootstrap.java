package com.yubai.blog.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * FD-6：伴侣（PARTNER）账号 bootstrap。
 * 只创建、不轮换——保持"数据库为账号事实源"的只读幂等语义，改 .env 口令不会更新已存在的账号
 * （将来自助改密走 FD-25 的专用端点）。未配置则静默跳过，不影响单人部署。
 */
@Component
@Order(10) // 在 AdminBootstrap（默认序）之后跑，保证站长先建
public class MemberBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(MemberBootstrap.class);
    static final int MIN_PASSWORD_LENGTH = 12;

    private final AdminUserRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final String adminUsername;
    private final String adminPassword;
    private final String username;
    private final String password;
    private final String displayName;

    public MemberBootstrap(
        AdminUserRepository repository,
        PasswordEncoder passwordEncoder,
        @Value("${app.admin.username}") String adminUsername,
        @Value("${app.admin.password}") String adminPassword,
        @Value("${app.partner.username:}") String username,
        @Value("${app.partner.password:}") String password,
        @Value("${app.partner.display-name:}") String displayName
    ) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
        this.username = username;
        this.password = password;
        this.displayName = displayName;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            log.info("Partner bootstrap skipped: app.partner.* not configured");
            return;
        }
        // 伴侣账号是新增攻击面，口令强度强制（站长历史口令只 WARN，见 AdminBootstrap）
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                "APP_PARTNER_PASSWORD 至少 " + MIN_PASSWORD_LENGTH + " 位（建议中文短语口令，手机好输入且强度高）");
        }
        if (password.startsWith("replace_with")) {
            throw new IllegalStateException("APP_PARTNER_PASSWORD 仍是 .env.example 的占位符，请改为真实口令");
        }
        if (password.equals(adminPassword)) {
            throw new IllegalStateException("APP_PARTNER_PASSWORD 不得与站长口令相同");
        }
        if (username.equalsIgnoreCase(adminUsername)) {
            log.warn("Partner bootstrap skipped: partner username duplicates the admin username");
            return;
        }
        if (repository.findByUsername(username).isEmpty()) {
            repository.save(AdminUserEntity.create(
                username, passwordEncoder.encode(password), AdminUserRole.PARTNER,
                StringUtils.hasText(displayName) ? displayName : username));
            log.info("Partner account created");
        }
    }
}
