package com.yubai.blog.admin.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yubai.blog.common.NotFoundException;
import com.yubai.blog.config.AiImageProperties;
import com.yubai.blog.storage.StorageService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

class AiImageServiceTest {

    private AiGeneratedImageRepository repository;
    private AiImageSessionRepository sessionRepository;
    private StorageService storage;
    private OpenAiImageClient client;
    private AiImageService service;

    @BeforeEach
    void setUp() {
        var properties = new AiImageProperties();
        properties.setEnabled(true);
        var grok = properties.getGrok();
        grok.setEnabled(true);
        grok.setBaseUrl("https://relay.example/v1");
        grok.setApiKey("test-key");
        grok.setModels("grok-imagine-image-quality");
        grok.setDefaultModel("grok-imagine-image-quality");
        repository = mock(AiGeneratedImageRepository.class);
        sessionRepository = mock(AiImageSessionRepository.class);
        storage = mock(StorageService.class);
        client = mock(OpenAiImageClient.class);
        service =
                new AiImageService(
                        properties,
                        mock(AiBaseUrlValidator.class),
                        client,
                        repository,
                        sessionRepository,
                        storage);
    }

    private static AiImageSessionEntity session(Long id, String owner, String title) {
        var entity = AiImageSessionEntity.create(owner, title);
        try {
            var idField = AiImageSessionEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, id);
        } catch (ReflectiveOperationException ignored) {
            // test-only id injection
        }
        return entity;
    }

    private AiImageGenerateRequest request(String prompt, Long sessionId) {
        return new AiImageGenerateRequest(
                prompt, sessionId, "grok", "grok-imagine-image-quality", 1, null, null, null, null);
    }

    @Test
    void generateCreatesSessionFromFirstPrompt() {
        var created = session(5L, "admin", null);
        when(sessionRepository.save(any(AiImageSessionEntity.class)))
                .thenAnswer(
                        invocation -> {
                            var entity = invocation.getArgument(0, AiImageSessionEntity.class);
                            return session(5L, entity.getOwner(), entity.getTitle());
                        });
        when(client.generate(any(), any(), anyLong()))
                .thenReturn(
                        new AiImageResult(
                                "grok-imagine-image-quality",
                                List.of(
                                        new AiImageResult.Image(
                                                new byte[] {1, 2, 3}, "image/png", 1024, 1024))));
        when(repository.save(any(AiGeneratedImageEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.generate(request("雨后的杭州西湖，国风插画", null), "admin");

        assertEquals(5L, result.sessionId());
        assertEquals("雨后的杭州西湖，国风", result.sessionTitle());
        assertEquals(1, result.images().size());
        var captor = ArgumentCaptor.forClass(AiImageSessionEntity.class);
        verify(sessionRepository).save(captor.capture());
        assertEquals("admin", captor.getValue().getOwner());
        var entityCaptor = ArgumentCaptor.forClass(AiGeneratedImageEntity.class);
        verify(repository).save(entityCaptor.capture());
        assertEquals(5L, entityCaptor.getValue().getSessionId());
        assertNotNull(entityCaptor.getValue().getGenerationId());
    }

    @Test
    void generateReusesExistingSession() {
        var existing = session(9L, "admin", "已有标题");
        when(sessionRepository.findByIdAndOwner(9L, "admin")).thenReturn(Optional.of(existing));
        when(sessionRepository.save(any(AiImageSessionEntity.class))).thenReturn(existing);
        when(client.generate(any(), any(), anyLong()))
                .thenReturn(
                        new AiImageResult(
                                "grok-imagine-image-quality",
                                List.of(
                                        new AiImageResult.Image(
                                                new byte[] {1, 2, 3}, "image/png", 1024, 1024))));
        when(repository.save(org.mockito.ArgumentMatchers.<AiGeneratedImageEntity>any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.generate(request("新的一轮", 9L), "admin");

        assertEquals(9L, result.sessionId());
        assertEquals("已有标题", result.sessionTitle());
    }

    @Test
    void validatesAndForwardsReferenceImage() throws Exception {
        var created = session(12L, "admin", null);
        when(sessionRepository.save(any(AiImageSessionEntity.class))).thenReturn(created);
        when(client.generate(any(), any(), anyLong()))
                .thenReturn(
                        new AiImageResult(
                                "grok-imagine-image-quality",
                                List.of(
                                        new AiImageResult.Image(
                                                new byte[] {1, 2, 3}, "image/png", 1, 1))));
        when(repository.save(any(AiGeneratedImageEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var input = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "png", input);
        var file =
                new MockMultipartFile(
                        "referenceImage", "reference.png", "image/png", input.toByteArray());

        service.generate(request("把它改成蓝色海报", null), "admin", file);

        var captor = ArgumentCaptor.forClass(AiImageGenerationRequest.class);
        verify(client).generate(any(), captor.capture(), anyLong());
        assertNotNull(captor.getValue().referenceImage());
        assertEquals("image/png", captor.getValue().referenceImage().mediaType());
        assertEquals(input.size(), captor.getValue().referenceImage().bytes().length);
    }

    @Test
    void generateRejectsForeignSession() {
        when(sessionRepository.findByIdAndOwner(9L, "xinn")).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> service.generate(request("试试", 9L), "xinn"));
        verify(client, never()).generate(any(), any(), anyLong());
    }

    @Test
    void listSessionsOrdersByUpdatedAtDesc() {
        when(sessionRepository.findByOwnerOrderByUpdatedAtDesc("admin"))
                .thenReturn(List.of(session(2L, "admin", "第二条"), session(1L, "admin", null)));

        var sessions = service.listSessions("admin");

        assertEquals(2, sessions.size());
        assertEquals(2L, sessions.get(0).id());
        assertEquals("第二条", sessions.get(0).title());
        assertNull(sessions.get(1).title());
    }

    @Test
    void sessionImagesReturnedInOrder() {
        when(sessionRepository.findByIdAndOwner(1L, "admin"))
                .thenReturn(Optional.of(session(1L, "admin", "标题")));
        when(repository.findBySessionIdOrderByCreatedAtAsc(1L))
                .thenReturn(
                        List.of(
                                AiGeneratedImageEntity.create(
                                        1L,
                                        java.util.UUID.randomUUID(),
                                        "grok",
                                        "m",
                                        "p",
                                        "k",
                                        "f",
                                        "image/png",
                                        1L,
                                        "a".repeat(64),
                                        1,
                                        1)));

        var images = service.sessionImages(1L, "admin");

        assertEquals(1, images.size());
        assertEquals("p", images.get(0).prompt());
    }

    @Test
    void sessionImagesOfForeignOwnerAreHidden() {
        when(sessionRepository.findByIdAndOwner(1L, "xinn")).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> service.sessionImages(1L, "xinn"));
        verify(repository, never()).findBySessionIdOrderByCreatedAtAsc(anyLong());
    }

    @Test
    void imageContentAndDeleteRecheckSessionOwner() {
        var publicId = UUID.randomUUID();
        var entity =
                AiGeneratedImageEntity.create(
                        1L,
                        publicId,
                        "grok",
                        "m",
                        "p",
                        "private-key",
                        "private.png",
                        "image/png",
                        1L,
                        "a".repeat(64),
                        1,
                        1);
        when(repository.findByPublicId(publicId)).thenReturn(Optional.of(entity));
        when(sessionRepository.findByIdAndOwner(1L, "attacker")).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> service.find(publicId, "attacker"));
        assertThrows(NotFoundException.class, () -> service.read(publicId, "attacker"));
        assertThrows(NotFoundException.class, () -> service.delete(publicId, "attacker"));

        verify(repository, never()).delete(entity);
        verify(storage, never()).read("private-key");
        verify(storage, never()).delete("private-key");
    }

    @Test
    void deleteSessionRemovesImagesFilesAndSession() {
        var entity =
                AiGeneratedImageEntity.create(
                        1L,
                        java.util.UUID.randomUUID(),
                        "grok",
                        "m",
                        "p",
                        "k",
                        "f",
                        "image/png",
                        1L,
                        "a".repeat(64),
                        1,
                        1);
        when(sessionRepository.findByIdAndOwner(1L, "admin"))
                .thenReturn(Optional.of(session(1L, "admin", "标题")));
        when(repository.findBySessionIdOrderByCreatedAtAsc(1L)).thenReturn(List.of(entity));

        service.deleteSession(1L, "admin");

        verify(repository).delete(entity);
        verify(storage).delete("k");
        verify(sessionRepository).delete(any(AiImageSessionEntity.class));
    }

    @Test
    void deleteMissingSessionThrows() {
        var generationId = 1L;
        when(sessionRepository.findByIdAndOwner(generationId, "admin"))
                .thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> service.deleteSession(generationId, "admin"));
        verify(storage, never()).delete(any(String.class));
    }

    @Test
    void titleFromTrimsWhitespaceAndStopsAtTenCodePoints() {
        assertEquals("你好世界你好世界你好", AiImageService.titleFrom("  你好世界你好世界你好世界  "));
        assertEquals("ab cd ef g", AiImageService.titleFrom("ab cd ef gh ij kl"));
        assertNull(AiImageService.titleFrom("   "));
    }
}
