package com.yubai.blog.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import java.nio.file.Path;
import com.yubai.blog.TestDatabase;
import com.yubai.blog.auth.CaptchaImageGenerator;
import com.yubai.blog.common.RateLimiter;

/**
 * P1-9：缓存首部回归——验证身份敏感的响应不被标记为公开缓存，
 * 动态内容索引、匿名/认证搜索与受保护的笔记端点返回正确的 Cache-Control / Vary。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CacheHeaderTest {

    private static final String FIXED_CAPTCHA_TEXT = "AB3CD";

    @org.springframework.boot.test.context.TestConfiguration
    static class FixedCaptchaConfig {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        CaptchaImageGenerator fixedCaptchaGenerator() {
            return new CaptchaImageGenerator() {
                @Override
                public CaptchaImageGenerator.Captcha generate() {
                    return new CaptchaImageGenerator.Captcha(FIXED_CAPTCHA_TEXT, "data:image/png;base64,fixed");
                }
            };
        }
    }

    @TempDir
    static Path tempStorageDir;

    @BeforeAll
    static void prepareDatabase() {
        TestDatabase.resetSchema();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
        registry.add("spring.flyway.clean-disabled", () -> "false");
        registry.add("app.jwt.secret", () -> "cache-header-test-secret-32chars!");
        registry.add("app.admin.username", () -> "admin");
        registry.add("app.admin.password", () -> "admin-pass-12345");
        registry.add("app.admin.display-name", () -> "测试站长");
        registry.add("app.partner.username", () -> "partner");
        registry.add("app.partner.password", () -> "partner-pass-12345");
        registry.add("app.partner.display-name", () -> "测试伴侣");
        registry.add("app.cors.allowed-origins", () -> "http://localhost:5173");
        registry.add("app.attachment.storage.dir", () -> tempStorageDir.toString());
        registry.add("app.site-url", () -> "http://localhost:5173");
        registry.add("app.ai.master-key", () -> "cache-header-test-master-key-32chars!");
        registry.add("app.ai.allow-local-endpoints", () -> "true");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    RateLimiter rateLimiter;

    @BeforeEach
    void resetRateLimiter() {
        rateLimiter.reset();
    }

    @Test
    void anonymousDishCategoriesReturnsOkAndNoCache() throws Exception {
        mockMvc.perform(get("/api/v1/dish-categories"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-cache"));
    }

    @Test
    void anonymousSearchIsPublicCache() throws Exception {
        mockMvc.perform(get("/api/v1/search").param("q", "设计"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("public")))
            .andExpect(header().string("Vary", org.hamcrest.Matchers.containsString("Authorization")));
    }

    @Test
    void postAndCategoryIndexesMustRevalidateAfterPublishing() throws Exception {
        mockMvc.perform(get("/api/v1/posts"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-cache"));

        mockMvc.perform(get("/api/v1/categories"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-cache"));
    }

    @Test
    void authenticatedSearchIsPrivateCacheWithVary() throws Exception {
        String token = login();

        mockMvc.perform(get("/api/v1/search").param("q", "设计")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("private")))
            .andExpect(header().string("Vary", org.hamcrest.Matchers.containsString("Authorization")));
    }

    @Test
    void notesEndpointIsPrivateCache() throws Exception {
        String token = login();

        mockMvc.perform(get("/api/v1/notes")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("private")))
            .andExpect(header().string("Vary", org.hamcrest.Matchers.containsString("Authorization")));
    }

    @Test
    void noteAssetEndpointIsPrivateNoStore() throws Exception {
        String token = login();

        MvcResult created = mockMvc.perform(post("/api/v1/admin/notes")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"title":"Cache header note","markdownContent":"# Cache\\n\\nTest","folder":"Tests","status":"PUBLISHED","tags":["cache-test"],"version":0}
                    """))
            .andExpect(status().isCreated())
            .andReturn();
        JsonNode note = objectMapper.readTree(created.getResponse().getContentAsString()).path("data");
        long noteId = note.path("id").asLong();

        byte[] png = buildTinyPng(1, 1);
        var image = new org.springframework.mock.web.MockMultipartFile("file", "pixel.png", "image/png", png);
        MvcResult uploaded = mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/admin/notes/" + noteId + "/attachments")
                    .file(image).header("Authorization", "Bearer " + token))
            .andExpect(status().isCreated())
            .andReturn();
        JsonNode attachment = objectMapper.readTree(uploaded.getResponse().getContentAsString()).path("data");
        String publicId = attachment.path("publicId").asText();

        mockMvc.perform(get("/api/v1/note-assets/" + publicId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control",
                org.hamcrest.Matchers.allOf(
                    org.hamcrest.Matchers.containsString("private"),
                    org.hamcrest.Matchers.containsString("no-store")
                )))
            .andExpect(header().string("Vary",
                org.hamcrest.Matchers.containsString("Authorization")));

        mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/admin/notes/" + noteId)
                    .header("Authorization", "Bearer " + token))
            .andExpect(status().isNoContent());
    }

    private String login() throws Exception {
        return loginAs("admin", "admin-pass-12345");
    }

    private String loginAs(String username, String password) throws Exception {
        JsonNode challenge = fetchChallenge(username);
        var body = objectMapper.createObjectNode()
            .put("username", username)
            .put("password", password)
            .put("challengeId", challenge.path("challengeId").asText())
            .put("nonce", solvePow(challenge.path("salt").asText(), challenge.path("difficulty").asInt()));
        if ("IMAGE".equals(challenge.path("type").asText())) {
            body.put("captchaAnswer", FIXED_CAPTCHA_TEXT);
        }
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        return root.path("data").path("token").asText();
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

    private static byte[] buildTinyPng(int width, int height) {
        try {
            var image = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
            var out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
