package com.yubai.blog.ai;

import java.util.List;

public record AiContextWindow(
        List<AiMemoryEntity> memories,
        String sessionSummary,
        List<AiTaskPartEntity> recentMessages,
        boolean memoryTruncated,
        boolean summaryTruncated,
        boolean recentMessagesTruncated) {
    public AiContextWindow {
        memories = List.copyOf(memories);
        recentMessages = List.copyOf(recentMessages);
    }

    public boolean truncated() {
        return memoryTruncated || summaryTruncated || recentMessagesTruncated;
    }
}
