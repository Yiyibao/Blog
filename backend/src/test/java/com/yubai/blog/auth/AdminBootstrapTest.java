package com.yubai.blog.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

/** P2-10：管理员引导幂等性——只在账号缺失时创建，凭据未配置时静默跳过。 */
@ExtendWith(MockitoExtension.class)
class AdminBootstrapTest {

    @Mock
    AdminUserRepository repository;

    @Mock
    PasswordEncoder passwordEncoder;

    @Test
    void createsAdminWhenMissing() {
        when(repository.findByUsername("admin")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("a-strong-password-123")).thenReturn("hashed");

        new AdminBootstrap(repository, passwordEncoder, "admin", "a-strong-password-123", "站长").run(null);

        verify(repository).save(any(AdminUserEntity.class));
    }

    @Test
    void isIdempotentWhenAdminAlreadyExists() {
        when(repository.findByUsername("admin"))
            .thenReturn(Optional.of(AdminUserEntity.create("admin", "existing-hash", AdminUserRole.ADMIN, "站长")));

        new AdminBootstrap(repository, passwordEncoder, "admin", "a-strong-password-123", "站长").run(null);

        verify(repository, never()).save(any());
    }

    @Test
    void skipsWhenCredentialsNotConfigured() {
        new AdminBootstrap(repository, passwordEncoder, "", "", "").run(null);
        new AdminBootstrap(repository, passwordEncoder, "admin", " ", "").run(null);

        verify(repository, never()).findByUsername(any());
        verify(repository, never()).save(any());
    }
}
