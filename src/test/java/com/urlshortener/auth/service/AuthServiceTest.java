package com.urlshortener.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.urlshortener.auth.entity.RefreshToken;
import com.urlshortener.auth.entity.User;
import com.urlshortener.auth.repository.RefreshTokenRepository;
import com.urlshortener.auth.repository.UserRepository;
import com.urlshortener.auth.service.AuthService.Tokens;
import com.urlshortener.error.ApiException;
import com.urlshortener.error.ErrorType;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.util.ReflectionTestUtils;

// Pure unit tests: repositories and the password encoder are mocks, JWT signing is real.
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    static final Duration ACCESS_TTL = Duration.ofMinutes(15);
    static final Duration REFRESH_TTL = Duration.ofDays(30);
    static final Duration VERIFICATION_TTL = Duration.ofHours(24);
    static final SecretKey KEY = new SecretKeySpec(
        "test-only-secret-that-is-at-least-32-bytes".getBytes(StandardCharsets.UTF_8),
        "HmacSHA256"
    );

    @Mock
    UserRepository users;

    @Mock
    RefreshTokenRepository refreshTokens;

    @Mock
    PasswordEncoder passwords;

    @Mock
    VerificationMailer mailer;

    JwtDecoder decoder = NimbusJwtDecoder.withSecretKey(KEY)
        .macAlgorithm(MacAlgorithm.HS256)
        .build();

    AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService(
            users,
            refreshTokens,
            passwords,
            new NimbusJwtEncoder(new ImmutableSecret<>(KEY)),
            mailer,
            ACCESS_TTL,
            REFRESH_TTL,
            VERIFICATION_TTL
        );
    }

    // ---------- register ----------

    @Test
    void registerStoresTrimmedLowercaseEmailAndEncodedPassword() {
        when(passwords.encode("correct-horse")).thenReturn("encoded");
        when(users.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));

        User user = service.register("  Alice@Example.COM ", "correct-horse");

        assertThat(user.getEmail()).isEqualTo("alice@example.com");
        assertThat(user.getPasswordHash()).isEqualTo("encoded");
    }

    @Test
    void registerMapsEmailConstraintViolationToEmailTaken() {
        when(passwords.encode(any())).thenReturn("encoded");
        when(users.saveAndFlush(any(User.class))).thenThrow(
            new DataIntegrityViolationException(
                "could not execute statement",
                new SQLException(
                    "ERROR: duplicate key value violates unique constraint \"uk_users_email\""
                )
            )
        );

        ApiException error = thrownBy(() -> service.register("a@example.com", "password1"));

        assertThat(error.getErrorType()).isEqualTo(ErrorType.EMAIL_TAKEN);
    }

    @Test
    void registerRethrowsOtherIntegrityViolations() {
        DataIntegrityViolationException other = new DataIntegrityViolationException(
            "could not execute statement",
            new SQLException("ERROR: null value in column \"password_hash\"")
        );
        when(passwords.encode(any())).thenReturn("encoded");
        when(users.saveAndFlush(any(User.class))).thenThrow(other);

        assertThatThrownBy(() -> service.register("a@example.com", "password1")).isSameAs(other);
    }

    @Test
    void registerRethrowsIntegrityViolationWithoutMessage() {
        DataIntegrityViolationException silent = new DataIntegrityViolationException(null);
        when(passwords.encode(any())).thenReturn("encoded");
        when(users.saveAndFlush(any(User.class))).thenThrow(silent);

        assertThatThrownBy(() -> service.register("a@example.com", "password1")).isSameAs(silent);
    }

    // ---------- login ----------

    @Test
    void loginIssuesAccessTokenSignedWithUserClaims() throws Exception {
        User user = userWithId("alice@example.com", "hash");
        when(users.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(passwords.matches("correct-horse", "hash")).thenReturn(true);

        Tokens tokens = service.login("  ALICE@example.com ", "correct-horse");

        Jwt jwt = decoder.decode(tokens.accessToken());
        assertThat(jwt.getSubject()).isEqualTo(user.getId().toString());
        assertThat(jwt.getClaimAsString("email")).isEqualTo("alice@example.com");
        assertThat(jwt.getHeaders().get("alg")).isEqualTo("HS256");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(ACCESS_TTL);
        assertThat(tokens.expiresInSeconds()).isEqualTo(900);
    }

    @Test
    void loginStoresOnlyTheHashOfTheRefreshToken() {
        User user = userWithId("alice@example.com", "hash");
        when(users.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(passwords.matches("correct-horse", "hash")).thenReturn(true);
        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);

        Instant before = Instant.now();
        Tokens tokens = service.login("alice@example.com", "correct-horse");
        Instant after = Instant.now();

        verify(refreshTokens).save(saved.capture());
        RefreshToken stored = saved.getValue();
        assertThat(stored.getTokenHash())
            .isEqualTo(AuthService.hash(tokens.refreshToken()))
            .isNotEqualTo(tokens.refreshToken());
        assertThat(stored.getUserId()).isEqualTo(user.getId());
        assertThat(stored.getRevokedAt()).isNull();
        assertThat(stored.getExpiresAt()).isBetween(
            before.plus(REFRESH_TTL),
            after.plus(REFRESH_TTL)
        );
    }

    @Test
    void refreshTokenIs32RandomBytesInUrlSafeBase64() {
        User user = userWithId("alice@example.com", "hash");
        when(users.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(passwords.matches("correct-horse", "hash")).thenReturn(true);

        Tokens tokens = service.login("alice@example.com", "correct-horse");

        // 32 bytes -> 43 base64url characters without padding.
        assertThat(tokens.refreshToken()).matches("[A-Za-z0-9_-]{43}");
    }

    @Test
    void everyLoginGetsADifferentRefreshToken() {
        User user = userWithId("alice@example.com", "hash");
        when(users.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(passwords.matches("correct-horse", "hash")).thenReturn(true);

        Tokens first = service.login("alice@example.com", "correct-horse");
        Tokens second = service.login("alice@example.com", "correct-horse");

        assertThat(first.refreshToken()).isNotEqualTo(second.refreshToken());
    }

    @Test
    void loginRejectsUnknownEmailAndIssuesNothing() {
        when(users.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        ApiException error = thrownBy(() -> service.login("nobody@example.com", "whatever1"));

        assertThat(error.getErrorType()).isEqualTo(ErrorType.INVALID_CREDENTIALS);
        verify(refreshTokens, never()).save(any());
    }

    @Test
    void loginRejectsWrongPasswordAndIssuesNothing() {
        User user = userWithId("alice@example.com", "hash");
        when(users.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(passwords.matches("wrong-horse", "hash")).thenReturn(false);

        ApiException error = thrownBy(() -> service.login("alice@example.com", "wrong-horse"));

        assertThat(error.getErrorType()).isEqualTo(ErrorType.INVALID_CREDENTIALS);
        verify(refreshTokens, never()).save(any());
    }

    @Test
    void unknownEmailAndWrongPasswordGiveTheSameError() {
        User user = userWithId("alice@example.com", "hash");
        when(users.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(users.findByEmail("nobody@example.com")).thenReturn(Optional.empty());
        when(passwords.matches("wrong-horse", "hash")).thenReturn(false);

        ApiException wrongPassword = thrownBy(() ->
            service.login("alice@example.com", "wrong-horse")
        );
        ApiException unknownEmail = thrownBy(() ->
            service.login("nobody@example.com", "wrong-horse")
        );

        // Same type and same message, so the response does not reveal which emails exist.
        assertThat(unknownEmail.getErrorType()).isEqualTo(wrongPassword.getErrorType());
        assertThat(unknownEmail.getMessage()).isEqualTo(wrongPassword.getMessage());
    }

    // ---------- refresh ----------

    @Test
    void refreshRevokesTheUsedTokenAndIssuesANewPair() {
        User user = userWithId("alice@example.com", "hash");
        RefreshToken stored = liveToken(user, "old-token");
        when(refreshTokens.findByTokenHash(AuthService.hash("old-token"))).thenReturn(
            Optional.of(stored)
        );
        when(users.findById(user.getId())).thenReturn(Optional.of(user));

        Tokens tokens = service.refresh("old-token");

        assertThat(stored.getRevokedAt()).isNotNull();
        assertThat(tokens.refreshToken()).isNotEqualTo("old-token");
        verify(refreshTokens).save(any(RefreshToken.class));
    }

    @Test
    void refreshRejectsUnknownToken() {
        when(refreshTokens.findByTokenHash(AuthService.hash("nope"))).thenReturn(Optional.empty());

        ApiException error = thrownBy(() -> service.refresh("nope"));

        assertThat(error.getErrorType()).isEqualTo(ErrorType.REFRESH_TOKEN_INVALID);
        verify(refreshTokens, never()).save(any());
    }

    @Test
    void refreshRejectsRevokedToken() {
        User user = userWithId("alice@example.com", "hash");
        RefreshToken stored = liveToken(user, "used-token");
        stored.revoke(Instant.now().minusSeconds(5));
        when(refreshTokens.findByTokenHash(AuthService.hash("used-token"))).thenReturn(
            Optional.of(stored)
        );

        ApiException error = thrownBy(() -> service.refresh("used-token"));

        assertThat(error.getErrorType()).isEqualTo(ErrorType.REFRESH_TOKEN_INVALID);
        verify(refreshTokens, never()).save(any());
    }

    @Test
    void refreshRejectsExpiredTokenAndLeavesItUntouched() {
        User user = userWithId("alice@example.com", "hash");
        RefreshToken stored = new RefreshToken(
            user.getId(),
            AuthService.hash("old-token"),
            Instant.now().minusSeconds(1)
        );
        when(refreshTokens.findByTokenHash(AuthService.hash("old-token"))).thenReturn(
            Optional.of(stored)
        );

        ApiException error = thrownBy(() -> service.refresh("old-token"));

        assertThat(error.getErrorType()).isEqualTo(ErrorType.REFRESH_TOKEN_INVALID);
        assertThat(stored.getRevokedAt()).isNull();
        verify(refreshTokens, never()).save(any());
    }

    @Test
    void refreshRejectsTokenWhoseUserNoLongerExists() {
        User user = userWithId("alice@example.com", "hash");
        RefreshToken stored = liveToken(user, "orphan-token");
        when(refreshTokens.findByTokenHash(AuthService.hash("orphan-token"))).thenReturn(
            Optional.of(stored)
        );
        when(users.findById(user.getId())).thenReturn(Optional.empty());

        ApiException error = thrownBy(() -> service.refresh("orphan-token"));

        assertThat(error.getErrorType()).isEqualTo(ErrorType.REFRESH_TOKEN_INVALID);
        verify(refreshTokens, never()).save(any());
    }

    // ---------- logout ----------

    @Test
    void logoutRevokesALiveToken() {
        User user = userWithId("alice@example.com", "hash");
        RefreshToken stored = liveToken(user, "live-token");
        when(refreshTokens.findByTokenHash(AuthService.hash("live-token"))).thenReturn(
            Optional.of(stored)
        );

        service.logout("live-token");

        assertThat(stored.getRevokedAt()).isNotNull();
    }

    @Test
    void logoutWithUnknownTokenDoesNothingAndDoesNotFail() {
        when(refreshTokens.findByTokenHash(AuthService.hash("nope"))).thenReturn(Optional.empty());

        service.logout("nope");

        verify(refreshTokens, never()).save(any());
    }

    @Test
    void logoutKeepsTheOriginalRevocationTime() {
        User user = userWithId("alice@example.com", "hash");
        RefreshToken stored = liveToken(user, "used-token");
        Instant firstRevocation = Instant.now().minusSeconds(60);
        stored.revoke(firstRevocation);
        when(refreshTokens.findByTokenHash(AuthService.hash("used-token"))).thenReturn(
            Optional.of(stored)
        );

        service.logout("used-token");

        assertThat(stored.getRevokedAt()).isEqualTo(firstRevocation);
    }

    @Test
    void logoutLeavesExpiredTokenUntouched() {
        User user = userWithId("alice@example.com", "hash");
        RefreshToken stored = new RefreshToken(
            user.getId(),
            AuthService.hash("old-token"),
            Instant.now().minusSeconds(1)
        );
        when(refreshTokens.findByTokenHash(AuthService.hash("old-token"))).thenReturn(
            Optional.of(stored)
        );

        service.logout("old-token");

        assertThat(stored.getRevokedAt()).isNull();
    }

    // ---------- hash and ttl ----------

    @Test
    void hashIsSha256HexOfTheUtf8Bytes() {
        // Well-known SHA-256 test vector.
        assertThat(AuthService.hash("abc")).isEqualTo(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
    }

    @Test
    void hashIs64LowercaseHexCharsAndDeterministic() {
        assertThat(AuthService.hash("some-token")).matches("[0-9a-f]{64}");
        assertThat(AuthService.hash("some-token")).isEqualTo(AuthService.hash("some-token"));
        assertThat(AuthService.hash("some-token")).isNotEqualTo(AuthService.hash("some-tokeN"));
    }

    @Test
    void exposesTheConfiguredRefreshTtl() {
        assertThat(service.refreshTtl()).isEqualTo(REFRESH_TTL);
    }

    // ---------- register: verification ----------

    @Test
    void registerStoresOnlyTheHashOfTheVerificationToken() {
        when(passwords.encode(any())).thenReturn("encoded");
        when(users.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));

        User user = service.register("alice@example.com", "correct-horse");

        String rawToken = sentToken("alice@example.com");
        assertThat(rawToken).isNotBlank();
        assertThat(user.getVerificationTokenHash()).isEqualTo(AuthService.hash(rawToken));
        assertThat(user.getVerificationTokenHash()).isNotEqualTo(rawToken);
        assertThat(user.isEmailVerified()).isFalse();
    }

    @Test
    void registerGivesTheTokenTheConfiguredLifetime() {
        when(passwords.encode(any())).thenReturn("encoded");
        when(users.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));
        Instant before = Instant.now();

        User user = service.register("alice@example.com", "correct-horse");

        assertThat(user.getVerificationExpiresAt()).isBetween(
            before.plus(VERIFICATION_TTL),
            Instant.now().plus(VERIFICATION_TTL)
        );
    }

    @Test
    void registerMailsTheNormalizedAddress() {
        when(passwords.encode(any())).thenReturn("encoded");
        when(users.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));

        service.register("  Alice@Example.COM ", "correct-horse");

        verify(mailer).sendVerification(eq("alice@example.com"), any());
    }

    @Test
    void registerSendsNothingWhenTheEmailIsTaken() {
        when(passwords.encode(any())).thenReturn("encoded");
        when(users.saveAndFlush(any(User.class))).thenThrow(
            new DataIntegrityViolationException(
                "could not execute statement",
                new SQLException(
                    "ERROR: duplicate key value violates unique constraint \"uk_users_email\""
                )
            )
        );

        thrownBy(() -> service.register("a@example.com", "password1"));

        verify(mailer, never()).sendVerification(any(), any());
    }

    @Test
    void registerSendsNothingWhenSavingFailsForAnotherReason() {
        when(passwords.encode(any())).thenReturn("encoded");
        when(users.saveAndFlush(any(User.class))).thenThrow(
            new DataIntegrityViolationException("other", new SQLException("something else"))
        );

        assertThatThrownBy(() -> service.register("a@example.com", "password1")).isInstanceOf(
            DataIntegrityViolationException.class
        );

        verify(mailer, never()).sendVerification(any(), any());
    }

    @Test
    void everyRegistrationGetsADifferentToken() {
        when(passwords.encode(any())).thenReturn("encoded");
        when(users.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));

        service.register("a@example.com", "password1");
        service.register("b@example.com", "password1");

        assertThat(sentToken("a@example.com")).isNotEqualTo(sentToken("b@example.com"));
    }

    // ---------- login: verification gate ----------

    @Test
    void loginRejectsAnUnverifiedEmailEvenWithTheCorrectPassword() {
        User user = unverifiedUserWithId("alice@example.com", "hash");
        when(users.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(passwords.matches("correct-horse", "hash")).thenReturn(true);

        ApiException error = thrownBy(() -> service.login("alice@example.com", "correct-horse"));

        assertThat(error.getErrorType()).isEqualTo(ErrorType.EMAIL_NOT_VERIFIED);
        verify(refreshTokens, never()).save(any());
    }

    @Test
    void loginHidesTheVerificationStateFromSomeoneWhoDoesNotKnowThePassword() {
        User user = unverifiedUserWithId("alice@example.com", "hash");
        when(users.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(passwords.matches("wrong-password", "hash")).thenReturn(false);

        ApiException error = thrownBy(() -> service.login("alice@example.com", "wrong-password"));

        assertThat(error.getErrorType()).isEqualTo(ErrorType.INVALID_CREDENTIALS);
    }

    // ---------- verifyEmail ----------

    @Test
    void verifyEmailMarksTheUserVerifiedAndClearsTheToken() {
        User user = unverifiedUserWithId("alice@example.com", "hash");
        user.startVerification(AuthService.hash("raw-token"), Instant.now().plusSeconds(60));
        when(users.findByVerificationTokenHash(AuthService.hash("raw-token"))).thenReturn(
            Optional.of(user)
        );

        service.verifyEmail("raw-token");

        assertThat(user.isEmailVerified()).isTrue();
        assertThat(user.getVerificationTokenHash()).isNull();
    }

    @Test
    void verifyEmailLooksUpTheHashNotTheRawToken() {
        when(users.findByVerificationTokenHash(any())).thenReturn(Optional.empty());

        thrownBy(() -> service.verifyEmail("raw-token"));

        verify(users).findByVerificationTokenHash(AuthService.hash("raw-token"));
    }

    @Test
    void verifyEmailRejectsAnUnknownToken() {
        when(users.findByVerificationTokenHash(any())).thenReturn(Optional.empty());

        ApiException error = thrownBy(() -> service.verifyEmail("nope"));

        assertThat(error.getErrorType()).isEqualTo(ErrorType.EMAIL_VERIFICATION_TOKEN_INVALID);
    }

    @Test
    void verifyEmailRejectsAnExpiredTokenAndLeavesTheUserUnverified() {
        User user = unverifiedUserWithId("alice@example.com", "hash");
        user.startVerification(AuthService.hash("raw-token"), Instant.now().minusSeconds(1));
        when(users.findByVerificationTokenHash(AuthService.hash("raw-token"))).thenReturn(
            Optional.of(user)
        );

        ApiException error = thrownBy(() -> service.verifyEmail("raw-token"));

        assertThat(error.getErrorType()).isEqualTo(ErrorType.EMAIL_VERIFICATION_TOKEN_INVALID);
        assertThat(user.isEmailVerified()).isFalse();
    }

    // ---------- resendVerification ----------

    @Test
    void resendStoresAFreshHashAndMailsTheRawToken() {
        when(users.replaceVerificationToken(eq("alice@example.com"), any(), any())).thenReturn(1);
        ArgumentCaptor<String> storedHash = ArgumentCaptor.forClass(String.class);

        service.resendVerification("alice@example.com");

        verify(users).replaceVerificationToken(
            eq("alice@example.com"),
            storedHash.capture(),
            any(Instant.class)
        );
        assertThat(storedHash.getValue()).isEqualTo(
            AuthService.hash(sentToken("alice@example.com"))
        );
    }

    @Test
    void resendNormalizesTheEmail() {
        when(users.replaceVerificationToken(eq("alice@example.com"), any(), any())).thenReturn(1);

        service.resendVerification("  ALICE@Example.com ");

        verify(mailer).sendVerification(eq("alice@example.com"), any());
    }

    @Test
    void resendSendsNothingWhenNoUnverifiedUserMatches() {
        // The repository returns 0 for an unknown email and for an already verified user.
        when(users.replaceVerificationToken(any(), any(), any())).thenReturn(0);

        service.resendVerification("nobody@example.com");

        verify(mailer, never()).sendVerification(any(), any());
    }

    @Test
    void everyResendGetsADifferentToken() {
        when(users.replaceVerificationToken(any(), any(), any())).thenReturn(1);

        service.resendVerification("alice@example.com");
        service.resendVerification("alice@example.com");

        ArgumentCaptor<String> tokens = ArgumentCaptor.forClass(String.class);
        verify(mailer, times(2)).sendVerification(eq("alice@example.com"), tokens.capture());
        assertThat(tokens.getAllValues().get(0)).isNotEqualTo(tokens.getAllValues().get(1));
    }

    // ---------- helpers ----------

    private static User userWithId(String email, String passwordHash) {
        User user = unverifiedUserWithId(email, passwordHash);
        user.verifyEmail();

        return user;
    }

    private static User unverifiedUserWithId(String email, String passwordHash) {
        User user = new User(email, passwordHash);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());

        return user;
    }

    // The raw token that was mailed to this address (last call).
    private String sentToken(String email) {
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(mailer, atLeastOnce()).sendVerification(eq(email), token.capture());

        return token.getValue();
    }

    private static RefreshToken liveToken(User user, String rawToken) {
        return new RefreshToken(
            user.getId(),
            AuthService.hash(rawToken),
            Instant.now().plus(Duration.ofDays(1))
        );
    }

    private static ApiException thrownBy(Runnable action) {
        try {
            action.run();
        } catch (ApiException e) {
            return e;
        }
        throw new AssertionError("Expected an ApiException");
    }
}
