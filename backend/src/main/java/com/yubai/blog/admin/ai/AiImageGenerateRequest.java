package com.yubai.blog.admin.ai;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AiImageGenerateRequest(
    @NotBlank(message = "prompt 不能为空")
    @Size(max = 32000, message = "prompt 不能超过 32000 个字符")
    String prompt,
    /** 可选：图片会话 id；为空则本次生成自动新建会话。 */
    Long sessionId,
    @Pattern(regexp = "(?i)grok|gpt", message = "provider 只能是 grok 或 gpt")
    String provider,
    @Size(max = 120, message = "model 过长")
    String model,
    @Min(value = 1, message = "n 至少为 1")
    @Max(value = 4, message = "n 不能超过 4")
    Integer n,
    String size,
    String quality,
    String aspectRatio,
    String resolution
) {
}
