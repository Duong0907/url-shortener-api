package com.urlshortener.auth.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RefreshTokenTest {

    static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private static RefreshToken tokenExpiringAt(Instant expiresAt) {
        return new RefreshToken(UUID.randomUUID(), "hash", expiresAt);
    }

    @Test
    void isUsableBeforeExpiry() {
        assertThat(tokenExpiringAt(NOW.plusSeconds(1)).isUsable(NOW)).isTrue();
    }

    @Test
    void isNotUsableExactlyAtExpiry() {
        // isAfter is strict: the token dies at the expiry instant, not one tick later.
        assertThat(tokenExpiringAt(NOW).isUsable(NOW)).isFalse();
    }

    @Test
    void isNotUsableAfterExpiry() {
        assertThat(tokenExpiringAt(NOW.minusSeconds(1)).isUsable(NOW)).isFalse();
    }

    @Test
    void isNotUsableOnceRevokedEvenBeforeExpiry() {
        RefreshToken token = tokenExpiringAt(NOW.plusSeconds(3600));

        token.revoke(NOW);

        assertThat(token.getRevokedAt()).isEqualTo(NOW);
        assertThat(token.isUsable(NOW)).isFalse();
    }

    @Test
    void newTokenIsNotRevoked() {
        assertThat(tokenExpiringAt(NOW).getRevokedAt()).isNull();
    }

    @Test
    void createdAtIsSetOnConstruction() {
        assertThat(tokenExpiringAt(NOW).getCreatedAt()).isNotNull();
    }
}
