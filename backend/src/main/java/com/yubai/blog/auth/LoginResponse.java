package com.yubai.blog.auth;

import java.time.Instant;

// FD-6：role 为单数 String（"ADMIN"/"PARTNER"），displayName 供前端署名与问候
public record LoginResponse(String token, String tokenType, String username,
                            String role, String displayName, Instant expiresAt) {
}
