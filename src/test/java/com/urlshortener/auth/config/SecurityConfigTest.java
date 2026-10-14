package com.urlshortener.auth.config;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.urlshortener.TestcontainersConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

// Integration tests for the filter chain: which routes are public, which need a token, and CORS.
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SecurityConfigTest {

    static final String PROTECTED_PATH = "/api/does-not-exist";

    @Autowired
    MockMvc mvc;

    @Value("${app.jwt.secret}")
    String secret;

    // First allowed UI origin from config, so the test follows CORS_ALLOWED_ORIGINS.
    @Value("#{'${app.cors.allowed-origins}'.split(',')[0].trim()}")
    String uiOrigin;

    // ---------- route rules ----------

    @Test
    void protectedApiRejectsMissingOrInvalidToken() throws Exception {
        mvc.perform(get("/api/links")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/links").header("Authorization", "Bearer not-a-jwt")).andExpect(
            status().isUnauthorized()
        );
    }

    @Test
    void protectedApiLetsAValidTokenThrough() throws Exception {
        // 404 (no such route) instead of 401 proves the request passed the security filter.
        mvc.perform(get(PROTECTED_PATH)).andExpect(status().isUnauthorized());
        mvc.perform(
            get(PROTECTED_PATH).header("Authorization", "Bearer " + token(secret, 5))
        ).andExpect(status().isNotFound());
    }

    @Test
    void protectedApiRejectsExpiredToken() throws Exception {
        mvc.perform(get(PROTECTED_PATH).header("Authorization", "Bearer " + token(secret, -5)))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("WWW-Authenticate", containsString("Bearer")));
    }

    @Test
    void protectedApiRejectsTokenSignedWithAnotherSecret() throws Exception {
        String forged = token("x".repeat(48), 5);

        mvc.perform(get(PROTECTED_PATH).header("Authorization", "Bearer " + forged)).andExpect(
            status().isUnauthorized()
        );
    }

    @Test
    void protectedApiRejectsOtherAuthorizationSchemes() throws Exception {
        mvc.perform(
            get(PROTECTED_PATH).header("Authorization", "Basic YWxpY2U6c2VjcmV0")
        ).andExpect(status().isUnauthorized());
        mvc.perform(get(PROTECTED_PATH).header("Authorization", "Bearer")).andExpect(
            status().isUnauthorized()
        );
    }

    @Test
    void anonymousLinkCreationStillWorks() throws Exception {
        mvc.perform(
            post("/api/links")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetUrl\":\"https://example.com\"}")
        ).andExpect(status().isCreated());
    }

    @Test
    void invalidBearerTokenOnAPublicRouteIsStillRejected() throws Exception {
        // Spring validates any Bearer header it sees, even on permitAll routes.
        // Effect for the UI: send no Authorization header when the user is logged out,
        // and refresh an expired token before calling POST /api/links.
        mvc.perform(
            post("/api/links")
                .header("Authorization", "Bearer " + token(secret, -5))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetUrl\":\"https://example.com\"}")
        ).andExpect(status().isUnauthorized());
    }

    @Test
    void redirectsAndHealthStayPublic() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk());
        mvc.perform(get("/unknown1")).andExpect(status().isNotFound());
    }

    @Test
    void authEndpointsNeedNoToken() throws Exception {
        mvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"nobody@example.com\",\"password\":\"whatever1\"}")
        )
            .andExpect(status().isUnauthorized())
            .andExpect(header().doesNotExist("WWW-Authenticate"));
    }

    // ---------- CORS ----------

    @Test
    void preflightFromAllowedOriginIsAccepted() throws Exception {
        mvc.perform(
            options("/api/links")
                .header("Origin", uiOrigin)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type")
        )
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", uiOrigin))
            .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
            .andExpect(header().string("Access-Control-Allow-Methods", containsString("PATCH")));
    }

    @Test
    void preflightFromUnknownOriginIsRejected() throws Exception {
        mvc.perform(
            options("/api/links")
                .header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "POST")
        )
            .andExpect(status().isForbidden())
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void preflightWithDisallowedMethodIsRejected() throws Exception {
        mvc.perform(
            options("/api/links")
                .header("Origin", uiOrigin)
                .header("Access-Control-Request-Method", "PUT")
        ).andExpect(status().isForbidden());
    }

    @Test
    void preflightWithDisallowedHeaderIsRejected() throws Exception {
        mvc.perform(
            options("/api/links")
                .header("Origin", uiOrigin)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "x-evil")
        ).andExpect(status().isForbidden());
    }

    @Test
    void actualRequestFromUnknownOriginGetsNoCorsHeaders() throws Exception {
        mvc.perform(get("/api/links").header("Origin", "https://evil.example")).andExpect(
            header().doesNotExist("Access-Control-Allow-Origin")
        );
    }

    private static String token(String secret, long minutesFromNow) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .subject("user-1")
            .issueTime(Date.from(now.minus(Duration.ofMinutes(30))))
            .expirationTime(Date.from(now.plus(Duration.ofMinutes(minutesFromNow))))
            .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));

        return jwt.serialize();
    }
}
