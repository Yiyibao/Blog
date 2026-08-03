package com.yubai.blog.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yubai.blog.admin.ai.ChatHistoryService;
import com.yubai.blog.admin.ai.ChatMessage;
import com.yubai.blog.admin.ai.ChatSessionResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminAiChatHistoryController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminAiChatHistoryControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ChatHistoryService service;

    @Test
    void listReturnsSessions() throws Exception {
        when(service.listSessions("admin"))
            .thenReturn(List.of(new ChatSessionResponse(1L, "你好世界", Instant.now(), Instant.now())));

        mockMvc.perform(get("/api/v1/admin/ai/chat-sessions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].id").value(1))
            .andExpect(jsonPath("$.data[0].title").value("你好世界"));
        verify(service).listSessions("admin");
    }

    @Test
    void createReturnsNewSession() throws Exception {
        when(service.createSession("admin"))
            .thenReturn(new ChatSessionResponse(2L, null, Instant.now(), Instant.now()));

        mockMvc.perform(post("/api/v1/admin/ai/chat-sessions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(2));
    }

    @Test
    void appendMessagesDelegates() throws Exception {
        when(service.appendMessages(eq(1L), eq("admin"), any()))
            .thenReturn(new ChatSessionResponse(1L, "你好", Instant.now(), Instant.now()));

        mockMvc.perform(post("/api/v1/admin/ai/chat-sessions/1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"messages\":[{\"role\":\"user\",\"content\":\"你好\"},{\"role\":\"assistant\",\"content\":\"你好呀\"}]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.title").value("你好"));
        verify(service).appendMessages(eq(1L), eq("admin"), any());
    }

    @Test
    void appendMessagesRejectsEmptyList() throws Exception {
        mockMvc.perform(post("/api/v1/admin/ai/chat-sessions/1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"messages\":[]}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void appendMessagesRejectsUnknownRole() throws Exception {
        mockMvc.perform(post("/api/v1/admin/ai/chat-sessions/1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"messages\":[{\"role\":\"system\",\"content\":\"hi\"}]}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void deleteSessionDelegates() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/ai/chat-sessions/1"))
            .andExpect(status().isOk());
        verify(service).deleteSession(1L, "admin");
    }

    @Test
    void messagesEndpointDelegates() throws Exception {
        mockMvc.perform(get("/api/v1/admin/ai/chat-sessions/1/messages"))
            .andExpect(status().isOk());
        verify(service).messages(1L, "admin");
    }

    @Test
    void appendRejectsMessagesOverLimit() throws Exception {
        var body = new StringBuilder("{\"messages\":[");
        for (var i = 0; i < 5; i++) {
            if (i > 0) body.append(',');
            body.append("{\"role\":\"user\",\"content\":\"m\"}");
        }
        body.append("]}");
        mockMvc.perform(post("/api/v1/admin/ai/chat-sessions/1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString()))
            .andExpect(status().isBadRequest());
    }
}
