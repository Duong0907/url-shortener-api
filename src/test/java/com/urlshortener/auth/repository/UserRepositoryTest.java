package com.urlshortener.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.urlshortener.TestcontainersConfiguration;
import com.urlshortener.auth.entity.RefreshToken;
import com.urlshortener.auth.entity.User;
import com.urlshortener.link.entity.Link;
import com.urlshortener.link.repository.LinkRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// Checks the schema rules from V2 against a real Postgres. Each test rolls back.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class UserRepositoryTest {

    @Autowired
    UserRepository users;

    @Autowired
    RefreshTokenRepository refreshTokens;

    @Autowired
    LinkRepository links;

    @Autowired
    EntityManager entityManager;

    @Test
    void savesUserWithGeneratedIdAndCreationTime() {
        User saved = users.saveAndFlush(new User(uniqueEmail(), "hash"));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void findsUserByExactEmail() {
        String email = uniqueEmail();
        users.saveAndFlush(new User(email, "hash"));

        assertThat(users.findByEmail(email)).isPresent();
        assertThat(users.findByEmail("other-" + email)).isEmpty();
    }

    @Test
    void duplicateEmailViolatesTheNamedUniqueConstraint() {
        // AuthService.register matches on this constraint name. If it changes, register breaks.
        String email = uniqueEmail();
        users.saveAndFlush(new User(email, "hash-1"));

        assertThatThrownBy(() -> users.saveAndFlush(new User(email, "hash-2")))
            .isInstanceOf(DataIntegrityViolationException.class)
            .rootCause()
            .hasMessageContaining("uk_users_email");
    }

    @Test
    void emailLongerThan254CharsIsRejectedByTheDatabase() {
        String tooLong = "a".repeat(255);

        assertThatThrownBy(() -> users.saveAndFlush(new User(tooLong, "hash"))).isInstanceOf(
            DataIntegrityViolationException.class
        );
    }

    @Test
    void deletingAUserDeletesTheirRefreshTokens() {
        User user = users.saveAndFlush(new User(uniqueEmail(), "hash"));
        RefreshToken token = refreshTokens.saveAndFlush(
            new RefreshToken(
                user.getId(),
                "hash-" + UUID.randomUUID(),
                Instant.now().plusSeconds(60)
            )
        );

        users.delete(user);
        users.flush();
        entityManager.clear();

        assertThat(refreshTokens.existsById(token.getId())).isFalse();
    }

    @Test
    void deletingAUserDeletesTheirLinks() {
        User user = users.saveAndFlush(new User(uniqueEmail(), "hash"));
        Link link = links.saveAndFlush(
            new Link(uniqueSlug(), "https://example.com", user.getId(), false, null, null)
        );

        users.delete(user);
        users.flush();
        entityManager.clear();

        assertThat(links.existsById(link.getId())).isFalse();
    }

    @Test
    void deletingAUserKeepsOtherUsersLinksAndAnonymousLinks() {
        User owner = users.saveAndFlush(new User(uniqueEmail(), "hash"));
        User other = users.saveAndFlush(new User(uniqueEmail(), "hash"));
        Link othersLink = links.saveAndFlush(
            new Link(uniqueSlug(), "https://example.com", other.getId(), false, null, null)
        );
        Link anonymousLink = links.saveAndFlush(
            new Link(uniqueSlug(), "https://example.com", null, false, null, null)
        );

        users.delete(owner);
        users.flush();
        entityManager.clear();

        assertThat(links.existsById(othersLink.getId())).isTrue();
        assertThat(links.existsById(anonymousLink.getId())).isTrue();
    }

    @Test
    void linkWithAnUnknownOwnerIsRejected() {
        Link orphan = new Link(
            uniqueSlug(),
            "https://example.com",
            UUID.randomUUID(),
            false,
            null,
            null
        );

        assertThatThrownBy(() -> links.saveAndFlush(orphan)).isInstanceOf(
            DataIntegrityViolationException.class
        );
    }

    @Test
    void newUserIsUnverified() {
        User saved = users.saveAndFlush(new User(uniqueEmail(), "hash"));
        entityManager.clear();

        User reloaded = users.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.isEmailVerified()).isFalse();
        assertThat(reloaded.getVerificationTokenHash()).isNull();
    }

    @Test
    void findsUserByVerificationTokenHash() {
        User user = new User(uniqueEmail(), "hash");
        String tokenHash = uniqueHash();
        user.startVerification(tokenHash, Instant.now().plusSeconds(60));
        users.saveAndFlush(user);

        assertThat(users.findByVerificationTokenHash(tokenHash)).isPresent();
        assertThat(users.findByVerificationTokenHash(uniqueHash())).isEmpty();
    }

    @Test
    void verificationTokenHashIsUnique() {
        String tokenHash = uniqueHash();
        User first = new User(uniqueEmail(), "hash");
        first.startVerification(tokenHash, Instant.now().plusSeconds(60));
        users.saveAndFlush(first);

        User second = new User(uniqueEmail(), "hash");
        second.startVerification(tokenHash, Instant.now().plusSeconds(60));

        assertThatThrownBy(() -> users.saveAndFlush(second))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("uk_users_verification_token_hash");
    }

    @Test
    void manyUsersWithoutATokenAreAllowed() {
        // NULL never collides in a unique index, so verified users do not block each other.
        users.saveAndFlush(new User(uniqueEmail(), "hash"));
        users.saveAndFlush(new User(uniqueEmail(), "hash"));
        users.saveAndFlush(new User(uniqueEmail(), "hash"));

        assertThat(users.count()).isGreaterThanOrEqualTo(3);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void lookupByVerificationHashNeedsATransactionBecauseOfTheRowLock() {
        assertThatThrownBy(() -> users.findByVerificationTokenHash(uniqueHash())).isInstanceOf(
            InvalidDataAccessApiUsageException.class
        );
    }

    @Test
    void replaceVerificationTokenUpdatesAnUnverifiedUser() {
        String email = uniqueEmail();
        users.saveAndFlush(new User(email, "hash"));
        Instant expiresAt = Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.MILLIS);

        int updated = users.replaceVerificationToken(email, "new-hash", expiresAt);
        entityManager.clear();

        assertThat(updated).isEqualTo(1);
        User reloaded = users.findByEmail(email).orElseThrow();
        assertThat(reloaded.getVerificationTokenHash()).isEqualTo("new-hash");
        assertThat(reloaded.getVerificationExpiresAt()).isEqualTo(expiresAt);
    }

    @Test
    void replaceVerificationTokenLeavesAVerifiedUserUntouched() {
        String email = uniqueEmail();
        User user = new User(email, "hash");
        user.verifyEmail();
        users.saveAndFlush(user);

        int updated = users.replaceVerificationToken(
            email,
            "new-hash",
            Instant.now().plusSeconds(60)
        );
        entityManager.clear();

        assertThat(updated).isZero();
        assertThat(users.findByEmail(email).orElseThrow().getVerificationTokenHash()).isNull();
    }

    @Test
    void replaceVerificationTokenForAnUnknownEmailChangesNothing() {
        assertThat(
            users.replaceVerificationToken("nobody@example.com", "h", Instant.now().plusSeconds(60))
        ).isZero();
    }

    private static String uniqueHash() {
        return (
            UUID.randomUUID().toString().replace("-", "") +
            UUID.randomUUID().toString().replace("-", "")
        );
    }

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    private static String uniqueSlug() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
