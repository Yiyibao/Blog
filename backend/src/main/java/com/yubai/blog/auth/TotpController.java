package com.yubai.blog.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.yubai.blog.common.ApiResponse;
import com.yubai.blog.common.ClientIps;
import com.yubai.blog.common.RateLimiter;
import com.yubai.blog.common.TooManyRequestsException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@RestController
@RequestMapping("/api/v1/auth/totp")
public class TotpController {
    private static final Logger log = LoggerFactory.getLogger(TotpController.class);

    static final int VERIFY_LIMIT = 5;
    static final Duration VERIFY_WINDOW = Duration.ofMinutes(1);
    static final int SETUP_LIMIT = 3;
    static final Duration SETUP_WINDOW = Duration.ofMinutes(10);

    private final AdminUserRepository userRepository;
    private final TotpService totpService;
    private final TotpChallengeStore challengeStore;
    private final PasswordEncoder passwordEncoder;
    private final RateLimiter rateLimiter;
    private final RefreshTokenService refreshTokenService;
    private final JwtService jwtService;
    private final boolean cookieSecure;

    public TotpController(AdminUserRepository userRepository, TotpService totpService,
                          TotpChallengeStore challengeStore, PasswordEncoder passwordEncoder,
                          RateLimiter rateLimiter, RefreshTokenService refreshTokenService,
                          JwtService jwtService,
                          @Value("${app.jwt.cookie-secure:true}") boolean cookieSecure) {
        this.userRepository = userRepository;
        this.totpService = totpService;
        this.challengeStore = challengeStore;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = rateLimiter;
        this.refreshTokenService = refreshTokenService;
        this.jwtService = jwtService;
        this.cookieSecure = cookieSecure;
    }

    public record TotpStatusResponse(boolean enabled) {}

    @GetMapping("/status")
    public ApiResponse<TotpStatusResponse> status(
            org.springframework.security.core.Authentication authentication) {
        var user = userRepository.findByUsername(authentication.getName())
            .orElseThrow(() -> new BadCredentialsException("用户不存在"));
        return ApiResponse.ok(new TotpStatusResponse(user.isTotpEnabled()));
    }

    public record SetupRequest(@NotBlank String currentPassword) {}
    public record SetupResponse(String secret, String otpauthUri) {}

    @PostMapping("/setup")
    @Transactional
    public ApiResponse<SetupResponse> setup(
            @Valid @RequestBody SetupRequest request,
            org.springframework.security.core.Authentication authentication,
            HttpServletRequest httpRequest) {
        var clientIp = ClientIps.resolve(httpRequest);
        if (!rateLimiter.tryAcquire("totp:setup:" + authentication.getName(), SETUP_LIMIT, SETUP_WINDOW)) {
            throw new TooManyRequestsException("尝试过于频繁，请稍后再试");
        }
        var user = userRepository.findByUsername(authentication.getName())
            .orElseThrow(() -> new BadCredentialsException("用户不存在"));
        if (user.isTotpEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "两步验证已启用");
        }
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BadCredentialsException("当前密码不正确");
        }
        var secret = totpService.generateSecret();
        var encrypted = totpService.encryptSecret(secret);
        user.setTotpSecretEncrypted(encrypted);
        userRepository.save(user);
        var uri = totpService.buildOtpauthUri(secret, "yubai-blog", user.getUsername());
        log.info("totp setup completed: user={} ip={}", authentication.getName(), clientIp);
        return ApiResponse.ok(new SetupResponse(secret, uri));
    }

    public record EnableRequest(@NotBlank @Pattern(regexp = "\\d{6}") String code) {}

    @PostMapping("/enable")
    @Transactional
    public ResponseEntity<Void> enable(
            @Valid @RequestBody EnableRequest request,
            org.springframework.security.core.Authentication authentication,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        var clientIp = ClientIps.resolve(httpRequest);
        if (!rateLimiter.tryAcquire("totp:enable:" + authentication.getName(), SETUP_LIMIT, SETUP_WINDOW)) {
            throw new TooManyRequestsException("尝试过于频繁，请稍后再试");
        }
        var user = userRepository.findByUsername(authentication.getName())
            .orElseThrow(() -> new BadCredentialsException("用户不存在"));
        if (user.isTotpEnabled()) {
            return ResponseEntity.noContent().build();
        }
        if (user.getTotpSecretEncrypted() == null) {
            return ResponseEntity.badRequest().build();
        }
        var secret = totpService.decryptSecret(user.getTotpSecretEncrypted());
        if (!totpService.verify(request.code(), secret)) {
            throw new BadCredentialsException("验证码不正确");
        }
        user.setTotpEnabled(true);
        userRepository.save(user);
        refreshTokenService.revokeAllByUserId(user.getId());
        log.info("totp enabled: user={} ip={}", authentication.getName(), clientIp);
        return ResponseEntity.noContent().build();
    }

    public record DisableRequest(@NotBlank String currentPassword, @NotBlank @Pattern(regexp = "\\d{6}") String code) {}

    @PostMapping("/disable")
    @Transactional
    public ResponseEntity<Void> disable(
            @Valid @RequestBody DisableRequest request,
            org.springframework.security.core.Authentication authentication,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        var clientIp = ClientIps.resolve(httpRequest);
        if (!rateLimiter.tryAcquire("totp:disable:" + authentication.getName(), SETUP_LIMIT, SETUP_WINDOW)) {
            throw new TooManyRequestsException("尝试过于频繁，请稍后再试");
        }
        var user = userRepository.findByUsername(authentication.getName())
            .orElseThrow(() -> new BadCredentialsException("用户不存在"));
        if (!user.isTotpEnabled()) {
            return ResponseEntity.noContent().build();
        }
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BadCredentialsException("当前密码不正确");
        }
        if (user.getTotpSecretEncrypted() != null) {
            var secret = totpService.decryptSecret(user.getTotpSecretEncrypted());
            if (!totpService.verify(request.code(), secret)) {
                throw new BadCredentialsException("验证码不正确");
            }
        }
        user.setTotpEnabled(false);
        user.setTotpSecretEncrypted(null);
        userRepository.save(user);
        refreshTokenService.revokeAllByUserId(user.getId());
        log.info("totp disabled: user={} ip={}", authentication.getName(), clientIp);
        return ResponseEntity.noContent().build();
    }

    public record VerifyTotpRequest(@NotBlank String challengeId, @NotBlank @Pattern(regexp = "\\d{6}") String code) {}

    @PostMapping("/verify")
    @Transactional
    public ResponseEntity<?> verify(
            @Valid @RequestBody VerifyTotpRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        var clientIp = ClientIps.resolve(httpRequest);
        if (!rateLimiter.tryAcquire("totp:verify:" + clientIp, VERIFY_LIMIT, VERIFY_WINDOW)) {
            throw new TooManyRequestsException("验证尝试过于频繁，请一分钟后再试");
        }
        var stored = challengeStore.find(request.challengeId());
        if (stored == null) {
            return ResponseEntity.status(401).body(Map.of(
                "status", 401, "message", "验证请求已失效，请重新登录", "timestamp", Instant.now()));
        }
        var user = userRepository.findById(stored.userId())
            .orElse(null);
        if (user == null || !user.isEnabled()) {
            return ResponseEntity.status(401).body(Map.of(
                "status", 401, "message", "账号状态异常", "timestamp", Instant.now()));
        }
        if (!user.isTotpEnabled() || user.getTotpSecretEncrypted() == null) {
            return ResponseEntity.status(401).body(Map.of(
                "status", 401, "message", "未启用两步验证", "timestamp", Instant.now()));
        }
        var secret = totpService.decryptSecret(user.getTotpSecretEncrypted());
        if (!totpService.verify(request.code(), secret)) {
            challengeStore.recordFailure(request.challengeId());
            return ResponseEntity.status(401).body(Map.of(
                "status", 401, "message", "验证码不正确", "timestamp", Instant.now()));
        }
        stored = challengeStore.consume(request.challengeId());
        if (stored == null) {
            return ResponseEntity.status(401).body(Map.of(
                "status", 401, "message", "验证请求已失效，请重新登录", "timestamp", Instant.now()));
        }
        var issued = refreshTokenService.issue(user.getId(), stored.remember());
        var accessResponse = jwtService.issue(user, stored.remember());
        var cookie = ResponseCookie.from("refresh_token", issued.raw())
            .httpOnly(true)
            .secure(cookieSecure)
            .sameSite("Strict")
            .path("/api/v1/auth")
            .maxAge(Duration.between(Instant.now(), issued.entity().getExpiresAt()).toSeconds())
            .build();
        httpResponse.addHeader("Set-Cookie", cookie.toString());
        log.info("totp verify success: user={} ip={}", user.getUsername(), clientIp);
        return ResponseEntity.ok(ApiResponse.ok(accessResponse));
    }
}
