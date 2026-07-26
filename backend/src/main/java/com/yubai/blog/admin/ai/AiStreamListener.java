package com.yubai.blog.admin.ai;

/**
 * 4A-2：流式输出回调。onDelta 逐段推送增量文本；onComplete 在流结束时携带完整响应。
 */
public interface AiStreamListener {
    void onDelta(String content);

    default void onComplete(ChatResponse response) {
    }
}
