package com.urlshortener.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.urlshortener.TestcontainersConfiguration;
import com.urlshortener.auth.entity.RefreshToken;
import com.urlshortener.auth.entity.User;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class RefreshTokenRepositoryTest {

    @Autowired
    UserRepository users;

    @Autowired
    RefreshTokenRepository refreshTokens;

    @Autowired
    EntityManager entityManager;

    @Test
    void findsATokenByItsHash() {
        UUID userId = savedUserId();
        String hash = uniqueHash();
        refreshTokens.saveAndFlush(new RefreshToken(userId, hash, Instant.now().plusSeconds(60)));

        RefreshToken found = refreshTokens.findByTokenHash(hash).orElseThrow();

        assertThat(found.getUserId()).isEqualTo(userId);
        assertThat(found.getRevokedAt()).isNull();
    }

    @Test
    void returnsEmptyForAnUnknownHash() {
        assertThat(refreshTokens.findByTokenHash(uniqueHash())).isEmpty();
    }

    @Test
    void revocationIsPersisted() {
        String hash = uniqueHash();
        refreshTokens.saveAndFlush(
            new RefreshToken(savedUserId(), hash, Instant.now().plusSeconds(60))
        );
        Instant revokedAt = Instant.now();

        refreshTokens.findByTokenHash(hash).orElseThrow().revoke(revokedAt);
        entityManager.flush();
        entityManager.clear();

        RefreshToken reloaded = refreshTokens.findByTokenHash(hash).orElseThrow();
        assertThat(reloaded.getRevokedAt()).isNotNull();
        assertThat(reloaded.isUsable(Instant.now())).isFalse();
    }

    @Test
    void duplicateHashViolatesTheUniqueConstraint() {
        UUID userId = savedUserId();
        String hash = uniqueHash();
        refreshTokens.saveAndFlush(new RefreshToken(userId, hash, Instant.now().plusSeconds(60)));

        assertThatThrownBy(() ->
            refreshTokens.saveAndFlush(
                new RefreshToken(userId, hash, Instant.now().plusSeconds(60))
            )
        )
            .isInstanceOf(DataIntegrityViolationException.class)
            .rootCause()
            .hasMessageContaining("uk_refresh_tokens_hash");
    }

    @Test
    void oneUserCanHoldManyTokensForManyDevices() {
        UUID userId = savedUserId();

        refreshTokens.saveAndFlush(
            new RefreshToken(userId, uniqueHash(), Instant.now().plusSeconds(60))
        );
        refreshTokens.saveAndFlush(
            new RefreshToken(userId, uniqueHash(), Instant.now().plusSeconds(60))
        );

        assertThat(
            refreshTokens
                .findAll()
                .stream()
                .filter(t -> t.getUserId().equals(userId))
        ).hasSize(2);
    }

    @Test
    void tokenForAnUnknownUserIsRejected() {
        RefreshToken orphan = new RefreshToken(
            UUID.randomUUID(),
            uniqueHash(),
            Instant.now().plusSeconds(60)
        );

        assertThatThrownBy(() -> refreshTokens.saveAndFlush(orphan)).isInstanceOf(
            DataIntegrityViolationException.class
        );
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void lookupByHashNeedsATransactionBecauseOfTheRowLock() {
        // PESSIMISTIC_WRITE only works inside a transaction. AuthService methods are @Transactional.
        assertThatThrownBy(() -> refreshTokens.findByTokenHash(uniqueHash())).isInstanceOf(
            InvalidDataAccessApiUsageException.class
        );
    }

    private UUID savedUserId() {
        return users
            .saveAndFlush(new User("user-" + UUID.randomUUID() + "@example.com", "hash"))
            .getId();
    }

    private static String uniqueHash() {
        return (
            UUID.randomUUID().toString().replace("-", "") +
            UUID.randomUUID().toString().replace("-", "")
        );
    }
}
