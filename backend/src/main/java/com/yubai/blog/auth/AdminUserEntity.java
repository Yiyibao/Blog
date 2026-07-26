package com.yubai.blog.auth;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "admin_users")
public class AdminUserEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 80)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(nullable = false)
    private boolean enabled;

    // FD-6：与 V17 的 check 约束同步，见 AdminUserRole 头注释
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AdminUserRole role;

    @Column(name = "display_name", nullable = false, length = 40)
    private String displayName;

    // FD-9 将用于"踢下线"：仅当 jwt.iat >= sessionsValidFrom 时 token 有效
    @Column(name = "sessions_valid_from", nullable = false)
    private Instant sessionsValidFrom;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AdminUserEntity() {
    }

    private AdminUserEntity(String username, String passwordHash, AdminUserRole role, String displayName) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.enabled = true;
        this.role = role;
        this.displayName = displayName;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.sessionsValidFrom = this.createdAt;
    }

    public static AdminUserEntity create(String username, String passwordHash, AdminUserRole role, String displayName) {
        return new AdminUserEntity(username, passwordHash, role, displayName);
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public boolean isEnabled() { return enabled; }
    public AdminUserRole getRole() { return role; }
    public String getDisplayName() { return displayName; }
    public Instant getSessionsValidFrom() { return sessionsValidFrom; }
}
