package com.urlshortener.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

// Unit tests for the secret check and for what the JwtDecoder bean accepts and rejects.
// Secrets are 64 bytes so that HS512 can be signed with them (see rejectsOtherHmacAlgorithm).
class JwtBeansTest {

    static final String SECRET = "a".repeat(64);
    static final String OTHER_SECRET = "b".repeat(64);

    final JwtDecoder decoder = new SecurityConfig(SECRET).jwtDecoder();

    // ---------- secret length ----------

    @Test
    void rejectsSecretShorterThan32Bytes() {
        assertThatThrownBy(() -> new SecurityConfig("a".repeat(31)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("32 bytes");
    }

    @Test
    void acceptsSecretOfExactly32Bytes() {
        new SecurityConfig("a".repeat(32));
    }

    @Test
    void rejectsEmptySecret() {
        assertThatThrownBy(() -> new SecurityConfig("")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void countsBytesNotCharacters() {
        // "é" is 2 bytes in UTF-8: 16 chars = 32 bytes (ok), 15 chars = 30 bytes (too short).
        new SecurityConfig("é".repeat(16));
        assertThatThrownBy(() -> new SecurityConfig("é".repeat(15))).isInstanceOf(
            IllegalStateException.class
        );
    }

    // ---------- decoder ----------

    @Test
    void decodesATokenSignedWithTheSameSecret() throws Exception {
        String token = sign(SECRET, JWSAlgorithm.HS256, Duration.ofMinutes(5));

        Jwt jwt = decoder.decode(token);

        assertThat(jwt.getSubject()).isEqualTo("user-1");
    }

    @Test
    void encoderAndDecoderBeansWorkTogether() throws Exception {
        SecurityConfig config = new SecurityConfig(SECRET);
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .subject("user-2")
            .issuedAt(now)
            .expiresAt(now.plusSeconds(60))
            .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();

        String token = config
            .jwtEncoder()
            .encode(JwtEncoderParameters.from(header, claims))
            .getTokenValue();

        assertThat(config.jwtDecoder().decode(token).getSubject()).isEqualTo("user-2");
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        // 5 minutes ago is well outside the default 60 s clock skew.
        String token = sign(SECRET, JWSAlgorithm.HS256, Duration.ofMinutes(-5));

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(BadJwtException.class);
    }

    @Test
    void rejectsTokenSignedWithAnotherSecret() throws Exception {
        String token = sign(OTHER_SECRET, JWSAlgorithm.HS256, Duration.ofMinutes(5));

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(BadJwtException.class);
    }

    @Test
    void rejectsOtherHmacAlgorithmEvenWithTheRightSecret() throws Exception {
        // Only HS256 is allowed. HS512 with the same key must not pass.
        String token = sign(SECRET, JWSAlgorithm.HS512, Duration.ofMinutes(5));

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(BadJwtException.class);
    }

    @Test
    void rejectsUnsignedAlgNoneToken() {
        String token = new PlainJWT(
            new JWTClaimsSet.Builder()
                .subject("attacker")
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .build()
        ).serialize();

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(BadJwtException.class);
    }

    @Test
    void rejectsTokenWithTamperedPayload() throws Exception {
        String[] parts = sign(SECRET, JWSAlgorithm.HS256, Duration.ofMinutes(5)).split("\\.");
        String forgedPayload = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                "{\"sub\":\"admin\",\"exp\":9999999999}".getBytes(StandardCharsets.UTF_8)
            );
        String token = parts[0] + "." + forgedPayload + "." + parts[2];

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(BadJwtException.class);
    }

    @Test
    void rejectsGarbageAndEmptyStrings() {
        assertThatThrownBy(() -> decoder.decode("not-a-jwt")).isInstanceOf(BadJwtException.class);
        assertThatThrownBy(() -> decoder.decode("a.b.c")).isInstanceOf(BadJwtException.class);
        assertThatThrownBy(() -> decoder.decode("")).isInstanceOf(RuntimeException.class);
    }

    private static String sign(String secret, JWSAlgorithm algorithm, Duration lifetimeFromNow)
        throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .subject("user-1")
            .issueTime(Date.from(now.minus(Duration.ofMinutes(10))))
            .expirationTime(Date.from(now.plus(lifetimeFromNow)))
            .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(algorithm), claims);
        jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));

        return jwt.serialize();
    }
}
