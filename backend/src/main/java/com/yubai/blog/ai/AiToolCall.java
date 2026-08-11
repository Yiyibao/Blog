package com.yubai.blog.ai;

/** Structured function call emitted by a provider adapter. */
public record AiToolCall(String id, String name, String arguments) {
    public String stableId() {
        if (id != null && !id.isBlank()) return id.trim();
        return name + ":" + (arguments == null ? "" : arguments);
    }
}
