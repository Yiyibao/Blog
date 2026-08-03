package com.yubai.blog.admin;

import com.yubai.blog.admin.ai.ChatAppendRequest;
import com.yubai.blog.admin.ai.ChatHistoryService;
import com.yubai.blog.admin.ai.ChatMessageResponse;
import com.yubai.blog.admin.ai.ChatSessionResponse;
import com.yubai.blog.common.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/ai/chat-sessions")
public class AdminAiChatHistoryController {
    private final ChatHistoryService service;

    public AdminAiChatHistoryController(ChatHistoryService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<ChatSessionResponse>> list() {
        return ApiResponse.ok(service.listSessions(currentOwner()));
    }

    @PostMapping
    public ApiResponse<ChatSessionResponse> create() {
        return ApiResponse.ok(service.createSession(currentOwner()));
    }

    @GetMapping("/{sessionId}/messages")
    public ApiResponse<List<ChatMessageResponse>> messages(@PathVariable Long sessionId) {
        return ApiResponse.ok(service.messages(sessionId, currentOwner()));
    }

    @PostMapping("/{sessionId}/messages")
    public ApiResponse<ChatSessionResponse> append(@PathVariable Long sessionId,
                                                   @Valid @RequestBody ChatAppendRequest request) {
        return ApiResponse.ok(service.appendMessages(sessionId, currentOwner(), request.messages()));
    }

    @DeleteMapping("/{sessionId}")
    public ApiResponse<Void> delete(@PathVariable Long sessionId) {
        service.deleteSession(sessionId, currentOwner());
        return ApiResponse.ok(null);
    }

    /** 该路由整体受 JWT 保护，未认证上下文的兜底仅用于测试场景。 */
    private static String currentOwner() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuth) return jwtAuth.getName();
        return "admin";
    }
}
