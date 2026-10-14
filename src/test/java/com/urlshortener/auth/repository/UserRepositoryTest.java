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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
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

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    private static String uniqueSlug() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
