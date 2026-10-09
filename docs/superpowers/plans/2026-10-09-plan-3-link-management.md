# Plan 3 — Link Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Creators can pick custom slugs and expiry times. Logged-in users own their links and can list, edit, disable and delete them. Disabled or expired links return `410`.

**Architecture:** `LinkService.create` takes a `NewLink` record. A custom slug skips the retry loop and maps a unique-constraint hit to `409`. `SlugResolver` returns `410` for inactive or expired links. Owner routes read the user ID from the JWT `sub` claim and compare it with `links.owner_id`.

**Tech Stack:** Spring MVC, Spring Data JPA paging, Spring Security `@AuthenticationPrincipal Jwt`, Lombok, springdoc-openapi.

**Spec:** `docs/superpowers/specs/2026-10-09-url-shortener-design.md` (sections 6 Owner only, 7 Slugs / Expiry and disable)

**Depends on:** Plan 2 complete (55 tests green).

**Done when:** `./mvnw test` reports `Tests run: 70, Failures: 0, Errors: 0`.

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

### Task 1: Custom slugs, expiry, and `410 Gone`

**Files:**
- Create: `src/main/java/com/urlshortener/link/NewLink.java`
- Modify: `src/main/java/com/urlshortener/link/LinkService.java` (full replacement)
- Modify: `src/main/java/com/urlshortener/link/SlugResolver.java` (full replacement)
- Modify: `src/main/java/com/urlshortener/link/CreateLinkRequest.java` (full replacement)
- Modify: `src/main/java/com/urlshortener/link/LinkController.java` (full replacement)
- Modify: `src/main/java/com/urlshortener/redirect/RedirectController.java` (full replacement; documents `410`)
- Modify: `src/test/java/com/urlshortener/link/LinkServiceTest.java` (full replacement)
- Test: `src/test/java/com/urlshortener/link/LinkCreationOptionsTest.java`

**Interfaces:**
- Consumes: `Link`, `LinkRepository`, `SlugGenerator`, `UrlValidator` (Plan 1).
- Produces:
  - `public record NewLink(String targetUrl, String customSlug, Instant expiresAt, UUID ownerId, String createdIp)`.
  - `LinkService.create(NewLink request): Link` replaces `create(String, String)`.
  - `public record CreateLinkRequest(@NotBlank String targetUrl, String customSlug, Instant expiresAt)`.
  - `SlugResolver.resolve(String)` now throws `ResponseStatusException(410)` for inactive or expired links.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/urlshortener/link/LinkCreationOptionsTest.java`:

```java
package com.urlshortener.link;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.urlshortener.TestcontainersConfiguration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LinkCreationOptionsTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    LinkRepository links;

    @BeforeEach
    void cleanDatabase() {
        links.deleteAll();
    }

    @Test
    void createsLinkWithCustomSlugThatRedirects() throws Exception {
        create("{\"targetUrl\":\"https://example.com\",\"customSlug\":\"my-brand\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value("my-brand"));

        mvc.perform(get("/my-brand")).andExpect(status().isFound());
    }

    @Test
    void rejectsTakenCustomSlug() throws Exception {
        create("{\"targetUrl\":\"https://example.com\",\"customSlug\":\"my-brand\"}");
        create("{\"targetUrl\":\"https://example.org\",\"customSlug\":\"my-brand\"}")
                .andExpect(status().isConflict());
    }

    @Test
    void rejectsReservedCustomSlugIgnoringCase() throws Exception {
        create("{\"targetUrl\":\"https://example.com\",\"customSlug\":\"Health\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMalformedCustomSlug() throws Exception {
        create("{\"targetUrl\":\"https://example.com\",\"customSlug\":\"has space\"}")
                .andExpect(status().isBadRequest());
        create("{\"targetUrl\":\"https://example.com\",\"customSlug\":\"ab\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsExpiryInThePast() throws Exception {
        create("{\"targetUrl\":\"https://example.com\",\"expiresAt\":\"2020-01-01T00:00:00Z\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void expiredLinkRedirectReturns410() throws Exception {
        Link link = new Link("expired", "https://example.com", null, false,
                Instant.now().minus(1, ChronoUnit.MINUTES), null);
        links.saveAndFlush(link);

        mvc.perform(get("/expired")).andExpect(status().isGone());
    }

    private ResultActions create(String json) throws Exception {
        return mvc.perform(post("/api/links").contentType(MediaType.APPLICATION_JSON).content(json));
    }
}
```

- [ ] **Step 2: Update the service test to the new `create` signature**

`src/test/java/com/urlshortener/link/LinkServiceTest.java`:

```java
package com.urlshortener.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.urlshortener.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LinkServiceTest {

    @Autowired
    LinkService service;

    @Autowired
    LinkRepository links;

    @MockitoBean
    SlugGenerator slugs;

    @BeforeEach
    void cleanDatabase() {
        links.deleteAll();
    }

    @Test
    void createsLinkWithGeneratedSlug() {
        when(slugs.next()).thenReturn("abc1234");

        Link link = service.create(new NewLink("https://example.com", null, null, null, "203.0.113.7"));

        assertThat(link.getId()).isNotNull();
        assertThat(link.getSlug()).isEqualTo("abc1234");
        assertThat(link.getOwnerId()).isNull();
        assertThat(links.findBySlug("abc1234")).isPresent();
    }

    @Test
    void retriesWhenGeneratedSlugIsTaken() {
        links.saveAndFlush(new Link("taken00", "https://example.com", null, false, null, null));
        when(slugs.next()).thenReturn("taken00", "free000");

        Link link = service.create(new NewLink("https://example.org", null, null, null, null));

        assertThat(link.getSlug()).isEqualTo("free000");
    }

    @Test
    void givesUpAfterThreeCollisions() {
        links.saveAndFlush(new Link("taken00", "https://example.com", null, false, null, null));
        when(slugs.next()).thenReturn("taken00");

        assertThatThrownBy(() -> service.create(new NewLink("https://example.org", null, null, null, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
```

- [ ] **Step 3: Run the tests to see them fail**

Run: `./mvnw test -Dtest='LinkCreationOptionsTest,LinkServiceTest'`
Expected: compilation failure, `cannot find symbol ... class NewLink`.

- [ ] **Step 4: Write the `NewLink` record**

`src/main/java/com/urlshortener/link/NewLink.java`:

```java
package com.urlshortener.link;

import java.time.Instant;
import java.util.UUID;

/** Input for LinkService.create. customSlug, expiresAt and ownerId are null when not given. */
public record NewLink(String targetUrl, String customSlug, Instant expiresAt, UUID ownerId, String createdIp) {
}
```

- [ ] **Step 5: Replace the service**

`src/main/java/com/urlshortener/link/LinkService.java`:

```java
package com.urlshortener.link;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class LinkService {

    static final int MAX_SLUG_ATTEMPTS = 3;
    static final Pattern CUSTOM_SLUG = Pattern.compile("^[A-Za-z0-9_-]{3,32}$");
    static final Set<String> RESERVED_SLUGS = Set.of(
            "api", "auth", "login", "logout", "register", "signup", "health", "admin",
            "static", "assets", "error", "favicon", "robots", "sitemap", "docs", "swagger-ui");

    private final LinkRepository links;
    private final SlugGenerator slugs;
    private final UrlValidator urls;

    // Not @Transactional on purpose: each saveAndFlush runs in its own transaction,
    // so a failed insert rolls back alone and the next attempt starts clean.
    public Link create(NewLink request) {
        urls.validate(request.targetUrl());
        requireFuture(request.expiresAt());
        if (request.customSlug() != null) {
            return createWithCustomSlug(request);
        }
        for (int attempt = 1; ; attempt++) {
            try {
                return links.saveAndFlush(toLink(request, slugs.next(), false));
            } catch (DataIntegrityViolationException e) {
                if (!isSlugConflict(e) || attempt == MAX_SLUG_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }

    private Link createWithCustomSlug(NewLink request) {
        String slug = request.customSlug();
        if (!CUSTOM_SLUG.matcher(slug).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Custom slug must be 3-32 characters of letters, digits, '_' or '-'");
        }
        if (RESERVED_SLUGS.contains(slug.toLowerCase(Locale.ROOT))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Custom slug is reserved");
        }
        try {
            return links.saveAndFlush(toLink(request, slug, true));
        } catch (DataIntegrityViolationException e) {
            if (isSlugConflict(e)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Custom slug is already taken");
            }
            throw e;
        }
    }

    private static Link toLink(NewLink request, String slug, boolean custom) {
        return new Link(slug, request.targetUrl(), request.ownerId(), custom, request.expiresAt(), request.createdIp());
    }

    private static void requireFuture(Instant expiresAt) {
        if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "expiresAt must be in the future");
        }
    }

    static boolean isSlugConflict(DataIntegrityViolationException e) {
        String message = NestedExceptionUtils.getMostSpecificCause(e).getMessage();
        return message != null && message.contains("uk_links_slug");
    }
}
```

- [ ] **Step 6: Replace the resolver**

`src/main/java/com/urlshortener/link/SlugResolver.java`:

```java
package com.urlshortener.link;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

// The one place redirects read links. Add a cache here when redirect load needs it.
@Component
@RequiredArgsConstructor
public class SlugResolver {

    private final LinkRepository links;

    public String resolve(String slug) {
        Link link = links.findBySlug(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Short link not found"));
        boolean expired = link.getExpiresAt() != null && !link.getExpiresAt().isAfter(Instant.now());
        if (!link.isActive() || expired) {
            throw new ResponseStatusException(HttpStatus.GONE, "Short link is disabled or expired");
        }
        return link.getTargetUrl();
    }
}
```

- [ ] **Step 7: Replace the request record**

`src/main/java/com/urlshortener/link/CreateLinkRequest.java`:

```java
package com.urlshortener.link;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;

public record CreateLinkRequest(@NotBlank String targetUrl, String customSlug, Instant expiresAt) {
}
```

- [ ] **Step 8: Replace the controller**

`ownerId` stays `null` in this task. Task 2 reads it from the JWT.

`src/main/java/com/urlshortener/link/LinkController.java`:

```java
package com.urlshortener.link;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/links")
@RequiredArgsConstructor
@Tag(name = "Links")
public class LinkController {

    private final LinkService service;

    @Value("${app.base-url}")
    private final String baseUrl;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Shorten a URL")
    @ApiResponse(responseCode = "400", description = "Invalid URL, custom slug or expiry")
    @ApiResponse(responseCode = "409", description = "Custom slug is taken")
    public LinkResponse create(@Valid @RequestBody CreateLinkRequest body, HttpServletRequest request) {
        Link link = service.create(new NewLink(body.targetUrl(), body.customSlug(), body.expiresAt(), null,
                request.getRemoteAddr()));
        return LinkResponse.from(link, baseUrl);
    }
}
```

- [ ] **Step 9: Document `410` on the redirect**

`src/main/java/com/urlshortener/redirect/RedirectController.java`:

```java
package com.urlshortener.redirect;

import com.urlshortener.link.SlugResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Tag(name = "Redirect")
public class RedirectController {

    private final SlugResolver resolver;

    // 302 + no-store, never 301: browsers cache a 301 forever, so a disabled link would keep working.
    @GetMapping("/{slug:[A-Za-z0-9_-]{3,32}}")
    @Operation(summary = "Open a short link")
    @ApiResponse(responseCode = "302", description = "Redirect to the target URL")
    @ApiResponse(responseCode = "404", description = "Short link not found")
    @ApiResponse(responseCode = "410", description = "Short link is disabled or expired")
    public ResponseEntity<Void> redirect(@PathVariable String slug) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(resolver.resolve(slug)))
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
```

- [ ] **Step 10: Run the tests to see them pass**

Run: `./mvnw test -Dtest='LinkCreationOptionsTest,LinkServiceTest'`
Expected: `Tests run: 9, Failures: 0, Errors: 0`.

- [ ] **Step 11: Run the whole suite**

Run: `./mvnw test`
Expected: `Tests run: 61, Failures: 0, Errors: 0`.

- [ ] **Step 12: Commit**

```bash
git add src/main/java/com/urlshortener src/test/java/com/urlshortener/link
git commit -m "feat: support custom slugs, expiry and 410 for dead links"
```

---

### Task 2: Ownership — list, update, delete

**Files:**
- Modify: `src/main/java/com/urlshortener/link/LinkRepository.java` (full replacement)
- Modify: `src/main/java/com/urlshortener/link/LinkService.java` (full replacement)
- Create: `src/main/java/com/urlshortener/link/UpdateLinkRequest.java`
- Create: `src/main/java/com/urlshortener/link/LinkPage.java`
- Modify: `src/main/java/com/urlshortener/link/LinkController.java` (full replacement)
- Create: `src/test/java/com/urlshortener/TestUsers.java`
- Test: `src/test/java/com/urlshortener/link/LinkManagementTest.java`
- Modify: `src/test/java/com/urlshortener/OpenApiDocsTest.java` (full replacement)

**Interfaces:**
- Consumes: `NewLink`, `LinkService.create(NewLink)` (Task 1); JWT `sub` claim and `/api/auth/register`, `/api/auth/login` (Plan 2).
- Produces:
  - `LinkRepository.findByOwnerIdOrderByCreatedAtDesc(UUID ownerId, Pageable pageable): Page<Link>`.
  - `LinkService.listOwned(UUID ownerId, int page, int size): Page<Link>`, `update(UUID linkId, UUID ownerId, String targetUrl, Boolean active, Instant expiresAt): Link`, `delete(UUID linkId, UUID ownerId)`.
  - `public record UpdateLinkRequest(String targetUrl, Boolean active, Instant expiresAt)`.
  - `public record LinkPage(List<LinkResponse> items, int page, int size, long totalItems)`.
  - Test helper `TestUsers.bearerFor(MockMvc mvc, String email): String` returning `"Bearer <token>"`. Plan 4 uses it.

- [ ] **Step 1: Write the test helper**

`src/test/java/com/urlshortener/TestUsers.java`:

```java
package com.urlshortener;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Registers a user through the real API and returns an "Authorization" header value. */
public final class TestUsers {

    private TestUsers() {
    }

    public static String bearerFor(MockMvc mvc, String email) throws Exception {
        String credentials = "{\"email\":\"" + email + "\",\"password\":\"correct-horse\"}";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(credentials));
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(credentials))
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.read(body, "$.accessToken");
    }
}
```

- [ ] **Step 2: Write the failing test**

`src/test/java/com/urlshortener/link/LinkManagementTest.java`:

```java
package com.urlshortener.link;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.urlshortener.TestUsers;
import com.urlshortener.TestcontainersConfiguration;
import com.urlshortener.auth.RefreshTokenRepository;
import com.urlshortener.auth.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LinkManagementTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    LinkRepository links;

    @Autowired
    UserRepository users;

    @Autowired
    RefreshTokenRepository refreshTokens;

    String alice;
    String bob;

    @BeforeEach
    void setUp() throws Exception {
        links.deleteAll();
        refreshTokens.deleteAll();
        users.deleteAll();
        alice = TestUsers.bearerFor(mvc, "alice@example.com");
        bob = TestUsers.bearerFor(mvc, "bob@example.com");
    }

    @Test
    void listShowsOnlyOwnLinksNewestFirst() throws Exception {
        create(alice, "{\"targetUrl\":\"https://example.com/1\"}");
        create(alice, "{\"targetUrl\":\"https://example.com/2\"}");
        create(bob, "{\"targetUrl\":\"https://example.com/bob\"}");
        create(null, "{\"targetUrl\":\"https://example.com/anon\"}");

        mvc.perform(get("/api/links").header("Authorization", alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].targetUrl").value("https://example.com/2"))
                .andExpect(jsonPath("$.totalItems").value(2));
    }

    @Test
    void listCapsPageSizeAt100() throws Exception {
        mvc.perform(get("/api/links?size=5000").header("Authorization", alice))
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void ownerCanDisableLinkAndRedirectReturns410() throws Exception {
        String id = idOf(create(alice, "{\"targetUrl\":\"https://example.com\",\"customSlug\":\"to-disable\"}"));

        mvc.perform(patch("/api/links/" + id).header("Authorization", alice)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mvc.perform(get("/to-disable")).andExpect(status().isGone());
    }

    @Test
    void ownerCanChangeTargetUrlButNotToAnUnsafeOne() throws Exception {
        String id = idOf(create(alice, "{\"targetUrl\":\"https://example.com\"}"));

        mvc.perform(patch("/api/links/" + id).header("Authorization", alice)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"targetUrl\":\"https://example.org\"}"))
                .andExpect(jsonPath("$.targetUrl").value("https://example.org"));
        mvc.perform(patch("/api/links/" + id).header("Authorization", alice)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"targetUrl\":\"javascript:alert(1)\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void otherUserCannotUpdateOrDelete() throws Exception {
        String id = idOf(create(alice, "{\"targetUrl\":\"https://example.com\"}"));

        mvc.perform(patch("/api/links/" + id).header("Authorization", bob)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/links/" + id).header("Authorization", bob)).andExpect(status().isForbidden());
    }

    @Test
    void anonymousLinksCannotBeManaged() throws Exception {
        String id = idOf(create(null, "{\"targetUrl\":\"https://example.com\"}"));

        mvc.perform(delete("/api/links/" + id).header("Authorization", alice)).andExpect(status().isForbidden());
    }

    @Test
    void unknownLinkIdIs404() throws Exception {
        mvc.perform(delete("/api/links/00000000-0000-0000-0000-000000000000").header("Authorization", alice))
                .andExpect(status().isNotFound());
    }

    @Test
    void ownerCanDeleteLinkAndRedirectReturns404() throws Exception {
        String id = idOf(create(alice, "{\"targetUrl\":\"https://example.com\",\"customSlug\":\"to-delete\"}"));

        mvc.perform(delete("/api/links/" + id).header("Authorization", alice)).andExpect(status().isNoContent());

        mvc.perform(get("/to-delete")).andExpect(status().isNotFound());
    }

    private ResultActions create(String bearer, String json) throws Exception {
        var request = post("/api/links").contentType(MediaType.APPLICATION_JSON).content(json);
        if (bearer != null) {
            request.header("Authorization", bearer);
        }
        return mvc.perform(request);
    }

    private static String idOf(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }
}
```

Replace the API docs test. It adds `documentsOwnerRoutesAndErrorCodes`, which guards what the UI repo reads:

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

    @Test
    void documentsOwnerRoutesAndErrorCodes() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['/api/links'].get.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/links'].get.responses['200'].content").exists())
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.responses['200'].content").exists())
                .andExpect(jsonPath("$.paths['/api/links'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/api/links'].post.responses['409']").exists())
                .andExpect(jsonPath("$.paths['/{slug}'].get.responses['410']").exists());
    }
}
```

- [ ] **Step 3: Run the tests to see them fail**

Run: `./mvnw test -Dtest='LinkManagementTest,OpenApiDocsTest'`
Expected: failures. For example `listShowsOnlyOwnLinksNewestFirst` gets `Status expected:<200> but was:<405>` because `GET /api/links` has no handler yet, and `documentsOwnerRoutesAndErrorCodes` fails.

- [ ] **Step 4: Replace the repository**

`src/main/java/com/urlshortener/link/LinkRepository.java`:

```java
package com.urlshortener.link;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LinkRepository extends JpaRepository<Link, UUID> {

    Optional<Link> findBySlug(String slug);

    Page<Link> findByOwnerIdOrderByCreatedAtDesc(UUID ownerId, Pageable pageable);
}
```

- [ ] **Step 5: Write the request and page records**

`src/main/java/com/urlshortener/link/UpdateLinkRequest.java`:

```java
package com.urlshortener.link;

import java.time.Instant;

/** Every field is optional. A null field stays unchanged. The slug can never change. */
public record UpdateLinkRequest(String targetUrl, Boolean active, Instant expiresAt) {
}
```

`src/main/java/com/urlshortener/link/LinkPage.java`:

```java
package com.urlshortener.link;

import java.util.List;

public record LinkPage(List<LinkResponse> items, int page, int size, long totalItems) {
}
```

A custom `LinkPage` record keeps the JSON shape stable. Serializing Spring's `Page` directly gives an unstable format and a startup warning.

- [ ] **Step 6: Replace the service**

`src/main/java/com/urlshortener/link/LinkService.java`:

```java
package com.urlshortener.link;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class LinkService {

    static final int MAX_SLUG_ATTEMPTS = 3;
    static final int MAX_PAGE_SIZE = 100;
    static final Pattern CUSTOM_SLUG = Pattern.compile("^[A-Za-z0-9_-]{3,32}$");
    static final Set<String> RESERVED_SLUGS = Set.of(
            "api", "auth", "login", "logout", "register", "signup", "health", "admin",
            "static", "assets", "error", "favicon", "robots", "sitemap", "docs", "swagger-ui");

    private final LinkRepository links;
    private final SlugGenerator slugs;
    private final UrlValidator urls;

    // Not @Transactional on purpose: each saveAndFlush runs in its own transaction,
    // so a failed insert rolls back alone and the next attempt starts clean.
    public Link create(NewLink request) {
        urls.validate(request.targetUrl());
        requireFuture(request.expiresAt());
        if (request.customSlug() != null) {
            return createWithCustomSlug(request);
        }
        for (int attempt = 1; ; attempt++) {
            try {
                return links.saveAndFlush(toLink(request, slugs.next(), false));
            } catch (DataIntegrityViolationException e) {
                if (!isSlugConflict(e) || attempt == MAX_SLUG_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }

    public Page<Link> listOwned(UUID ownerId, int page, int size) {
        int safeSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        return links.findByOwnerIdOrderByCreatedAtDesc(ownerId, PageRequest.of(Math.max(page, 0), safeSize));
    }

    @Transactional
    public Link update(UUID linkId, UUID ownerId, String targetUrl, Boolean active, Instant expiresAt) {
        Link link = findOwned(linkId, ownerId);
        if (targetUrl != null) {
            urls.validate(targetUrl);
            link.setTargetUrl(targetUrl);
        }
        if (active != null) {
            link.setActive(active);
        }
        if (expiresAt != null) {
            requireFuture(expiresAt);
            link.setExpiresAt(expiresAt);
        }
        return link;
    }

    @Transactional
    public void delete(UUID linkId, UUID ownerId) {
        links.delete(findOwned(linkId, ownerId));
    }

    private Link createWithCustomSlug(NewLink request) {
        String slug = request.customSlug();
        if (!CUSTOM_SLUG.matcher(slug).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Custom slug must be 3-32 characters of letters, digits, '_' or '-'");
        }
        if (RESERVED_SLUGS.contains(slug.toLowerCase(Locale.ROOT))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Custom slug is reserved");
        }
        try {
            return links.saveAndFlush(toLink(request, slug, true));
        } catch (DataIntegrityViolationException e) {
            if (isSlugConflict(e)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Custom slug is already taken");
            }
            throw e;
        }
    }

    private Link findOwned(UUID linkId, UUID ownerId) {
        Link link = links.findById(linkId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Link not found"));
        if (!ownerId.equals(link.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not own this link");
        }
        return link;
    }

    private static Link toLink(NewLink request, String slug, boolean custom) {
        return new Link(slug, request.targetUrl(), request.ownerId(), custom, request.expiresAt(), request.createdIp());
    }

    private static void requireFuture(Instant expiresAt) {
        if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "expiresAt must be in the future");
        }
    }

    static boolean isSlugConflict(DataIntegrityViolationException e) {
        String message = NestedExceptionUtils.getMostSpecificCause(e).getMessage();
        return message != null && message.contains("uk_links_slug");
    }
}
```

- [ ] **Step 7: Replace the controller**

`src/main/java/com/urlshortener/link/LinkController.java`:

```java
package com.urlshortener.link;

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

    @Value("${app.base-url}")
    private final String baseUrl;

    // jwt is null for anonymous callers: the link is created without an owner.
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Shorten a URL", description = "Bearer token is optional. Without one, the link has no owner and cannot be managed later.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "400", description = "Invalid URL, custom slug or expiry")
    @ApiResponse(responseCode = "409", description = "Custom slug is taken")
    public LinkResponse create(@Valid @RequestBody CreateLinkRequest body, @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) {
        UUID ownerId = jwt == null ? null : userId(jwt);
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

`POST /api/links` is public, so `jwt` is `null` for anonymous callers. The owner routes sit behind `.authenticated()`, so `jwt` is never `null` there. springdoc hides `@AuthenticationPrincipal` and `HttpServletRequest` parameters from the docs.

`create` also carries `@SecurityRequirement`: Swagger UI then sends the token when you are authorized, and nothing when you are not, which matches the optional auth.

- [ ] **Step 8: Run the tests to see them pass**

Run: `./mvnw test -Dtest='LinkManagementTest,OpenApiDocsTest'`
Expected: `Tests run: 12, Failures: 0, Errors: 0`.

- [ ] **Step 9: Run the whole suite**

Run: `./mvnw test`
Expected: `Tests run: 70, Failures: 0, Errors: 0`.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/urlshortener/link src/test/java/com/urlshortener
git commit -m "feat: let owners list, update, disable and delete their links"
```
