package com.yubai.blog.auth;

import java.time.Duration;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yubai.blog.common.ApiResponse;
import com.yubai.blog.common.ClientIps;
import com.yubai.blog.common.RateLimiter;
import com.yubai.blog.common.TooManyRequestsException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    /** P0-3：登录按 IP 限流，防在线暴力猜解；nginx 层另有 limit_req 双保险。 */
    static final int LOGIN_LIMIT = 5;
    static final Duration LOGIN_WINDOW = Duration.ofMinutes(1);

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final RateLimiter rateLimiter;

    public AuthController(AuthenticationManager authenticationManager, JwtService jwtService, RateLimiter rateLimiter) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        var clientIp = ClientIps.resolve(httpRequest);
        if (!rateLimiter.tryAcquire("login:" + clientIp, LOGIN_LIMIT, LOGIN_WINDOW)) {
            throw new TooManyRequestsException("登录尝试过于频繁，请一分钟后再试");
        }
        authenticationManager.authenticate(
            UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password())
        );
        return ApiResponse.ok(jwtService.issue(request.username()));
    }
}
