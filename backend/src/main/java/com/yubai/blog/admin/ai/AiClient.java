package com.yubai.blog.admin.ai;

import java.util.List;

/**
 * AI 客户端接口：所有供应商协议实现的共同契约。
 * 目前有两个实现：OpenAiCompatibleClient（OpenAI 兼容协议）和 OpenCodeServerClient（OpenCode Server 会话 API）。
 */
public interface AiClient {
    ChatResponse chat(AiEndpoint endpoint, List<ChatMessage> messages);

    ChatResponse stream(AiEndpoint endpoint, List<ChatMessage> messages, AiStreamListener listener);

    List<String> listModels(AiEndpoint endpoint);
}
