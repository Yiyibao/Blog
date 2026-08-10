package com.yubai.blog.admin.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yubai.blog.admin.ai.AiChatService;
import com.yubai.blog.admin.ai.ChatResponse;
import com.yubai.blog.config.AiProperties;
import com.yubai.blog.dish.DishImportPreviewResponse;
import com.yubai.blog.dish.DishImportService;
import com.yubai.blog.dish.YrecipePackage;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class RecipeExtractionServiceTest {
    private final RecipeExtractionJobRepository repository =
            mock(RecipeExtractionJobRepository.class);
    private final AiChatService chatService = mock(AiChatService.class);
    private final DishImportService importService = mock(DishImportService.class);
    private final ExecutorService executor = mock(ExecutorService.class);
    private final RecipeExtractionService service =
            new RecipeExtractionService(
                    repository,
                    chatService,
                    importService,
                    mock(RecipeUrlValidator.class),
                    mock(VideoRecipeSourceExtractor.class),
                    new AiProperties(),
                    executor,
                    mock(ScheduledExecutorService.class));
    private RecipeExtractionJobEntity job;

    @BeforeEach
    void setUp() {
        job =
                new RecipeExtractionJobEntity(
                        RecipeExtractionJobEntity.SourceType.TEXT, "番茄 鸡蛋", null, "model");
        ReflectionTestUtils.setField(job, "id", 1L);
        when(repository.findById(1L)).thenAnswer(ignored -> Optional.of(job));
        when(repository.save(any()))
                .thenAnswer(
                        invocation -> {
                            var saved = (RecipeExtractionJobEntity) invocation.getArgument(0);
                            if (saved.getId() == null)
                                ReflectionTestUtils.setField(saved, "id", 2L);
                            return saved;
                        });
    }

    @Test
    void createReturnsQueuedJobAndOnlySchedulesBackgroundWork() {
        var response = service.create(new RecipeExtractionRequest("TEXT", "番茄 鸡蛋", null, "model"));

        assertThat(response.status()).isEqualTo("QUEUED");
        assertThat(response.startedAt()).isNull();
        verify(executor).execute(any(Runnable.class));
    }

    @Test
    void executesQueuedTextJobAndPersistsImportToken() {
        when(chatService.chat(any()))
                .thenReturn(
                        new ChatResponse(
                                """
            {"name":"番茄炒蛋","slug":"tomato-eggs","summary":"家常菜","categoryHint":"家常菜",
             "prepMinutes":15,"difficulty":"家常","baseServings":2,
             "ingredients":["番茄 2个","鸡蛋 3个"],"steps":["炒鸡蛋","加入番茄"]}
            """,
                                "model",
                                null));
        var token = UUID.randomUUID();
        when(importService.previewFromBytes(any()))
                .thenReturn(
                        new DishImportPreviewResponse(
                                token,
                                Instant.now().plusSeconds(600),
                                mock(YrecipePackage.class),
                                List.of(),
                                "家常菜",
                                true,
                                "/api/v1/admin/dish-imports/" + token + "/cover"));

        service.execute(1L);

        assertThat(job.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(job.getResultImportToken()).isEqualTo(token);
        assertThat(job.getProgress()).isEqualTo(100);
    }

    @Test
    void recordsSafeFailureWhenAiReturnsBlankContent() {
        when(chatService.chat(any())).thenReturn(new ChatResponse("", "model", null));

        service.execute(1L);

        assertThat(job.getStatus()).isEqualTo("FAILED");
        assertThat(job.getSafeErrorMessage()).contains("AI 未返回有效内容");
    }

    @Test
    void entityAllowsThreeAttemptsAndSupportsCancellation() {
        job.start();
        job.fail("temporary");
        job.retry();
        job.start();
        job.cancel();

        assertThat(job.getStatus()).isEqualTo("CANCELLED");
        assertThat(job.getAttempts()).isEqualTo(2);
        assertThat(job.getFinishedAt()).isNotNull();
    }
}
