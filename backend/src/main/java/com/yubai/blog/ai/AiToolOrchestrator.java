package com.yubai.blog.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yubai.blog.admin.ai.AiImageGenerateRequest;
import com.yubai.blog.admin.ai.AiImageService;
import com.yubai.blog.admin.ai.AiServiceException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Executes only the tools exposed by the unified workspace allowlist. */
@Service
public class AiToolOrchestrator {
    private static final int MAX_ARGUMENT_CHARS = 120_000;

    private final ObjectMapper objectMapper;
    private final AiTaskService taskService;
    private final AiArtifactService artifactService;
    private final AiArtifactRepository artifactRepository;
    private final AiTaskPartRepository partRepository;
    private final AiImageService imageService;

    public AiToolOrchestrator(
            ObjectMapper objectMapper,
            AiTaskService taskService,
            AiArtifactService artifactService,
            AiArtifactRepository artifactRepository,
            AiTaskPartRepository partRepository,
            AiImageService imageService) {
        this.objectMapper = objectMapper;
        this.taskService = taskService;
        this.artifactService = artifactService;
        this.artifactRepository = artifactRepository;
        this.partRepository = partRepository;
        this.imageService = imageService;
    }

    public ToolBatch execute(UUID taskId, String owner, List<AiToolCall> calls) {
        if (calls == null || calls.isEmpty()) return new ToolBatch(List.of(), List.of());
        var results = new ArrayList<ToolResult>();
        var failures = new ArrayList<String>();
        for (var call : calls) {
            try {
                var previous = existingResult(taskId, owner, call);
                if (previous != null) {
                    results.add(previous);
                    continue;
                }
                var result = executeOne(taskId, owner, call);
                results.add(result);
                taskService.appendToolPart(
                        taskId,
                        owner,
                        AiPartRole.TOOL,
                        AiPartKind.TOOL_RESULT,
                        result.message(),
                        result.payload(),
                        result.artifactId(),
                        "tool-result:" + call.stableId());
            } catch (AiServiceException exception) {
                var safeMessage =
                        exception.getMessage() == null ? "tool failed" : exception.getMessage();
                failures.add(call.name() + ": " + safeMessage);
                taskService.appendToolPart(
                        taskId,
                        owner,
                        AiPartRole.TOOL,
                        AiPartKind.TOOL_RESULT,
                        "工具执行失败",
                        "{\"status\":\"failed\",\"tool\":\"" + safeJson(call.name()) + "\"}",
                        null,
                        "tool-result:" + call.stableId());
            }
        }
        return new ToolBatch(List.copyOf(results), List.copyOf(failures));
    }

    private ToolResult existingResult(UUID taskId, String owner, AiToolCall call) {
        if (call == null) return null;
        var sourceRef = "tool-result:" + call.stableId();
        return partRepository.findByTaskIdOrderBySequenceAsc(taskId).stream()
                .filter(part -> part.getKind() == AiPartKind.TOOL_RESULT)
                .filter(part -> sourceRef.equals(part.getSourceRef()))
                .filter(part -> part.getArtifactId() != null)
                .map(
                        part ->
                                artifactRepository
                                        .findByIdAndOwner(part.getArtifactId(), owner)
                                        .filter(
                                                artifact ->
                                                        artifact.getStatus()
                                                                == AiArtifactStatus.READY)
                                        .map(
                                                artifact ->
                                                        new ToolResult(
                                                                artifact.getId(),
                                                                artifact.getName(),
                                                                "宸插鐢ㄧ敓鎴愮墿: " + artifact.getName(),
                                                                part.getPayload()))
                                        .orElse(null))
                .filter(result -> result != null)
                .findFirst()
                .orElse(null);
    }

    private ToolResult executeOne(UUID taskId, String owner, AiToolCall call) {
        if (call == null || call.name() == null || call.name().isBlank()) {
            throw badRequest("Tool name is missing");
        }
        var arguments = call.arguments() == null ? "{}" : call.arguments();
        if (arguments.length() > MAX_ARGUMENT_CHARS)
            throw badRequest("Tool arguments are too large");
        var parsed = readArguments(arguments);
        taskService.appendToolPart(
                taskId,
                owner,
                AiPartRole.ASSISTANT,
                AiPartKind.TOOL_CALL,
                null,
                arguments,
                null,
                "tool-call:" + call.stableId());
        return switch (call.name().trim()) {
            case "generate_document" -> generateDocument(taskId, owner, parsed, call);
            case "generate_image" -> generateImage(taskId, owner, parsed, call);
            default -> throw badRequest("Tool is not allowed: " + call.name());
        };
    }

    private ToolResult generateDocument(UUID taskId, String owner, JsonNode args, AiToolCall call) {
        var formatValue = text(args, "format", "PDF").toUpperCase(Locale.ROOT);
        final AiArtifactFormat format;
        try {
            format = AiArtifactFormat.valueOf(formatValue);
        } catch (IllegalArgumentException exception) {
            throw badRequest("Document format is not allowed");
        }
        if (!format.isDocument()
                && format != AiArtifactFormat.MARKDOWN
                && format != AiArtifactFormat.TEXT
                && format != AiArtifactFormat.JSON
                && format != AiArtifactFormat.CSV) {
            throw badRequest("Document format is not allowed");
        }
        var name = text(args, "name", "artifact" + format.extension());
        var content = text(args, "content", "");
        if (content.isBlank()) throw badRequest("Document content is missing");
        var artifact =
                artifactService.create(
                        taskId, owner, new AiArtifactCreateRequest(name, format, content, null));
        return new ToolResult(
                artifact.id(),
                artifact.name(),
                "已生成 " + artifact.name(),
                "{\"status\":\"ready\",\"artifactId\":\""
                        + artifact.id()
                        + "\",\"name\":\""
                        + safeJson(artifact.name())
                        + "\"}");
    }

    private ToolResult generateImage(UUID taskId, String owner, JsonNode args, AiToolCall call) {
        var prompt = text(args, "prompt", "");
        if (prompt.isBlank()) throw badRequest("Image prompt is missing");
        var name = text(args, "name", "generated-image.png");
        var generated =
                imageService.generate(
                        new AiImageGenerateRequest(
                                prompt,
                                null,
                                nullableText(args, "provider"),
                                nullableText(args, "model"),
                                1,
                                null,
                                null,
                                null,
                                null),
                        owner);
        if (generated.images().isEmpty()) throw badRequest("Image provider returned no image");
        var image = generated.images().getFirst();
        var artifact =
                artifactService.create(
                        taskId,
                        owner,
                        new AiArtifactCreateRequest(
                                name, AiArtifactFormat.IMAGE, null, image.publicId()));
        return new ToolResult(
                artifact.id(),
                artifact.name(),
                "已生成 " + artifact.name(),
                "{\"status\":\"ready\",\"artifactId\":\""
                        + artifact.id()
                        + "\",\"name\":\""
                        + safeJson(artifact.name())
                        + "\"}");
    }

    private JsonNode readArguments(String arguments) {
        try {
            var node = objectMapper.readTree(arguments);
            if (node == null || !node.isObject())
                throw badRequest("Tool arguments must be an object");
            return node;
        } catch (AiServiceException exception) {
            throw exception;
        } catch (Exception exception) {
            throw badRequest("Tool arguments are invalid JSON");
        }
    }

    private static String text(JsonNode node, String field, String fallback) {
        var value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.asText().trim() : fallback;
    }

    private static String nullableText(JsonNode node, String field) {
        var value = text(node, field, "");
        return value.isBlank() ? null : value;
    }

    private static AiServiceException badRequest(String message) {
        return new AiServiceException(HttpStatus.BAD_REQUEST, message);
    }

    private static String safeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public record ToolBatch(List<ToolResult> results, List<String> failures) {
        public boolean hasFailures() {
            return !failures.isEmpty();
        }

        public String summary() {
            if (results.isEmpty()) return "未生成文件";
            var names = results.stream().map(ToolResult::name).toList();
            return "已生成：" + String.join("、", names);
        }
    }

    public record ToolResult(UUID artifactId, String name, String message, String payload) {}
}
