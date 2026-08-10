package com.yubai.blog.ai;

public record AiModelInputPart(
        AiPartRole role,
        AiPartKind kind,
        String text,
        String filename,
        String mediaType,
        byte[] bytes) {
    public AiModelInputPart {
        role = role == null ? AiPartRole.USER : role;
        bytes = bytes == null ? null : bytes.clone();
    }

    public AiModelInputPart(
            AiPartKind kind, String text, String filename, String mediaType, byte[] bytes) {
        this(AiPartRole.USER, kind, text, filename, mediaType, bytes);
    }

    @Override
    public byte[] bytes() {
        return bytes == null ? null : bytes.clone();
    }

    public static AiModelInputPart text(String text) {
        return text(AiPartRole.USER, text);
    }

    public static AiModelInputPart text(AiPartRole role, String text) {
        return new AiModelInputPart(role, AiPartKind.TEXT, text, null, null, null);
    }
}
