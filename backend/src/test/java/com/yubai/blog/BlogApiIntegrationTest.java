package com.yubai.blog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.mock.web.MockMultipartFile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yubai.blog.auth.CaptchaImageGenerator;
import com.yubai.blog.auth.ChallengeService;
import com.yubai.blog.auth.LoginAttemptTracker;
import com.yubai.blog.common.RateLimiter;
import com.yubai.blog.post.PostService;

@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BlogApiIntegrationTest {
    /** 可完整解码的最小真 PNG（P0-6 magic-byte + NB-4 尺寸预检双关卡都要过）。 */
    private static final byte[] PNG_SAMPLE = buildTinyPng(2, 2);

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
    /** L-7：集成测试固定图形码答案，绕过图片 OCR；真实生成逻辑由 CaptchaImageGeneratorTest 覆盖。 */
    private static final String FIXED_CAPTCHA_TEXT = "AB3CD";

    @org.springframework.boot.test.context.TestConfiguration
    static class FixedCaptchaConfig {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        CaptchaImageGenerator fixedCaptchaGenerator() {
            return new CaptchaImageGenerator() {
                @Override
                public Captcha generate() {
                    return new Captcha(FIXED_CAPTCHA_TEXT, "data:image/png;base64,fixed");
                }
            };
        }
    }

    // P2-4：数据库解析统一收敛到 TestDatabase（可达直连快速模式 / Testcontainers 自起容器）

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
        // FD-10：固定展示名——本地 .env.properties 的 APP_ADMIN_DISPLAY_NAME 会渗入测试上下文
        registry.add("app.admin.display-name", () -> "测试站长");
        // FD-6：伴侣账号——FD-7 的 PARTNER 越权用例依赖它
        registry.add("app.partner.username", () -> "partner");
        registry.add("app.partner.password", () -> "partner-pass-12345");
        registry.add("app.partner.display-name", () -> "测试伴侣");
        registry.add("app.cors.allowed-origins", () -> "http://localhost:5173");
        registry.add("app.site-url", () -> "http://localhost:5173");
        // 4A-1：供应商注册表测试需要主密钥；allow-local 放开环回地址以便用字面量 IP 免 DNS 测试
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
    PostService postService;

    @Autowired
    ChallengeService challengeService;

    @Autowired
    LoginAttemptTracker attemptTracker;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    /** P0-2/P0-3/L-7：限流器、challenge 存储与失败计数均为进程级单例，逐测试重置避免相互污染。 */
    @BeforeEach
    void resetRateLimiter() {
        rateLimiter.reset();
        challengeService.reset();
        attemptTracker.reset();
    }

    @Test
    @Order(1)
    void publicPostsArePaginatedAndHideDrafts() throws Exception {
        String token = login();

        mockMvc.perform(get("/api/v1/posts").param("page", "0").param("size", "2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items").isArray())
            .andExpect(jsonPath("$.data.size").value(2))
            .andExpect(jsonPath("$.data.totalElements").value(5))
            .andExpect(jsonPath("$.data.totalPages").value(3))
            // P1-2：列表为摘要 DTO——保留元数据字段，但绝不携带正文
            .andExpect(jsonPath("$.data.items[0].slug").isNotEmpty())
            .andExpect(jsonPath("$.data.items[0].likeCount").exists())
            .andExpect(jsonPath("$.data.items[0].content").doesNotExist());

        String draftBody = """
            {
              "slug":"draft-only-post",
              "title":"草稿文章",
              "excerpt":"不会出现在公开列表",
              "date":"2026-07-22",
              "readTime":3,
              "category":"测试",
              "tags":["draft"],
              "color":"#123456",
              "number":"99",
              "featured":false,
              "status":"DRAFT",
              "content":"<p>draft</p>"
            }
            """;

        MvcResult created = mockMvc.perform(post("/api/v1/admin/posts")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(draftBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.status").value("DRAFT"))
            .andReturn();

        long draftId = objectMapper.readTree(created.getResponse().getContentAsString()).path("data").path("id").asLong();

        mockMvc.perform(get("/api/v1/posts/draft-only-post"))
            .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/posts").param("page", "0").param("size", "50"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(5));

        mockMvc.perform(get("/api/v1/admin/posts").param("status", "DRAFT")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.items[0].slug").value("draft-only-post"))
            // P1-2：管理端列表同样不携带正文
            .andExpect(jsonPath("$.data.items[0].content").doesNotExist());

        String publishBody = """
            {
              "slug":"draft-only-post",
              "title":"已发布文章",
              "excerpt":"现在会出现在公开列表",
              "date":"2026-07-22",
              "readTime":3,
              "category":"测试",
              "tags":["published"],
              "color":"#123456",
              "number":"99",
              "featured":false,
              "status":"PUBLISHED",
              "content":"<p>published</p>"
            }
            """;

        mockMvc.perform(put("/api/v1/admin/posts/" + draftId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(publishBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PUBLISHED"));

        mockMvc.perform(get("/api/v1/posts/draft-only-post"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.title").value("已发布文章"))
            // P1-2：详情保留全文
            .andExpect(jsonPath("$.data.content").isNotEmpty());

        mockMvc.perform(delete("/api/v1/admin/posts/" + draftId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isNoContent());

        // P1-2：sort=asc 最早优先
        MvcResult ascResult = mockMvc.perform(get("/api/v1/posts").param("size", "50").param("sort", "asc"))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode ascItems = objectMapper.readTree(ascResult.getResponse().getContentAsString())
            .path("data").path("items");
        for (int i = 1; i < ascItems.size(); i++) {
            String prev = ascItems.get(i - 1).path("date").asText();
            String curr = ascItems.get(i).path("date").asText();
            assertTrue(prev.compareTo(curr) <= 0, "sort=asc 应按日期升序：" + prev + " -> " + curr);
        }

        // P1-2：categorySlug 过滤——用任一已发布文章的分类做过滤，结果应全部命中该分类
        String filterSlug = ascItems.get(0).path("categorySlug").asText();
        MvcResult catResult = mockMvc.perform(get("/api/v1/posts").param("categorySlug", filterSlug))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode catItems = objectMapper.readTree(catResult.getResponse().getContentAsString())
            .path("data").path("items");
        assertTrue(catItems.size() > 0, "分类过滤应返回至少一篇文章");
        for (JsonNode item : catItems) {
            assertEquals(filterSlug, item.path("categorySlug").asText());
        }
    }

    @Test
    @Order(2)
    void adminEndpointsRequireAuth() throws Exception {
        mockMvc.perform(get("/api/v1/admin/posts"))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody("admin", "wrong")))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/admin/ai/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(3)
    void dishesUseDatabaseAndSupportAdminCrud() throws Exception {
        String token = login();

        mockMvc.perform(get("/api/v1/dishes"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(6))
            .andExpect(jsonPath("$.data.items[0].slug").value("authentic-mapo-tofu"))
            .andExpect(jsonPath("$.data.items[0].ingredients").isArray())
            .andExpect(jsonPath("$.data.items[0].imageCredit").isNotEmpty());

        String draftDish = """
            {
              "slug":"test-scallion-noodles",
              "name":"葱油拌面",
              "summary":"集成测试菜品",
              "category":"面点主食",
              "imageUrl":"https://example.com/noodles.jpg",
              "imageAlt":"一碗葱油拌面",
              "imageCredit":"Test · CC0",
              "imageSourceUrl":"https://example.com/source",
              "prepMinutes":15,
              "difficulty":"简单",
              "rating":4.5,
              "featured":false,
              "published":false,
              "displayOrder":99,
              "ingredients":["面条 200 克","葱适量"],
              "steps":["煮面。","拌入葱油。"]
            }
            """;

        MvcResult created = mockMvc.perform(post("/api/v1/admin/dishes")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(draftDish))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.published").value(false))
            .andReturn();

        long dishId = objectMapper.readTree(created.getResponse().getContentAsString()).path("data").path("id").asLong();

        mockMvc.perform(get("/api/v1/admin/dishes/" + dishId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.slug").value("test-scallion-noodles"));

        mockMvc.perform(get("/api/v1/admin/dishes").param("page", "0").param("size", "2")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.size").value(2))
            .andExpect(jsonPath("$.data.totalElements").value(7));

        mockMvc.perform(get("/api/v1/dishes/test-scallion-noodles"))
            .andExpect(status().isNotFound());

        String publishedDish = draftDish.replace("\"published\":false", "\"published\":true")
            .replace("集成测试菜品", "已经发布的测试菜品");

        mockMvc.perform(put("/api/v1/admin/dishes/" + dishId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(publishedDish))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.summary").value("已经发布的测试菜品"));

        mockMvc.perform(get("/api/v1/dishes/test-scallion-noodles"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.ingredients.length()").value(2));

        mockMvc.perform(delete("/api/v1/admin/dishes/" + dishId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isNoContent());
    }

    @Test
    @Order(4)
    void removedProjectEndpointsReturnNotFound() throws Exception {
        String token = login();

        mockMvc.perform(get("/api/v1/projects").header("Authorization", "Bearer " + token))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/admin/projects").header("Authorization", "Bearer " + token))
            .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/admin/projects")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isNotFound());
    }

    @Test
    @Order(5)
    void notesSupportCrudFilteringPaginationAndDatabaseAttachments() throws Exception {
        String token = login();
        String draftBody = """
            {"title":"Draft note","markdownContent":"# Draft\\n\\nDatabase content","folder":"Tests","status":"DRAFT","tags":["postgres"],"version":0}
            """;
        MvcResult draftCreated = mockMvc.perform(post("/api/v1/admin/notes")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(draftBody))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("DRAFT")).andReturn();
        JsonNode draft = objectMapper.readTree(draftCreated.getResponse().getContentAsString()).path("data");
        long draftId = draft.path("id").asLong();

        String publishedBody = """
            {"title":"Public note","markdownContent":"# Public\\n\\nVisible content","folder":"Tests","status":"PUBLISHED","tags":["api"],"version":0}
            """;
        // NB-11：create 尊重请求 status（不再被静默改成 DRAFT），且 201 响应包络 code 同为 201
        MvcResult publishedCreated = mockMvc.perform(post("/api/v1/admin/notes")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(publishedBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.code").value(201))
            .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
            .andReturn();
        JsonNode publicDraft = objectMapper.readTree(publishedCreated.getResponse().getContentAsString()).path("data");
        long publishedId = publicDraft.path("id").asLong();

        mockMvc.perform(get("/api/v1/admin/notes").param("page", "0").param("size", "1")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(2))
            .andExpect(jsonPath("$.data.totalPages").value(2))
            // P1-2：管理端列表同样不携带正文
            .andExpect(jsonPath("$.data.items[0].markdownContent").doesNotExist());
        mockMvc.perform(get("/api/v1/admin/notes").param("status", "DRAFT")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
        mockMvc.perform(get("/api/v1/notes"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
            // P1-2：公开笔记列表为摘要 DTO，不含 markdown 正文
            .andExpect(jsonPath("$.data.items[0].title").isNotEmpty())
            .andExpect(jsonPath("$.data.items[0].markdownContent").doesNotExist());
        mockMvc.perform(get("/api/v1/notes/" + draftId)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/notes/" + publishedId)).andExpect(status().isOk())
            // P1-2：详情保留全文
            .andExpect(jsonPath("$.data.markdownContent").isNotEmpty());

        long version = draft.path("version").asLong();
        String updatedDraft = draftBody.replace("Draft note", "Updated draft").replace("\"version\":0", "\"version\":" + version);
        MvcResult updated = mockMvc.perform(put("/api/v1/admin/notes/" + draftId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(updatedDraft))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.title").value("Updated draft"))
            .andReturn();
        long updatedVersion = objectMapper.readTree(updated.getResponse().getContentAsString()).path("data").path("version").asLong();
        mockMvc.perform(put("/api/v1/admin/notes/" + draftId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(updatedDraft))
            .andExpect(status().isConflict());

        // P0-6：附件校验 magic bytes，测试数据需带真实 PNG 文件头
        var image = new MockMultipartFile("file", "pixel.png", "image/png", PNG_SAMPLE);
        MvcResult uploaded = mockMvc.perform(multipart("/api/v1/admin/notes/" + draftId + "/attachments")
                .file(image).header("Authorization", "Bearer " + token))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.data.byteSize").value(PNG_SAMPLE.length)).andReturn();
        JsonNode attachment = objectMapper.readTree(uploaded.getResponse().getContentAsString()).path("data");
        long attachmentId = attachment.path("id").asLong();
        String publicId = attachment.path("publicId").asText();
        mockMvc.perform(get("/api/v1/admin/notes/" + draftId + "/attachments/" + attachmentId + "/content")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(result -> {
                if (result.getResponse().getContentAsByteArray().length != PNG_SAMPLE.length) {
                    throw new AssertionError("authenticated draft preview did not return attachment bytes");
                }
            });
        mockMvc.perform(get("/api/v1/note-assets/" + publicId)).andExpect(status().isNotFound());
        MvcResult attachmentPublished = mockMvc.perform(put("/api/v1/admin/notes/" + draftId + "/publish")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + updatedVersion + "}"))
            .andExpect(status().isOk())
            .andReturn();
        long attachmentPublishedVersion = objectMapper.readTree(attachmentPublished.getResponse().getContentAsString())
            .path("data").path("version").asLong();
        mockMvc.perform(get("/api/v1/note-assets/" + publicId))
            .andExpect(status().isOk())
            .andExpect(result -> {
                // P1-6：publicId 不可变，公开附件允许长缓存（撤回后服务端仍 404，已缓存副本残留为计划批准的取舍）
                var cacheControl = result.getResponse().getHeader("Cache-Control");
                if (cacheControl == null || !cacheControl.contains("max-age=31536000") || !cacheControl.contains("immutable")) {
                    throw new AssertionError("immutable note attachments should be long-cached, got: " + cacheControl);
                }
                if (result.getResponse().getContentAsByteArray().length != PNG_SAMPLE.length) throw new AssertionError("attachment bytes were not read from database");
            });
        mockMvc.perform(put("/api/v1/admin/notes/" + draftId + "/unpublish")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + attachmentPublishedVersion + "}"))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/note-assets/" + publicId)).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/admin/notes/" + draftId + "/attachments/" + attachmentId)
                .header("Authorization", "Bearer " + token)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/note-assets/" + publicId)).andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/admin/notes/" + draftId).header("Authorization", "Bearer " + token)).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/admin/notes/" + publishedId).header("Authorization", "Bearer " + token)).andExpect(status().isNoContent());
    }

    @Test
    @Order(6)
    void categoriesValidationPageBoundsAndMarkdownRoundTripWork() throws Exception {
        String token = login();

        mockMvc.perform(get("/api/v1/categories"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.data.length()").value(3))
            .andExpect(jsonPath("$.data[0].name").isString())
            .andExpect(jsonPath("$.data[0].slug").isString())
            .andExpect(jsonPath("$.data[0].publishedPostCount").isNumber());

        mockMvc.perform(get("/api/v1/categories/\u8bbe\u8ba1\u672d\u8bb0"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("\u8bbe\u8ba1\u672d\u8bb0"))
            .andExpect(jsonPath("$.data.slug").value("\u8bbe\u8ba1\u672d\u8bb0"))
            .andExpect(jsonPath("$.data.total").isNumber())
            .andExpect(jsonPath("$.data.posts").isArray());

        mockMvc.perform(get("/api/v1/categories/nonexistent-slug"))
            .andExpect(status().isNotFound());

        // P2-2：分页参数越界不再静默夹取，如实 400（详见 @Order(41) 契约用例）
        mockMvc.perform(get("/api/v1/posts").param("page", "-9").param("size", "999"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400));

        mockMvc.perform(get("/api/v1/admin/dishes"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/notes"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/admin/posts")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());

        byte[] markdown = "# Imported note\n\nStored in PostgreSQL.".getBytes(StandardCharsets.UTF_8);
        var markdownFile = new MockMultipartFile("file", "integration.md", "text/markdown", markdown);
        MvcResult imported = mockMvc.perform(multipart("/api/v1/admin/notes/import")
                .file(markdownFile).header("Authorization", "Bearer " + token))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.sourceFileName").value("integration.md"))
            .andReturn();
        long importedId = objectMapper.readTree(imported.getResponse().getContentAsString()).path("data").path("id").asLong();

        mockMvc.perform(get("/api/v1/admin/notes/" + importedId + "/export")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(result -> {
                if (!java.util.Arrays.equals(markdown, result.getResponse().getContentAsByteArray())) {
                    throw new AssertionError("exported Markdown does not match the imported database content");
                }
            });

        mockMvc.perform(delete("/api/v1/admin/notes/" + importedId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isNoContent());
    }

    @Test
    @Order(7)
    void postHtmlIsSanitizedBeforeItReachesPublicReaders() throws Exception {
        String token = login();
        String unsafeBody = """
            {
              "slug":"sanitizer-regression",
              "title":"Sanitizer regression",
              "excerpt":"HTML policy test",
              "date":"2026-07-23",
              "readTime":2,
              "category":"测试",
              "tags":["security"],
              "color":"#123456",
              "number":"98",
              "featured":false,
              "status":"PUBLISHED",
              "content":"<p>safe</p><script>alert(1)</script><a href=\\\"javascript:alert(2)\\\" onclick=\\\"alert(3)\\\">link</a><img src=\\\"javascript:alert(4)\\\" onerror=\\\"alert(5)\\\">"
            }
            """;
        MvcResult created = mockMvc.perform(post("/api/v1/admin/posts")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(unsafeBody))
            .andExpect(status().isCreated())
            .andReturn();
        long id = objectMapper.readTree(created.getResponse().getContentAsString()).path("data").path("id").asLong();

        MvcResult publicRead = mockMvc.perform(get("/api/v1/posts/sanitizer-regression"))
            .andExpect(status().isOk())
            .andReturn();
        String content = objectMapper.readTree(publicRead.getResponse().getContentAsString()).path("data").path("content").asText();
        if (content.contains("<script") || content.contains("onclick") || content.contains("javascript:")) {
            throw new AssertionError("unsafe HTML reached the public post response: " + content);
        }
        if (!content.contains("<p>safe</p>")) {
            throw new AssertionError("safe article markup was unexpectedly removed: " + content);
        }

        mockMvc.perform(delete("/api/v1/admin/posts/" + id).header("Authorization", "Bearer " + token))
            .andExpect(status().isNoContent());
    }

    @Test
    @Order(8)
    void notesCanBePublishedAndWithdrawnExplicitly() throws Exception {
        String token = login();
        String draftBody = objectMapper.writeValueAsString(java.util.Map.of(
            "title", "Publish workflow",
            "markdownContent", "# Publish workflow\n\nVisible after publish.",
            "folder", "Tests",
            "status", "DRAFT",
            "tags", java.util.List.of("publish"),
            "version", 0
        ));
        MvcResult created = mockMvc.perform(post("/api/v1/admin/notes")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(draftBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.status").value("DRAFT"))
            .andReturn();
        JsonNode draft = objectMapper.readTree(created.getResponse().getContentAsString()).path("data");
        long id = draft.path("id").asLong();
        long version = draft.path("version").asLong();

        mockMvc.perform(get("/api/v1/notes/" + id)).andExpect(status().isNotFound());

        String attemptedImplicitPublish = draftBody.replace("\"status\":\"DRAFT\"", "\"status\":\"PUBLISHED\"")
            .replace("\"version\":0", "\"version\":" + version);
        MvcResult contentUpdated = mockMvc.perform(put("/api/v1/admin/notes/" + id)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(attemptedImplicitPublish))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("DRAFT"))
            .andReturn();
        version = objectMapper.readTree(contentUpdated.getResponse().getContentAsString()).path("data").path("version").asLong();

        MvcResult published = mockMvc.perform(put("/api/v1/admin/notes/" + id + "/publish")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + version + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
            .andReturn();
        long publishedVersion = objectMapper.readTree(published.getResponse().getContentAsString()).path("data").path("version").asLong();

        mockMvc.perform(get("/api/v1/notes/" + id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.title").value("Publish workflow"));

        MvcResult unpublished = mockMvc.perform(put("/api/v1/admin/notes/" + id + "/unpublish")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + publishedVersion + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("DRAFT"))
            .andReturn();
        long unpublishedVersion = objectMapper.readTree(unpublished.getResponse().getContentAsString()).path("data").path("version").asLong();

        mockMvc.perform(get("/api/v1/notes/" + id)).andExpect(status().isNotFound());
        mockMvc.perform(put("/api/v1/admin/notes/" + id + "/publish")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + publishedVersion + "}"))
            .andExpect(status().isConflict());

        MvcResult archived = mockMvc.perform(put("/api/v1/admin/notes/" + id + "/archive")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + unpublishedVersion + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("ARCHIVED"))
            .andReturn();
        long archivedVersion = objectMapper.readTree(archived.getResponse().getContentAsString()).path("data").path("version").asLong();
        mockMvc.perform(put("/api/v1/admin/notes/" + id + "/unpublish")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + archivedVersion + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("DRAFT"));

        mockMvc.perform(delete("/api/v1/admin/notes/" + id).header("Authorization", "Bearer " + token))
            .andExpect(status().isNoContent());
    }

    @Test
    @Order(9)
    void searchIsPublicGroupedLimitedAndExcludesDrafts() throws Exception {
        String token = login();
        String draftBody = """
            {
              "slug":"search-hidden-draft",
              "title":"private-search-sentinel",
              "excerpt":"private-search-sentinel",
              "date":"2026-07-23",
              "readTime":2,
              "category":"Tests",
              "tags":["private-search-sentinel"],
              "color":"#123456",
              "number":"98",
              "featured":false,
              "status":"DRAFT",
              "content":"<p>private-search-sentinel</p>"
            }
            """;
        MvcResult draftCreated = mockMvc.perform(post("/api/v1/admin/posts")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(draftBody))
            .andExpect(status().isCreated())
            .andReturn();
        long draftId = objectMapper.readTree(draftCreated.getResponse().getContentAsString())
            .path("data").path("id").asLong();

        String noteBody = """
            {"title":"Public search sentinel","markdownContent":"# Search\\n\\npublic-note-sentinel","folder":"Search","status":"DRAFT","tags":["public-note-sentinel"],"version":0}
            """;
        MvcResult noteCreated = mockMvc.perform(post("/api/v1/admin/notes")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(noteBody))
            .andExpect(status().isCreated())
            .andReturn();
        JsonNode noteDraft = objectMapper.readTree(noteCreated.getResponse().getContentAsString()).path("data");
        long noteId = noteDraft.path("id").asLong();
        mockMvc.perform(put("/api/v1/admin/notes/" + noteId + "/publish")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + noteDraft.path("version").asLong() + "}"))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/search").param("q", "设计"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.articles").isArray())
            .andExpect(jsonPath("$.data.notes").isArray())
            .andExpect(jsonPath("$.data.dishes").isArray())
            .andExpect(jsonPath("$.data.articles[0].type").value("POST"))
            .andExpect(jsonPath("$.data.articles[0].url").value("/articles/clarity-by-design"));

        mockMvc.perform(get("/api/v1/search").param("q", "public-note-sentinel"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.notes[0].type").value("NOTE"))
            .andExpect(jsonPath("$.data.notes[0].url").value("/notes?note=" + noteId));

        mockMvc.perform(get("/api/v1/search").param("q", "菠萝"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.dishes[0].type").value("DISH"))
            .andExpect(jsonPath("$.data.dishes[0].url").value("/recipes?dish=sweet-sour-pork"));

        mockMvc.perform(get("/api/v1/search").param("q", "private-search-sentinel"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(0));

        mockMvc.perform(get("/api/v1/search").param("q", "的").param("limit", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.articles.length()").value(1))
            .andExpect(jsonPath("$.data.dishes.length()").value(1));

        mockMvc.perform(get("/api/v1/search").param("q", "  "))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(0))
            .andExpect(jsonPath("$.data.articles.length()").value(0))
            .andExpect(jsonPath("$.data.notes.length()").value(0))
            .andExpect(jsonPath("$.data.dishes.length()").value(0));

        mockMvc.perform(delete("/api/v1/admin/posts/" + draftId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/admin/notes/" + noteId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isNoContent());
    }

    @Test
    @Order(10)
    void sitemapContainsStaticPagesAndPublishedContent() throws Exception {
        var result = mockMvc.perform(get("/sitemap.xml"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_XML))
            .andReturn();

        var raw = result.getResponse().getContentAsString();
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        var doc = factory.newDocumentBuilder()
            .parse(new org.xml.sax.InputSource(new java.io.StringReader(raw)));
        var urls = doc.getDocumentElement().getElementsByTagNameNS("http://www.sitemaps.org/schemas/sitemap/0.9", "loc");
        var locs = new java.util.ArrayList<String>();
        for (var i = 0; i < urls.getLength(); i++) {
            locs.add(urls.item(i).getTextContent());
        }

        assertTrue(locs.contains("http://localhost:5173/"), "home");
        assertTrue(locs.contains("http://localhost:5173/articles"), "articles list");
        assertTrue(locs.contains("http://localhost:5173/categories"), "categories index");
        assertTrue(locs.contains("http://localhost:5173/notes"), "notes list");
        assertTrue(locs.contains("http://localhost:5173/recipes"), "recipes list");
        assertTrue(locs.contains("http://localhost:5173/about"), "about");

        assertTrue(locs.contains("http://localhost:5173/categories/\u8bbe\u8ba1\u672d\u8bb0"),
            "category detail should appear for category with published posts");

        assertTrue(locs.contains("http://localhost:5173/articles/clarity-by-design"),
            "published post clarity-by-design should appear");

        assertTrue(locs.stream().anyMatch(l -> l.contains("/recipes?dish=")),
            "published dish URLs should appear");

        assertTrue(locs.stream().noneMatch(l -> l.contains("/admin")),
            "no admin URLs in sitemap");

        assertTrue(locs.stream().noneMatch(l -> l.contains("/api/")),
            "no API URLs in sitemap");

        assertTrue(locs.stream().allMatch(l -> l.startsWith("http://localhost:5173/")),
            "all URLs use configured site root");
    }

    @Test
    @Order(11)
    void dishFavoriteIncrementsAndReturnsCount() throws Exception {
        mockMvc.perform(post("/api/v1/dishes/authentic-mapo-tofu/favorite"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.message").value("success"))
            .andExpect(jsonPath("$.data.slug").value("authentic-mapo-tofu"))
            // P0-7（已批准）：纯计数语义，响应不再包含 isFavorite
            .andExpect(jsonPath("$.data.isFavorite").doesNotExist())
            .andExpect(jsonPath("$.data.favoriteCount").isNumber());

        mockMvc.perform(get("/api/v1/dishes/favorites"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].slug").value("authentic-mapo-tofu"))
            .andExpect(jsonPath("$.data.items[0].favoriteCount").isNumber());
    }

    @Test
    @Order(12)
    void postLikeIncrementsCount() throws Exception {
        mockMvc.perform(post("/api/v1/posts/clarity-by-design/like"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.slug").value("clarity-by-design"))
            .andExpect(jsonPath("$.data.likeCount").isNumber());
    }

    @Test
    @Order(13)
    void postStatsReturnsCurrentViewsAndLikes() throws Exception {
        mockMvc.perform(get("/api/v1/posts/clarity-by-design/stats"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.slug").value("clarity-by-design"))
            .andExpect(jsonPath("$.data.viewsCount").isNumber())
            .andExpect(jsonPath("$.data.likeCount").isNumber());
    }

    @Test
    @Order(40)
    void postDetailReadCountsViewOncePerIpWindow() throws Exception {
        // P1-8：详情读计真实浏览量；同 IP 同文章去重窗口内重复访问只计一次
        MvcResult beforeResult = mockMvc.perform(get("/api/v1/posts/clarity-by-design/stats"))
            .andExpect(status().isOk())
            .andReturn();
        int before = objectMapper.readTree(beforeResult.getResponse().getContentAsString())
            .path("data").path("viewsCount").asInt();

        mockMvc.perform(get("/api/v1/posts/clarity-by-design")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/posts/clarity-by-design")).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/posts/clarity-by-design/stats"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.viewsCount").value(before + 1));

        // 不存在的 slug：不计数、正常 404，不影响详情读取流程
        mockMvc.perform(get("/api/v1/posts/view-count-no-such-post")).andExpect(status().isNotFound());
    }

    @Test
    @Order(41)
    void invalidPaginationAndTypeParamsReturn400WithUnifiedEnvelope() throws Exception {
        // P2-2：声明式校验——越界/负值/类型错误统一 400，包络含 status/message/timestamp
        mockMvc.perform(get("/api/v1/posts").param("size", "999"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.message").isString())
            .andExpect(jsonPath("$.timestamp").exists());
        mockMvc.perform(get("/api/v1/posts").param("page", "-1"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/posts").param("page", "abc"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400));
        // P2-2/NB-11：POST /search 移除静默修正——缺 type 如实 400
        mockMvc.perform(post("/api/v1/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"x\",\"page\":0,\"size\":5}"))
            .andExpect(status().isBadRequest());
        // P2-1：畸形 JSON 统一 400 包络
        mockMvc.perform(post("/api/v1/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{not-json"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @Order(42)
    void requestIdIsIssuedEchoedAndApiDocsStaysDisabledByDefault() throws Exception {
        // P2-9：每个响应都带 X-Request-Id；合法上游 ID 透传，非法字符则重新生成
        mockMvc.perform(get("/api/v1/posts"))
            .andExpect(status().isOk())
            .andExpect(header().exists("X-Request-Id"));
        mockMvc.perform(get("/api/v1/posts").header("X-Request-Id", "nginx-abc.123"))
            .andExpect(header().string("X-Request-Id", "nginx-abc.123"));
        mockMvc.perform(get("/api/v1/posts").header("X-Request-Id", "bad id<script>"))
            .andExpect(result -> {
                String issued = result.getResponse().getHeader("X-Request-Id");
                if (issued == null || issued.contains(" ") || issued.contains("<")) {
                    throw new AssertionError("unsafe upstream request id must be regenerated, got: " + issued);
                }
            });
        // P2-3：springdoc 默认关闭——文档路径在安全层放行但功能未启用，如实 404
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
        mockMvc.perform(get("/swagger-ui.html")).andExpect(status().isNotFound());
    }

    @Test
    @Order(14)
    void postSearchWithTypeReturnsFilteredResults() throws Exception {
        String postSearchBody = """
            {"query":"设计","type":"POST","page":0,"size":5}
            """;
        mockMvc.perform(post("/api/v1/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(postSearchBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.type").value("POST"))
            .andExpect(jsonPath("$.data.results").isArray());

        String dishSearchBody = """
            {"query":"菠萝","type":"DISH","page":0,"size":5}
            """;
        mockMvc.perform(post("/api/v1/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(dishSearchBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.type").value("DISH"))
            .andExpect(jsonPath("$.data.results[0].slug").value("sweet-sour-pork"));

        String allSearchBody = """
            {"query":"设计","type":"ALL","page":0,"size":5}
            """;
        mockMvc.perform(post("/api/v1/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(allSearchBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.type").value("ALL"))
            .andExpect(jsonPath("$.data.results").isArray());

        String emptyBody = """
            {"query":"  ","type":"POST","page":0,"size":5}
            """;
        mockMvc.perform(post("/api/v1/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(emptyBody))
            .andExpect(status().isBadRequest());
    }

    @Test
    @Order(15)
    void robotsTxtIsPublicAndContainsExpectedDirectives() throws Exception {
        var result = mockMvc.perform(get("/robots.txt"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
            .andReturn();

        var body = result.getResponse().getContentAsString();

        assertTrue(body.contains("User-agent: *"), "User-agent directive");
        assertTrue(body.contains("Allow: /"), "Allow root");
        assertTrue(body.contains("Disallow: /admin"), "Disallow admin");
        assertTrue(body.contains("Sitemap: http://localhost:5173/sitemap.xml"), "Sitemap URL");

        assertFalse(body.contains("Disallow: /articles\n"), "articles should not be disallowed");
        assertFalse(body.contains("Disallow: /notes\n"), "notes should not be disallowed");
        assertFalse(body.contains("Disallow: /recipes\n"), "recipes should not be disallowed");
        assertFalse(body.contains("Disallow: /\n"), "full-site disallow should not exist");

        assertFalse(result.getResponse().getContentType().contains("json"),
            "response must not be JSON");
    }

    @Test
    @Order(16)
    void musicTracksArePublicAndReturnSeedData() throws Exception {
        mockMvc.perform(get("/api/v1/music/tracks"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.data.length()").value(5))
            .andExpect(jsonPath("$.data[0].id").value("track-1"))
            .andExpect(jsonPath("$.data[0].title").value("雨的印记 (Kiss the Rain)"))
            .andExpect(jsonPath("$.data[0].artist").value("钢琴纯音乐"))
            .andExpect(jsonPath("$.data[0].duration").isNumber())
            .andExpect(jsonPath("$.data[0].audioUrl").isString())
            .andExpect(jsonPath("$.data[0].coverUrl").isString());
    }

    @Test
    @Order(17)
    void graphNodesArePublicAndReturnsConnectedData() throws Exception {
        var body = mockMvc.perform(get("/api/v1/graph/nodes"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.nodes").isArray())
            .andExpect(jsonPath("$.data.edges").isArray())
            .andExpect(jsonPath("$.data.nodes[0].type").isString())
            .andExpect(jsonPath("$.data.nodes[0].label").isString())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        var nodes = objectMapper.readTree(body).path("data").path("nodes");
        assertTrue(nodes.size() > 0, "graph should expose nodes");

        var sawTag = false;
        var sawContent = false;
        for (var node : nodes) {
            var url = node.path("url");
            if ("TAG".equals(node.path("type").asText())) {
                sawTag = true;
                // Public category pages were removed: TAG nodes are filter hubs with no URL.
                assertTrue(url.isNull(), "TAG node url must be null but was " + url);
            } else {
                sawContent = true;
                assertTrue(url.isTextual() && !url.asText().isBlank(), "content node must keep a real url");
            }
            assertFalse("/categories".equals(url.asText(null)), "no node may link to /categories");
        }
        assertTrue(sawTag, "graph should expose TAG nodes");
        assertTrue(sawContent, "graph should expose content nodes");
    }

    @Test
    @Order(18)
    void quotesDailyArePublicAndReturnsSeedData() throws Exception {
        // NB-6：daily 按 day-of-year 轮转（Asia/Shanghai），当日语录固定排首位——按同一公式推期望值
        var ids = jdbcTemplate.queryForList("select id from sys_quote order by display_order asc, id asc", Long.class);
        int offset = (java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).getDayOfYear() - 1) % ids.size();
        mockMvc.perform(get("/api/v1/quotes/daily"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.data.length()").value(ids.size()))
            .andExpect(jsonPath("$.data[0].id").value("q-" + ids.get(offset)))
            .andExpect(jsonPath("$.data[0].content").isString())
            .andExpect(jsonPath("$.data[0].author").isString())
            .andExpect(jsonPath("$.data[0].category").isString());
    }

    @Test
    @Order(19)
    void unlistedRoutesAreDeniedByDefault() throws Exception {
        // P0-1：兜底 denyAll——未显式白名单的路径未登录 401，已登录也 403
        mockMvc.perform(get("/internal-debug")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v2/anything")).andExpect(status().isUnauthorized());
        // FD-7：kitchen 前缀的近似路径不得沾光，仍走兜底
        mockMvc.perform(get("/api/v1/kitchenette")).andExpect(status().isUnauthorized());

        String token = login();
        mockMvc.perform(get("/internal-debug").header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden());
    }

    @Test
    @Order(20)
    void loginIsRateLimitedPerIp() throws Exception {
        // P0-3：同一 IP 一分钟内第 6 次登录尝试被 429 拒绝（每次携带合法 challenge，隔离限流断言）
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(loginBody("admin", "definitely-wrong")))
                .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody("admin", "definitely-wrong")))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.status").value(429));
        rateLimiter.reset();
        attemptTracker.reset();
    }

    @Test
    @Order(31)
    void loginWithoutChallengeIsRejected() throws Exception {
        // L-7：不带 challenge 凭据直接登录 → 400（Bean Validation）
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"admin-pass-12345\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @Order(32)
    void replayedChallengeIsRejected() throws Exception {
        // L-7：challenge 一次性使用——同一 challenge 重放第二次登录必须 400
        JsonNode challenge = fetchChallenge("admin");
        var body = objectMapper.createObjectNode()
            .put("username", "admin")
            .put("password", "admin-pass-12345")
            .put("challengeId", challenge.path("challengeId").asText())
            .put("nonce", solvePow(challenge.path("salt").asText(), challenge.path("difficulty").asInt()))
            .toString();
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    @Order(33)
    void thirdFailureEscalatesToImageCaptcha() throws Exception {
        // L-7：第 3 次失败后 challenge 升级为 IMAGE；无图形答案被拒，全要素正确则登录成功
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(loginBody("admin", "definitely-wrong")))
                .andExpect(status().isUnauthorized());
        }
        JsonNode escalated = fetchChallenge("admin");
        assertEquals("IMAGE", escalated.path("type").asText(), "达到阈值后应下发图形验证码");
        assertTrue(escalated.path("captchaImage").asText().startsWith("data:image/png;base64,"));

        // 只解 PoW、不带图形答案 → 400
        var withoutAnswer = objectMapper.createObjectNode()
            .put("username", "admin")
            .put("password", "admin-pass-12345")
            .put("challengeId", escalated.path("challengeId").asText())
            .put("nonce", solvePow(escalated.path("salt").asText(), escalated.path("difficulty").asInt()))
            .toString();
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content(withoutAnswer))
            .andExpect(status().isBadRequest());

        // 全要素（PoW + 图形答案，小写验证大小写不敏感）→ 登录成功；成功后计数清零回到纯 PoW
        JsonNode retry = fetchChallenge("admin");
        var fullBody = objectMapper.createObjectNode()
            .put("username", "admin")
            .put("password", "admin-pass-12345")
            .put("challengeId", retry.path("challengeId").asText())
            .put("nonce", solvePow(retry.path("salt").asText(), retry.path("difficulty").asInt()))
            .put("captchaAnswer", FIXED_CAPTCHA_TEXT.toLowerCase())
            .toString();
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content(fullBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.token").isNotEmpty());
        assertEquals("POW", fetchChallenge("admin").path("type").asText(), "成功登录后失败计数应清零");
    }

    @Test
    @Order(34)
    void tenthFailureTriggersCooldown() throws Exception {
        // L-7 + FD-0：同 (IP, 用户名) 配对第 10 次失败进入冷却——该用户名的登录与带名 challenge
        // 均 429 且带 Retry-After；同 IP 其他用户名不受牵连（家庭共用 Wi-Fi 不互锁）
        for (int i = 0; i < 10; i++) {
            rateLimiter.reset(); // 隔离 P0-3 的 5 次/分限流，只验证冷却层
            mockMvc.perform(post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(loginBody("admin", "definitely-wrong")))
                .andExpect(status().isUnauthorized());
        }
        rateLimiter.reset();
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"admin-pass-12345\",\"challengeId\":\"x\",\"nonce\":\"0\"}"))
            .andExpect(status().isTooManyRequests())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().exists("Retry-After"));
        mockMvc.perform(get("/api/v1/auth/challenge").param("username", "admin"))
            .andExpect(status().isTooManyRequests());
        // FD-0：另一位家庭成员（同 IP 不同用户名）不被锁死，仍可正常取 challenge
        mockMvc.perform(get("/api/v1/auth/challenge").param("username", "someone-else"))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/auth/challenge"))
            .andExpect(status().isOk());
        attemptTracker.reset();
    }

    @Test
    @Order(35)
    void partnerLoginIssuesPartnerScopedToken() throws Exception {
        // FD-6/FD-7：伴侣账号登录 → 响应带 PARTNER 角色与展示名；解 JWT 断言精确 roles，
        // 防"谁登录都是 ADMIN"的回归
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody("partner", "partner-pass-12345")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.role").value("PARTNER"))
            .andExpect(jsonPath("$.data.displayName").value("测试伴侣"))
            .andReturn();
        String token = objectMapper.readTree(result.getResponse().getContentAsString())
            .path("data").path("token").asText();
        JsonNode payload = objectMapper.readTree(new String(
            java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8));
        assertEquals(1, payload.path("roles").size(), "roles 必须只有一个值");
        assertEquals("PARTNER", payload.path("roles").get(0).asText());
        assertTrue(payload.path("uid").isIntegralNumber(), "uid 供 kitchen 署名/限流");
        assertEquals("测试伴侣", payload.path("name").asText());
    }

    @Test
    @Order(36)
    void partnerIsForbiddenFromEveryAdminEndpoint() throws Exception {
        // FD-7：PARTNER 打全部管理端点逐条 403（身份已认出、权限不足；401 意味着规则配置错误）
        String token = loginAs("partner", "partner-pass-12345");
        var probes = java.util.List.of(
            get("/api/v1/admin/stats"),
            get("/api/v1/admin/posts"),
            post("/api/v1/admin/posts"),
            get("/api/v1/admin/notes"),
            post("/api/v1/admin/notes"),
            get("/api/v1/admin/dishes"),
            post("/api/v1/admin/dishes"),
            put("/api/v1/admin/dishes/1"),
            delete("/api/v1/admin/dishes/1"),
            get("/api/v1/admin/ai/providers"),
            post("/api/v1/admin/ai/providers"),
            post("/api/v1/admin/notes/1/attachments"));
        for (var probe : probes) {
            mockMvc.perform(probe.header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        }
    }

    @Test
    @Order(37)
    void adminLoginKeepsAdminRoleAndAccess() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody("admin", "admin-pass-12345")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.role").value("ADMIN"))
            .andReturn();
        String token = objectMapper.readTree(result.getResponse().getContentAsString())
            .path("data").path("token").asText();
        mockMvc.perform(get("/api/v1/admin/stats").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk());
    }

    @Test
    @Order(38)
    void kitchenPrefixRequiresLoginForBothRoles() throws Exception {
        // FD-7：/api/v1/kitchen/** 必须登录（两人私有空间，无免鉴权读路径）；
        // 控制器 FD-10 才落地，规则放行后无处理器 → 404，足以证明授权层语义正确
        mockMvc.perform(get("/api/v1/kitchen/menus")).andExpect(status().isUnauthorized());
        String partnerToken = loginAs("partner", "partner-pass-12345");
        // FD-10 起控制器已落地：带 date 返回 200 空壳（exists:false），语义从 404 升级
        mockMvc.perform(get("/api/v1/kitchen/menus").param("date", "2026-01-01")
                .header("Authorization", "Bearer " + partnerToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.exists").value(false));
        String adminToken = login();
        mockMvc.perform(get("/api/v1/kitchen/menus").param("date", "2026-01-01")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk());
    }

    @Test
    @Order(39)
    void loginRejectionDoesNotRevealWhetherUsernameExists() throws Exception {
        // FD-7：真实用户名+错口令 与 不存在用户名 的错误响应必须完全一致（防枚举）
        var wrongPassword = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody("admin", "wrong-password-xyz")))
            .andExpect(status().isUnauthorized())
            .andReturn().getResponse().getContentAsString();
        var ghostUser = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody("ghost-nobody", "wrong-password-xyz")))
            .andExpect(status().isUnauthorized())
            .andReturn().getResponse().getContentAsString();
        assertEquals(objectMapper.readTree(wrongPassword).path("message").asText(),
            objectMapper.readTree(ghostUser).path("message").asText());
        attemptTracker.reset();
    }

    @Test
    @Order(43)
    void rememberLoginIssuesLongerLivedToken() throws Exception {
        // FD-9：remember=true 走 24h TTL；普通登录仍 2h
        var normal = objectMapper.readTree(mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody("admin", "admin-pass-12345")))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString()).path("data");
        var remembered = objectMapper.readTree(mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody("admin", "admin-pass-12345", true)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString()).path("data");
        var normalExpiry = java.time.Instant.parse(normal.path("expiresAt").asText());
        var rememberedExpiry = java.time.Instant.parse(remembered.path("expiresAt").asText());
        assertTrue(rememberedExpiry.isAfter(normalExpiry.plus(java.time.Duration.ofHours(20))),
            "保持登录的过期时间应比普通登录晚约 22 小时");
        rateLimiter.reset();
    }

    @Test
    @Order(44)
    void bumpingSessionsValidFromInvalidatesIssuedTokens() throws Exception {
        // FD-9：sessions_valid_from 是改密/踢下线止损阀——拨到未来，既有 token 立即 401
        String token = login();
        mockMvc.perform(get("/api/v1/admin/stats").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk());
        jdbcTemplate.update("update admin_users set sessions_valid_from = now() + interval '5 minutes' where username = 'admin'");
        mockMvc.perform(get("/api/v1/admin/stats").header("Authorization", "Bearer " + token))
            .andExpect(status().isUnauthorized());
        // 拨回过去恢复，原 token 重新生效，不影响后续用例
        jdbcTemplate.update("update admin_users set sessions_valid_from = now() - interval '1 hour' where username = 'admin'");
        mockMvc.perform(get("/api/v1/admin/stats").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk());
        rateLimiter.reset();
    }

    @Test
    @Order(45)
    void selfServicePasswordChangeRotatesAndKicksOldTokens() throws Exception {
        // FD-25：伴侣自助改密全循环——改密成功 → 旧 token 立即 401 → 新口令可登录 → 改回原口令
        String oldToken = loginAs("partner", "partner-pass-12345");
        rateLimiter.reset();

        // 匿名不可打；当前口令错 → 400 表单级错误（非 401，防前端拦截器误清会话）
        mockMvc.perform(put("/api/v1/auth/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"x\",\"newPassword\":\"whatever-long-enough\"}"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/v1/auth/password")
                .header("Authorization", "Bearer " + oldToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"wrong-current\",\"newPassword\":\"新口令是一句短语呀2026\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("当前密码不正确"));

        mockMvc.perform(put("/api/v1/auth/password")
                .header("Authorization", "Bearer " + oldToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"partner-pass-12345\",\"newPassword\":\"新口令是一句短语呀2026\"}"))
            .andExpect(status().isNoContent());

        // 旧 token 已被 sessions_valid_from 踢掉
        mockMvc.perform(get("/api/v1/kitchen/menus").header("Authorization", "Bearer " + oldToken))
            .andExpect(status().isUnauthorized());

        // 新口令可登录；随后改回原口令保持种子状态，后续用例不受影响
        rateLimiter.reset();
        attemptTracker.reset();
        challengeService.reset();
        String newToken = loginAs("partner", "新口令是一句短语呀2026");
        mockMvc.perform(put("/api/v1/auth/password")
                .header("Authorization", "Bearer " + newToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"新口令是一句短语呀2026\",\"newPassword\":\"partner-pass-12345\"}"))
            .andExpect(status().isNoContent());
        rateLimiter.reset();
        attemptTracker.reset();
        challengeService.reset();
        loginAs("partner", "partner-pass-12345");
        rateLimiter.reset();
    }

    @Test
    @Order(46)
    void kitchenMenuSupportsCollaborativeEditing() throws Exception {
        // FD-10：伴侣按 slug 点菜（快照菜谱名+署名）→ 站长自由文本加菜 → 全量 PUT 重排定档（署名保真）
        String partnerToken = loginAs("partner", "partner-pass-12345");
        String adminToken = login();
        rateLimiter.reset();

        mockMvc.perform(post("/api/v1/kitchen/menus/items").param("date", "2026-08-01")
                .header("Authorization", "Bearer " + partnerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dishSlug\":\"authentic-mapo-tofu\",\"mealSlot\":\"DINNER\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.exists").value(true))
            .andExpect(jsonPath("$.data.items[0].title").value("麻婆豆腐"))
            .andExpect(jsonPath("$.data.items[0].dishSlug").value("authentic-mapo-tofu"))
            .andExpect(jsonPath("$.data.items[0].authorName").value("测试伴侣"));

        mockMvc.perform(post("/api/v1/kitchen/menus/items").param("date", "2026-08-01")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"楼下的烤冷面\",\"mealSlot\":\"SNACK\",\"note\":\"加蛋\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.items.length()").value(2));

        var menuJson = objectMapper.readTree(mockMvc.perform(get("/api/v1/kitchen/menus")
                .param("date", "2026-08-01")
                .header("Authorization", "Bearer " + partnerToken))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString()).path("data");
        long partnerItemId = menuJson.path("items").get(0).path("id").asLong();
        long version = menuJson.path("version").asLong();

        // 全量 PUT：保留伴侣的菜（署名必须仍是她）+ 新增一道 + 定档 CONFIRMED
        mockMvc.perform(put("/api/v1/kitchen/menus").param("date", "2026-08-01")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"CONFIRMED\",\"note\":\"今晚一起做\",\"expectedVersion\":" + version + ",\"items\":["
                    + "{\"id\":" + partnerItemId + ",\"mealSlot\":\"DINNER\"},"
                    + "{\"title\":\"番茄蛋汤\",\"mealSlot\":\"DINNER\"}]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.data.items.length()").value(2))
            .andExpect(jsonPath("$.data.items[0].authorName").value("测试伴侣"))
            .andExpect(jsonPath("$.data.items[1].authorName").value("测试站长"));

        // 旧 version 的 PUT 如实 409
        mockMvc.perform(put("/api/v1/kitchen/menus").param("date", "2026-08-01")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"DRAFT\",\"note\":\"\",\"expectedVersion\":" + version + ",\"items\":[]}"))
            .andExpect(status().isConflict());
        rateLimiter.reset();
    }

    @Test
    @Order(47)
    void kitchenConcurrentAppendsBothSucceed() throws Exception {
        // FD-10：append 是可交换操作——两人同刻加菜双方都成功（含菜单首创竞态）
        String partnerToken = loginAs("partner", "partner-pass-12345");
        String adminToken = login();
        rateLimiter.reset();
        var statuses = new java.util.concurrent.CopyOnWriteArrayList<Integer>();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var tasks = java.util.List.of(
                (java.util.concurrent.Callable<Void>) () -> {
                    statuses.add(mockMvc.perform(post("/api/v1/kitchen/menus/items").param("date", "2026-08-02")
                            .header("Authorization", "Bearer " + partnerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"她想吃的煲仔饭\",\"mealSlot\":\"DINNER\"}"))
                        .andReturn().getResponse().getStatus());
                    return null;
                },
                (java.util.concurrent.Callable<Void>) () -> {
                    statuses.add(mockMvc.perform(post("/api/v1/kitchen/menus/items").param("date", "2026-08-02")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"我想吃的水煮鱼\",\"mealSlot\":\"DINNER\"}"))
                        .andReturn().getResponse().getStatus());
                    return null;
                });
            for (var future : pool.invokeAll(tasks)) future.get();
        }
        assertEquals(java.util.List.of(201, 201), statuses.stream().sorted().toList(), "并发 append 双方都应成功");
        mockMvc.perform(get("/api/v1/kitchen/menus").param("date", "2026-08-02")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(jsonPath("$.data.items.length()").value(2));
        rateLimiter.reset();
    }

    @Test
    @Order(48)
    void kitchenConcurrentPutsOneConflicts() throws Exception {
        // FD-10：真并发全量 PUT——FORCE_INCREMENT 下恰有一方 409（顺序化测试会假绿，必须真并发）
        String adminToken = login();
        rateLimiter.reset();
        mockMvc.perform(post("/api/v1/kitchen/menus/items").param("date", "2026-08-03")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"占位菜\",\"mealSlot\":\"DINNER\"}"))
            .andExpect(status().isCreated());
        var statuses = new java.util.concurrent.CopyOnWriteArrayList<Integer>();
        try (var pool = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Void> putOnce = () -> {
                statuses.add(mockMvc.perform(put("/api/v1/kitchen/menus").param("date", "2026-08-03")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CONFIRMED\",\"note\":\"并发\",\"expectedVersion\":0,\"items\":[]}"))
                    .andReturn().getResponse().getStatus());
                return null;
            };
            for (var future : pool.invokeAll(java.util.List.of(putOnce, putOnce))) future.get();
        }
        assertEquals(java.util.List.of(200, 409), statuses.stream().sorted().toList(),
            "并发 PUT 应恰有一方成功、一方版本冲突");
        rateLimiter.reset();
    }

    @Test
    @Order(49)
    void kitchenDeletePermissionsFollowAuthorship() throws Exception {
        // FD-10：删自己的菜可以；删对方的 403；ADMIN 可代删
        String partnerToken = loginAs("partner", "partner-pass-12345");
        String adminToken = login();
        rateLimiter.reset();
        var created = objectMapper.readTree(mockMvc.perform(post("/api/v1/kitchen/menus/items")
                .param("date", "2026-08-04")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"站长的菜\",\"mealSlot\":\"DINNER\"}"))
            .andReturn().getResponse().getContentAsString()).path("data");
        long adminItem = created.path("items").get(0).path("id").asLong();

        mockMvc.perform(delete("/api/v1/kitchen/menus/items/" + adminItem)
                .header("Authorization", "Bearer " + partnerToken))
            .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/kitchen/menus/items/" + adminItem)
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(0));
        rateLimiter.reset();
    }

    @Test
    @Order(50)
    void kitchenValidatesDatesAndDishReferences() throws Exception {
        String adminToken = login();
        rateLimiter.reset();
        mockMvc.perform(get("/api/v1/kitchen/menus").param("date", "8月1日")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("YYYY-MM-DD")));
        mockMvc.perform(post("/api/v1/kitchen/menus/items").param("date", "2026-08-05")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mealSlot\":\"DINNER\"}"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/kitchen/menus/items").param("date", "2026-08-05")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dishSlug\":\"ghost-dish\",\"mealSlot\":\"DINNER\"}"))
            .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/v1/kitchen/menus").param("date", "2099-01-01")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"DRAFT\",\"note\":\"\",\"expectedVersion\":0,\"items\":[]}"))
            .andExpect(status().isNotFound());
        rateLimiter.reset();
    }

    @Test
    @Order(51)
    void kitchenWritesAreRateLimitedPerUser() throws Exception {
        // FD-10：写限流按 uid（两人共用家庭 IP，按 IP 会互吃额度）——第 31 次 429
        String adminToken = login();
        rateLimiter.reset();
        for (int i = 0; i < 30; i++) {
            mockMvc.perform(post("/api/v1/kitchen/menus/items").param("date", "2026-08-06")
                    .header("Authorization", "Bearer " + adminToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"限流菜" + i + "\",\"mealSlot\":\"DINNER\"}"))
                .andExpect(status().isCreated());
        }
        mockMvc.perform(post("/api/v1/kitchen/menus/items").param("date", "2026-08-06")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"第 31 道\",\"mealSlot\":\"DINNER\"}"))
            .andExpect(status().isTooManyRequests());
        rateLimiter.reset();
    }

    @Test
    @Order(52)
    void kitchenResponsesAreNeverStoredWhilePublicCachePolicyHolds() throws Exception {
        // FD-11：kitchen 私有数据 no-store；收藏榜 no-cache（NB-7）；普通菜谱列表仍 5 分钟公共缓存
        String adminToken = login();
        mockMvc.perform(get("/api/v1/kitchen/menus").param("date", "2026-08-01")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .string("Cache-Control", "no-store"));
        mockMvc.perform(get("/api/v1/dishes/favorites"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .string("Cache-Control", "no-cache"));
        mockMvc.perform(get("/api/v1/dishes"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .string("Cache-Control", "max-age=300, public"));
        rateLimiter.reset();
    }

    @Test
    @Order(53)
    void mealLogLifecycleWithSnapshotAndPermissions() throws Exception {
        // FD-15：打卡（按菜谱/自由文本）→ 时间线 → 删他人 403 → 删自己 204
        String partnerToken = loginAs("partner", "partner-pass-12345");
        String adminToken = login();
        rateLimiter.reset();
        mockMvc.perform(post("/api/v1/kitchen/meal-logs")
                .header("Authorization", "Bearer " + partnerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dishSlug\":\"authentic-mapo-tofu\",\"mealSlot\":\"DINNER\",\"logDate\":\"2026-08-10\",\"rating\":5,\"note\":\"今天的豆腐特别嫩\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.title").value("麻婆豆腐"))
            .andExpect(jsonPath("$.data.dishSlug").value("authentic-mapo-tofu"))
            .andExpect(jsonPath("$.data.authorName").value("测试伴侣"))
            .andExpect(jsonPath("$.data.rating").value(5));
        var free = objectMapper.readTree(mockMvc.perform(post("/api/v1/kitchen/meal-logs")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"深夜泡面\",\"mealSlot\":\"SNACK\",\"logDate\":\"2026-08-10\"}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString()).path("data");
        long freeId = free.path("id").asLong();

        mockMvc.perform(get("/api/v1/kitchen/meal-logs")
                .header("Authorization", "Bearer " + partnerToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].logDate").value("2026-08-10"));

        mockMvc.perform(delete("/api/v1/kitchen/meal-logs/" + freeId)
                .header("Authorization", "Bearer " + partnerToken))
            .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/kitchen/meal-logs/" + freeId)
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isNoContent());
        rateLimiter.reset();
    }

    @Test
    @Order(54)
    void menuCheckInIsIdempotentAndFeedsCookStats() throws Exception {
        // FD-15/FD-18：一键打卡整桌菜；重复打卡幂等跳过；dish-stats 聚合可用
        String adminToken = login();
        rateLimiter.reset();
        mockMvc.perform(post("/api/v1/kitchen/menus/items").param("date", "2026-08-11")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dishSlug\":\"authentic-mapo-tofu\",\"mealSlot\":\"DINNER\"}"))
            .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/kitchen/menus/items").param("date", "2026-08-11")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"手打柠檬茶\",\"mealSlot\":\"SNACK\"}"))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/kitchen/menus/check-in").param("date", "2026-08-11")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.length()").value(2));
        // 幂等：再打一次全部跳过
        mockMvc.perform(post("/api/v1/kitchen/menus/check-in").param("date", "2026-08-11")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.length()").value(0));

        var stats = objectMapper.readTree(mockMvc.perform(get("/api/v1/kitchen/dish-stats")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString()).path("data");
        boolean found = false;
        for (var stat : stats) {
            if ("authentic-mapo-tofu".equals(stat.path("slug").asText())) {
                found = true;
                assertTrue(stat.path("cookCount").asLong() >= 1, "麻婆豆腐至少做过一次");
                assertFalse(stat.path("lastCookedAt").asText().isBlank());
            }
        }
        assertTrue(found, "聚合里应有麻婆豆腐");
        rateLimiter.reset();
    }

    @Test
    @Order(55)
    void deletingDishKeepsMealLogTitleSnapshot() throws Exception {
        // FD-15：删菜谱后打卡仍在——dishId 置空、title 快照保留（ON DELETE SET NULL 回归）
        String adminToken = login();
        rateLimiter.reset();
        var created = objectMapper.readTree(mockMvc.perform(post("/api/v1/admin/dishes")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"slug":"fd15-temp-dish","name":"临时炖菜","summary":"回归用","category":"测试",
                     "imageUrl":"/food/x.jpg","imageAlt":"x","imageCredit":"x","imageSourceUrl":"https://example.com",
                     "prepMinutes":10,"difficulty":"简单","rating":4.0,"featured":false,"published":true,
                     "displayOrder":98,"ingredients":["水"],"steps":["炖"]}
                    """))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString()).path("data");
        long dishId = created.path("id").asLong();

        var log = objectMapper.readTree(mockMvc.perform(post("/api/v1/kitchen/meal-logs")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dishSlug\":\"fd15-temp-dish\",\"mealSlot\":\"DINNER\",\"logDate\":\"2026-08-12\"}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString()).path("data");
        long logId = log.path("id").asLong();

        mockMvc.perform(delete("/api/v1/admin/dishes/" + dishId)
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isNoContent());

        var timeline = objectMapper.readTree(mockMvc.perform(get("/api/v1/kitchen/meal-logs")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString()).path("data").path("items");
        boolean kept = false;
        for (var item : timeline) {
            if (item.path("id").asLong() == logId) {
                kept = true;
                assertTrue(item.path("dishId").isNull(), "菜谱删除后 dishId 应为 null");
                assertEquals("临时炖菜", item.path("title").asText(), "title 快照保留");
            }
        }
        assertTrue(kept, "打卡记录不应随菜谱删除消失");
        rateLimiter.reset();
    }

    @Test
    @Order(21)
    void publicCountersAreRateLimitedPerIpAndSlug() throws Exception {
        // P0-2：点赞按 IP+slug 限流，第 11 次 429；其他 slug 不受影响
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/v1/posts/clarity-by-design/like")).andExpect(status().isOk());
        }
        mockMvc.perform(post("/api/v1/posts/clarity-by-design/like")).andExpect(status().isTooManyRequests());
        mockMvc.perform(post("/api/v1/dishes/authentic-mapo-tofu/favorite")).andExpect(status().isOk());
        rateLimiter.reset();
    }

    @Test
    @Order(22)
    void concurrentLikesDoNotLoseUpdates() throws Exception {
        // P0-4：数据库端原子自增，并发点赞不丢计数
        long before = objectMapper.readTree(
                mockMvc.perform(get("/api/v1/posts/clarity-by-design/stats"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString())
            .path("data").path("likeCount").asLong();

        int likes = 32;
        try (var pool = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < likes; i++) {
                pool.submit(() -> postService.likePost("clarity-by-design"));
            }
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "concurrent likes did not finish in time");
        }

        long after = objectMapper.readTree(
                mockMvc.perform(get("/api/v1/posts/clarity-by-design/stats"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString())
            .path("data").path("likeCount").asLong();
        assertEquals(before + likes, after, "atomic increment must not lose concurrent updates");
    }

    @Test
    @Order(23)
    void attachmentUploadRejectsForgedContentType() throws Exception {
        // P0-6：Content-Type 伪造成 image/png 但内容不是 PNG，应 400 拒绝
        String token = login();
        String noteBody = """
            {"title":"Magic byte test","markdownContent":"# t","folder":"Tests","status":"DRAFT","tags":[],"version":0}
            """;
        MvcResult created = mockMvc.perform(post("/api/v1/admin/notes")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(noteBody))
            .andExpect(status().isCreated()).andReturn();
        long noteId = objectMapper.readTree(created.getResponse().getContentAsString()).path("data").path("id").asLong();

        var forged = new MockMultipartFile("file", "fake.png", "image/png", new byte[] {1, 2, 3, 4});
        mockMvc.perform(multipart("/api/v1/admin/notes/" + noteId + "/attachments")
                .file(forged).header("Authorization", "Bearer " + token))
            .andExpect(status().isBadRequest());

        var forgedHtml = new MockMultipartFile("file", "page.png", "image/png",
            "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/v1/admin/notes/" + noteId + "/attachments")
                .file(forgedHtml).header("Authorization", "Bearer " + token))
            .andExpect(status().isBadRequest());

        // NB-4：真 PNG 但尺寸超 8000 上限——解码前预检拒绝
        var oversized = new MockMultipartFile("file", "wide.png", "image/png", buildTinyPng(8001, 1));
        mockMvc.perform(multipart("/api/v1/admin/notes/" + noteId + "/attachments")
                .file(oversized).header("Authorization", "Bearer " + token))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("8000")));

        mockMvc.perform(delete("/api/v1/admin/notes/" + noteId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isNoContent());
    }

    @Test
    @Order(24)
    void searchEscapesLikeWildcards() throws Exception {
        // P0-9：% 与 _ 作为字面量匹配，不再命中所有内容
        mockMvc.perform(post("/api/v1/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"%\",\"type\":\"POST\",\"page\":0,\"size\":5}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(0));

        mockMvc.perform(get("/api/v1/search").param("q", "_____"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    @Order(25)
    void aiProviderCrudMasksSecretsEverywhere() throws Exception {
        // 4A-1：密钥只写不回显——创建/列表/更新的任何响应都不得出现明文密钥
        String token = login();
        var secret = "sk-it-secret-key-9876";
        MvcResult created = mockMvc.perform(post("/api/v1/admin/ai/providers")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"it-provider\",\"baseUrl\":\"https://127.0.0.1:9443/v1\",\"apiKey\":\"" + secret
                    + "\",\"models\":[\"model-a\",\"model-b\"],\"defaultModel\":\"model-a\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("it-provider"))
            .andExpect(jsonPath("$.data.hasKey").value(true))
            .andExpect(jsonPath("$.data.keyTail").value("9876"))
            .andExpect(jsonPath("$.data.isDefault").value(true))
            .andReturn();
        assertFalse(created.getResponse().getContentAsString().contains(secret));
        long providerId = objectMapper.readTree(created.getResponse().getContentAsString())
            .path("data").path("id").asLong();

        MvcResult listed = mockMvc.perform(get("/api/v1/admin/ai/providers")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].hasKey").value(true))
            .andReturn();
        assertFalse(listed.getResponse().getContentAsString().contains(secret));

        // 更新时密钥留空表示保留原值
        MvcResult updated = mockMvc.perform(put("/api/v1/admin/ai/providers/" + providerId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"it-provider\",\"baseUrl\":\"https://127.0.0.1:9443/v1\",\"apiKey\":\"\","
                    + "\"models\":[\"model-a\"],\"defaultModel\":\"model-a\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.hasKey").value(true))
            .andExpect(jsonPath("$.data.keyTail").value("9876"))
            .andReturn();
        assertFalse(updated.getResponse().getContentAsString().contains(secret));

        mockMvc.perform(delete("/api/v1/admin/ai/providers/" + providerId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isNoContent());
    }

    @Test
    @Order(26)
    void aiProviderBaseUrlSsrfRejected() throws Exception {
        // 4A-1 SSRF：公网 http 与链路本地（云元数据 169.254.*）一律 400；字面量 IP 免 DNS
        String token = login();
        for (var badUrl : new String[]{"http://93.184.216.34", "https://169.254.169.254", "ftp://example.com"}) {
            mockMvc.perform(post("/api/v1/admin/ai/providers")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"bad-provider\",\"baseUrl\":\"" + badUrl
                        + "\",\"defaultModel\":\"m\"}"))
                .andExpect(status().isBadRequest());
        }
    }

    @Test
    @Order(27)
    void aiProviderEndpointsRequireAdminToken() throws Exception {
        mockMvc.perform(get("/api/v1/admin/ai/providers"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/admin/ai/providers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"baseUrl\":\"https://127.0.0.1:9443\",\"defaultModel\":\"m\"}"))
            .andExpect(status().isUnauthorized());
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

    /** L-7：登录前先取 challenge 并解 PoW；challenge 为 IMAGE 时附上固定图形码答案。 */
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
            body.put("captchaAnswer", FIXED_CAPTCHA_TEXT);
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
                    digest.digest((salt + candidate).getBytes(StandardCharsets.UTF_8)));
                if (hash.startsWith(prefix)) {
                    return candidate;
                }
            }
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

}
