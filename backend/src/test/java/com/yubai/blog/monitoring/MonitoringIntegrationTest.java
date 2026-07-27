package com.yubai.blog.monitoring;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yubai.blog.TestDatabase;
import com.yubai.blog.auth.ChallengeService;
import com.yubai.blog.auth.LoginAttemptTracker;
import com.yubai.blog.common.RateLimiter;

@SpringBootTest
@AutoConfigureMockMvc
class MonitoringIntegrationTest {

    @BeforeAll
    static void prepareDatabase() {
        TestDatabase.resetSchema();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
        registry.add("spring.flyway.clean-disabled", () -> "false");
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

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    RateLimiter rateLimiter;

    @Autowired
    ChallengeService challengeService;

    @Autowired
    LoginAttemptTracker attemptTracker;

    @BeforeEach
    void resetState() {
        rateLimiter.reset();
        challengeService.reset();
        attemptTracker.reset();
    }

    @Test
    void healthIsAccessibleWithoutAuth() throws Exception {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk());
    }

    @Test
    void prometheusReturns401ForUnauthenticatedRequests() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void prometheusReturns403ForNonAdminUsers() throws Exception {
        String token = loginAs("partner", "partner-pass-12345");
        mockMvc.perform(get("/actuator/prometheus")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden());
    }

    @Test
    void adminCanReadPrometheusText() throws Exception {
        String token = login();
        mockMvc.perform(get("/actuator/prometheus")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN));
    }

    private String login() throws Exception {
        return loginAs("admin", "admin-pass-12345");
    }

    private String loginAs(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody(username, password)))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        return root.path("data").path("token").asText();
    }

    private String loginBody(String username, String password) throws Exception {
        return loginBody(username, password, false);
    }

    private String loginBody(String username, String password, boolean remember) throws Exception {
        JsonNode challenge = fetchChallenge(username);
        var body = objectMapper.createObjectNode()
            .put("username", username)
            .put("password", password)
            .put("challengeId", challenge.path("challengeId").asText())
            .put("nonce", solvePow(challenge.path("salt").asText(), challenge.path("difficulty").asInt()));
        if (remember) {
            body.put("remember", true);
        }
        if ("IMAGE".equals(challenge.path("type").asText())) {
            body.put("captchaAnswer", "AB3CD");
        }
        return objectMapper.writeValueAsString(body);
    }

    private JsonNode fetchChallenge(String username) throws Exception {
        var request = get("/api/v1/auth/challenge");
        if (username != null) {
            request = request.param("username", username);
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private static String solvePow(String salt, int difficulty) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            var prefix = "0".repeat(difficulty);
            for (long nonce = 0; ; nonce++) {
                var candidate = Long.toString(nonce);
                var hash = java.util.HexFormat.of().formatHex(
                    digest.digest((salt + candidate).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                if (hash.startsWith(prefix)) {
                    return candidate;
                }
            }
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
