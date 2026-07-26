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

    public AiChatService(AiProperties properties, AiProviderService providerService,
                         OpenAiCompatibleClient client) {
        this.properties = properties;
        this.providerService = providerService;
        this.client = client;
    }

    public ChatResponse chat(ChatRequest request) {
        var totalChars = request.messages().stream().mapToInt(m -> m.content().length()).sum();
        if (totalChars > properties.getMaxTotalChars()) {
            throw new AiServiceException(HttpStatus.BAD_REQUEST,
                "Total content length exceeds maximum of " + properties.getMaxTotalChars());
        }
        var endpoint = providerService.resolveEndpoint(request.providerId(), request.model());
        return client.chat(endpoint, request.messages());
    }
}
