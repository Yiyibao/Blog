package com.yubai.blog.ai;

public enum AiReasoningEffort {
    NONE,
    LOW,
    MEDIUM,
    HIGH,
    XHIGH,
    MAX;

    public static AiReasoningEffort parse(String value) {
        if (value == null || value.isBlank()) return NONE;
        return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }

    public String wireValue() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
