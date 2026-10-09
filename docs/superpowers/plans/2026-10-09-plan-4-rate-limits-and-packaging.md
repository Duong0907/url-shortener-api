# Plan 4 — Rate Limits and Packaging Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Abuse-prone endpoints return `429` past their limits, and the app runs from `docker compose up`.

**Architecture:** `RateLimiter` holds one Bucket4j bucket per key (IP or user ID) in a bounded Caffeine cache. Controllers call it before doing work. A `429` carries `Retry-After`. A multi-stage Dockerfile builds the jar; `compose.yaml` runs it with Postgres.

**Tech Stack:** Bucket4j 8.14.0 (`com.bucket4j:bucket4j_jdk17-core`), Caffeine (version managed by Spring Boot), Docker, Docker Compose.

**Spec:** `docs/superpowers/specs/2026-10-09-url-shortener-design.md` (sections 7 Rate limits, 9 Configuration)

**Depends on:** Plan 3 complete (70 tests green).

**Done when:** `./mvnw test` reports `Tests run: 73, Failures: 0, Errors: 0` and `docker compose up --build` serves `/health`.

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

---

### Task 1: Rate limiting

**Files:**
- Modify: `pom.xml` (dependencies and surefire properties)
- Modify: `src/main/resources/application.properties` (append)
- Create: `src/main/java/com/urlshortener/ratelimit/TooManyRequestsException.java`
- Create: `src/main/java/com/urlshortener/ratelimit/RateLimiter.java`
- Modify: `src/main/java/com/urlshortener/link/LinkController.java` (full replacement)
- Modify: `src/main/java/com/urlshortener/auth/AuthController.java` (full replacement)
- Test: `src/test/java/com/urlshortener/ratelimit/RateLimitTest.java`

**Interfaces:**
- Consumes: `LinkController.create`, `AuthController.register`, `AuthController.login`; `TestUsers.bearerFor` (Plan 3).
- Produces: `@Component public class RateLimiter` with `checkLinkCreation(UUID ownerId, String ip)`, `checkLogin(String ip)`, `checkRegistration(String ip)`. Each throws `TooManyRequestsException` (status `429`, header `Retry-After`).

- [ ] **Step 1: Write the failing test**

The test sets low limits through `@SpringBootTest(properties = ...)` and fakes client IPs with a `RequestPostProcessor`.

`src/test/java/com/urlshortener/ratelimit/RateLimitTest.java`:

```java
package com.urlshortener.ratelimit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.urlshortener.TestUsers;
import com.urlshortener.TestcontainersConfiguration;
import com.urlshortener.auth.RefreshTokenRepository;
import com.urlshortener.auth.UserRepository;
import com.urlshortener.link.LinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = {
        "app.rate-limit.anonymous-links-per-hour=2",
        "app.rate-limit.user-links-per-hour=3",
        "app.rate-limit.logins-per-minute=2",
        "app.rate-limit.registrations-per-hour=10"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RateLimitTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    LinkRepository links;

    @Autowired
    UserRepository users;

    @Autowired
    RefreshTokenRepository refreshTokens;

    @BeforeEach
    void cleanDatabase() {
        links.deleteAll();
        refreshTokens.deleteAll();
        users.deleteAll();
    }

    @Test
    void anonymousLinkCreationIsLimitedPerIp() throws Exception {
        mvc.perform(createLink().with(ip("198.51.100.1"))).andExpect(status().isCreated());
        mvc.perform(createLink().with(ip("198.51.100.1"))).andExpect(status().isCreated());
        mvc.perform(createLink().with(ip("198.51.100.1")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

        mvc.perform(createLink().with(ip("198.51.100.2"))).andExpect(status().isCreated());
    }

    @Test
    void signedInUsersGetTheirOwnLargerBucket() throws Exception {
        String bearer = TestUsers.bearerFor(mvc, "carol@example.com");
        for (int i = 0; i < 3; i++) {
            mvc.perform(createLink().header("Authorization", bearer).with(ip("198.51.100.9")))
                    .andExpect(status().isCreated());
        }
        mvc.perform(createLink().header("Authorization", bearer).with(ip("198.51.100.9")))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void loginIsLimitedPerIp() throws Exception {
        String wrong = "{\"email\":\"x@example.com\",\"password\":\"wrong-pass\"}";
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(wrong)
                    .with(ip("198.51.100.3"))).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(wrong)
                .with(ip("198.51.100.3"))).andExpect(status().isTooManyRequests());
    }

    private static MockHttpServletRequestBuilder createLink() {
        return post("/api/links").contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetUrl\":\"https://example.com\"}");
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor ip(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }
}
```

- [ ] **Step 2: Run the test to see it fail**

Run: `./mvnw test -Dtest=RateLimitTest`
Expected: `anonymousLinkCreationIsLimitedPerIp` fails with `Status expected:<429> but was:<201>`.

- [ ] **Step 3: Add dependencies**

In `pom.xml`, add inside `<dependencies>`:

```xml
		<dependency>
			<groupId>com.bucket4j</groupId>
			<artifactId>bucket4j_jdk17-core</artifactId>
			<version>8.14.0</version>
		</dependency>
		<dependency>
			<groupId>com.github.ben-manes.caffeine</groupId>
			<artifactId>caffeine</artifactId>
		</dependency>
```

- [ ] **Step 4: Raise limits for the other tests**

Most tests send every request from `127.0.0.1` and register several users. Real limits would break them. In the surefire `<systemPropertyVariables>` block from Plan 2, add after `JWT_SECRET`:

```xml
						<!-- Most tests share 127.0.0.1; RateLimitTest sets real limits itself. -->
						<app.rate-limit.anonymous-links-per-hour>100000</app.rate-limit.anonymous-links-per-hour>
						<app.rate-limit.user-links-per-hour>100000</app.rate-limit.user-links-per-hour>
						<app.rate-limit.logins-per-minute>100000</app.rate-limit.logins-per-minute>
						<app.rate-limit.registrations-per-hour>100000</app.rate-limit.registrations-per-hour>
```

`@SpringBootTest(properties = ...)` beats system properties, so `RateLimitTest` still gets its low limits.

- [ ] **Step 5: Append the limits config**

Append to `src/main/resources/application.properties`:

```properties
app.rate-limit.anonymous-links-per-hour=10
app.rate-limit.user-links-per-hour=100
app.rate-limit.logins-per-minute=5
app.rate-limit.registrations-per-hour=3
server.forward-headers-strategy=${FORWARD_HEADERS_STRATEGY:none}
```

`FORWARD_HEADERS_STRATEGY=native` makes `getRemoteAddr()` read `X-Forwarded-For`. Set it only behind a trusted proxy. Without a proxy, clients could fake their IP and skip every limit.

- [ ] **Step 6: Write the exception**

`src/main/java/com/urlshortener/ratelimit/TooManyRequestsException.java`:

```java
package com.urlshortener.ratelimit;

import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class TooManyRequestsException extends ResponseStatusException {

    private final long retryAfterSeconds;

    public TooManyRequestsException(Duration retryAfter) {
        super(HttpStatus.TOO_MANY_REQUESTS, "Too many requests, try again later");
        this.retryAfterSeconds = Math.max(1, (retryAfter.toMillis() + 999) / 1000);
    }

    @Override
    public HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        return headers;
    }
}
```

- [ ] **Step 7: Write the limiter**

`src/main/java/com/urlshortener/ratelimit/RateLimiter.java`:

```java
package com.urlshortener.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// ponytail: buckets live in this JVM. Running 2+ instances multiplies every limit;
// move buckets to Redis (bucket4j-redis) when you scale out.
@Component
@RequiredArgsConstructor
public class RateLimiter {

    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofHours(2))
            .maximumSize(100_000)
            .build();

    @Value("${app.rate-limit.anonymous-links-per-hour}")
    private final long anonymousLinksPerHour;

    @Value("${app.rate-limit.user-links-per-hour}")
    private final long userLinksPerHour;

    @Value("${app.rate-limit.logins-per-minute}")
    private final long loginsPerMinute;

    @Value("${app.rate-limit.registrations-per-hour}")
    private final long registrationsPerHour;

    public void checkLinkCreation(UUID ownerId, String ip) {
        if (ownerId != null) {
            consume("links:user:" + ownerId, userLinksPerHour, Duration.ofHours(1));
        } else {
            consume("links:ip:" + ip, anonymousLinksPerHour, Duration.ofHours(1));
        }
    }

    public void checkLogin(String ip) {
        consume("login:ip:" + ip, loginsPerMinute, Duration.ofMinutes(1));
    }

    public void checkRegistration(String ip) {
        consume("register:ip:" + ip, registrationsPerHour, Duration.ofHours(1));
    }

    private void consume(String key, long capacity, Duration period) {
        Bucket bucket = buckets.get(key, k -> Bucket.builder()
                .addLimit(limit -> limit.capacity(capacity).refillIntervally(capacity, period))
                .build());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            throw new TooManyRequestsException(Duration.ofNanos(probe.getNanosToWaitForRefill()));
        }
    }
}
```

`expireAfterAccess` (2 hours) must stay longer than the longest window (1 hour). Otherwise an idle bucket could be evicted mid-window and reset early.

- [ ] **Step 8: Replace the link controller**

It calls the limiter and documents `429`.

`src/main/java/com/urlshortener/link/LinkController.java`:

```java
package com.urlshortener.link;

import com.urlshortener.ratelimit.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/links")
@RequiredArgsConstructor
@Tag(name = "Links")
public class LinkController {

    private final LinkService service;
    private final RateLimiter rateLimiter;

    @Value("${app.base-url}")
    private final String baseUrl;

    // jwt is null for anonymous callers: the link is created without an owner.
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Shorten a URL", description = "Bearer token is optional. Without one, the link has no owner and cannot be managed later.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "400", description = "Invalid URL, custom slug or expiry")
    @ApiResponse(responseCode = "409", description = "Custom slug is taken")
    @ApiResponse(responseCode = "429", description = "Rate limit exceeded; see Retry-After")
    public LinkResponse create(@Valid @RequestBody CreateLinkRequest body, @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) {
        UUID ownerId = jwt == null ? null : userId(jwt);
        rateLimiter.checkLinkCreation(ownerId, request.getRemoteAddr());
        Link link = service.create(new NewLink(body.targetUrl(), body.customSlug(), body.expiresAt(), ownerId,
                request.getRemoteAddr()));
        return LinkResponse.from(link, baseUrl);
    }

    @GetMapping
    @Operation(summary = "List my links, newest first")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Your links")
    @ApiResponse(responseCode = "401", description = "Missing or invalid Bearer token")
    public LinkPage list(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Link> result = service.listOwned(userId(jwt), page, size);
        return new LinkPage(result.map(link -> LinkResponse.from(link, baseUrl)).getContent(),
                result.getNumber(), result.getSize(), result.getTotalElements());
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update my link", description = "A null field stays unchanged. The slug never changes.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "The updated link")
    @ApiResponse(responseCode = "400", description = "Invalid URL or expiry")
    @ApiResponse(responseCode = "401", description = "Missing or invalid Bearer token")
    @ApiResponse(responseCode = "403", description = "Not your link")
    @ApiResponse(responseCode = "404", description = "Link not found")
    public LinkResponse update(@PathVariable UUID id, @RequestBody UpdateLinkRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        Link link = service.update(id, userId(jwt), body.targetUrl(), body.active(), body.expiresAt());
        return LinkResponse.from(link, baseUrl);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete my link")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "401", description = "Missing or invalid Bearer token")
    @ApiResponse(responseCode = "403", description = "Not your link")
    @ApiResponse(responseCode = "404", description = "Link not found")
    public void delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        service.delete(id, userId(jwt));
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
```

- [ ] **Step 9: Replace the auth controller**

It calls the limiter and documents `429` on register and login.

`src/main/java/com/urlshortener/auth/AuthController.java`:

```java
package com.urlshortener.auth;

import com.urlshortener.ratelimit.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
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
    private final RateLimiter rateLimiter;

    @Value("${app.refresh-token.cookie-secure}")
    private final boolean secureCookie;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an account")
    @ApiResponse(responseCode = "400", description = "Invalid email or password")
    @ApiResponse(responseCode = "409", description = "Email is already registered")
    @ApiResponse(responseCode = "429", description = "Rate limit exceeded; see Retry-After")
    public UserResponse register(@Valid @RequestBody RegisterRequest body, HttpServletRequest request) {
        rateLimiter.checkRegistration(request.getRemoteAddr());
        User user = auth.register(body.email(), body.password());
        return new UserResponse(user.getId(), user.getEmail());
    }

    @PostMapping("/login")
    @Operation(summary = "Log in", description = "Returns an access token and sets the refresh_token cookie.")
    @ApiResponse(responseCode = "200", description = "Access token; refresh_token cookie is set")
    @ApiResponse(responseCode = "401", description = "Invalid email or password")
    @ApiResponse(responseCode = "429", description = "Rate limit exceeded; see Retry-After")
    public ResponseEntity<AccessTokenResponse> login(@Valid @RequestBody LoginRequest body,
            HttpServletRequest request) {
        rateLimiter.checkLogin(request.getRemoteAddr());
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

- [ ] **Step 10: Run the test to see it pass**

Run: `./mvnw test -Dtest=RateLimitTest`
Expected: `Tests run: 3, Failures: 0, Errors: 0`.

- [ ] **Step 11: Run the whole suite**

Run: `./mvnw test`
Expected: `Tests run: 73, Failures: 0, Errors: 0`.

- [ ] **Step 12: Commit**

```bash
git add pom.xml src/main/resources/application.properties src/main/java/com/urlshortener src/test/java/com/urlshortener/ratelimit
git commit -m "feat: rate-limit link creation, login and registration"
```

---

### Task 2: Docker image, Compose file, README

**Files:**
- Create: `Dockerfile`
- Create: `.dockerignore`
- Create: `compose.yaml`
- Create: `README.md`

**Interfaces:**
- Consumes: the env variables in spec section 9.
- Produces: image that listens on `8080` as a non-root user; `docker compose up` runs app + Postgres.

- [ ] **Step 1: Write the Dockerfile**

`Dockerfile`:

```dockerfile
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 1001 app
COPY --from=build /app/target/url-shortener-api-*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

Copying `pom.xml` and running `dependency:go-offline` before `COPY src` lets Docker cache the dependency layer. Code changes then rebuild in seconds.

- [ ] **Step 2: Write the Docker ignore file**

`.dockerignore`:

```text
target
.git
.idea
*.iml
```

- [ ] **Step 3: Write the Compose file**

`compose.yaml`:

```yaml
services:
  db:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: urlshortener
      POSTGRES_USER: urlshortener
      POSTGRES_PASSWORD: ${DATABASE_PASSWORD:?set DATABASE_PASSWORD}
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U urlshortener"]
      interval: 5s
      retries: 10

  app:
    build: .
    depends_on:
      db:
        condition: service_healthy
    ports:
      - "8080:8080"
    environment:
      DATABASE_URL: jdbc:postgresql://db:5432/urlshortener
      DATABASE_USERNAME: urlshortener
      DATABASE_PASSWORD: ${DATABASE_PASSWORD}
      JWT_SECRET: ${JWT_SECRET:?set JWT_SECRET}
      APP_BASE_URL: ${APP_BASE_URL:-http://localhost:8080}
      CORS_ALLOWED_ORIGINS: ${CORS_ALLOWED_ORIGINS:-http://localhost:5173}
      COOKIE_SECURE: ${COOKIE_SECURE:-true}
      FORWARD_HEADERS_STRATEGY: ${FORWARD_HEADERS_STRATEGY:-none}

volumes:
  pgdata:
```

`${VAR:?message}` makes Compose stop with an error when a required secret is missing, so nobody ships a default password.

- [ ] **Step 4: Write the README**

`README.md`:

````markdown
# url-shortener-api

Spring Boot API for a URL shortener. Design: `docs/superpowers/specs/2026-10-09-url-shortener-design.md`.

## Run tests

Docker must be running. Tests start Postgres with Testcontainers.

```bash
./mvnw test
```

## Run locally (no setup)

Starts the app with a throwaway Postgres container:

```bash
JWT_SECRET=$(openssl rand -base64 48) ./mvnw spring-boot:test-run
```

## Run with Docker Compose

```bash
export DATABASE_PASSWORD=$(openssl rand -hex 16)
export JWT_SECRET=$(openssl rand -base64 48)
docker compose up --build
```

## API docs

With the app running:

- Swagger UI: http://localhost:8080/swagger-ui.html
- OpenAPI JSON: http://localhost:8080/v3/api-docs (the UI repo can generate a typed client from this)

## Configuration

| Variable | Default | Purpose |
|----------|---------|---------|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/urlshortener` | JDBC URL |
| `DATABASE_USERNAME` | `urlshortener` | |
| `DATABASE_PASSWORD` | `urlshortener` | |
| `JWT_SECRET` | none (required) | HS256 key, at least 32 bytes |
| `APP_BASE_URL` | `http://localhost:8080` | Prefix for `shortUrl`. No trailing slash. |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | Comma-separated UI origins |
| `COOKIE_SECURE` | `true` | Set `false` only for plain-HTTP testing outside localhost |
| `FORWARD_HEADERS_STRATEGY` | `none` | Set `native` only behind a trusted reverse proxy |

## Limits of v1

- Rate-limit buckets live in memory. Run one instance, or move buckets to Redis first.
- The UI and the API must be on the same site, because the refresh cookie is `SameSite=Strict`.
````

- [ ] **Step 5: Build and run the stack**

```bash
export DATABASE_PASSWORD=$(openssl rand -hex 16)
export JWT_SECRET=$(openssl rand -base64 48)
docker compose up --build -d
```

Expected: both containers start. `docker compose ps` shows `db` as `healthy`.

- [ ] **Step 6: Smoke test**

```bash
curl -s localhost:8080/health
curl -s -X POST localhost:8080/api/links -H 'Content-Type: application/json' -d '{"targetUrl":"https://example.com"}'
curl -si localhost:8080/<slug from the response> | head -3
```

Expected: `{"groups":["liveness","readiness"],"status":"UP"}`, then a JSON link, then `HTTP/1.1 302` with `Location: https://example.com`. http://localhost:8080/swagger-ui.html shows all 9 endpoints.

- [ ] **Step 7: Stop the stack**

Run: `docker compose down`
Expected: containers removed. The `pgdata` volume stays.

- [ ] **Step 8: Commit**

```bash
git add Dockerfile .dockerignore compose.yaml README.md
git commit -m "chore: add Docker image, Compose file and README"
```
