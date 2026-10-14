package com.urlshortener.auth.service;

import com.urlshortener.auth.entity.RefreshToken;
import com.urlshortener.auth.entity.User;
import com.urlshortener.auth.repository.RefreshTokenRepository;
import com.urlshortener.auth.repository.UserRepository;
import com.urlshortener.error.ApiException;
import com.urlshortener.error.ErrorType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    public record Tokens(String accessToken, long expiresInSeconds, String refreshToken) {}

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwords;
    private final JwtEncoder jwtEncoder;

    @Value("${app.jwt.access-ttl}")
    private final Duration accessTtl;

    @Value("${app.refresh-token.ttl}")
    private final Duration refreshTtl;

    private final SecureRandom random = new SecureRandom();

    public User register(String email, String password) {
        try {
            return users.saveAndFlush(new User(normalize(email), passwords.encode(password)));
        } catch (DataIntegrityViolationException e) {
            String message = NestedExceptionUtils.getMostSpecificCause(e).getMessage();
            if (message != null && message.contains("uk_users_email")) {
                throw new ApiException(ErrorType.EMAIL_TAKEN, "Email is already registered");
            }
            throw e;
        }
    }

    @Transactional
    public Tokens login(String email, String password) {
        User user = users
            .findByEmail(normalize(email))
            .filter(u -> passwords.matches(password, u.getPasswordHash()))
            .orElseThrow(() ->
                new ApiException(ErrorType.INVALID_CREDENTIALS, "Invalid email or password")
            );
        return issueTokens(user);
    }

    @Transactional
    public Tokens refresh(String refreshToken) {
        Instant now = Instant.now();
        RefreshToken stored = refreshTokens
            .findByTokenHash(hash(refreshToken))
            .filter(t -> t.isUsable(now))
            .orElseThrow(() -> refreshInvalid("Refresh token is invalid or expired"));
        stored.revoke(now);

        User user = users
            .findById(stored.getUserId())
            .orElseThrow(() -> refreshInvalid("User no longer exists"));

        return issueTokens(user);
    }

    @Transactional
    public void logout(String refreshToken) {
        Instant now = Instant.now();
        refreshTokens
            .findByTokenHash(hash(refreshToken))
            .filter(t -> t.isUsable(now))
            .ifPresent(t -> t.revoke(now));
    }

    public Duration refreshTtl() {
        return refreshTtl;
    }

    private Tokens issueTokens(User user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .subject(user.getId().toString())
            .issuedAt(now)
            .expiresAt(now.plus(accessTtl))
            .claim("email", user.getEmail())
            .build();
        String accessToken = jwtEncoder
            .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
            .getTokenValue();

        byte[] bytes = new byte[32];
        random.nextBytes(bytes);

        String refreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        refreshTokens.save(
            new RefreshToken(user.getId(), hash(refreshToken), now.plus(refreshTtl))
        );

        return new Tokens(accessToken, accessTtl.toSeconds(), refreshToken);
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                token.getBytes(StandardCharsets.UTF_8)
            );
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static ApiException refreshInvalid(String reason) {
        return new ApiException(ErrorType.REFRESH_TOKEN_INVALID, reason);
    }
}
