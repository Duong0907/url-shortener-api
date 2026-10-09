# Plan 2 — Accounts and Auth Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Users can register, log in, refresh and log out. `/api/**` routes other than link creation and auth need a valid Bearer token.

**Architecture:** Spring Security as an OAuth2 resource server validates HS256 JWT access tokens signed with `JWT_SECRET`. `AuthService` issues those tokens itself with `NimbusJwtEncoder`. Refresh tokens are random opaque strings, stored as SHA-256 hashes, rotated on each use, and sent as an `HttpOnly` `SameSite=Strict` cookie scoped to `/api/auth`.

**Tech Stack:** Spring Security 7, `spring-boot-starter-security-oauth2-resource-server` (Nimbus JOSE), BCrypt, Hibernate + Flyway, Lombok, springdoc-openapi, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-10-09-url-shortener-design.md` (sections 5 `users` / `refresh_tokens`, 6 Auth, 7 Authentication)

**Depends on:** Plan 1 complete (42 tests green).

**Done when:** `./mvnw test` reports `Tests run: 55, Failures: 0, Errors: 0`.

## Global Constraints

- Java 21. Spring Boot 4.1.1. Maven wrapper (`./mvnw`) from Spring Initializr.
- Root package `com.urlshortener`. Feature packages: `link`, `redirect`, `auth`, `ratelimit`.
- PostgreSQL 16 (`postgres:16-alpine`) everywhere: Testcontainers, Compose.
- ORM: Hibernate through Spring Data JPA. Flyway owns the DDL. Hibernate runs with `spring.jpa.hibernate.ddl-auto=validate`, so the app does not start if an entity and its table disagree. A schema change is a new `V<n>__*.sql` file plus the entity change, in one commit. Never edit a migration that has run.
- Lombok:
  - Entities: `@Getter`, `@NoArgsConstructor(access = AccessLevel.PROTECTED)`, and `@Setter` only on fields that may change. Never `@Data` or `@EqualsAndHashCode` on an entity: generated `equals`/`hashCode` over mutable fields breaks Hibernate collections and lazy loading.
  - Spring beans: `@RequiredArgsConstructor` with `private final` fields. Put `@Value` on the field; `lombok.config` copies it to the constructor.
  - DTOs stay Java `record`s. Lombok adds nothing there.
- API docs (springdoc-openapi): every controller has `@Tag`. Every endpoint has `@Operation(summary = ...)` and one `@ApiResponse` per status it can return, including the success status (springdoc drops the generated success response once you declare any `@ApiResponse`, unless the method has `@ResponseStatus`). Owner endpoints add `@SecurityRequirement(name = "bearerAuth")`.
- Errors: throw `ResponseStatusException` (or a subclass). `spring.mvc.problemdetails.enabled=true` renders it as `application/problem+json`.
- Integration tests use Testcontainers Postgres through `TestcontainersConfiguration`. No H2. Docker must be running.
- Redirects use `302` + `Cache-Control: no-store`. Never `301`.
- Add no dependency that this plan does not list.
- Run all commands from the repository root (`url-shortener-api/`).
- `JWT_SECRET` has no default. Tests get it from the surefire config. Local runs need it in the environment.

---

### Task 1: Security filter chain, JWT beans, CORS

**Files:**
- Modify: `pom.xml` (dependencies and surefire plugin)
- Modify: `src/main/resources/application.properties` (append)
- Create: `src/main/java/com/urlshortener/auth/SecurityConfig.java`
- Modify: `src/main/java/com/urlshortener/OpenApiConfig.java` (full replacement)
- Test: `src/test/java/com/urlshortener/auth/SecurityConfigTest.java`
- Modify: `src/test/java/com/urlshortener/OpenApiDocsTest.java` (full replacement)

**Interfaces:**
- Consumes: `POST /api/links`, `GET /{slug}`, `/health` from Plan 1.
- Produces: beans `PasswordEncoder` (BCrypt), `JwtEncoder`, `JwtDecoder` (HS256), `CorsConfigurationSource`. Rules: `POST /api/links` and `/api/auth/**` are public; other `/api/**` need a JWT; everything else is public. OpenAPI security scheme `bearerAuth` (HTTP bearer, JWT), so Swagger UI shows an **Authorize** button. Properties `app.jwt.secret`, `app.jwt.access-ttl`, `app.refresh-token.ttl`, `app.refresh-token.cookie-secure`, `app.cors.allowed-origins`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/urlshortener/auth/SecurityConfigTest.java`:

```java
package com.urlshortener.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.urlshortener.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SecurityConfigTest {

    @Autowired
    MockMvc mvc;

    @Test
    void protectedApiRejectsMissingOrInvalidToken() throws Exception {
        mvc.perform(get("/api/links")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/links").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousLinkCreationStillWorks() throws Exception {
        mvc.perform(post("/api/links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUrl\":\"https://example.com\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void redirectsAndHealthStayPublic() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk());
        mvc.perform(get("/unknown1")).andExpect(status().isNotFound());
    }
}
```

Replace the API docs test. It adds `declaresBearerAuthScheme`:

`src/test/java/com/urlshortener/OpenApiDocsTest.java`:

```java
package com.urlshortener;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OpenApiDocsTest {

    @Autowired
    MockMvc mvc;

    @Test
    void servesOpenApiJson() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("URL Shortener API"));
    }

    @Test
    void servesSwaggerUi() throws Exception {
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    @Test
    void declaresBearerAuthScheme() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"));
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `./mvnw test -Dtest='SecurityConfigTest,OpenApiDocsTest'`
Expected: `protectedApiRejectsMissingOrInvalidToken` fails with `Status expected:<401> but was:<405>`, and `declaresBearerAuthScheme` fails because the scheme does not exist yet.

- [ ] **Step 3: Add dependencies**

In `pom.xml`, add inside `<dependencies>`, next to the other Spring Boot starters:

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security-oauth2-resource-server</artifactId>
		</dependency>
```

and next to the other test-scope starters:

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security-test</artifactId>
			<scope>test</scope>
		</dependency>
```

- [ ] **Step 4: Give tests a JWT secret**

In `pom.xml`, add this plugin inside `<build><plugins>`, after `spring-boot-maven-plugin`:

```xml
			<plugin>
				<groupId>org.apache.maven.plugins</groupId>
				<artifactId>maven-surefire-plugin</artifactId>
				<configuration>
					<systemPropertyVariables>
						<JWT_SECRET>test-only-secret-that-is-at-least-32-bytes</JWT_SECRET>
					</systemPropertyVariables>
				</configuration>
			</plugin>
```

- [ ] **Step 5: Append the auth config**

Append to `src/main/resources/application.properties`:

```properties
app.jwt.secret=${JWT_SECRET}
app.jwt.access-ttl=PT15M
app.refresh-token.ttl=P30D
app.refresh-token.cookie-secure=${COOKIE_SECURE:true}
app.cors.allowed-origins=${CORS_ALLOWED_ORIGINS:http://localhost:5173}
```

- [ ] **Step 6: Write the security config**

`src/main/java/com/urlshortener/auth/SecurityConfig.java`:

```java
package com.urlshortener.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {

    private final SecretKey jwtKey;

    public SecurityConfig(@Value("${app.jwt.secret}") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 bytes");
        }
        this.jwtKey = new SecretKeySpec(bytes, "HmacSHA256");
    }

    // CSRF is off: the API reads Bearer headers, and the only cookie is SameSite=Strict on /api/auth.
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/links").permitAll()
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtKey));
    }

    @Bean
    JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withSecretKey(jwtKey).macAlgorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${app.cors.allowed-origins}") List<String> origins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
```

Spring Boot's default in-memory user backs off because a `JwtDecoder` bean exists. No generated password appears in the log.

`anyRequest().permitAll()` also keeps `/swagger-ui/**` and `/v3/api-docs/**` public.

- [ ] **Step 7: Add the Bearer scheme to the OpenAPI config**

`src/main/java/com/urlshortener/OpenApiConfig.java`:

```java
package com.urlshortener;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

// Swagger UI: /swagger-ui.html   OpenAPI JSON: /v3/api-docs
@Configuration
@OpenAPIDefinition(info = @Info(title = "URL Shortener API", version = "v1",
        description = "Short links, redirects, accounts and link management."))
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class OpenApiConfig {
}
```

- [ ] **Step 8: Run the tests to see them pass**

Run: `./mvnw test -Dtest='SecurityConfigTest,OpenApiDocsTest'`
Expected: `Tests run: 6, Failures: 0, Errors: 0`.

- [ ] **Step 9: Run the whole suite**

Run: `./mvnw test`
Expected: `Tests run: 46, Failures: 0, Errors: 0`. Plan 1 tests still pass, so anonymous creation and redirects stay public.

- [ ] **Step 10: Commit**

```bash
git add pom.xml src/main/resources/application.properties src/main/java/com/urlshortener src/test/java/com/urlshortener
git commit -m "feat: add JWT resource server security and CORS"
```

---

### Task 2: Register, login, refresh, logout

**Files:**
- Create: `src/main/resources/db/migration/V2__create_users_and_refresh_tokens.sql`
- Create: `src/main/java/com/urlshortener/auth/User.java`
- Create: `src/main/java/com/urlshortener/auth/UserRepository.java`
- Create: `src/main/java/com/urlshortener/auth/RefreshToken.java`
- Create: `src/main/java/com/urlshortener/auth/RefreshTokenRepository.java`
- Create: `src/main/java/com/urlshortener/auth/AuthService.java`
- Create: `src/main/java/com/urlshortener/auth/AuthController.java`
- Test: `src/test/java/com/urlshortener/auth/AuthControllerTest.java`

**Interfaces:**
- Consumes: `PasswordEncoder`, `JwtEncoder`, properties from Task 1.
- Produces:
  - Tables `users` (constraint `uk_users_email`) and `refresh_tokens`; FK `fk_links_owner` from `links.owner_id`.
  - `public interface UserRepository extends JpaRepository<User, UUID>` with `Optional<User> findByEmail(String email)`.
  - `public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID>` with `Optional<RefreshToken> findByTokenHash(String)`.
  - `@Service public class AuthService` with `User register(String email, String password)`, `Tokens login(String email, String password)`, `Tokens refresh(String refreshToken)`, `void logout(String refreshToken)`, `Duration refreshTtl()`, and `public record Tokens(String accessToken, long expiresInSeconds, String refreshToken)`.
  - Access token claim `sub` = user ID as a UUID string. Plan 3 reads it with `UUID.fromString(jwt.getSubject())`.
  - Endpoints under `/api/auth`: `register`, `login`, `refresh`, `logout`. Cookie name `refresh_token`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/urlshortener/auth/AuthControllerTest.java`:

```java
package com.urlshortener.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.urlshortener.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuthControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtDecoder jwtDecoder;

    @Autowired
    UserRepository users;

    @Autowired
    RefreshTokenRepository refreshTokens;

    @BeforeEach
    void cleanDatabase() {
        refreshTokens.deleteAll();
        users.deleteAll();
    }

    @Test
    void registersUserWithNormalizedEmail() throws Exception {
        register("Alice@Example.com", "correct-horse")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("alice@example.com"));
        assertThat(users.findByEmail("alice@example.com").orElseThrow().getPasswordHash())
                .isNotEqualTo("correct-horse");
    }

    @Test
    void rejectsDuplicateEmailIgnoringCase() throws Exception {
        register("alice@example.com", "correct-horse").andExpect(status().isCreated());
        register("ALICE@example.com", "another-pass").andExpect(status().isConflict());
    }

    @Test
    void rejectsShortPassword() throws Exception {
        register("alice@example.com", "short").andExpect(status().isBadRequest());
    }

    @Test
    void loginReturnsAccessTokenAndRefreshCookie() throws Exception {
        register("alice@example.com", "correct-horse");

        MvcResult result = login("alice@example.com", "correct-horse")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(header().string("Set-Cookie", allOf(
                        containsString("refresh_token="), containsString("HttpOnly"),
                        containsString("SameSite=Strict"), containsString("Path=/api/auth"))))
                .andReturn();

        String userId = users.findByEmail("alice@example.com").orElseThrow().getId().toString();
        assertThat(jwtDecoder.decode(accessToken(result)).getSubject()).isEqualTo(userId);
    }

    @Test
    void loginRejectsWrongPassword() throws Exception {
        register("alice@example.com", "correct-horse");
        login("alice@example.com", "wrong-horse").andExpect(status().isUnauthorized());
    }

    @Test
    void loginRejectsUnknownEmail() throws Exception {
        login("nobody@example.com", "correct-horse").andExpect(status().isUnauthorized());
    }

    @Test
    void refreshRotatesTokenAndRejectsReuse() throws Exception {
        register("alice@example.com", "correct-horse");
        Cookie first = login("alice@example.com", "correct-horse").andReturn().getResponse().getCookie("refresh_token");

        MvcResult refreshed = mvc.perform(post("/api/auth/refresh").cookie(first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn();
        Cookie second = refreshed.getResponse().getCookie("refresh_token");
        assertThat(second.getValue()).isNotEqualTo(first.getValue());

        mvc.perform(post("/api/auth/refresh").cookie(first)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").cookie(second)).andExpect(status().isOk());
    }

    @Test
    void refreshWithoutCookieIsUnauthorized() throws Exception {
        mvc.perform(post("/api/auth/refresh")).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesRefreshTokenAndClearsCookie() throws Exception {
        register("alice@example.com", "correct-horse");
        Cookie cookie = login("alice@example.com", "correct-horse").andReturn().getResponse().getCookie("refresh_token");

        mvc.perform(post("/api/auth/logout").cookie(cookie))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));

        mvc.perform(post("/api/auth/refresh").cookie(cookie)).andExpect(status().isUnauthorized());
    }

    private org.springframework.test.web.servlet.ResultActions register(String email, String password)
            throws Exception {
        return mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    private org.springframework.test.web.servlet.ResultActions login(String email, String password)
            throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    private static String accessToken(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }
}
```

- [ ] **Step 2: Run the test to see it fail**

Run: `./mvnw test -Dtest=AuthControllerTest`
Expected: compilation failure, `cannot find symbol ... class UserRepository`.

- [ ] **Step 3: Write the migration**

`src/main/resources/db/migration/V2__create_users_and_refresh_tokens.sql`:

```sql
CREATE TABLE users (
    id            UUID PRIMARY KEY,
    email         VARCHAR(254) NOT NULL CONSTRAINT uk_users_email UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE refresh_tokens (
    id         UUID PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL CONSTRAINT uk_refresh_tokens_hash UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE links
    ADD CONSTRAINT fk_links_owner FOREIGN KEY (owner_id) REFERENCES users (id) ON DELETE CASCADE;
```

- [ ] **Step 4: Write the user entity and repository**

`src/main/java/com/urlshortener/auth/User.java`:

```java
package com.urlshortener.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false, length = 254)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public User(String email, String passwordHash) {
        this.email = email;
        this.passwordHash = passwordHash;
    }
}
```

Column `length` values must match `V2`, or `ddl-auto=validate` stops the app.

`src/main/java/com/urlshortener/auth/UserRepository.java`:

```java
package com.urlshortener.auth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);
}
```

- [ ] **Step 5: Write the refresh token entity and repository**

`src/main/java/com/urlshortener/auth/RefreshToken.java`:

```java
package com.urlshortener.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "refresh_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public RefreshToken(UUID userId, String tokenHash, Instant expiresAt) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public boolean isUsable(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    public void revoke(Instant now) {
        this.revokedAt = now;
    }
}
```

`src/main/java/com/urlshortener/auth/RefreshTokenRepository.java`:

```java
package com.urlshortener.auth;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    // Row lock: two parallel refreshes with one token must not both rotate it.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshToken> findByTokenHash(String tokenHash);
}
```

- [ ] **Step 6: Write the service**

`src/main/java/com/urlshortener/auth/AuthService.java`:

```java
package com.urlshortener.auth;

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
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AuthService {

    public record Tokens(String accessToken, long expiresInSeconds, String refreshToken) {
    }

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
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Email is already registered");
            }
            throw e;
        }
    }

    @Transactional
    public Tokens login(String email, String password) {
        User user = users.findByEmail(normalize(email))
                .filter(u -> passwords.matches(password, u.getPasswordHash()))
                .orElseThrow(() -> unauthorized("Invalid email or password"));
        return issueTokens(user);
    }

    @Transactional
    public Tokens refresh(String refreshToken) {
        Instant now = Instant.now();
        RefreshToken stored = refreshTokens.findByTokenHash(hash(refreshToken))
                .filter(t -> t.isUsable(now))
                .orElseThrow(() -> unauthorized("Refresh token is invalid or expired"));
        stored.revoke(now);
        User user = users.findById(stored.getUserId()).orElseThrow(() -> unauthorized("User no longer exists"));
        return issueTokens(user);
    }

    @Transactional
    public void logout(String refreshToken) {
        Instant now = Instant.now();
        refreshTokens.findByTokenHash(hash(refreshToken))
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
        refreshTokens.save(new RefreshToken(user.getId(), hash(refreshToken), now.plus(refreshTtl)));

        return new Tokens(accessToken, accessTtl.toSeconds(), refreshToken);
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static ResponseStatusException unauthorized(String reason) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, reason);
    }
}
```

Login gives the same `401` message for an unknown email and a wrong password, so the response does not reveal which emails exist.

- [ ] **Step 7: Write the controller**

`src/main/java/com/urlshortener/auth/AuthController.java`:

```java
package com.urlshortener.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Auth")
public class AuthController {

    static final String REFRESH_COOKIE = "refresh_token";

    public record RegisterRequest(@NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 72) String password) {
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    public record UserResponse(UUID id, String email) {
    }

    public record AccessTokenResponse(String accessToken, String tokenType, long expiresIn) {
    }

    private final AuthService auth;

    @Value("${app.refresh-token.cookie-secure}")
    private final boolean secureCookie;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an account")
    @ApiResponse(responseCode = "400", description = "Invalid email or password")
    @ApiResponse(responseCode = "409", description = "Email is already registered")
    public UserResponse register(@Valid @RequestBody RegisterRequest body) {
        User user = auth.register(body.email(), body.password());
        return new UserResponse(user.getId(), user.getEmail());
    }

    @PostMapping("/login")
    @Operation(summary = "Log in", description = "Returns an access token and sets the refresh_token cookie.")
    @ApiResponse(responseCode = "200", description = "Access token; refresh_token cookie is set")
    @ApiResponse(responseCode = "401", description = "Invalid email or password")
    public ResponseEntity<AccessTokenResponse> login(@Valid @RequestBody LoginRequest body) {
        return withTokens(auth.login(body.email(), body.password()));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Get a new access token", description = "Reads and rotates the refresh_token cookie.")
    @ApiResponse(responseCode = "200", description = "New access token; refresh_token cookie is rotated")
    @ApiResponse(responseCode = "401", description = "Refresh token is missing, revoked or expired")
    public ResponseEntity<AccessTokenResponse> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        if (refreshToken == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token is missing");
        }
        return withTokens(auth.refresh(refreshToken));
    }

    @PostMapping("/logout")
    @Operation(summary = "Log out", description = "Revokes the refresh token and clears the cookie.")
    @ApiResponse(responseCode = "204", description = "Logged out")
    public ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        if (refreshToken != null) {
            auth.logout(refreshToken);
        }
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO).toString())
                .build();
    }

    private ResponseEntity<AccessTokenResponse> withTokens(AuthService.Tokens tokens) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(tokens.refreshToken(), auth.refreshTtl()).toString())
                .body(new AccessTokenResponse(tokens.accessToken(), "Bearer", tokens.expiresInSeconds()));
    }

    // Path limits the cookie to /api/auth, so it never rides along on link or redirect requests.
    private ResponseCookie refreshCookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Strict")
                .path("/api/auth")
                .maxAge(maxAge)
                .build();
    }
}
```

- [ ] **Step 8: Run the test to see it pass**

Run: `./mvnw test -Dtest=AuthControllerTest`
Expected: `Tests run: 9, Failures: 0, Errors: 0`.

- [ ] **Step 9: Run the whole suite**

Run: `./mvnw test`
Expected: `Tests run: 55, Failures: 0, Errors: 0`.

- [ ] **Step 10: Try it by hand**

```bash
JWT_SECRET=$(openssl rand -base64 48) ./mvnw spring-boot:test-run
# in a second terminal:
curl -s -X POST localhost:8080/api/auth/register -H 'Content-Type: application/json' -d '{"email":"me@example.com","password":"correct-horse"}'
curl -si -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' -d '{"email":"me@example.com","password":"correct-horse"}'
```

Expected: register prints `{"id":"...","email":"me@example.com"}`. Login prints `HTTP/1.1 200`, a `Set-Cookie: refresh_token=...; Path=/api/auth; ... HttpOnly; SameSite=Strict` header and an `accessToken`.

In Swagger UI (http://localhost:8080/swagger-ui.html), click **Authorize**, paste the `accessToken`, and the Bearer header is sent on secured calls.

- [ ] **Step 11: Commit**

```bash
git add src/main/resources/db/migration src/main/java/com/urlshortener/auth src/test/java/com/urlshortener/auth
git commit -m "feat: add register, login, refresh rotation and logout"
```
