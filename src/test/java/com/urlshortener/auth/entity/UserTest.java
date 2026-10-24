package com.urlshortener.auth.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class UserTest {

    static final Instant NOW = Instant.parse("2026-10-15T10:00:00Z");

    @Test
    void newUserIsUnverifiedWithoutAToken() {
        User user = new User("alice@example.com", "hash");

        assertThat(user.isEmailVerified()).isFalse();
        assertThat(user.getVerificationTokenHash()).isNull();
        assertThat(user.hasUsableVerification(NOW)).isFalse();
    }

    @Test
    void tokenIsUsableBeforeItExpires() {
        User user = new User("alice@example.com", "hash");
        user.startVerification("h", NOW.plus(Duration.ofHours(24)));

        assertThat(user.hasUsableVerification(NOW)).isTrue();
        assertThat(user.hasUsableVerification(NOW.plus(Duration.ofHours(23)))).isTrue();
    }

    @Test
    void tokenIsNotUsableAtOrAfterExpiry() {
        User user = new User("alice@example.com", "hash");
        Instant expiresAt = NOW.plus(Duration.ofHours(24));
        user.startVerification("h", expiresAt);

        assertThat(user.hasUsableVerification(expiresAt)).isFalse();
        assertThat(user.hasUsableVerification(expiresAt.plusSeconds(1))).isFalse();
    }

    @Test
    void startVerificationReplacesThePreviousToken() {
        User user = new User("alice@example.com", "hash");
        user.startVerification("first", NOW.plusSeconds(60));
        user.startVerification("second", NOW.plusSeconds(120));

        assertThat(user.getVerificationTokenHash()).isEqualTo("second");
        assertThat(user.getVerificationExpiresAt()).isEqualTo(NOW.plusSeconds(120));
    }

    @Test
    void verifyEmailMarksVerifiedAndClearsTheToken() {
        User user = new User("alice@example.com", "hash");
        user.startVerification("h", NOW.plusSeconds(60));

        user.verifyEmail();

        assertThat(user.isEmailVerified()).isTrue();
        assertThat(user.getVerificationTokenHash()).isNull();
        assertThat(user.getVerificationExpiresAt()).isNull();
        assertThat(user.hasUsableVerification(NOW)).isFalse();
    }
}
