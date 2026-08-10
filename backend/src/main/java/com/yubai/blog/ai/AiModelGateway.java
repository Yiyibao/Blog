package com.yubai.blog.ai;

import com.yubai.blog.admin.ai.AiChatService;
import com.yubai.blog.admin.ai.AiProviderService;
import com.yubai.blog.admin.ai.AiProviderType;
import com.yubai.blog.admin.ai.AiServiceException;
import com.yubai.blog.admin.ai.ChatMessage;
import com.yubai.blog.admin.ai.ChatRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
        var endpoint = providerService.resolveEndpoint(providerId, model);
        var capabilities = capabilityRegistry.capabilities(endpoint.providerType());
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
        return new AiModelPreparedRequest(endpoint, providerId, List.copyOf(input), capabilities);
    }

    public AiModelResult execute(AiModelPreparedRequest request) {
        if (request.endpoint().providerType() == AiProviderType.OPENAI_RESPONSES) {
            return responsesClient.execute(request);
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
                                request.requestedProviderId(),
                                request.endpoint().model()));
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
}
