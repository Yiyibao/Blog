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

/** FD-6：UserDetails 的权限来自实体角色而非写死 ADMIN。 */
@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    @Mock
    private AdminUserRepository repository;

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
}
