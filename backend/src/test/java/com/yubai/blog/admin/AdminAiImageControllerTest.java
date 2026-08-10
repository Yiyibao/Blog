package com.yubai.blog.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yubai.blog.admin.ai.AiGeneratedImageResponse;
import com.yubai.blog.admin.ai.AiImageGenerateRequest;
import com.yubai.blog.admin.ai.AiImageGenerateResponse;
import com.yubai.blog.admin.ai.AiImageService;
import com.yubai.blog.admin.ai.AiImageSessionResponse;
import com.yubai.blog.common.RateLimiter;
import com.yubai.blog.config.AiImageProperties;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;

@WebMvcTest(AdminAiImageController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminAiImageControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean AiImageService service;

    @MockitoBean AiImageProperties properties;

    @MockitoBean RateLimiter rateLimiter;

    @Test
    void sessionsReturnsList() throws Exception {
        when(service.listSessions("admin"))
                .thenReturn(
                        List.of(
                                new AiImageSessionResponse(
                                        1L, "雨后的西湖", Instant.now(), Instant.now())));

        mockMvc.perform(get("/api/v1/admin/ai/images/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].title").value("雨后的西湖"));
        verify(service).listSessions("admin");
    }

    @Test
    void sessionImagesDelegates() throws Exception {
        mockMvc.perform(get("/api/v1/admin/ai/images/sessions/1/images"))
                .andExpect(status().isOk());
        verify(service).sessionImages(1L, "admin");
    }

    @Test
    void deleteSessionDelegates() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/ai/images/sessions/1")).andExpect(status().isOk());
        verify(service).deleteSession(1L, "admin");
    }

    @Test
    void generateReturnsSessionAndImages() throws Exception {
        var publicId = UUID.randomUUID();
        var image =
                new AiGeneratedImageResponse(
                        publicId,
                        UUID.randomUUID(),
                        "grok",
                        "grok-imagine-image-quality",
                        "a cat in the rain",
                        "image/png",
                        1234L,
                        1024,
                        1024,
                        "/content/url",
                        Instant.parse("2026-08-04T01:00:00Z"));
        when(rateLimiter.tryAcquire(any(String.class), any(Integer.class), any())).thenReturn(true);
        when(properties.getRateLimit()).thenReturn(3);
        when(properties.getRateWindowSeconds()).thenReturn(60);
        when(service.generate(any(AiImageGenerateRequest.class), eq("admin")))
                .thenReturn(new AiImageGenerateResponse(7L, "a cat in th", List.of(image)));

        mockMvc.perform(
                        post("/api/v1/admin/ai/images")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"prompt\":\"a cat in the rain\",\"sessionId\":7,\"provider\":\"grok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionId").value(7))
                .andExpect(jsonPath("$.data.sessionTitle").value("a cat in th"))
                .andExpect(jsonPath("$.data.images[0].publicId").value(publicId.toString()));
    }

    @Test
    void generateWithoutSessionIsAllowed() throws Exception {
        when(rateLimiter.tryAcquire(any(String.class), any(Integer.class), any())).thenReturn(true);
        when(properties.getRateLimit()).thenReturn(3);
        when(properties.getRateWindowSeconds()).thenReturn(60);
        when(service.generate(any(AiImageGenerateRequest.class), eq("admin")))
                .thenReturn(new AiImageGenerateResponse(1L, "新对话", List.of()));

        mockMvc.perform(
                        post("/api/v1/admin/ai/images")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"prompt\":\"hello\"}"))
                .andExpect(status().isOk());
        verify(service).generate(any(AiImageGenerateRequest.class), eq("admin"));
    }

    @Test
    void generateWithReferenceImageAcceptsMultipartPayload() throws Exception {
        when(rateLimiter.tryAcquire(any(String.class), any(Integer.class), any())).thenReturn(true);
        when(properties.getRateLimit()).thenReturn(3);
        when(properties.getRateWindowSeconds()).thenReturn(60);
        when(service.generate(
                        any(AiImageGenerateRequest.class), eq("admin"), any(MultipartFile.class)))
                .thenReturn(new AiImageGenerateResponse(2L, "参考图", List.of()));

        var payload =
                new MockMultipartFile(
                        "payload",
                        "payload.json",
                        MediaType.APPLICATION_JSON_VALUE,
                        "{\"prompt\":\"把这张图改成蓝色海报\",\"provider\":\"grok\"}"
                                .getBytes(StandardCharsets.UTF_8));
        var reference =
                new MockMultipartFile(
                        "referenceImage",
                        "reference.png",
                        MediaType.IMAGE_PNG_VALUE,
                        new byte[] {1, 2, 3});

        mockMvc.perform(multipart("/api/v1/admin/ai/images").file(payload).file(reference))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionId").value(2));
        verify(service)
                .generate(any(AiImageGenerateRequest.class), eq("admin"), any(MultipartFile.class));
    }

    @Test
    void generateRejectsBlankPrompt() throws Exception {
        mockMvc.perform(
                        post("/api/v1/admin/ai/images")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"prompt\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }
}
