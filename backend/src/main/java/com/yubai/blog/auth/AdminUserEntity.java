package com.yubai.blog.auth;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AdminUserEntity() {
    }

    private AdminUserEntity(String username, String passwordHash) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.enabled = true;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public static AdminUserEntity create(String username, String passwordHash) {
        return new AdminUserEntity(username, passwordHash);
    }

    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public boolean isEnabled() { return enabled; }
}
