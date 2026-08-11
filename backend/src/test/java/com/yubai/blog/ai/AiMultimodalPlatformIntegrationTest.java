package com.yubai.blog.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.yubai.blog.TestDatabase;
import com.yubai.blog.admin.ai.AiGeneratedImageEntity;
import com.yubai.blog.admin.ai.AiGeneratedImageRepository;
import com.yubai.blog.admin.ai.AiImageSessionEntity;
import com.yubai.blog.admin.ai.AiImageSessionRepository;
import com.yubai.blog.admin.ai.AiProviderRequest;
import com.yubai.blog.admin.ai.AiProviderService;
import com.yubai.blog.admin.ai.AiProviderType;
import com.yubai.blog.common.NotFoundException;
import com.yubai.blog.storage.StorageService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "springdoc.api-docs.enabled=true")
@AutoConfigureMockMvc
class AiMultimodalPlatformIntegrationTest {
    private static final String OWNER = "m1-alice";
    private static final String OTHER = "m1-bob";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<JsonNode> REQUESTS = new CopyOnWriteArrayList<>();
    private static final Path STORAGE =
            Path.of("target", "ai-m1-storage-" + UUID.randomUUID()).toAbsolutePath();
    private static final String SCHEMA =
            "ai_m1_it_" + UUID.randomUUID().toString().replace("-", "");
    private static HttpServer provider;

    @Autowired AiProviderService providerService;
    @Autowired AiFileService fileService;
    @Autowired AiTaskService taskService;
    @Autowired AiTaskOrchestrator orchestrator;
    @Autowired AiTaskEventService eventService;
    @Autowired AiMemoryService memoryService;
    @Autowired AiArtifactService artifactService;
    @Autowired AiImageSessionRepository imageSessionRepository;
    @Autowired AiGeneratedImageRepository generatedImageRepository;
    @Autowired StorageService storageService;
    @Autowired MockMvc mockMvc;

    @BeforeAll
    static void prepare() throws Exception {
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext(
                "/responses",
                exchange -> {
                    var request = MAPPER.readTree(exchange.getRequestBody());
                    REQUESTS.add(request);
                    var responseBody =
                            request.has("tools")
                                    ? toolResponse()
                                    : "{\"model\":\"fake-multimodal\",\"output_text\":\"fake answer\"}";
                    var response = responseBody.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        provider.start();
    }

    private static String toolResponse() {
        try {
            var output = MAPPER.createArrayNode();
            for (var spec :
                    List.of(
                            List.of("call-pdf", "PDF", "report.pdf", "# 中文报告\n\nPDF 关键事实"),
                            List.of("call-docx", "DOCX", "report.docx", "## Word 结论\n\nDOCX 关键事实"),
                            List.of("call-xlsx", "XLSX", "report.xlsx", "名称,值\n关键事实,100"))) {
                var arguments =
                        MAPPER.writeValueAsString(
                                java.util.Map.of(
                                        "format", spec.get(1),
                                        "name", spec.get(2),
                                        "content", spec.get(3)));
                var call = output.addObject();
                call.put("type", "function_call");
                call.put("call_id", spec.get(0));
                call.put("name", "generate_document");
                call.put("arguments", arguments);
            }
            var body = MAPPER.createObjectNode();
            body.put("model", "fake-tools");
            body.set("output", output);
            return MAPPER.writeValueAsString(body);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to build fake tool response", exception);
        }
    }

    @AfterAll
    static void cleanup() {
        if (provider != null) provider.stop(0);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        createIsolatedSchema();
        var separator = TestDatabase.URL.contains("?") ? "&" : "?";
        registry.add(
                "spring.datasource.url",
                () -> TestDatabase.URL + separator + "currentSchema=" + SCHEMA + ",public");
        registry.add("spring.datasource.username", () -> TestDatabase.USERNAME);
        registry.add("spring.datasource.password", () -> TestDatabase.PASSWORD);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        registry.add("app.jwt.secret", () -> "ai-m1-integration-secret-key-32chars!");
        registry.add("app.ai.master-key", () -> "ai-m1-integration-master-key-32chars!");
        registry.add("app.ai.allow-local-endpoints", () -> "true");
        registry.add("app.attachment.storage.dir", () -> STORAGE.toString());
        registry.add("app.ai.platform.tasks-enabled", () -> "true");
        registry.add("app.ai.platform.multimodal-enabled", () -> "true");
        registry.add("app.ai.platform.memory-enabled", () -> "true");
        registry.add("app.ai.platform.artifacts-enabled", () -> "true");
    }

    private static void createIsolatedSchema() {
        try (var connection =
                        DriverManager.getConnection(
                                TestDatabase.URL, TestDatabase.USERNAME, TestDatabase.PASSWORD);
                var statement = connection.createStatement()) {
            statement.execute("create schema " + SCHEMA);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Unable to create isolated AI integration schema", exception);
        }
    }

    @Test
    void openApiPublishesTheAiPlatformContract() throws Exception {
        var body =
                mockMvc.perform(get("/v3/api-docs"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);
        var paths = MAPPER.readTree(body).path("paths");
        assertThat(paths.has("/api/v1/ai/sessions")).isTrue();
        assertThat(paths.has("/api/v1/ai/projects")).isTrue();
        assertThat(paths.has("/api/v1/ai/projects/{projectId}/sessions")).isTrue();
        assertThat(paths.has("/api/v1/ai/sessions/{sessionId}/conversation")).isTrue();
        assertThat(paths.has("/api/v1/ai/sessions/{sessionId}/summary")).isTrue();
        assertThat(paths.has("/api/v1/ai/tasks")).isTrue();
        assertThat(paths.has("/api/v1/ai/tasks/{taskId}/run")).isTrue();
        assertThat(paths.has("/api/v1/ai/tasks/{taskId}/stream")).isTrue();
        assertThat(paths.has("/api/v1/ai/files")).isTrue();
        assertThat(paths.has("/api/v1/ai/files/{fileId}/content")).isTrue();
        assertThat(paths.has("/api/v1/ai/memories/{memoryId}/confirm")).isTrue();
        assertThat(paths.has("/api/v1/ai/tasks/{taskId}/artifacts")).isTrue();
        assertThat(paths.has("/api/v1/ai/artifacts/{artifactId}/download")).isTrue();

        var output = System.getProperty("ai.openapi.output", "").trim();
        if (!output.isEmpty()) {
            Files.writeString(Path.of(output), body, StandardCharsets.UTF_8);
        }
    }

    @Test
    void completePersistentMultimodalMemoryArtifactAndOwnerIsolationLoop() throws Exception {
        var registered =
                providerService.create(
                        new AiProviderRequest(
                                "M1 fake responses",
                                "http://127.0.0.1:" + provider.getAddress().getPort(),
                                "fake-key",
                                List.of("fake-multimodal"),
                                "fake-multimodal",
                                true,
                                100,
                                100_000,
                                AiProviderType.OPENAI_RESPONSES));
        var image =
                fileService.upload(
                        OWNER,
                        new MockMultipartFile("file", "pixel.png", "image/png", tinyPng()),
                        AiFileRetention.THIRTY_DAYS);
        var document =
                fileService.upload(
                        OWNER,
                        new MockMultipartFile(
                                "file",
                                "note.md",
                                "text/markdown",
                                "# 私有资料\n只供测试".getBytes(StandardCharsets.UTF_8)),
                        AiFileRetention.THIRTY_DAYS);
        var pdf =
                fileService.upload(
                        OWNER,
                        new MockMultipartFile("file", "paper.pdf", "application/pdf", pdf()),
                        AiFileRetention.THIRTY_DAYS);
        var docx =
                fileService.upload(
                        OWNER,
                        new MockMultipartFile(
                                "file",
                                "brief.docx",
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                docx()),
                        AiFileRetention.THIRTY_DAYS);
        var uploadedCsv =
                fileService.upload(
                        OWNER,
                        new MockMultipartFile(
                                "file",
                                "table.csv",
                                "text/csv",
                                "name,value\nalpha,1".getBytes(StandardCharsets.UTF_8)),
                        AiFileRetention.THIRTY_DAYS);
        var first =
                taskService
                        .create(
                                OWNER,
                                new AiTaskCreateRequest(
                                        null,
                                        "多模态理解",
                                        "CHAT",
                                        registered.id(),
                                        "fake-multimodal",
                                        "m1-first",
                                        List.of(
                                                new AiTaskPartRequest(
                                                        AiPartKind.TEXT, "理解附件", null, null, null),
                                                new AiTaskPartRequest(
                                                        AiPartKind.IMAGE_REF,
                                                        null,
                                                        image.id(),
                                                        null,
                                                        null),
                                                new AiTaskPartRequest(
                                                        AiPartKind.FILE_REF,
                                                        null,
                                                        document.id(),
                                                        null,
                                                        null),
                                                new AiTaskPartRequest(
                                                        AiPartKind.FILE_REF,
                                                        null,
                                                        pdf.id(),
                                                        null,
                                                        null),
                                                new AiTaskPartRequest(
                                                        AiPartKind.FILE_REF,
                                                        null,
                                                        docx.id(),
                                                        null,
                                                        null),
                                                new AiTaskPartRequest(
                                                        AiPartKind.FILE_REF,
                                                        null,
                                                        uploadedCsv.id(),
                                                        null,
                                                        null))))
                        .task();

        var completed = orchestrator.run(first.id(), OWNER);

        assertThat(completed.status()).isEqualTo(AiTaskStatus.COMPLETED);
        assertThat(completed.parts()).anyMatch(part -> part.role() == AiPartRole.ASSISTANT);
        assertThat(REQUESTS.getLast().at("/input/0/content").findValuesAsText("type"))
                .containsExactly(
                        "input_text",
                        "input_image",
                        "input_file",
                        "input_file",
                        "input_file",
                        "input_file");
        assertThat(REQUESTS.getLast().at("/input/0/content").findValuesAsText("filename"))
                .containsExactly("note.md", "paper.pdf", "brief.docx", "table.csv");
        assertThat(eventService.replay(first.id(), 0).stream().map(AiTaskEventResponse::eventType))
                .contains("task.started", "message.delta", "message.completed", "task.completed");

        var followUp =
                taskService
                        .create(
                                OWNER,
                                new AiTaskCreateRequest(
                                        first.sessionId(),
                                        null,
                                        "CHAT",
                                        registered.id(),
                                        "fake-multimodal",
                                        "m1-follow-up",
                                        List.of(
                                                new AiTaskPartRequest(
                                                        AiPartKind.TEXT,
                                                        "继续解释上一轮",
                                                        null,
                                                        null,
                                                        null))))
                        .task();
        assertThat(orchestrator.run(followUp.id(), OWNER).status())
                .isEqualTo(AiTaskStatus.COMPLETED);
        var followUpInput = REQUESTS.getLast().path("input");
        assertThat(followUpInput.findValuesAsText("role"))
                .containsExactly("user", "assistant", "user");
        assertThat(followUpInput.toString()).contains("理解附件", "fake answer", "继续解释上一轮");
        assertThat(eventService.replay(first.id(), 2).getFirst().sequence()).isGreaterThan(2);

        var requestCountBeforeCapabilityRouting = REQUESTS.size();
        var textOnlyProvider =
                providerService.create(
                        new AiProviderRequest(
                                "M1 text-only provider",
                                "http://127.0.0.1:" + provider.getAddress().getPort(),
                                "fake-key",
                                List.of("text-only"),
                                "text-only",
                                true,
                                100,
                                100_000,
                                AiProviderType.OPENAI_COMPATIBLE));
        var unsupported =
                taskService
                        .create(
                                OWNER,
                                new AiTaskCreateRequest(
                                        null,
                                        "capability-mismatch",
                                        "CHAT",
                                        textOnlyProvider.id(),
                                        "text-only",
                                        "m1-capability-mismatch",
                                        List.of(
                                                new AiTaskPartRequest(
                                                        AiPartKind.IMAGE_REF,
                                                        null,
                                                        image.id(),
                                                        null,
                                                        null))))
                        .task();
        var capabilityRouting = orchestrator.run(unsupported.id(), OWNER);
        assertThat(capabilityRouting.status()).isEqualTo(AiTaskStatus.COMPLETED);
        assertThat(capabilityRouting.requestedProviderId()).isEqualTo(textOnlyProvider.id());
        assertThat(capabilityRouting.resolvedProviderId()).isNotEqualTo(textOnlyProvider.id());
        assertThat(capabilityRouting.routeReason()).contains("VISION");
        assertThat(REQUESTS).hasSize(requestCountBeforeCapabilityRouting + 1);

        var proposed =
                memoryService.create(
                        OWNER,
                        new AiMemoryCreateRequest(
                                "USER",
                                "PREFERENCE",
                                "优先给出结论",
                                first.id(),
                                "task:first",
                                null,
                                null));
        assertThat(proposed.status()).isEqualTo(AiMemoryStatus.PROPOSED);
        var active = memoryService.confirm(proposed.id(), OWNER);
        runTextTask(registered.id(), "m1-memory-active");
        assertThat(REQUESTS.getLast().toString()).contains("优先给出结论");
        memoryService.disable(active.id(), OWNER);
        runTextTask(registered.id(), "m1-memory-disabled");
        assertThat(REQUESTS.getLast().toString()).doesNotContain("优先给出结论");
        memoryService.forget(active.id(), OWNER);
        assertThat(memoryService.activeForContext(OWNER)).isEmpty();

        var markdown =
                artifactService.create(
                        first.id(),
                        OWNER,
                        new AiArtifactCreateRequest(
                                "answer.md", AiArtifactFormat.MARKDOWN, null, null));
        var json =
                artifactService.create(
                        first.id(),
                        OWNER,
                        new AiArtifactCreateRequest(
                                "data.json", AiArtifactFormat.JSON, "{\"ok\":true}", null));
        var csv =
                artifactService.create(
                        first.id(),
                        OWNER,
                        new AiArtifactCreateRequest(
                                "sheet.csv",
                                AiArtifactFormat.CSV,
                                "name,value\nformula,=2+2",
                                null));
        var generatedImage = createExistingGeneratedImage();
        var imageArtifact =
                artifactService.create(
                        first.id(),
                        OWNER,
                        new AiArtifactCreateRequest(
                                "generated.png",
                                AiArtifactFormat.IMAGE,
                                null,
                                generatedImage.getPublicId()));

        assertThat(
                        new String(
                                artifactService.read(markdown.id(), OWNER).bytes(),
                                StandardCharsets.UTF_8))
                .isEqualTo("fake answer");
        assertThat(
                        new String(
                                artifactService.read(json.id(), OWNER).bytes(),
                                StandardCharsets.UTF_8))
                .contains("\"ok\" : true");
        assertThat(
                        new String(
                                artifactService.read(csv.id(), OWNER).bytes(),
                                StandardCharsets.UTF_8))
                .contains("\"'=2+2\"");
        assertThat(artifactService.read(imageArtifact.id(), OWNER).metadata().getMediaType())
                .isEqualTo("image/png");

        assertThatThrownBy(() -> fileService.get(image.id(), OTHER))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> taskService.get(first.id(), OTHER))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> memoryService.get(proposed.id(), OTHER))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> artifactService.read(markdown.id(), OTHER))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void structuredDocumentToolCallsCreateThreeValidatedArtifactsInTheConversation()
            throws Exception {
        var registered =
                providerService.create(
                        new AiProviderRequest(
                                "M2 fake tools",
                                "http://127.0.0.1:" + provider.getAddress().getPort(),
                                "fake-key",
                                List.of("fake-tools"),
                                "fake-tools",
                                true,
                                100,
                                100_000,
                                AiProviderType.OPENAI_RESPONSES));
        var creation =
                taskService.create(
                        OWNER,
                        new AiTaskCreateRequest(
                                null,
                                null,
                                "生成三种文件",
                                "GENERATE",
                                registered.id(),
                                "fake-tools",
                                null,
                                "m2-tools-" + UUID.randomUUID(),
                                List.of(
                                        new AiTaskPartRequest(
                                                AiPartKind.TEXT,
                                                "请生成中文 PDF、DOCX 和 XLSX",
                                                null,
                                                null,
                                                null))));

        var response = orchestrator.run(creation.task().id(), OWNER);
        var artifacts = artifactService.listForTask(creation.task().id(), OWNER);

        assertThat(response.status())
                .as(
                        "tool task error=%s message=%s parts=%s",
                        response.errorCode(), response.errorMessage(), response.parts())
                .isEqualTo(AiTaskStatus.COMPLETED);
        assertThat(artifacts).hasSize(3);
        assertThat(artifacts)
                .extracting(AiArtifactResponse::mediaType)
                .containsExactlyInAnyOrder(
                        "application/pdf",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(response.parts())
                .extracting(AiTaskPartResponse::kind)
                .contains(AiPartKind.TOOL_CALL, AiPartKind.TOOL_RESULT, AiPartKind.ARTIFACT_REF);
        for (var artifact : artifacts) {
            assertThat(artifactService.read(artifact.id(), OWNER).bytes()).isNotEmpty();
        }
    }

    private void runTextTask(Long providerId, String idempotencyKey) {
        var task =
                taskService
                        .create(
                                OWNER,
                                new AiTaskCreateRequest(
                                        null,
                                        idempotencyKey,
                                        "CHAT",
                                        providerId,
                                        "fake-multimodal",
                                        idempotencyKey,
                                        List.of(
                                                new AiTaskPartRequest(
                                                        AiPartKind.TEXT, "继续", null, null, null))))
                        .task();
        assertThat(orchestrator.run(task.id(), OWNER).status()).isEqualTo(AiTaskStatus.COMPLETED);
    }

    private AiGeneratedImageEntity createExistingGeneratedImage() throws Exception {
        var bytes = tinyPng();
        var storageKey = "ai-generated/m1-existing.png";
        storageService.store(storageKey, bytes);
        var session = imageSessionRepository.save(AiImageSessionEntity.create(OWNER, "existing"));
        return generatedImageRepository.save(
                AiGeneratedImageEntity.create(
                        session.getId(),
                        UUID.randomUUID(),
                        "fake",
                        "fake-image",
                        "existing",
                        storageKey,
                        "existing.png",
                        "image/png",
                        bytes.length,
                        AiFileService.sha256(bytes),
                        2,
                        2));
    }

    private static byte[] tinyPng() throws Exception {
        var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static byte[] pdf() throws Exception {
        try (var document = new PDDocument()) {
            document.addPage(new PDPage());
            var output = new ByteArrayOutputStream();
            document.save(output);
            return output.toByteArray();
        }
    }

    private static byte[] docx() throws Exception {
        try (var document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("fake DOCX material");
            var output = new ByteArrayOutputStream();
            document.write(output);
            return output.toByteArray();
        }
    }
}
