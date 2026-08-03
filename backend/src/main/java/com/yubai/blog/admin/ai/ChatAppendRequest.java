package com.yubai.blog.admin.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ChatAppendRequest(
    @Valid
    @NotNull(message = "messages 不能为空")
    @Size(min = 1, max = 4, message = "一次最多追加 4 条消息")
    List<ChatMessage> messages
) {
}
