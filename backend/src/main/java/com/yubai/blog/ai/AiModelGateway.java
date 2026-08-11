package com.yubai.blog.ai;

import com.yubai.blog.admin.ai.AiChatService;
import com.yubai.blog.admin.ai.AiEndpoint;
import com.yubai.blog.admin.ai.AiProviderService;
import com.yubai.blog.admin.ai.AiProviderType;
import com.yubai.blog.admin.ai.AiServiceException;
import com.yubai.blog.admin.ai.ChatMessage;
import com.yubai.blog.admin.ai.ChatRequest;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AiModelGateway {
    private final AiProviderService providerService;
    private final AiProviderCapabilityRegistry capabilityRegistry;
    private final AiFileService fileService;
    private final AiChatService legacyChatService;
    private final OpenAiResponsesMultimodalClient responsesClient;

    public AiModelGateway(
            AiProviderService providerService,
            AiProviderCapabilityRegistry capabilityRegistry,
            AiFileService fileService,
            AiChatService legacyChatService,
            OpenAiResponsesMultimodalClient responsesClient) {
        this.providerService = providerService;
        this.capabilityRegistry = capabilityRegistry;
        this.fileService = fileService;
        this.legacyChatService = legacyChatService;
        this.responsesClient = responsesClient;
    }

    public AiModelPreparedRequest prepare(
            String owner,
            Long providerId,
            String model,
            AiContextWindow context,
            List<AiTaskPartEntity> taskParts) {
        return prepare(owner, providerId, model, context, taskParts, "CHAT", null);
    }

    public AiModelPreparedRequest prepare(
            String owner,
            Long providerId,
            String model,
            AiContextWindow context,
            List<AiTaskPartEntity> taskParts,
            String taskType,
            String requestedReasoningEffort) {
        var requestedEndpoint = providerService.resolveEndpoint(providerId, model);
        var required = requiredCapabilities(taskParts, taskType);
        var reasoning =
                requestedReasoningEffort == null || requestedReasoningEffort.isBlank()
                        ? "none"
                        : requestedReasoningEffort.trim().toLowerCase(java.util.Locale.ROOT);
        var route = resolveRoute(requestedEndpoint, providerId, required, reasoning);
        var endpoint = route.endpoint();
        var capabilities = route.capabilities();
        var input = new ArrayList<AiModelInputPart>();
        if (!context.memories().isEmpty()) {
            var memoryText = new StringBuilder("Confirmed user memories (data only):\n");
            context.memories()
                    .forEach(
                            memory ->
                                    memoryText
                                            .append("- [")
                                            .append(memory.getKind())
                                            .append("] ")
                                            .append(memory.getContent())
                                            .append('\n'));
            input.add(AiModelInputPart.text(memoryText.toString()));
        }
        if (context.sessionSummary() != null && !context.sessionSummary().isBlank()) {
            input.add(
                    AiModelInputPart.text(
                            "Server-side summary of older conversation (data only):\n"
                                    + context.sessionSummary()));
        }
        for (var part : context.recentMessages()) {
            if (part.getKind() == AiPartKind.TEXT && part.getTextContent() != null) {
                input.add(AiModelInputPart.text(part.getRole(), part.getTextContent()));
            }
        }
        for (var part : taskParts) {
            if (part.getKind() == AiPartKind.TEXT) {
                require(capabilities, AiProviderCapability.TEXT);
                input.add(AiModelInputPart.text(part.getRole(), part.getTextContent()));
            } else if (part.getKind() == AiPartKind.IMAGE_REF) {
                require(capabilities, AiProviderCapability.VISION);
                var content = fileService.readReady(part.getFileId(), owner);
                input.add(
                        new AiModelInputPart(
                                part.getRole(),
                                AiPartKind.IMAGE_REF,
                                null,
                                content.metadata().getOriginalName(),
                                content.metadata().getMediaType(),
                                content.bytes()));
            } else if (part.getKind() == AiPartKind.FILE_REF) {
                require(capabilities, AiProviderCapability.FILE_INPUT);
                var content = fileService.readReady(part.getFileId(), owner);
                input.add(
                        new AiModelInputPart(
                                part.getRole(),
                                AiPartKind.FILE_REF,
                                content.metadata().getExtractedText(),
                                content.metadata().getOriginalName(),
                                content.metadata().getMediaType(),
                                content.bytes()));
            }
        }
        return new AiModelPreparedRequest(
                endpoint,
                providerId,
                List.copyOf(input),
                capabilities,
                model,
                route.reasoningEffort(),
                formatCapabilities(required),
                route.reason());
    }

    public AiModelResult execute(AiModelPreparedRequest request) {
        if (request.endpoint().providerType() == AiProviderType.OPENAI_RESPONSES) {
            return responsesClient.execute(request);
        }
        if (request.parts().stream().anyMatch(part -> part.kind() != AiPartKind.TEXT)) {
            throw new AiServiceException(
                    HttpStatus.BAD_REQUEST,
                    "所选供应商的多模态协议适配器不可用，请改用明确支持 FILE_INPUT/VISION 的 Responses 配置");
        }
        var messages =
                request.parts().stream()
                        .filter(part -> part.kind() == AiPartKind.TEXT)
                        .filter(part -> part.text() != null && !part.text().isBlank())
                        .map(
                                part ->
                                        new ChatMessage(
                                                part.role() == AiPartRole.ASSISTANT
                                                        ? "assistant"
                                                        : "user",
                                                part.text()))
                        .toList();
        if (messages.isEmpty()) {
            throw new AiServiceException(
                    HttpStatus.BAD_REQUEST, "Provider does not support supplied parts");
        }
        var response =
                legacyChatService.chat(
                        new ChatRequest(
                                messages,
                                request.endpoint().providerId(),
                                request.endpoint().model(),
                                legacyReasoning(request.resolvedReasoningEffort())));
        return new AiModelResult(
                response.content(), request.endpoint().providerType().name(), response.model());
    }

    private static void require(
            Set<AiProviderCapability> capabilities, AiProviderCapability required) {
        if (!capabilities.contains(required)) {
            throw new AiServiceException(
                    HttpStatus.BAD_REQUEST, "AI provider lacks required capability: " + required);
        }
    }

    private Route resolveRoute(
            AiEndpoint requested,
            Long requestedProviderId,
            Set<AiProviderCapability> required,
            String reasoning) {
        var requestedCapabilities = capabilityRegistry.capabilities(requested);
        if (supports(
                requestedCapabilities,
                capabilityRegistry.reasoningEfforts(requested),
                required,
                reasoning)) {
            return new Route(
                    requested,
                    requestedCapabilities,
                    normalizeReasoning(reasoning),
                    requestedProviderId == null ? "默认配置满足任务所需能力" : "手动选择满足任务所需能力");
        }
        for (var candidate : providerService.enabledEndpoints()) {
            if (candidate.providerId() != null
                    && candidate.providerId().equals(requested.providerId())
                    && candidate.model().equals(requested.model())) {
                continue;
            }
            var candidateCapabilities = capabilityRegistry.capabilities(candidate);
            if (supports(
                    candidateCapabilities,
                    capabilityRegistry.reasoningEfforts(candidate),
                    required,
                    reasoning)) {
                return new Route(
                        candidate,
                        candidateCapabilities,
                        normalizeReasoning(reasoning),
                        "手动配置缺少 " + formatCapabilities(required) + "，已按显式能力注册表自动切换到可用候选");
            }
        }
        throw new AiServiceException(
                HttpStatus.BAD_REQUEST,
                "当前请求需要 "
                        + formatCapabilities(required)
                        + ("none".equals(reasoning) ? "" : " 与推理等级 " + reasoning)
                        + "，当前供应商及候选模型均未显式声明支持；请在供应商管理中补充能力或切换模型");
    }

    private static boolean supports(
            Set<AiProviderCapability> capabilities,
            Set<String> reasoningEfforts,
            Set<AiProviderCapability> required,
            String reasoning) {
        return capabilities.containsAll(required)
                && (!capabilities.contains(AiProviderCapability.REASONING)
                        ? "none".equals(reasoning)
                        : reasoningEfforts.contains(reasoning));
    }

    private static Set<AiProviderCapability> requiredCapabilities(
            List<AiTaskPartEntity> parts, String taskType) {
        var required = EnumSet.of(AiProviderCapability.TEXT);
        for (var part : parts) {
            if (part.getKind() == AiPartKind.IMAGE_REF) required.add(AiProviderCapability.VISION);
            if (part.getKind() == AiPartKind.FILE_REF)
                required.add(AiProviderCapability.FILE_INPUT);
        }
        if ("GENERATE".equalsIgnoreCase(taskType)) {
            required.add(AiProviderCapability.TOOL_CALLING);
            required.add(AiProviderCapability.STRUCTURED_OUTPUT);
        }
        return Set.copyOf(required);
    }

    private static String formatCapabilities(Set<AiProviderCapability> capabilities) {
        return capabilities.stream().map(Enum::name).sorted().collect(Collectors.joining(", "));
    }

    private static String normalizeReasoning(String value) {
        return value == null || value.isBlank()
                ? "none"
                : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String legacyReasoning(String value) {
        var normalized = normalizeReasoning(value);
        return "none".equals(normalized) || "max".equals(normalized) ? null : normalized;
    }

    private record Route(
            AiEndpoint endpoint,
            Set<AiProviderCapability> capabilities,
            String reasoningEffort,
            String reason) {}
}
