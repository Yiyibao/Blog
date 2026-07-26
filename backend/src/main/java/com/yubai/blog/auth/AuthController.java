package com.yubai.blog.auth;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
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

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final RateLimiter rateLimiter;
    private final ChallengeService challengeService;
    private final LoginAttemptTracker attemptTracker;
    private final AdminUserRepository userRepository;
    private final AdminUserService userService;

    public AuthController(AuthenticationManager authenticationManager, JwtService jwtService,
                          RateLimiter rateLimiter, ChallengeService challengeService,
                          LoginAttemptTracker attemptTracker, AdminUserRepository userRepository,
                          AdminUserService userService) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.rateLimiter = rateLimiter;
        this.challengeService = challengeService;
        this.attemptTracker = attemptTracker;
        this.userRepository = userRepository;
        this.userService = userService;
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
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        var clientIp = ClientIps.resolve(httpRequest);
        rejectIfCoolingDown(clientIp, request.username());
        if (!rateLimiter.tryAcquire("login:" + clientIp, LOGIN_LIMIT, LOGIN_WINDOW)) {
            throw new TooManyRequestsException("登录尝试过于频繁，请一分钟后再试");
        }
        // L-7：人机验证先于口令校验；challenge 一次性使用，验证即作废
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
        // FD-6：认证已通过，读实体签发含角色/uid/displayName 的 token；
        // 文案与"账号不存在"保持一致，不泄露用户名存在性
        var user = userRepository.findByUsername(request.username())
            .orElseThrow(() -> new BadCredentialsException("用户名或密码错误"));
        // FD-0：登录成功审计——多账号后需要能回答"谁在什么时候从哪登录过"
        log.info("login success: user={} role={} ip={}", request.username(), user.getRole(), clientIp);
        return ApiResponse.ok(jwtService.issue(user, request.rememberRequested()));
    }

    private void rejectIfCoolingDown(String clientIp, String username) {
        attemptTracker.cooldownRemaining(clientIp, username)
            .ifPresent(remaining -> {
                throw new LoginCooldownException(remaining);
            });
    }

    /** FD-25：自助改密（已登录任意角色）。成功即 sessions_valid_from 推进，客户端应清会话重登。 */
    @PutMapping("/password")
    public org.springframework.http.ResponseEntity<Void> changePassword(
        @Valid @RequestBody PasswordChangeRequest request,
        org.springframework.security.core.Authentication authentication,
        HttpServletRequest httpRequest) {
        var clientIp = ClientIps.resolve(httpRequest);
        // 已登录仍限流：防拿到会话后在线爆破 currentPassword
        if (!rateLimiter.tryAcquire("pwdchange:" + authentication.getName(), PASSWORD_CHANGE_LIMIT, PASSWORD_CHANGE_WINDOW)) {
            throw new TooManyRequestsException("尝试过于频繁，请稍后再试");
        }
        userService.changePassword(authentication.getName(), request.currentPassword(), request.newPassword());
        log.info("password changed: user={} ip={}", authentication.getName(), clientIp);
        return org.springframework.http.ResponseEntity.noContent().build();
    }
}
