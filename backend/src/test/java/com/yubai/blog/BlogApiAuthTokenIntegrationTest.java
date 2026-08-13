package com.yubai.blog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yubai.blog.auth.ChallengeService;
import com.yubai.blog.auth.LoginAttemptTracker;
import com.yubai.blog.common.RateLimiter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 6C-1：独立的 refresh-token 生命周期集成测试。
 *
 * <p>从 BlogApiIntegrationTest 拆出认证令牌竞态用例，避免单个全栈测试类继续承载互不相关的 认证、内容、菜谱和 AI 领域场景。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(BlogApiIntegrationTest.FixedCaptchaConfig.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BlogApiAuthTokenIntegrationTest {
    private static final String FIXED_CAPTCHA_TEXT = "AB3CD";

    @BeforeAll
    static void prepareDatabase() {
        TestDatabase.resetSchema();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
        registry.add("spring.flyway.clean-disabled", () -> "false");
        registry.add("app.database.remove-historical-demo-content", () -> "false");
        registry.add("app.jwt.secret", () -> "integration-test-secret-key-32chars!");
        registry.add("app.admin.username", () -> "admin");
        registry.add("app.admin.password", () -> "admin-pass-12345");
        registry.add("app.admin.display-name", () -> "测试站长");
        registry.add("app.partner.username", () -> "partner");
        registry.add("app.partner.password", () -> "partner-pass-12345");
        registry.add("app.partner.display-name", () -> "测试伴侣");
        registry.add("app.cors.allowed-origins", () -> "http://localhost:5173");
        registry.add("app.site-url", () -> "http://localhost:5173");
        registry.add("app.ai.master-key", () -> "integration-test-master-key-32chars!");
        registry.add("app.ai.allow-local-endpoints", () -> "true");
    }

    @Autowired MockMvc mockMvc;

    @Autowired ObjectMapper objectMapper;

    @Autowired RateLimiter rateLimiter;

    @Autowired ChallengeService challengeService;

    @Autowired LoginAttemptTracker attemptTracker;

    @BeforeEach
    void resetRateLimiter() {
        rateLimiter.reset();
        challengeService.reset();
        attemptTracker.reset();
    }

    @Test
    @Order(1)
    void refreshTokenFlowLoginSetsCookieRefreshReturnsNewToken() throws Exception {
        rateLimiter.reset();
        MvcResult loginResult =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(loginBody("admin", "admin-pass-12345")))
                        .andExpect(status().isOk())
                        .andReturn();

        String refreshCookie = extractSetCookie(loginResult.getResponse(), "refresh_token");
        assertThat(refreshCookie).isNotEmpty();

        MvcResult refreshResult =
                mockMvc.perform(
                                post("/api/v1/auth/refresh")
                                        .cookie(
                                                new jakarta.servlet.http.Cookie(
                                                        "refresh_token", refreshCookie)))
                        .andExpect(status().isOk())
                        .andReturn();
        JsonNode refreshData =
                objectMapper
                        .readTree(refreshResult.getResponse().getContentAsString())
                        .path("data");
        assertThat(refreshData.path("token").asText()).isNotEmpty();
        assertThat(refreshData.path("username").asText()).isEqualTo("admin");
        assertThat(refreshData.path("role").asText()).isEqualTo("ADMIN");

        String newRefreshCookie = extractSetCookie(refreshResult.getResponse(), "refresh_token");
        assertThat(newRefreshCookie).isNotEmpty().isNotEqualTo(refreshCookie);
    }

    @Test
    @Order(2)
    void refreshTokenReplayDetectedAndFamilyRevoked() throws Exception {
        rateLimiter.reset();
        MvcResult loginResult =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(loginBody("admin", "admin-pass-12345")))
                        .andExpect(status().isOk())
                        .andReturn();
        String refreshCookie = extractSetCookie(loginResult.getResponse(), "refresh_token");

        MvcResult firstRefresh =
                mockMvc.perform(
                                post("/api/v1/auth/refresh")
                                        .cookie(
                                                new jakarta.servlet.http.Cookie(
                                                        "refresh_token", refreshCookie)))
                        .andExpect(status().isOk())
                        .andReturn();
        String rotatedCookie = extractSetCookie(firstRefresh.getResponse(), "refresh_token");

        mockMvc.perform(
                        post("/api/v1/auth/refresh")
                                .cookie(
                                        new jakarta.servlet.http.Cookie(
                                                "refresh_token", refreshCookie)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(containsString("already used")));

        mockMvc.perform(
                        post("/api/v1/auth/refresh")
                                .cookie(
                                        new jakarta.servlet.http.Cookie(
                                                "refresh_token", rotatedCookie)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(containsString("already used")));
    }

    @Test
    @Order(3)
    void concurrentRefreshOnlyOneSucceedsThenWinnerRevoked() throws Exception {
        rateLimiter.reset();
        MvcResult loginResult =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(loginBody("admin", "admin-pass-12345")))
                        .andExpect(status().isOk())
                        .andReturn();
        String refreshCookie = extractSetCookie(loginResult.getResponse(), "refresh_token");
        assertThat(refreshCookie).isNotEmpty();

        var statuses = new java.util.concurrent.CopyOnWriteArrayList<MvcResult>();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var tasks =
                    java.util.List.of(
                            (java.util.concurrent.Callable<Void>)
                                    () -> {
                                        var result =
                                                mockMvc.perform(
                                                                post("/api/v1/auth/refresh")
                                                                        .cookie(
                                                                                new jakarta.servlet
                                                                                        .http
                                                                                        .Cookie(
                                                                                        "refresh_token",
                                                                                        refreshCookie)))
                                                        .andReturn();
                                        statuses.add(result);
                                        return null;
                                    },
                            (java.util.concurrent.Callable<Void>)
                                    () -> {
                                        var result =
                                                mockMvc.perform(
                                                                post("/api/v1/auth/refresh")
                                                                        .cookie(
                                                                                new jakarta.servlet
                                                                                        .http
                                                                                        .Cookie(
                                                                                        "refresh_token",
                                                                                        refreshCookie)))
                                                        .andReturn();
                                        statuses.add(result);
                                        return null;
                                    });
            for (var future : pool.invokeAll(tasks)) future.get();
        }

        var codes = statuses.stream().map(r -> r.getResponse().getStatus()).sorted().toList();
        assertEquals(
                java.util.List.of(200, 401), codes, "exactly one refresh succeeds, one replays");

        MvcResult winner =
                statuses.stream()
                        .filter(r -> r.getResponse().getStatus() == 200)
                        .findFirst()
                        .orElseThrow();
        String winnerCookie = extractSetCookie(winner.getResponse(), "refresh_token");
        mockMvc.perform(
                        post("/api/v1/auth/refresh")
                                .cookie(
                                        new jakarta.servlet.http.Cookie(
                                                "refresh_token", winnerCookie)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(containsString("already used")));
    }

    @Test
    @Order(4)
    void refreshWithoutCookieReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")).andExpect(status().isUnauthorized());
    }

    @Test
    @Order(5)
    void logoutRevokesRefreshTokenAndClearsCookie() throws Exception {
        rateLimiter.reset();
        MvcResult loginResult =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(loginBody("admin", "admin-pass-12345")))
                        .andExpect(status().isOk())
                        .andReturn();
        String refreshCookie = extractSetCookie(loginResult.getResponse(), "refresh_token");

        mockMvc.perform(
                        post("/api/v1/auth/logout")
                                .cookie(
                                        new jakarta.servlet.http.Cookie(
                                                "refresh_token", refreshCookie)))
                .andExpect(status().isNoContent());

        mockMvc.perform(
                        post("/api/v1/auth/refresh")
                                .cookie(
                                        new jakarta.servlet.http.Cookie(
                                                "refresh_token", refreshCookie)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(6)
    void refreshTokenExpiresEventually() throws Exception {
        rateLimiter.reset();
        MvcResult loginResult =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(loginBody("admin", "admin-pass-12345")))
                        .andExpect(status().isOk())
                        .andReturn();
        String refreshCookie = extractSetCookie(loginResult.getResponse(), "refresh_token");
        assertThat(refreshCookie).isNotEmpty();

        MvcResult firstRefresh =
                mockMvc.perform(
                                post("/api/v1/auth/refresh")
                                        .cookie(
                                                new jakarta.servlet.http.Cookie(
                                                        "refresh_token", refreshCookie)))
                        .andExpect(status().isOk())
                        .andReturn();
        String cookie2 = extractSetCookie(firstRefresh.getResponse(), "refresh_token");

        MvcResult secondRefresh =
                mockMvc.perform(
                                post("/api/v1/auth/refresh")
                                        .cookie(
                                                new jakarta.servlet.http.Cookie(
                                                        "refresh_token", cookie2)))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(extractSetCookie(secondRefresh.getResponse(), "refresh_token")).isNotEmpty();
    }

    private static String extractSetCookie(
            org.springframework.mock.web.MockHttpServletResponse response, String cookieName) {
        var setCookie = response.getHeader("Set-Cookie");
        if (setCookie == null) return "";
        for (var part : setCookie.split(";")) {
            part = part.trim();
            if (part.startsWith(cookieName + "=")) {
                return part.substring((cookieName + "=").length());
            }
        }
        return "";
    }

    private String loginBody(String username, String password) throws Exception {
        return loginBody(username, password, false);
    }

    private String loginBody(String username, String password, boolean remember) throws Exception {
        JsonNode challenge = fetchChallenge(username);
        var body =
                objectMapper
                        .createObjectNode()
                        .put("username", username)
                        .put("password", password)
                        .put("challengeId", challenge.path("challengeId").asText())
                        .put(
                                "nonce",
                                solvePow(
                                        challenge.path("salt").asText(),
                                        challenge.path("difficulty").asInt()));
        if (remember) {
            body.put("remember", true);
        }
        if ("IMAGE".equals(challenge.path("type").asText())) {
            body.put("captchaAnswer", FIXED_CAPTCHA_TEXT);
        }
        return objectMapper.writeValueAsString(body);
    }

    private JsonNode fetchChallenge(String username) throws Exception {
        var result =
                mockMvc.perform(get("/api/v1/auth/challenge").param("username", username))
                        .andExpect(status().isOk())
                        .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private static String solvePow(String salt, int difficulty) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            var prefix = "0".repeat(difficulty);
            for (long nonce = 0; ; nonce++) {
                var candidate = Long.toString(nonce);
                var hash =
                        java.util.HexFormat.of()
                                .formatHex(
                                        digest.digest(
                                                (salt + candidate)
                                                        .getBytes(StandardCharsets.UTF_8)));
                if (hash.startsWith(prefix)) {
                    return candidate;
                }
            }
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
