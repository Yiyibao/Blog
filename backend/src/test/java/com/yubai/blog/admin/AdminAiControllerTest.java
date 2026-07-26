package com.yubai.blog.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yubai.blog.admin.ai.AiChatService;
import com.yubai.blog.admin.ai.ChatResponse;
import com.yubai.blog.config.AiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminAiController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminAiControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    AiChatService chatService;

    @MockitoBean
    AiProperties aiProperties;

    @BeforeEach
    void setUpLimits() {
        when(aiProperties.getMaxHistoryMessages()).thenReturn(20);
        when(aiProperties.getMaxInputChars()).thenReturn(8000);
        when(aiProperties.getMaxTotalChars()).thenReturn(40000);
    }

    @Test
    void chatReturnsOkWithData() throws Exception {
        var usage = new ChatResponse.Usage(5, 10, 15);
        when(chatService.chat(any())).thenReturn(new ChatResponse("Hello!", "deepseek-v4-flash", usage));

        mockMvc.perform(post("/api/v1/admin/ai/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.message").value("success"))
            .andExpect(jsonPath("$.data.content").value("Hello!"))
            .andExpect(jsonPath("$.data.model").value("deepseek-v4-flash"))
            .andExpect(jsonPath("$.data.usage.promptTokens").value(5))
            .andExpect(jsonPath("$.data.usage.completionTokens").value(10))
            .andExpect(jsonPath("$.data.usage.totalTokens").value(15));
    }

    @Test
    void chatReturnsOkWithNullUsage() throws Exception {
        when(chatService.chat(any())).thenReturn(new ChatResponse("Hello!", "deepseek-v4-flash", null));

        mockMvc.perform(post("/api/v1/admin/ai/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.usage").doesNotExist());
    }

    @Test
    void emptyMessagesRejected() throws Exception {
        mockMvc.perform(post("/api/v1/admin/ai/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"messages\":[]}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void tooManyMessagesRejected() throws Exception {
        var msgs = new StringBuilder("[");
        for (int i = 0; i < 21; i++) {
            if (i > 0) msgs.append(",");
            msgs.append("{\"role\":\"user\",\"content\":\"m\"}");
        }
        msgs.append("]");
        mockMvc.perform(post("/api/v1/admin/ai/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"messages\":" + msgs + "}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void invalidRoleRejected() throws Exception {
        mockMvc.perform(post("/api/v1/admin/ai/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"messages\":[{\"role\":\"admin\",\"content\":\"hi\"}]}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void blankContentRejected() throws Exception {
        mockMvc.perform(post("/api/v1/admin/ai/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"messages\":[{\"role\":\"user\",\"content\":\"\"}]}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void contentTooLongRejected() throws Exception {
        var content = "x".repeat(8001);
        mockMvc.perform(post("/api/v1/admin/ai/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"messages\":[{\"role\":\"user\",\"content\":\"" + content + "\"}]}"))
            .andExpect(status().isBadRequest());
    }
}
