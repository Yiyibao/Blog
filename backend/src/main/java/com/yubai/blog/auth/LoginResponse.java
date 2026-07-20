package com.yubai.blog.auth;

import java.time.Instant;

public record LoginResponse(String token, String tokenType, String username, Instant expiresAt) {
}
