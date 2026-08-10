package com.yubai.blog.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yubai.blog.admin.ai.AiImageService;
import com.yubai.blog.admin.ai.AiServiceException;
import com.yubai.blog.config.AiPlatformProperties;
import com.yubai.blog.storage.StorageService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

class AiResourceLifecycleTest {
    @Test
    void expiresMetadataAndPurgesControlledStorage() {
        var fileRepository = mock(AiFileRepository.class);
        var artifactRepository = mock(AiArtifactRepository.class);
        var storage = mock(StorageService.class);
        var properties = new AiPlatformProperties();
        properties.setMultimodalEnabled(true);
        properties.setArtifactsEnabled(true);
        var file =
                AiFileEntity.ready(
                        UUID.randomUUID(),
                        "alice",
                        "ai-files/expired",
                        "old.txt",
                        "text/plain",
                        3,
                        "a".repeat(64),
                        AiFileRetention.THIRTY_DAYS,
                        Instant.now().minusSeconds(1),
                        "old");
        var artifact =
                AiArtifactEntity.ready(
                        UUID.randomUUID(),
                        "alice",
                        UUID.randomUUID(),
                        "ai-artifacts/expired",
                        "old.md",
                        "text/markdown",
                        3,
                        "b".repeat(64),
                        Instant.now().minusSeconds(1));
        when(fileRepository.findByStatusInAndExpiresAtBefore(anyList(), any()))
                .thenReturn(List.of(file));
        when(artifactRepository.findByStatusInAndExpiresAtBefore(anyList(), any()))
                .thenReturn(List.of(artifact));
        var service =
                new AiResourceMaintenanceService(
                        fileRepository, artifactRepository, storage, properties);

        var result = service.cleanupExpired();

        assertThat(result.expiredFiles()).isEqualTo(1);
        assertThat(result.expiredArtifacts()).isEqualTo(1);
        assertThat(file.getStatus()).isEqualTo(AiFileStatus.EXPIRED);
        assertThat(file.getExtractedText()).isNull();
        assertThat(artifact.getStatus()).isEqualTo(AiArtifactStatus.EXPIRED);
        verify(storage).delete("ai-files/expired");
        verify(storage).delete("ai-artifacts/expired");
    }

    @Test
    void artifactDatabaseFailureCompensatesStoredBytes() {
        var repository = mock(AiArtifactRepository.class);
        var taskService = mock(AiTaskService.class);
        var storage = mock(StorageService.class);
        var taskId = UUID.randomUUID();
        when(taskService.requireOwned(taskId, "alice")).thenReturn(mock(AiTaskEntity.class));
        when(repository.findByTaskIdAndName(taskId, "answer.md")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(AiArtifactEntity.class)))
                .thenThrow(new IllegalStateException("database unavailable"));
        var service =
                new AiArtifactService(
                        repository,
                        taskService,
                        mock(AiTaskPartRepository.class),
                        mock(AiImageService.class),
                        mock(AiTaskEventService.class),
                        storage,
                        new AiPlatformProperties(),
                        new ObjectMapper());

        assertThatThrownBy(
                        () ->
                                service.create(
                                        taskId,
                                        "alice",
                                        new AiArtifactCreateRequest(
                                                "answer.md",
                                                AiArtifactFormat.MARKDOWN,
                                                "answer",
                                                null)))
                .isInstanceOf(IllegalStateException.class);

        var storageKey = ArgumentCaptor.forClass(String.class);
        verify(storage).store(storageKey.capture(), any(byte[].class));
        verify(storage).delete(storageKey.getValue());
    }

    @Test
    void ownerFileCountQuotaFailsBeforeParsingOrStorage() {
        var repository = mock(AiFileRepository.class);
        var parser = mock(AiFileParserRegistry.class);
        var storage = mock(StorageService.class);
        var properties = new AiPlatformProperties();
        properties.setMaxOwnerFiles(2);
        when(repository.countByOwnerAndStatusNotIn(any(), anyList())).thenReturn(2L);
        var service = new AiFileService(repository, parser, storage, properties);
        var upload =
                new MockMultipartFile(
                        "file", "note.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.upload("alice", upload, AiFileRetention.THIRTY_DAYS))
                .isInstanceOf(AiServiceException.class)
                .hasMessageContaining("count quota");
        verify(parser, never()).parse(any(), any(), any());
        verify(storage, never()).store(any(), any());
    }
}
