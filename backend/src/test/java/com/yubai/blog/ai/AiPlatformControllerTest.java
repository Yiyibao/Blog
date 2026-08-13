package com.yubai.blog.ai;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yubai.blog.config.AiPlatformProperties;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AiPlatformControllerTest {
    private AiTaskService taskService;
    private AiTaskEventService eventService;
    private AiFileService fileService;
    private AiArtifactService artifactService;
    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        var properties = new AiPlatformProperties();
        properties.setTasksEnabled(true);
        properties.setMultimodalEnabled(true);
        properties.setArtifactsEnabled(true);
        taskService = mock(AiTaskService.class);
        eventService = mock(AiTaskEventService.class);
        fileService = mock(AiFileService.class);
        artifactService = mock(AiArtifactService.class);
        var controller =
                new AiPlatformController(
                        properties,
                        mock(AiSessionService.class),
                        taskService,
                        mock(AiTaskOrchestrator.class),
                        eventService,
                        fileService,
                        mock(AiMemoryService.class),
                        artifactService,
                        mock(AiActionProposalService.class));
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void artifactDownloadIsAControlledPrivateAttachment() throws Exception {
        var artifactId = UUID.randomUUID();
        var bytes = "private result".getBytes(StandardCharsets.UTF_8);
        var entity =
                AiArtifactEntity.ready(
                        artifactId,
                        "alice",
                        UUID.randomUUID(),
                        "ai-artifacts/private",
                        "result.md",
                        "text/markdown",
                        bytes.length,
                        AiFileService.sha256(bytes),
                        Instant.now().plusSeconds(60));
        when(artifactService.read(artifactId, "alice"))
                .thenReturn(new AiArtifactService.AiArtifactContent(entity, bytes));

        mockMvc.perform(
                        get("/api/v1/ai/artifacts/{artifactId}/download", artifactId)
                                .principal(() -> "alice"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(bytes))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(
                        header().string(
                                        HttpHeaders.CONTENT_DISPOSITION,
                                        "attachment; filename*=UTF-8''result.md"));
        verify(artifactService).read(artifactId, "alice");
    }

    @Test
    void fileContentIsAControlledPrivateInlineResource() throws Exception {
        var fileId = UUID.randomUUID();
        var bytes = "private note".getBytes(StandardCharsets.UTF_8);
        var entity =
                AiFileEntity.ready(
                        fileId,
                        "alice",
                        "ai-files/private.txt",
                        "私人资料.txt",
                        "text/plain",
                        bytes.length,
                        AiFileService.sha256(bytes),
                        AiFileRetention.THIRTY_DAYS,
                        Instant.now().plusSeconds(60),
                        "private note");
        when(fileService.readReady(fileId, "alice"))
                .thenReturn(new AiFileService.AiFileContent(entity, bytes));

        mockMvc.perform(get("/api/v1/ai/files/{fileId}/content", fileId).principal(() -> "alice"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(bytes))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(
                        header().string(
                                        HttpHeaders.CONTENT_DISPOSITION,
                                        "inline; filename*=UTF-8''%E7%A7%81%E4%BA%BA%E8%B5%84%E6%96%99.txt"));
        verify(fileService).readReady(fileId, "alice");
    }

    @Test
    void lastEventIdReplaysOnlyLaterPersistentEvents() throws Exception {
        var taskId = UUID.randomUUID();
        var event =
                new AiTaskEventResponse(
                        taskId,
                        6,
                        "task.completed",
                        new ObjectMapper().createObjectNode().put("status", "COMPLETED"),
                        Instant.now());
        when(taskService.requireOwned(taskId, "alice")).thenReturn(mock(AiTaskEntity.class));
        when(eventService.replay(taskId, 5)).thenReturn(List.of(event));

        var stream =
                mockMvc.perform(
                                get("/api/v1/ai/tasks/{taskId}/stream", taskId)
                                        .header("Last-Event-ID", "5")
                                        .principal(() -> "alice"))
                        .andExpect(request().asyncStarted())
                        .andReturn();
        mockMvc.perform(asyncDispatch(stream))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id:6")))
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.containsString(
                                                "event:task.completed")));
        verify(eventService).replay(taskId, 5);
    }
}
