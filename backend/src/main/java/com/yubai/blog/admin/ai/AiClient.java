package com.yubai.blog.admin.ai;

import java.util.List;

/**
 * AI 客户端接口：所有供应商协议实现的共同契约。
 * 目前有三个实现：OpenAiCompatibleClient（OpenAI 兼容协议）、AnthropicClient（原生 Messages API）和
 * OpenCodeServerClient（OpenCode Server 会话 API）。
 */
public interface AiClient {
    ChatResponse chat(AiEndpoint endpoint, List<ChatMessage> messages);

    /**
     * Optional per-request reasoning override. Existing clients keep their
     * provider default when no override is supplied.
     */
    default ChatResponse chat(AiEndpoint endpoint, List<ChatMessage> messages, String reasoningEffort) {
        return chat(endpoint, messages);
    }

    ChatResponse stream(AiEndpoint endpoint, List<ChatMessage> messages, AiStreamListener listener);

    default ChatResponse stream(AiEndpoint endpoint, List<ChatMessage> messages,
                                AiStreamListener listener, String reasoningEffort) {
        return stream(endpoint, messages, listener);
    }

    List<String> listModels(AiEndpoint endpoint);
}
