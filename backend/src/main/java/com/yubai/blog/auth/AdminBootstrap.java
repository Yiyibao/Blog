package com.yubai.blog.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Component
public class AdminBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AdminUserRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String password;
    private final String displayName;

    public AdminBootstrap(
        AdminUserRepository repository,
        PasswordEncoder passwordEncoder,
        @Value("${app.admin.username}") String username,
        @Value("${app.admin.password}") String password,
        @Value("${app.admin.display-name:}") String displayName
    ) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.username = username;
        this.password = password;
        this.displayName = displayName;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            log.warn("Admin bootstrap skipped because local admin credentials are not configured");
            return;
        }
        // FD-6：站长口令只告警不阻断（本机历史口令偏短，阻断会让应用起不来）；
        // 伴侣账号的口令强度由 MemberBootstrap 强制
        if (password.length() < 16) {
            log.warn("APP_ADMIN_PASSWORD 长度不足 16 位，公网部署建议更换为强口令（如 openssl rand -base64 16）");
        }
        if (repository.findByUsername(username).isEmpty()) {
            repository.save(AdminUserEntity.create(
                username, passwordEncoder.encode(password), AdminUserRole.ADMIN,
                StringUtils.hasText(displayName) ? displayName : username));
            log.info("Initial administrator account created");
        }
    }
}
