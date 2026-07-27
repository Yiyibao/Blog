package com.yubai.blog.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yubai.blog.TestDatabase;
import com.yubai.blog.common.RateLimiter;

/**
 * 6B：存储集成测试——验证上传落盘、下载走存储、删除清文件、bytea 惰性迁移。
 */
@SpringBootTest
@AutoConfigureMockMvc
class StorageIntegrationTest {

    private static final byte[] PNG_SAMPLE = buildTinyPng(2, 2);

    private static byte[] buildTinyPng(int width, int height) {
        try {
            var image = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
            var out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @TempDir
    static Path tempStorageDir;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private LocalFileStorage localFileStorage;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RateLimiter rateLimiter;

    private String token;
    private long noteId;

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
        registry.add("app.attachment.storage.dir", () -> tempStorageDir.toString());
    }

    @BeforeEach
    void setUp() throws Exception {
        rateLimiter.reset();
        try (var files = Files.walk(localFileStorage.getRootDir())) {
            for (var path : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                if (!path.equals(localFileStorage.getRootDir())) Files.deleteIfExists(path);
            }
        }
        token = login();
        var body = """
            {"title":"Storage test","markdownContent":"# Storage","folder":"Tests","status":"DRAFT","tags":[],"version":0}
            """;
        var result = mockMvc.perform(post("/api/v1/admin/notes")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andReturn();
        noteId = objectMapper.readTree(result.getResponse().getContentAsString())
            .path("data").path("id").asLong();
    }

    @Test
    void uploadPersistsFileToDisk() throws Exception {
        var image = new MockMultipartFile("file", "test.png", "image/png", PNG_SAMPLE);
        var response = mockMvc.perform(multipart("/api/v1/admin/notes/" + noteId + "/attachments")
                .file(image).header("Authorization", "Bearer " + token))
            .andExpect(status().isCreated()).andReturn();
        var data = objectMapper.readTree(response.getResponse().getContentAsString()).path("data");

        var attachmentId = data.path("id").asLong();
        var publicId = data.path("publicId").asText();

        // File should exist on disk
        var storageKey = jdbc.queryForObject(
            "SELECT storage_key FROM note_attachments WHERE id = ?", String.class, attachmentId);
        assertThat(storageKey).isNotNull().contains(publicId);

        var fileOnDisk = localFileStorage.getRootDir().resolve(storageKey);
        assertThat(fileOnDisk).exists();
        assertThat(Files.readAllBytes(fileOnDisk)).isEqualTo(PNG_SAMPLE);

        // Download through API should return the same bytes
        mockMvc.perform(get("/api/v1/admin/notes/" + noteId + "/attachments/" + attachmentId + "/content")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(result -> assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(PNG_SAMPLE));

        // Cleanup
        mockMvc.perform(delete("/api/v1/admin/notes/" + noteId + "/attachments/" + attachmentId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isNoContent());
        assertThat(fileOnDisk).doesNotExist();
    }

    @Test
    void missingNoteDoesNotWriteFile() throws Exception {
        var image = new MockMultipartFile("file", "broken.png", "image/png", PNG_SAMPLE);
        var response = mockMvc.perform(multipart("/api/v1/admin/notes/" + 99999 + "/attachments")
                .file(image).header("Authorization", "Bearer " + token))
            .andExpect(status().isNotFound()).andReturn();

        // After the failed upload, no orphan files should remain in storage
        try (var files = Files.list(localFileStorage.getRootDir())) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void legacyByteaContentIsLazilyMigrated() throws Exception {
        var publicId = UUID.randomUUID();
        var fileName = "legacy.png";
        var mediaType = "image/png";

        jdbc.update("""
            INSERT INTO note_attachments (public_id, note_id, file_name, media_type, byte_size, content, created_at)
            VALUES (?, ?, ?, ?, ?, ?, now())
            """, publicId, noteId, fileName, mediaType, PNG_SAMPLE.length, PNG_SAMPLE);

        // Initially no storage_key
        var storageKeyBefore = jdbc.queryForObject(
            "SELECT storage_key FROM note_attachments WHERE public_id = ?", String.class, publicId);
        assertThat(storageKeyBefore).isNull();

        // Get attachment id then access via admin endpoint triggers lazy migration
        var attachmentId = jdbc.queryForObject(
            "SELECT id FROM note_attachments WHERE public_id = ?", Long.class, publicId);
        mockMvc.perform(get("/api/v1/admin/notes/" + noteId + "/attachments/" + attachmentId + "/content")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(result -> assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(PNG_SAMPLE));

        // After access, storage_key should be set
        var storageKeyAfter = jdbc.queryForObject(
            "SELECT storage_key FROM note_attachments WHERE public_id = ?", String.class, publicId);
        assertThat(storageKeyAfter).isNotNull().contains(publicId.toString());

        // File should exist on disk
        var fileOnDisk = localFileStorage.getRootDir().resolve(storageKeyAfter);
        assertThat(fileOnDisk).exists();
        assertThat(Files.readAllBytes(fileOnDisk)).isEqualTo(PNG_SAMPLE);

        // bytea content should still be present (compatibility window)
        var contentStillThere = jdbc.queryForObject(
            "SELECT content IS NOT NULL FROM note_attachments WHERE public_id = ?", Boolean.class, publicId);
        assertThat(contentStillThere).isTrue();

        // Cleanup
        mockMvc.perform(delete("/api/v1/admin/notes/" + noteId + "/attachments/" + attachmentId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isNoContent());
        assertThat(fileOnDisk).doesNotExist();
    }

    @Test
    void deleteRemovesFileFromDisk() throws Exception {
        var image = new MockMultipartFile("file", "del.png", "image/png", PNG_SAMPLE);
        var response = mockMvc.perform(multipart("/api/v1/admin/notes/" + noteId + "/attachments")
                .file(image).header("Authorization", "Bearer " + token))
            .andExpect(status().isCreated()).andReturn();
        var data = objectMapper.readTree(response.getResponse().getContentAsString()).path("data");
        var attachmentId = data.path("id").asLong();

        var storageKey = jdbc.queryForObject(
            "SELECT storage_key FROM note_attachments WHERE id = ?", String.class, attachmentId);
        var fileOnDisk = localFileStorage.getRootDir().resolve(storageKey);
        assertThat(fileOnDisk).exists();

        mockMvc.perform(delete("/api/v1/admin/notes/" + noteId + "/attachments/" + attachmentId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isNoContent());

        assertThat(fileOnDisk).doesNotExist();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM note_attachments WHERE id = ?", Integer.class, attachmentId))
            .isZero();
    }

    private String login() throws Exception {
        return loginAs("admin", "admin-pass-12345");
    }

    private String loginAs(String username, String password) throws Exception {
        var result = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody(username, password)))
            .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
            .path("data").path("token").asText();
    }

    private String loginBody(String username, String password) throws Exception {
        var challenge = objectMapper.readTree(mockMvc.perform(get("/api/v1/auth/challenge")
                .param("username", username))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
            .path("data");
        var body = objectMapper.createObjectNode()
            .put("username", username)
            .put("password", password)
            .put("challengeId", challenge.path("challengeId").asText())
            .put("nonce", solvePow(challenge.path("salt").asText(), challenge.path("difficulty").asInt()));
        if ("IMAGE".equals(challenge.path("type").asText())) {
            body.put("captchaAnswer", "AB3CD");
        }
        return objectMapper.writeValueAsString(body);
    }

    private static String solvePow(String salt, int difficulty) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var prefix = "0".repeat(difficulty);
            for (long nonce = 0; ; nonce++) {
                var candidate = Long.toString(nonce);
                var hash = java.util.HexFormat.of().formatHex(
                    digest.digest((salt + candidate).getBytes(StandardCharsets.UTF_8)));
                if (hash.startsWith(prefix)) {
                    return candidate;
                }
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
