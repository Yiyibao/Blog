package com.yubai.blog.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;

import com.yubai.blog.common.ApiResponse;
import com.yubai.blog.common.RateLimiter;

class AuthControllerTotpTest {

    @Test
    void enabledUserReceivesChallengeBeforeAnySessionToken() {
        var authenticationManager = mock(AuthenticationManager.class);
        var jwtService = mock(JwtService.class);
        var rateLimiter = mock(RateLimiter.class);
        var challengeService = mock(ChallengeService.class);
        var attemptTracker = mock(LoginAttemptTracker.class);
        var userRepository = mock(AdminUserRepository.class);
        var userService = mock(AdminUserService.class);
        var refreshTokenService = mock(RefreshTokenService.class);
        var totpChallenges = mock(TotpChallengeStore.class);
        var user = mock(AdminUserEntity.class);
        when(rateLimiter.tryAcquire(any(), any(Integer.class), any())).thenReturn(true);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
        when(user.getId()).thenReturn(7L);
        when(user.getRole()).thenReturn(AdminUserRole.ADMIN);
        when(user.isTotpEnabled()).thenReturn(true);
        when(totpChallenges.create(7L, true)).thenReturn("opaque-challenge");
        var controller = new AuthController(authenticationManager, jwtService, rateLimiter, challengeService,
            attemptTracker, userRepository, userService, refreshTokenService, totpChallenges, false);
        var request = new LoginRequest("admin", "password", "human", "nonce", null, true);
        var httpRequest = new MockHttpServletRequest();
        var httpResponse = new MockHttpServletResponse();

        var response = controller.login(request, httpRequest, httpResponse);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).isInstanceOf(ApiResponse.class);
        var body = (ApiResponse<?>) response.getBody();
        assertThat(body.code()).isEqualTo(202);
        assertThat(body.data()).isEqualTo(new AuthController.TotpRequiredResponse("opaque-challenge"));
        assertThat(httpResponse.getHeader("Set-Cookie")).contains("refresh_token=", "Max-Age=0");
        verify(refreshTokenService, never()).issue(any(), any(Boolean.class));
        verify(jwtService, never()).issue(any(), any(Boolean.class));
    }
}
