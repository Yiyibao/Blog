package com.yubai.blog.admin.recipe;

import static org.assertj.core.api.Assertions.assertThat;

import com.yubai.blog.TestDatabase;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest(
        properties = {
            "server.port=0",
            "app.admin.username=",
            "app.partner.username=",
            "app.recipe.extraction.video-enabled=false",
            "app.recipe.extraction.recovery-initial-delay-ms=3600000"
        })
class RecipeExtractionReliabilityIntegrationTest {
    private static final String SCHEMA = "recipe_job_reliability_test";

    @Autowired RecipeExtractionJobRepository repository;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        recreateSchema();
        var separator = TestDatabase.URL.contains("?") ? "&" : "?";
        registry.add(
                "spring.datasource.url",
                () -> TestDatabase.URL + separator + "currentSchema=" + SCHEMA + ",public");
        registry.add("spring.datasource.username", () -> TestDatabase.USERNAME);
        registry.add("spring.datasource.password", () -> TestDatabase.PASSWORD);
        registry.add("app.jwt.secret", () -> "recipe-reliability-test-secret-key-32chars");
    }

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @AfterAll
    static void cleanup() {
        try (var connection =
                        DriverManager.getConnection(
                                TestDatabase.URL, TestDatabase.USERNAME, TestDatabase.PASSWORD);
                var statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + SCHEMA + " cascade");
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Test
    void onlyOneWorkerClaimsAndCancellationWinsTheTerminalRace() {
        var job = repository.saveAndFlush(job(UUID.randomUUID()));
        var now = Instant.now();

        assertThat(repository.claim(job.getId(), "worker-a", now, now.plusSeconds(60)))
                .isEqualTo(1);
        assertThat(repository.claim(job.getId(), "worker-b", now, now.plusSeconds(60))).isZero();
        assertThat(repository.cancelActive(job.getId(), Instant.now())).isEqualTo(1);
        assertThat(repository.succeed(job.getId(), "worker-a", UUID.randomUUID(), Instant.now()))
                .isZero();
        assertThat(repository.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo("CANCELLED");
    }

    @Test
    void expiredLeaseIsDiscoverableAndCanBeTakenOverOnce() {
        var job = repository.saveAndFlush(job(UUID.randomUUID()));
        var now = Instant.now();
        assertThat(repository.claim(job.getId(), "dead-worker", now, now.plusSeconds(60)))
                .isEqualTo(1);
        repository.heartbeat(
                job.getId(),
                "dead-worker",
                "blocked",
                20,
                now.minus(2, ChronoUnit.MINUTES),
                now.minus(1, ChronoUnit.MINUTES));

        assertThat(repository.findRecoverableIds(now)).contains(job.getId());
        assertThat(repository.claim(job.getId(), "new-worker", now, now.plusSeconds(60)))
                .isEqualTo(1);
        assertThat(repository.findById(job.getId()).orElseThrow().getAttempts()).isEqualTo(2);
    }

    @Test
    void exhaustedExpiredJobIsFailedInsteadOfRemainingRunning() {
        var job = job(UUID.randomUUID());
        ReflectionTestUtils.setField(job, "status", "RUNNING");
        ReflectionTestUtils.setField(job, "attempts", 3);
        ReflectionTestUtils.setField(job, "leaseUntil", Instant.now().minusSeconds(30));
        job = repository.saveAndFlush(job);

        assertThat(repository.failExhausted(Instant.now())).isEqualTo(1);
        var failed = repository.findById(job.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo("FAILED");
        assertThat(failed.getErrorCode()).isEqualTo("ATTEMPTS_EXHAUSTED");
    }

    private static RecipeExtractionJobEntity job(UUID idempotencyKey) {
        return new RecipeExtractionJobEntity(
                RecipeExtractionJobEntity.SourceType.TEXT,
                "番茄 鸡蛋",
                null,
                "deterministic-model",
                idempotencyKey);
    }

    private static void recreateSchema() {
        try (var connection =
                        DriverManager.getConnection(
                                TestDatabase.URL, TestDatabase.USERNAME, TestDatabase.PASSWORD);
                var statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + SCHEMA + " cascade");
            statement.execute("create schema " + SCHEMA);
            statement.execute("grant all on schema " + SCHEMA + " to " + TestDatabase.USERNAME);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
