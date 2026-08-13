package com.yubai.blog.kitchen;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yubai.blog.TestDatabase;
import com.yubai.blog.auth.ChallengeService;
import com.yubai.blog.auth.LoginAttemptTracker;
import com.yubai.blog.common.RateLimiter;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
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

@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(OrderAnnotation.class)
class ShoppingListIntegrationTest {
    private static final String MONDAY = "2026-08-10";

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
        registry.add("app.partner.display-name", () -> "测试伙伴");
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
    void resetState() {
        rateLimiter.reset();
        challengeService.reset();
        attemptTracker.reset();
    }

    @Test
    @Order(1)
    void listRequiresAuthenticationAndIsOwnerScoped() throws Exception {
        mockMvc.perform(get("/api/v1/kitchen/shopping-lists").param("weekStart", MONDAY))
                .andExpect(status().isUnauthorized());

        String adminToken = loginAs("admin", "admin-pass-12345");
        String partnerToken = loginAs("partner", "partner-pass-12345");
        var adminList =
                mockMvc.perform(
                                get("/api/v1/kitchen/shopping-lists")
                                        .param("weekStart", MONDAY)
                                        .header("Authorization", "Bearer " + adminToken))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.weekStart").value(MONDAY))
                        .andExpect(jsonPath("$.data.items").isArray())
                        .andReturn();
        String adminListId = data(adminList).path("id").asText();

        var partnerList =
                mockMvc.perform(
                                get("/api/v1/kitchen/shopping-lists")
                                        .param("weekStart", MONDAY)
                                        .header("Authorization", "Bearer " + partnerToken))
                        .andExpect(status().isOk())
                        .andReturn();
        org.junit.jupiter.api.Assertions.assertNotEquals(
                adminListId, data(partnerList).path("id").asText());
    }

    @Test
    @Order(2)
    void generationUpdateConflictClearAndReplayAreRealApiContracts() throws Exception {
        String adminToken = loginAs("admin", "admin-pass-12345");
        String partnerToken = loginAs("partner", "partner-pass-12345");
        rateLimiter.reset();

        String categoryName = "M11 测试分类";
        var createdCategory =
                mockMvc.perform(
                                post("/api/v1/admin/dish-categories")
                                        .header("Authorization", "Bearer " + adminToken)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper
                                                        .createObjectNode()
                                                        .put("name", categoryName)
                                                        .put(
                                                                "description",
                                                                "shopping list integration")
                                                        .toString()))
                        .andExpect(status().isCreated())
                        .andReturn();

        var createdDish =
                mockMvc.perform(
                                post("/api/v1/admin/dishes")
                                        .header("Authorization", "Bearer " + adminToken)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                    {
                      "name":"M11 测试番茄炒蛋","summary":"购物清单集成测试",
                      "category":"%s","imageUrl":"/food/m11-test.jpg","imageAlt":"番茄炒蛋",
                      "prepMinutes":10,"difficulty":"简单","rating":4.5,"featured":false,
                      "published":true,"displayOrder":1,"baseServings":2,
                      "ingredients":["番茄 2 个","食用油 1 汤匙"],"steps":["翻炒"]
                    }
                    """
                                                        .formatted(categoryName)))
                        .andExpect(status().isCreated())
                        .andReturn();
        String dishSlug = data(createdDish).path("slug").asText();
        long dishId = data(createdDish).path("id").asLong();

        mockMvc.perform(
                        post("/api/v1/kitchen/menus/items")
                                .param("date", MONDAY)
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper
                                                .createObjectNode()
                                                .put("dishSlug", dishSlug)
                                                .put("mealSlot", "DINNER")
                                                .toString()))
                .andExpect(status().isCreated());

        var generatedResult =
                mockMvc.perform(
                                post("/api/v1/kitchen/shopping-lists/generate")
                                        .param("weekStart", MONDAY)
                                        .header("Authorization", "Bearer " + adminToken)
                                        .header("Idempotency-Key", "shopping-generate-1"))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.items").isArray())
                        .andReturn();
        JsonNode generated = data(generatedResult);
        org.junit.jupiter.api.Assertions.assertFalse(generated.path("items").isEmpty());
        String listId = generated.path("id").asText();
        long generatedVersion = generated.path("version").asLong();

        mockMvc.perform(
                        delete("/api/v1/admin/dishes/" + dishId)
                                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());
        var snapshotAfterDishDelete =
                mockMvc.perform(
                                get("/api/v1/kitchen/shopping-lists")
                                        .param("weekStart", MONDAY)
                                        .header("Authorization", "Bearer " + adminToken))
                        .andExpect(status().isOk())
                        .andReturn();
        org.junit.jupiter.api.Assertions.assertEquals(
                generated.path("items").size(), data(snapshotAfterDishDelete).path("items").size());
        org.junit.jupiter.api.Assertions.assertEquals(
                generated.path("items").get(0).path("sourceRecipe").asText(),
                data(snapshotAfterDishDelete).path("items").get(0).path("sourceRecipe").asText());

        var replayedGeneration =
                mockMvc.perform(
                                post("/api/v1/kitchen/shopping-lists/generate")
                                        .param("weekStart", MONDAY)
                                        .header("Authorization", "Bearer " + adminToken)
                                        .header("Idempotency-Key", "shopping-generate-1"))
                        .andExpect(status().isCreated())
                        .andReturn();
        org.junit.jupiter.api.Assertions.assertEquals(
                generatedVersion, data(replayedGeneration).path("version").asLong());

        JsonNode firstItem = generated.path("items").get(0);
        String updateBody = updateBody(generatedVersion, firstItem, true, true);
        var updatedResult =
                mockMvc.perform(
                                put("/api/v1/kitchen/shopping-lists/" + listId)
                                        .header("Authorization", "Bearer " + adminToken)
                                        .header("Idempotency-Key", "shopping-update-1")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(updateBody))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.note").value("周末采购"))
                        .andExpect(jsonPath("$.data.items.length()").value(2))
                        .andReturn();
        long updatedVersion = data(updatedResult).path("version").asLong();

        var replayedUpdate =
                mockMvc.perform(
                                put("/api/v1/kitchen/shopping-lists/" + listId)
                                        .header("Authorization", "Bearer " + adminToken)
                                        .header("Idempotency-Key", "shopping-update-1")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(updateBody))
                        .andExpect(status().isOk())
                        .andReturn();
        org.junit.jupiter.api.Assertions.assertEquals(
                updatedVersion, data(replayedUpdate).path("version").asLong());

        mockMvc.perform(
                        put("/api/v1/kitchen/shopping-lists/" + listId)
                                .header("Authorization", "Bearer " + adminToken)
                                .header("Idempotency-Key", "shopping-stale-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(updateBody(generatedVersion, firstItem, false, false)))
                .andExpect(status().isConflict());

        mockMvc.perform(
                        put("/api/v1/kitchen/shopping-lists/" + listId)
                                .header("Authorization", "Bearer " + partnerToken)
                                .header("Idempotency-Key", "shopping-owner-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(updateBody(updatedVersion, firstItem, false, false)))
                .andExpect(status().isNotFound());

        var clearedResult =
                mockMvc.perform(
                                post("/api/v1/kitchen/shopping-lists/" + listId + "/clear-checked")
                                        .param("expectedVersion", Long.toString(updatedVersion))
                                        .header("Authorization", "Bearer " + adminToken)
                                        .header("Idempotency-Key", "shopping-clear-1"))
                        .andExpect(status().isOk())
                        .andReturn();
        org.junit.jupiter.api.Assertions.assertEquals(1, data(clearedResult).path("items").size());
    }

    private String updateBody(
            long expectedVersion, JsonNode generatedItem, boolean checked, boolean addManual)
            throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("expectedVersion", expectedVersion).put("note", "周末采购");
        ArrayNode items = body.putArray("items");
        ObjectNode generated = generatedItem.deepCopy();
        generated.put("checked", checked);
        items.add(generated);
        if (addManual) {
            items.add(
                    objectMapper
                            .createObjectNode()
                            .put("displayName", "厨房纸")
                            .put("normalizedName", "厨房纸")
                            .putNull("quantity")
                            .put("unit", "包")
                            .put("originalQuantity", "")
                            .put("sourceRecipe", "手工添加")
                            .put("category", "日用品")
                            .put("checked", false)
                            .put("manual", true)
                            .put("note", ""));
        }
        return objectMapper.writeValueAsString(body);
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private String loginAs(String username, String password) throws Exception {
        JsonNode challenge =
                objectMapper
                        .readTree(
                                mockMvc.perform(
                                                get("/api/v1/auth/challenge")
                                                        .param("username", username))
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString())
                        .path("data");
        ObjectNode body =
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
        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(body)))
                        .andExpect(status().isOk())
                        .andReturn();
        return objectMapper
                .readTree(result.getResponse().getContentAsString())
                .path("data")
                .path("token")
                .asText();
    }

    private static String solvePow(String salt, int difficulty) throws Exception {
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        String prefix = "0".repeat(difficulty);
        for (long nonce = 0; ; nonce++) {
            String candidate = Long.toString(nonce);
            String hash =
                    java.util.HexFormat.of()
                            .formatHex(
                                    digest.digest(
                                            (salt + candidate).getBytes(StandardCharsets.UTF_8)));
            if (hash.startsWith(prefix)) return candidate;
        }
    }
}
