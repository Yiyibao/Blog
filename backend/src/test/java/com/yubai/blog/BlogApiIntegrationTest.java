package com.yubai.blog;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.Properties;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.BeforeAll;
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

@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BlogApiIntegrationTest {
    private static final String TEST_DB = "yubai_blog_it";
    private static final Properties ENV = loadEnv();

    @BeforeAll
    static void prepareDatabase() {
        var url = ENV.getProperty("DB_URL", "jdbc:postgresql://localhost:5432/yubai_blog");
        var username = ENV.getProperty("DB_USERNAME", "yubai_app");
        var password = ENV.getProperty("DB_PASSWORD", "");
        var testUrl = url.replaceAll("/[^/]+$", "/" + TEST_DB);
        // Prefer connecting to the dedicated IT database. Create it outside tests if missing:
        // psql -U postgres -c "CREATE DATABASE yubai_blog_it OWNER yubai_app;"
        try (var connection = DriverManager.getConnection(testUrl, username, password);
             var statement = connection.createStatement()) {
            statement.execute("drop schema if exists public cascade");
            statement.execute("create schema public");
            statement.execute("grant all on schema public to " + username);
            statement.execute("grant all on schema public to public");
        } catch (Exception exception) {
            throw new IllegalStateException(
                "Integration database is unavailable. Create PostgreSQL database '" + TEST_DB
                    + "' for user '" + username + "' before running the test suite.",
                exception
            );
        }
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        var baseUrl = ENV.getProperty("DB_URL", "jdbc:postgresql://localhost:5432/yubai_blog");
        var testUrl = baseUrl.replaceAll("/[^/]+$", "/" + TEST_DB);
        registry.add("spring.datasource.url", () -> testUrl);
        registry.add("spring.datasource.username", () -> ENV.getProperty("DB_USERNAME", "yubai_app"));
        registry.add("spring.datasource.password", () -> ENV.getProperty("DB_PASSWORD", ""));
        registry.add("spring.flyway.clean-disabled", () -> "false");
        registry.add("app.jwt.secret", () -> "integration-test-secret-key-32chars!");
        registry.add("app.admin.username", () -> "admin");
        registry.add("app.admin.password", () -> "admin-pass-12345");
        registry.add("app.cors.allowed-origins", () -> "http://localhost:5173");
        registry.add("app.site-url", () -> "http://localhost:5173");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    @Order(1)
    void publicPostsArePaginatedAndHideDrafts() throws Exception {
        String token = login();

        mockMvc.perform(get("/api/v1/posts").param("page", "0").param("size", "2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items").isArray())
            .andExpect(jsonPath("$.data.size").value(2))
            .andExpect(jsonPath("$.data.totalElements").value(5))
            .andExpect(jsonPath("$.data.totalPages").value(3));

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
            .andExpect(jsonPath("$.data.items[0].slug").value("draft-only-post"));

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
            .andExpect(jsonPath("$.data.title").value("已发布文章"));

        mockMvc.perform(delete("/api/v1/admin/posts/" + draftId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isNoContent());
    }

    @Test
    @Order(2)
    void adminEndpointsRequireAuth() throws Exception {
        mockMvc.perform(get("/api/v1/admin/posts"))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
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
        MvcResult publishedCreated = mockMvc.perform(post("/api/v1/admin/notes")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(publishedBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.status").value("DRAFT"))
            .andReturn();
        JsonNode publicDraft = objectMapper.readTree(publishedCreated.getResponse().getContentAsString()).path("data");
        long publishedId = publicDraft.path("id").asLong();
        mockMvc.perform(put("/api/v1/admin/notes/" + publishedId + "/publish")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + publicDraft.path("version").asLong() + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PUBLISHED"));

        mockMvc.perform(get("/api/v1/admin/notes").param("page", "0").param("size", "1")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(2))
            .andExpect(jsonPath("$.data.totalPages").value(2));
        mockMvc.perform(get("/api/v1/admin/notes").param("status", "DRAFT")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
        mockMvc.perform(get("/api/v1/notes"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
        mockMvc.perform(get("/api/v1/notes/" + draftId)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/notes/" + publishedId)).andExpect(status().isOk());

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

        var image = new MockMultipartFile("file", "pixel.png", "image/png", new byte[] {1, 2, 3, 4});
        MvcResult uploaded = mockMvc.perform(multipart("/api/v1/admin/notes/" + draftId + "/attachments")
                .file(image).header("Authorization", "Bearer " + token))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.data.byteSize").value(4)).andReturn();
        JsonNode attachment = objectMapper.readTree(uploaded.getResponse().getContentAsString()).path("data");
        long attachmentId = attachment.path("id").asLong();
        String publicId = attachment.path("publicId").asText();
        mockMvc.perform(get("/api/v1/admin/notes/" + draftId + "/attachments/" + attachmentId + "/content")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(result -> {
                if (result.getResponse().getContentAsByteArray().length != 4) {
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
                if (!"no-store".equals(result.getResponse().getHeader("Cache-Control"))) {
                    throw new AssertionError("revocable note attachments must not be cached");
                }
                if (result.getResponse().getContentAsByteArray().length != 4) throw new AssertionError("attachment bytes were not read from database");
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

        mockMvc.perform(get("/api/v1/posts").param("page", "-9").param("size", "999"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.page").value(0))
            .andExpect(jsonPath("$.data.size").value(50))
            .andExpect(jsonPath("$.data.items.length()").value(5));

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
            .andExpect(jsonPath("$.data.isFavorite").value(true))
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
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(0));
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

    private String login() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"admin-pass-12345\"}"))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        return root.path("data").path("token").asText();
    }

    private static Properties loadEnv() {
        var properties = new Properties();
        var candidates = new Path[] {
            Path.of(".env.properties"),
            Path.of("backend/.env.properties")
        };
        for (var candidate : candidates) {
            if (!Files.isRegularFile(candidate)) continue;
            try (var reader = Files.newBufferedReader(candidate)) {
                properties.load(reader);
                break;
            } catch (Exception ignored) {
                // fall through to defaults
            }
        }
        if (properties.isEmpty()) {
            var env = System.getenv();
            for (var key : new String[]{"DB_URL", "DB_USERNAME", "DB_PASSWORD"}) {
                var val = env.get(key);
                if (val != null) properties.setProperty(key, val);
            }
        }
        return properties;
    }
}
