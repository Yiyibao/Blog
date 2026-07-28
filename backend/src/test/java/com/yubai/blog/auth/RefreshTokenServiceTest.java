package com.yubai.blog.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    private static final class MutableClock extends Clock {
        private final AtomicLong millis = new AtomicLong(0);

        void advance(Duration duration) {
            millis.addAndGet(duration.toMillis());
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }
    }

    @Mock
    private RefreshTokenRepository repository;

    @Mock
    private AdminUserRepository userRepository;

    private MutableClock clock;
    private RefreshTokenService service;
    private static final java.util.function.Function<String, String> HASH = RefreshTokenService::hashToken;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        service = new RefreshTokenService(repository, userRepository, Duration.ofDays(7), Duration.ofDays(30), clock);
    }

    @Test
    void issueCreatesTokenWithHashAndReturnsRaw() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var issued = service.issue(1L, false);

        assertThat(issued.raw()).hasSize(64);
        assertThat(issued.entity().getTokenHash()).isNotEqualTo(issued.raw());
        assertThat(issued.entity().getTokenHash()).hasSize(64);
        assertThat(issued.entity().getUserId()).isEqualTo(1L);
        assertThat(issued.entity().getFamily()).isNotNull();
        assertThat(issued.entity().isRevoked()).isFalse();
        assertThat(issued.entity().getLastUsedAt()).isNull();
        assertThat(issued.entity().getExpiresAt()).isAfter(Instant.now(clock));

        var captor = ArgumentCaptor.forClass(RefreshTokenEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getTokenHash())
            .isEqualTo(RefreshTokenService.hashToken(issued.raw()));
    }

    @Test
    void issueRespectsRememberTtl() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var normal = service.issue(1L, false);
        var remembered = service.issue(1L, true);

        assertThat(normal.entity().getExpiresAt())
            .isBefore(remembered.entity().getExpiresAt());
    }

    @Test
    void rotateReturnsNewTokenAndRevokesOld() {
        var oldHash = RefreshTokenService.hashToken("old-raw-token-abc123");
        var oldExpiry = Instant.now(clock).plus(Duration.ofDays(7));
        var oldEntity = new RefreshTokenEntity(oldHash, 1L, UUID.randomUUID(), oldExpiry);
        var user = mock(AdminUserEntity.class);

        when(repository.findByTokenHash(oldHash)).thenReturn(Optional.of(oldEntity));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(user.isEnabled()).thenReturn(true);
        when(user.getSessionsValidFrom()).thenReturn(Instant.EPOCH);
        when(repository.atomicRevokeAndMarkUsed(eq(oldEntity.getId()), any())).thenAnswer(invocation -> {
            oldEntity.setRevoked(true);
            oldEntity.setLastUsedAt(invocation.getArgument(1));
            return 1;
        });
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        clock.advance(Duration.ofDays(3));
        var result = service.rotate("old-raw-token-abc123");

        assertThat(result.rawToken()).hasSize(64);
        assertThat(result.oldCreatedAt()).isNotNull();
        assertThat(result.newEntity().getFamily()).isEqualTo(oldEntity.getFamily());
        assertThat(result.newEntity().getUserId()).isEqualTo(1L);
        assertThat(result.newEntity().isRevoked()).isFalse();
        assertThat(result.newEntity().getExpiresAt()).isEqualTo(oldExpiry);
        assertThat(oldEntity.isRevoked()).isTrue();
        assertThat(oldEntity.getLastUsedAt()).isNotNull();
    }

    @Test
    void rotateRejectsUnknownToken() {
        when(repository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rotate("no-such-token"))
            .isInstanceOf(RefreshTokenService.RefreshTokenException.class)
            .hasMessageContaining("not found");
    }

    @Test
    void rotateRejectsRevokedTokenAndRevokesFamily() {
        var raw = "hash";
        var hash = HASH.apply(raw);
        var family = UUID.randomUUID();
        var entity = new RefreshTokenEntity(hash, 1L, family,
            Instant.now(clock).plus(Duration.ofDays(7)));
        entity.setRevoked(true);
        var user = mock(AdminUserEntity.class);

        when(repository.findByTokenHash(hash)).thenReturn(Optional.of(entity));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(user.isEnabled()).thenReturn(true);
        when(user.getSessionsValidFrom()).thenReturn(Instant.EPOCH);

        assertThatThrownBy(() -> service.rotate(raw))
            .isInstanceOf(RefreshTokenService.RefreshTokenException.class)
            .hasMessageContaining("already used");

        verify(repository).revokeFamily(family);
    }

    @Test
    void rotateRejectsExpiredToken() {
        var raw = "hash";
        var hash = HASH.apply(raw);
        var entity = new RefreshTokenEntity(hash, 1L, UUID.randomUUID(),
            Instant.now(clock).minus(Duration.ofSeconds(1)));

        when(repository.findByTokenHash(hash)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.rotate(raw))
            .isInstanceOf(RefreshTokenService.RefreshTokenException.class)
            .hasMessageContaining("expired");
    }

    @Test
    void rotateDetectsReplayAndRevokesFamily() {
        var raw = "hash";
        var hash = HASH.apply(raw);
        var family = UUID.randomUUID();
        var entity = new RefreshTokenEntity(hash, 1L, family,
            Instant.now(clock).plus(Duration.ofDays(7)));
        var user = mock(AdminUserEntity.class);
        // atomicRevokeAndMarkUsed returns 0 => already used
        when(repository.findByTokenHash(hash)).thenReturn(Optional.of(entity));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(user.isEnabled()).thenReturn(true);
        when(user.getSessionsValidFrom()).thenReturn(Instant.EPOCH);
        when(repository.atomicRevokeAndMarkUsed(eq(entity.getId()), any())).thenReturn(0);

        assertThatThrownBy(() -> service.rotate(raw))
            .isInstanceOf(RefreshTokenService.RefreshTokenException.class)
            .hasMessageContaining("already used");

        verify(repository).revokeFamily(family);
    }

    @Test
    void rotateRejectsDisabledUser() {
        var raw = "hash";
        var hash = HASH.apply(raw);
        var entity = new RefreshTokenEntity(hash, 1L, UUID.randomUUID(),
            Instant.now(clock).plus(Duration.ofDays(7)));
        var user = mock(AdminUserEntity.class);

        when(repository.findByTokenHash(hash)).thenReturn(Optional.of(entity));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(user.isEnabled()).thenReturn(false);

        assertThatThrownBy(() -> service.rotate(raw))
            .isInstanceOf(RefreshTokenService.RefreshTokenException.class)
            .hasMessageContaining("禁用");

        verify(repository, never()).atomicRevokeAndMarkUsed(anyLong(), any());
        verify(repository, never()).save(any());
    }

    @Test
    void rotateRejectsSessionInvalidated() {
        var raw = "hash";
        var hash = HASH.apply(raw);
        // entity constructor sets createdAt to Instant.now() (system clock, not mock clock)
        var entity = new RefreshTokenEntity(hash, 1L, UUID.randomUUID(),
            Instant.now(clock).plus(Duration.ofDays(7)));
        var user = mock(AdminUserEntity.class);

        when(repository.findByTokenHash(hash)).thenReturn(Optional.of(entity));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(user.isEnabled()).thenReturn(true);
        // sessionsValidFrom after entity's real createdAt
        when(user.getSessionsValidFrom()).thenReturn(Instant.now().plus(Duration.ofDays(1)));

        assertThatThrownBy(() -> service.rotate(raw))
            .isInstanceOf(RefreshTokenService.RefreshTokenException.class)
            .hasMessageContaining("invalidated");

        verify(repository, never()).atomicRevokeAndMarkUsed(anyLong(), any());
        verify(repository, never()).save(any());
    }

    @Test
    void revokeMarksTokenRevoked() {
        var hash = RefreshTokenService.hashToken("raw-token");
        var entity = new RefreshTokenEntity(hash, 1L, UUID.randomUUID(),
            Instant.now(clock).plus(Duration.ofDays(7)));

        when(repository.findByTokenHash(hash)).thenReturn(Optional.of(entity));

        service.revoke("raw-token");

        assertThat(entity.isRevoked()).isTrue();
        verify(repository).save(entity);
    }

    @Test
    void revokeDoesNothingForUnknownToken() {
        when(repository.findByTokenHash(any())).thenReturn(Optional.empty());

        service.revoke("unknown-token");

        verify(repository, never()).save(any());
    }

    @Test
    void tokenGenerationIsRandom() {
        var t1 = RefreshTokenService.generateRawToken();
        var t2 = RefreshTokenService.generateRawToken();
        assertThat(t1).isNotEqualTo(t2);
    }

    @Test
    void hashIsDeterministic() {
        var hash1 = RefreshTokenService.hashToken("hello");
        var hash2 = RefreshTokenService.hashToken("hello");
        assertThat(hash1).isEqualTo(hash2);
        assertThat(RefreshTokenService.hashToken("world")).isNotEqualTo(hash1);
    }
}
