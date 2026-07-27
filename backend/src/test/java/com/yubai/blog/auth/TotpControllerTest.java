package com.yubai.blog.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.yubai.blog.common.RateLimiter;

class TotpControllerTest {
    private AdminUserRepository repository;
    private TotpService totpService;
    private PasswordEncoder passwordEncoder;
    private RateLimiter rateLimiter;
    private RefreshTokenService refreshTokens;
    private JwtService jwtService;
    private TotpChallengeStore challenges;
    private MutableClock clock;
    private TotpController controller;
    private AdminUserEntity user;
    private UsernamePasswordAuthenticationToken authentication;

    @BeforeEach
    void setUp() {
        repository = mock(AdminUserRepository.class);
        totpService = mock(TotpService.class);
        passwordEncoder = mock(PasswordEncoder.class);
        rateLimiter = mock(RateLimiter.class);
        refreshTokens = mock(RefreshTokenService.class);
        jwtService = mock(JwtService.class);
        clock = new MutableClock();
        challenges = new TotpChallengeStore(clock);
        controller = new TotpController(repository, totpService, challenges, passwordEncoder, rateLimiter,
            refreshTokens, jwtService, false);
        user = mock(AdminUserEntity.class);
        authentication = UsernamePasswordAuthenticationToken.authenticated("admin", "", Set.of());
        when(rateLimiter.tryAcquire(any(), any(Integer.class), any())).thenReturn(true);
        when(repository.findByUsername("admin")).thenReturn(Optional.of(user));
        when(user.getId()).thenReturn(7L);
        when(user.getUsername()).thenReturn("admin");
        when(user.isEnabled()).thenReturn(true);
    }

    @Test
    void setupStoresOnlyEncryptedSecret() {
        when(user.isTotpEnabled()).thenReturn(false);
        when(user.getPasswordHash()).thenReturn("hash");
        when(passwordEncoder.matches("password", "hash")).thenReturn(true);
        when(totpService.generateSecret()).thenReturn("BASE32SECRET");
        when(totpService.encryptSecret("BASE32SECRET")).thenReturn("ciphertext");
        when(totpService.buildOtpauthUri("BASE32SECRET", "yubai-blog", "admin")).thenReturn("otpauth://setup");

        var result = controller.setup(new TotpController.SetupRequest("password"), authentication,
            new MockHttpServletRequest());

        assertThat(result.data().secret()).isEqualTo("BASE32SECRET");
        verify(user).setTotpSecretEncrypted("ciphertext");
        verify(user, times(0)).setTotpSecretEncrypted("BASE32SECRET");
    }

    @Test
    void enableVerifiesCodeAndRevokesExistingRefreshSessions() {
        when(user.isTotpEnabled()).thenReturn(false);
        when(user.getTotpSecretEncrypted()).thenReturn("ciphertext");
        when(totpService.decryptSecret("ciphertext")).thenReturn("BASE32SECRET");
        when(totpService.verify("123456", "BASE32SECRET")).thenReturn(true);

        var response = controller.enable(new TotpController.EnableRequest("123456"), authentication,
            new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(user).setTotpEnabled(true);
        verify(refreshTokens).revokeAllByUserId(7L);
    }

    @Test
    void disableRequiresPasswordAndCodeThenClearsSecret() {
        when(user.isTotpEnabled()).thenReturn(true);
        when(user.getPasswordHash()).thenReturn("hash");
        when(passwordEncoder.matches("password", "hash")).thenReturn(true);
        when(user.getTotpSecretEncrypted()).thenReturn("ciphertext");
        when(totpService.decryptSecret("ciphertext")).thenReturn("BASE32SECRET");
        when(totpService.verify("123456", "BASE32SECRET")).thenReturn(true);

        var response = controller.disable(new TotpController.DisableRequest("password", "123456"), authentication,
            new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(user).setTotpEnabled(false);
        verify(user).setTotpSecretEncrypted(null);
        verify(refreshTokens).revokeAllByUserId(7L);
    }

    @Test
    void invalidCodeDoesNotConsumeChallengeUntilAttemptLimit() {
        var token = challenges.create(7L, false);
        stubEnabledUser();
        when(totpService.verify("000000", "BASE32SECRET")).thenReturn(false);

        var response = controller.verify(new TotpController.VerifyTotpRequest(token, "000000"),
            new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(challenges.find(token)).isNotNull();
    }

    @Test
    void validChallengeIsSingleUseAndIssuesOneSession() {
        var token = challenges.create(7L, true);
        stubEnabledUser();
        when(totpService.verify("123456", "BASE32SECRET")).thenReturn(true);
        var refreshEntity = mock(RefreshTokenEntity.class);
        when(refreshEntity.getExpiresAt()).thenReturn(Instant.now().plus(Duration.ofDays(1)));
        when(refreshTokens.issue(7L, true)).thenReturn(new RefreshTokenService.IssuedToken("refresh", refreshEntity));
        when(jwtService.issue(user, true)).thenReturn(new LoginResponse("access", "Bearer", "admin", "ADMIN",
            "Admin", Instant.now().plusSeconds(900), Set.of()));

        var first = controller.verify(new TotpController.VerifyTotpRequest(token, "123456"),
            new MockHttpServletRequest(), new MockHttpServletResponse());
        var replay = controller.verify(new TotpController.VerifyTotpRequest(token, "123456"),
            new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(replay.getStatusCode().value()).isEqualTo(401);
        verify(refreshTokens, times(1)).issue(7L, true);
    }

    @Test
    void expiredChallengeCannotIssueSession() {
        var token = challenges.create(7L, false);
        clock.advance(Duration.ofMinutes(6));

        var response = controller.verify(new TotpController.VerifyTotpRequest(token, "123456"),
            new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        verify(refreshTokens, times(0)).issue(any(), any(Boolean.class));
    }

    private void stubEnabledUser() {
        when(repository.findById(7L)).thenReturn(Optional.of(user));
        when(user.isTotpEnabled()).thenReturn(true);
        when(user.getTotpSecretEncrypted()).thenReturn("ciphertext");
        when(totpService.decryptSecret("ciphertext")).thenReturn("BASE32SECRET");
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.EPOCH;

        void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
