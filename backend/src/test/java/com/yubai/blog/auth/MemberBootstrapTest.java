package com.yubai.blog.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class MemberBootstrapTest {

    private static final String ADMIN_USER = "boss";
    private static final String ADMIN_PASS = "admin-pass-16-chars!";

    @Mock
    private AdminUserRepository repository;
    @Mock
    private PasswordEncoder passwordEncoder;

    private MemberBootstrap bootstrap(String username, String password, String displayName) {
        return new MemberBootstrap(repository, passwordEncoder, ADMIN_USER, ADMIN_PASS, username, password, displayName);
    }

    @Test
    void createsPartnerWithRoleAndDefaultDisplayName() {
        when(repository.findByUsername("gf")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("红烧肉要少放糖多放辣2026")).thenReturn("encoded");

        bootstrap("gf", "红烧肉要少放糖多放辣2026", "").run(null);

        var captor = ArgumentCaptor.forClass(AdminUserEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo(AdminUserRole.PARTNER);
        assertThat(captor.getValue().getUsername()).isEqualTo("gf");
        assertThat(captor.getValue().getDisplayName()).as("未配置展示名时回退用户名").isEqualTo("gf");
        assertThat(captor.getValue().getPasswordHash()).isEqualTo("encoded");
    }

    @Test
    void usesConfiguredDisplayName() {
        when(repository.findByUsername("gf")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(any())).thenReturn("encoded");

        bootstrap("gf", "红烧肉要少放糖多放辣2026", "小甜").run(null);

        var captor = ArgumentCaptor.forClass(AdminUserEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getDisplayName()).isEqualTo("小甜");
    }

    @Test
    void isCreateOnlyAndNeverRotatesExistingAccount() {
        when(repository.findByUsername("gf"))
            .thenReturn(Optional.of(AdminUserEntity.create("gf", "old-hash", AdminUserRole.PARTNER, "gf")));

        bootstrap("gf", "红烧肉要少放糖多放辣2026", "").run(null);

        verify(repository, never()).save(any());
    }

    @Test
    void skipsSilentlyWhenNotConfigured() {
        bootstrap("", "", "").run(null);
        verifyNoInteractions(repository, passwordEncoder);
    }

    @Test
    void rejectsShortPassword() {
        assertThatThrownBy(() -> bootstrap("gf", "short-pass", "").run(null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("12");
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsTemplatePlaceholderPassword() {
        assertThatThrownBy(() -> bootstrap("gf", "replace_with_a_real_pass", "").run(null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("占位符");
    }

    @Test
    void rejectsPasswordIdenticalToAdmin() {
        assertThatThrownBy(() -> bootstrap("gf", ADMIN_PASS, "").run(null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("站长口令");
    }

    @Test
    void skipsWhenUsernameCollidesWithAdminIgnoringCase() {
        bootstrap("BOSS", "红烧肉要少放糖多放辣2026", "").run(null);
        verify(repository, never()).save(any());
    }
}
