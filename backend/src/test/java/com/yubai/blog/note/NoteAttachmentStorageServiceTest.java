package com.yubai.blog.note;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.yubai.blog.storage.StorageService;

class NoteAttachmentStorageServiceTest {
    private final NoteAttachmentRepository attachments = mock(NoteAttachmentRepository.class);
    private final NoteRepository notes = mock(NoteRepository.class);
    private final StorageService storage = mock(StorageService.class);
    private final NoteAttachmentService service = new NoteAttachmentService(attachments, notes, storage);

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void repositoryFailureCleansStoredObjectWithoutTransactionSynchronization() throws Exception {
        when(notes.existsById(1L)).thenReturn(true);
        when(attachments.saveAndFlush(any())).thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> service.upload(1L, pngFile()))
            .isInstanceOf(IllegalStateException.class);

        verify(storage).delete(anyString());
    }

    @Test
    void transactionRollbackCleansStoredObjectAfterSuccessfulFlush() throws Exception {
        when(notes.existsById(1L)).thenReturn(true);
        when(attachments.saveAndFlush(any())).thenAnswer(invocation -> {
            var entity = invocation.getArgument(0, NoteAttachmentEntity.class);
            ReflectionTestUtils.setField(entity, "id", 1L);
            return entity;
        });
        TransactionSynchronizationManager.initSynchronization();

        service.upload(1L, pngFile());
        TransactionSynchronizationManager.getSynchronizations().forEach(
            synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(storage).delete(anyString());
    }

    private static MockMultipartFile pngFile() throws Exception {
        var image = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return new MockMultipartFile("file", "test.png", "image/png", output.toByteArray());
    }
}
