package com.yubai.blog.auth;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AdminUserService implements UserDetailsService {
    private final AdminUserRepository repository;

    public AdminUserService(AdminUserRepository repository) {
        this.repository = repository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        var admin = repository.findByUsername(username)
            .orElseThrow(() -> new UsernameNotFoundException("管理员账号不存在"));
        return User.withUsername(admin.getUsername())
            .password(admin.getPasswordHash())
            // FD-6：角色读实体，不再写死 ADMIN
            .roles(admin.getRole().name())
            .disabled(!admin.isEnabled())
            .build();
    }
}
