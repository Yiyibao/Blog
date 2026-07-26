package com.yubai.blog.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

/** FD-6：UserDetails 的权限来自实体角色而非写死 ADMIN；FD-25：自助改密规则。 */
@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    @Mock
    private AdminUserRepository repository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private AdminUserService service;

    private AdminUserEntity user(String username, AdminUserRole role) {
        var entity = mock(AdminUserEntity.class);
        when(entity.getUsername()).thenReturn(username);
        when(entity.getPasswordHash()).thenReturn("hash");
        when(entity.getRole()).thenReturn(role);
        when(entity.isEnabled()).thenReturn(true);
        return entity;
    }

    @Test
    void partnerLoadsWithPartnerAuthorityOnly() {
        var partner = user("gf", AdminUserRole.PARTNER);
        when(repository.findByUsername("gf")).thenReturn(Optional.of(partner));
        var details = service.loadUserByUsername("gf");
        assertThat(details.getAuthorities()).extracting(GrantedAuthority::getAuthority)
            .containsExactly("ROLE_PARTNER");
    }

    @Test
    void adminLoadsWithAdminAuthority() {
        var admin = user("boss", AdminUserRole.ADMIN);
        when(repository.findByUsername("boss")).thenReturn(Optional.of(admin));
        var details = service.loadUserByUsername("boss");
        assertThat(details.getAuthorities()).extracting(GrantedAuthority::getAuthority)
            .containsExactly("ROLE_ADMIN");
    }

    @Test
    void missingUserThrows() {
        when(repository.findByUsername("nobody")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.loadUserByUsername("nobody"))
            .isInstanceOf(UsernameNotFoundException.class);
    }

    // ---- FD-25 自助改密 ----

    private AdminUserEntity realUser() {
        return AdminUserEntity.create("gf", "old-hash", AdminUserRole.PARTNER, "小伙伴");
    }

    @Test
    void changePasswordVerifiesCurrentUpdatesHashAndBumpsSessionsValidFrom() {
        var user = realUser();
        var before = user.getSessionsValidFrom();
        when(repository.findByUsername("gf")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("current-ok", "old-hash")).thenReturn(true);
        when(passwordEncoder.matches("新密码是一句好记的短语呀", "old-hash")).thenReturn(false);
        when(passwordEncoder.encode("新密码是一句好记的短语呀")).thenReturn("new-hash");

        service.changePassword("gf", "current-ok", "新密码是一句好记的短语呀");

        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        assertThat(user.getSessionsValidFrom()).as("改密必须推进会话有效起点（踢掉全部旧 token）")
            .isAfterOrEqualTo(before);
        org.mockito.Mockito.verify(repository).save(user);
    }

    @Test
    void changePasswordRejectsWrongCurrentPassword() {
        var user = realUser();
        when(repository.findByUsername("gf")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "old-hash")).thenReturn(false);
        assertThatThrownBy(() -> service.changePassword("gf", "wrong", "新密码是一句好记的短语呀"))
            .isInstanceOf(PasswordChangeException.class)
            .hasMessageContaining("当前密码");
    }

    @Test
    void changePasswordRejectsShortNewPassword() {
        var user = realUser();
        when(repository.findByUsername("gf")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("current-ok", "old-hash")).thenReturn(true);
        assertThatThrownBy(() -> service.changePassword("gf", "current-ok", "too-short"))
            .isInstanceOf(PasswordChangeException.class)
            .hasMessageContaining("12");
    }

    @Test
    void changePasswordRejectsReusingCurrentPassword() {
        var user = realUser();
        when(repository.findByUsername("gf")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("current-ok", "old-hash")).thenReturn(true);
        when(passwordEncoder.matches("current-ok-current-ok", "old-hash")).thenReturn(true);
        assertThatThrownBy(() -> service.changePassword("gf", "current-ok", "current-ok-current-ok"))
            .isInstanceOf(PasswordChangeException.class)
            .hasMessageContaining("相同");
    }
}
