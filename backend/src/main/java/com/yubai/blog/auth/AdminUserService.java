package com.yubai.blog.auth;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminUserService implements UserDetailsService {
    private final AdminUserRepository repository;
    private final PasswordEncoder passwordEncoder;

    public AdminUserService(AdminUserRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
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

    /**
     * FD-25：自助改密。验当前口令 → 强度校验（与 MemberBootstrap 同源 ≥12）→
     * 换哈希并推进 sessions_valid_from（全部既有 token 即刻失效，客户端需重新登录）。
     */
    @Transactional
    public void changePassword(String username, String currentPassword, String newPassword) {
        var user = repository.findByUsername(username)
            .orElseThrow(() -> new PasswordChangeException("账号状态异常，请重新登录后再试"));
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new PasswordChangeException("当前密码不正确");
        }
        if (newPassword.length() < MemberBootstrap.MIN_PASSWORD_LENGTH) {
            throw new PasswordChangeException("新密码至少 " + MemberBootstrap.MIN_PASSWORD_LENGTH + " 位（推荐一句只有你们懂的短语）");
        }
        if (newPassword.startsWith("replace_with")) {
            throw new PasswordChangeException("新密码不能使用模板占位符");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new PasswordChangeException("新密码不能与当前密码相同");
        }
        user.changePassword(passwordEncoder.encode(newPassword));
        repository.save(user);
    }
}
