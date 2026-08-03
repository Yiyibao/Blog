package com.yubai.blog.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yubai.blog.admin.ai.AiChatService;
import com.yubai.blog.admin.ai.AiServiceException;
import com.yubai.blog.admin.ai.AiStreamListener;
import com.yubai.blog.admin.ai.ChatResponse;
import com.yubai.blog.config.AiProperties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
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

    @MockitoBean(name = "aiStreamExecutor")
    ExecutorService streamExecutor;

    @MockitoBean(name = "aiSseHeartbeatScheduler")
    ScheduledExecutorService heartbeatScheduler;

    @BeforeEach
    void setUpLimits() {
        when(aiProperties.getRequestTimeout()).thenReturn(60);
        // 测试内联执行流式任务，保证 SSE 输出在断言前完成
        when(streamExecutor.submit(org.mockito.ArgumentMatchers.any(Runnable.class))).thenAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return CompletableFuture.completedFuture(null);
        });
        when(heartbeatScheduler.scheduleAtFixedRate(
                org.mockito.ArgumentMatchers.any(Runnable.class),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any()))
            .thenAnswer(invocation -> org.mockito.Mockito.mock(ScheduledFuture.class));
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
    void serviceLimitViolationMapsTo400() throws Exception {
        // 限额校验已收敛到 AiChatService（见 AiChatServiceTest），这里验证异常映射
        when(chatService.chat(any()))
            .thenThrow(new AiServiceException(HttpStatus.BAD_REQUEST, "Message count exceeds maximum of 20"));
        mockMvc.perform(post("/api/v1/admin/ai/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
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


    // ===== 4A-2：SSE 流式端点 =====

    @Test
    void streamEndpointEmitsDeltaAndDoneEvents() throws Exception {
        when(chatService.stream(any(), any())).thenAnswer(invocation -> {
            AiStreamListener listener = invocation.getArgument(1);
            listener.onDelta("Hel");
            listener.onDelta("lo");
            var response = new ChatResponse("Hello", "deepseek-v4-flash", null);
            listener.onComplete(response);
            return response;
        });

        var result = mockMvc.perform(post("/api/v1/admin/ai/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .header().string("X-Accel-Buffering", "no"))
            .andReturn();

        var body = result.getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("event:delta"), body);
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("Hel"), body);
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("lo"), body);
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("event:done"), body);
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("deepseek-v4-flash"), body);
    }

    @Test
    void streamEndpointSendsErrorEventOnServiceFailure() throws Exception {
        when(chatService.stream(any(), any()))
            .thenThrow(new AiServiceException(HttpStatus.SERVICE_UNAVAILABLE, "AI service is not configured"));

        var result = mockMvc.perform(post("/api/v1/admin/ai/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
            .andExpect(status().isOk())
            .andReturn();

        var body = result.getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("event:error"), body);
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("503"), body);
    }

    @Test
    void streamEndpointValidatesLimitsBeforeStreaming() throws Exception {
        // 建流前校验失败 → 普通 HTTP 400，且不进入流式执行
        org.mockito.Mockito.doThrow(new AiServiceException(HttpStatus.BAD_REQUEST,
                "Message length exceeds maximum of 32000"))
            .when(chatService).validateLimits(any());
        mockMvc.perform(post("/api/v1/admin/ai/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
            .andExpect(status().isBadRequest());
        org.mockito.Mockito.verify(chatService, org.mockito.Mockito.never()).stream(any(), any());
        org.mockito.Mockito.verify(streamExecutor, org.mockito.Mockito.never())
            .submit(org.mockito.ArgumentMatchers.any(Runnable.class));
    }
}
