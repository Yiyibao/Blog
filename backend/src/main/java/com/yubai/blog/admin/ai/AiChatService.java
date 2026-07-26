package com.yubai.blog.admin.ai;

import com.yubai.blog.config.AiProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 4A-1：聊天编排——解析供应商端点（注册表优先、env 回退）后调用 OpenAI 兼容客户端。
 * 原 DeepSeekChatService 的职责拆分为本类（编排）与 OpenAiCompatibleClient（HTTP）。
 */
@Service
public class AiChatService {
    private final AiProperties properties;
    private final AiProviderService providerService;
    private final OpenAiCompatibleClient client;
    private final AiUsageService usageService;

    public AiChatService(AiProperties properties, AiProviderService providerService,
                         OpenAiCompatibleClient client, AiUsageService usageService) {
        this.properties = properties;
        this.providerService = providerService;
        this.client = client;
        this.usageService = usageService;
    }

    public ChatResponse chat(ChatRequest request) {
        var endpoint = resolveValidated(request);
        return audited(endpoint, () -> client.chat(endpoint, request.messages()));
    }

    /** 4A-2：流式对话，校验与端点解析复用非流式路径。 */
    public ChatResponse stream(ChatRequest request, AiStreamListener listener) {
        var endpoint = resolveValidated(request);
        return audited(endpoint, () -> client.stream(endpoint, request.messages(), listener));
    }

    /** 4A-6：预算闸门 + 成败皆记的用量审计（审计为旁路，不影响主流程）。 */
    private ChatResponse audited(AiEndpoint endpoint, java.util.function.Supplier<ChatResponse> call) {
        usageService.assertWithinDailyBudget(endpoint);
        long started = System.nanoTime();
        try {
            var response = call.get();
            usageService.recordSuccess(endpoint, response, elapsedMs(started));
            return response;
        } catch (RuntimeException exception) {
            usageService.recordFailure(endpoint, elapsedMs(started));
            throw exception;
        }
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    /**
     * 限额校验的唯一入口（条数/单条长度/总长）。控制器在建立 SSE 流之前调用以便返回普通
     * HTTP 400；chat/stream 内部同样执行，保证绕过控制器的调用方也不会失守。
     */
    public void validateLimits(ChatRequest request) {
        if (request.messages().size() > properties.getMaxHistoryMessages()) {
            throw new AiServiceException(HttpStatus.BAD_REQUEST,
                "Message count exceeds maximum of " + properties.getMaxHistoryMessages());
        }
        if (request.messages().stream().anyMatch(m -> m.content().length() > properties.getMaxInputChars())) {
            throw new AiServiceException(HttpStatus.BAD_REQUEST,
                "Message length exceeds maximum of " + properties.getMaxInputChars());
        }
        var totalChars = request.messages().stream().mapToInt(m -> m.content().length()).sum();
        if (totalChars > properties.getMaxTotalChars()) {
            throw new AiServiceException(HttpStatus.BAD_REQUEST,
                "Total content length exceeds maximum of " + properties.getMaxTotalChars());
        }
    }

    private AiEndpoint resolveValidated(ChatRequest request) {
        validateLimits(request);
        return providerService.resolveEndpoint(request.providerId(), request.model());
    }
}
