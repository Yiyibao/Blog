package com.yubai.blog.quote;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 4F：语录管理写契约。 */
public record QuoteRequest(
    @NotBlank @Size(max = 1000) String content,
    @NotBlank @Size(max = 120) String author,
    @NotBlank @Size(max = 80) String category,
    @Min(0) @Max(9999) int displayOrder
) {
}
