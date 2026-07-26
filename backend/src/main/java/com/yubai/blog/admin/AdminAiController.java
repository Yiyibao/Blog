package com.yubai.blog.admin;

import com.yubai.blog.admin.ai.AiServiceException;
import com.yubai.blog.config.AiProperties;
import com.yubai.blog.admin.ai.ChatRequest;
import com.yubai.blog.admin.ai.ChatResponse;
import com.yubai.blog.admin.ai.DeepSeekChatService;
import com.yubai.blog.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/ai")
public class AdminAiController {
    private final DeepSeekChatService chatService;
    private final AiProperties properties;

    public AdminAiController(DeepSeekChatService chatService, AiProperties properties) {
        this.chatService = chatService;
        this.properties = properties;
    }

    @PostMapping("/chat")
    public ApiResponse<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        if (request.messages().size() > properties.getMaxHistoryMessages()) {
            throw new AiServiceException(HttpStatus.BAD_REQUEST,
                "Message count exceeds maximum of " + properties.getMaxHistoryMessages());
        }
        if (request.messages().stream().anyMatch(message -> message.content().length() > properties.getMaxInputChars())) {
            throw new AiServiceException(HttpStatus.BAD_REQUEST,
                "Message length exceeds maximum of " + properties.getMaxInputChars());
        }
        var totalChars = request.messages().stream().mapToInt(m -> m.content().length()).sum();
        if (totalChars > properties.getMaxTotalChars()) {
            throw new AiServiceException(HttpStatus.BAD_REQUEST,
                "Total content length exceeds maximum of " + properties.getMaxTotalChars());
        }
        return ApiResponse.ok(chatService.chat(request));
    }
}
