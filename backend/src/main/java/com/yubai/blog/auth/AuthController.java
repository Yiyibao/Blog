package com.yubai.blog.auth;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final RateLimiter rateLimiter;
    private final ChallengeService challengeService;
    private final LoginAttemptTracker attemptTracker;

    public AuthController(AuthenticationManager authenticationManager, JwtService jwtService,
                          RateLimiter rateLimiter, ChallengeService challengeService,
                          LoginAttemptTracker attemptTracker) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.rateLimiter = rateLimiter;
        this.challengeService = challengeService;
        this.attemptTracker = attemptTracker;
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
        // FD-0：登录成功审计——多账号后需要能回答"谁在什么时候从哪登录过"
        log.info("login success: user={} ip={}", request.username(), clientIp);
        return ApiResponse.ok(jwtService.issue(request.username()));
    }

    private void rejectIfCoolingDown(String clientIp, String username) {
        attemptTracker.cooldownRemaining(clientIp, username)
            .ifPresent(remaining -> {
                throw new LoginCooldownException(remaining);
            });
    }
}
