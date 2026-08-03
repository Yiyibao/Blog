package com.yubai.blog.admin.ai;

import java.util.List;

public record AiImageResult(String model, List<Image> images) {
    public record Image(byte[] bytes, String mediaType, int width, int height) {
    }
}
