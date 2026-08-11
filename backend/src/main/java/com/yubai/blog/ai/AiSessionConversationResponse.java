package com.yubai.blog.ai;

import java.util.List;

public record AiSessionConversationResponse(
        AiSessionResponse session,
        List<AiConversationMessageResponse> messages,
        boolean hasMore,
        int page,
        int size) {}
