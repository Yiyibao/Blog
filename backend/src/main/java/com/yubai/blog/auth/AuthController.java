package com.yubai.blog.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.yubai.blog.common.ApiResponse;
import com.yubai.blog.common.ClientIps;
import com.yubai.blog.common.RateLimiter;
import com.yubai.blog.common.TooManyRequestsException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    /** P0-3：登录按 IP 限流，防在线暴力猜解；nginx 层另有 limit_req 双保险。 */
    static final int LOGIN_LIMIT = 5;
    static final Duration LOGIN_WINDOW = Duration.ofMinutes(1);
    /** L-7：challenge 获取限流放宽（重试 + 换一张），仍防脚本刷图。 */
    static final int CHALLENGE_LIMIT = 15;
    static final Duration CHALLENGE_WINDOW = Duration.ofMinutes(1);
    /** FD-25：改密限流（按用户名），防持会话在线爆破当前口令。 */
    static final int PASSWORD_CHANGE_LIMIT = 5;
    static final Duration PASSWORD_CHANGE_WINDOW = Duration.ofMinutes(10);

    private static final String REFRESH_COOKIE = "refresh_token";

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final RateLimiter rateLimiter;
    private final ChallengeService challengeService;
    private final LoginAttemptTracker attemptTracker;
    private final AdminUserRepository userRepository;
    private final AdminUserService userService;
    private final RefreshTokenService refreshTokenService;
    private final boolean cookieSecure;

    public AuthController(AuthenticationManager authenticationManager, JwtService jwtService,
                          RateLimiter rateLimiter, ChallengeService challengeService,
                          LoginAttemptTracker attemptTracker, AdminUserRepository userRepository,
                          AdminUserService userService, RefreshTokenService refreshTokenService,
                          @Value("${app.jwt.cookie-secure:true}") boolean cookieSecure) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.rateLimiter = rateLimiter;
        this.challengeService = challengeService;
        this.attemptTracker = attemptTracker;
        this.userRepository = userRepository;
        this.userService = userService;
        this.refreshTokenService = refreshTokenService;
        this.cookieSecure = cookieSecure;
    }

    /** L-7：登录前必须先取 challenge；按 IP/用户名风险状态自动升级为图形验证码。 */
    @GetMapping("/challenge")
    public ApiResponse<ChallengeResponse> challenge(@RequestParam(required = false) String username,
                                                    HttpServletRequest httpRequest) {
        var clientIp = ClientIps.resolve(httpRequest);
        rejectIfCoolingDown(clientIp, username);
        if (!rateLimiter.tryAcquire("challenge:" + clientIp, CHALLENGE_LIMIT, CHALLENGE_WINDOW)) {
            throw new TooManyRequestsException("请求过于频繁，请稍后再试");
        }
        return ApiResponse.ok(challengeService.create(clientIp, username));
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest,
                                            HttpServletResponse httpResponse) {
        var clientIp = ClientIps.resolve(httpRequest);
        rejectIfCoolingDown(clientIp, request.username());
        if (!rateLimiter.tryAcquire("login:" + clientIp, LOGIN_LIMIT, LOGIN_WINDOW)) {
            throw new TooManyRequestsException("登录尝试过于频繁，请一分钟后再试");
        }
        challengeService.verify(request.challengeId(), request.nonce(), request.captchaAnswer(),
            clientIp, request.username());
        try {
            authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password())
            );
        } catch (BadCredentialsException e) {
            attemptTracker.recordFailure(clientIp, request.username());
            throw e;
        }
        attemptTracker.clear(clientIp, request.username());
        var user = userRepository.findByUsername(request.username())
            .orElseThrow(() -> new BadCredentialsException("用户名或密码错误"));
        log.info("login success: user={} role={} ip={}", request.username(), user.getRole(), clientIp);

        var issued = refreshTokenService.issue(user.getId(), request.rememberRequested());
        var accessResponse = jwtService.issue(user, request.rememberRequested());
        setRefreshCookie(httpResponse, issued.raw(), issued.entity());
        return ApiResponse.ok(accessResponse);
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshTokenValue,
            HttpServletResponse httpResponse) {
        if (refreshTokenValue == null || refreshTokenValue.isBlank()) {
            clearRefreshCookie(httpResponse);
            return ResponseEntity.status(401).body(Map.of(
                "status", 401, "message", "Refresh token missing", "timestamp", Instant.now()));
        }
        try {
            var result = refreshTokenService.rotate(refreshTokenValue);
            var user = userRepository.findById(result.newEntity().getUserId())
                .orElseThrow(() -> new RefreshTokenService.RefreshTokenException("user not found"));
            var loginResponse = jwtService.issue(user, false);
            setRefreshCookie(httpResponse, result.rawToken(), result.newEntity());
            return ResponseEntity.ok(ApiResponse.ok(loginResponse));
        } catch (RefreshTokenService.RefreshTokenException e) {
            clearRefreshCookie(httpResponse);
            return ResponseEntity.status(401).body(Map.of(
                "status", 401, "message", e.getMessage(), "timestamp", Instant.now()));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshTokenValue,
            HttpServletResponse httpResponse) {
        if (refreshTokenValue != null && !refreshTokenValue.isBlank()) {
            refreshTokenService.revoke(refreshTokenValue);
        }
        clearRefreshCookie(httpResponse);
        return ResponseEntity.noContent().build();
    }

    private void setRefreshCookie(HttpServletResponse response, String rawToken, RefreshTokenEntity entity) {
        var maxAge = Duration.between(Instant.now(), entity.getExpiresAt()).toSeconds();
        var cookie = ResponseCookie.from(REFRESH_COOKIE, rawToken)
            .httpOnly(true)
            .secure(cookieSecure)
            .sameSite("Strict")
            .path("/api/v1/auth")
            .maxAge(Math.max(1, maxAge))
            .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        var cookie = ResponseCookie.from(REFRESH_COOKIE, "")
            .httpOnly(true)
            .secure(cookieSecure)
            .sameSite("Strict")
            .path("/api/v1/auth")
            .maxAge(0)
            .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    private void rejectIfCoolingDown(String clientIp, String username) {
        attemptTracker.cooldownRemaining(clientIp, username)
            .ifPresent(remaining -> {
                throw new LoginCooldownException(remaining);
            });
    }

    /** FD-25：自助改密（已登录任意角色）。成功即 sessions_valid_from 推进，旧 refresh 全部撤销，客户端应清会话重登。 */
    @PutMapping("/password")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<Void> changePassword(
        @Valid @RequestBody PasswordChangeRequest request,
        org.springframework.security.core.Authentication authentication,
        HttpServletRequest httpRequest,
        HttpServletResponse httpResponse) {
        var clientIp = ClientIps.resolve(httpRequest);
        if (!rateLimiter.tryAcquire("pwdchange:" + authentication.getName(), PASSWORD_CHANGE_LIMIT, PASSWORD_CHANGE_WINDOW)) {
            throw new TooManyRequestsException("尝试过于频繁，请稍后再试");
        }
        userService.changePassword(authentication.getName(), request.currentPassword(), request.newPassword());
        var user = userRepository.findByUsername(authentication.getName())
            .orElseThrow(() -> new BadCredentialsException("用户不存在"));
        refreshTokenService.revokeAllByUserId(user.getId());
        clearRefreshCookie(httpResponse);
        log.info("password changed: user={} ip={}", authentication.getName(), clientIp);
        return ResponseEntity.noContent().build();
    }
}
