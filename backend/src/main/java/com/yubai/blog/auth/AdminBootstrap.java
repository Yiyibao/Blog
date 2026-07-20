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

    public AdminBootstrap(
        AdminUserRepository repository,
        PasswordEncoder passwordEncoder,
        @Value("${app.admin.username}") String username,
        @Value("${app.admin.password}") String password
    ) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.username = username;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            log.warn("Admin bootstrap skipped because local admin credentials are not configured");
            return;
        }
        if (repository.findByUsername(username).isEmpty()) {
            repository.save(AdminUserEntity.create(username, passwordEncoder.encode(password)));
            log.info("Initial administrator account created");
        }
    }
}
