# Plan 1 — Core Shortener Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Anyone can POST a URL and get a short link, and `GET /{slug}` redirects to it.

**Architecture:** One Spring Boot app with a Postgres `links` table. Flyway creates the table; Hibernate validates the `Link` entity against it. `LinkService` validates the URL, generates a random base62 slug, and relies on the unique constraint to detect collisions (retry up to 3 times). `RedirectController` reads through `SlugResolver` and answers `302` + `no-store`. springdoc serves Swagger UI from the controller annotations.

**Tech Stack:** Java 21, Spring Boot 4.1.1 (MVC, Data JPA / Hibernate, Validation, Actuator), Flyway, PostgreSQL 16, Lombok, springdoc-openapi 3.1.1, JUnit 5, MockMvc, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-10-09-url-shortener-design.md` (sections 3, 4, 5 `links`, 6 Public and API documentation, 7 Slugs / Target URL validation / Redirect)

**Depends on:** nothing. This is the first plan.

**Done when:** `./mvnw test` reports `Tests run: 42, Failures: 0, Errors: 0`.

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

### Task 1: Project scaffold and `links` schema

**Files:**
- Create (generated): `pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/`, `.gitignore`, `.gitattributes`, `src/main/java/com/urlshortener/UrlShortenerApiApplication.java`, `src/test/java/com/urlshortener/UrlShortenerApiApplicationTests.java`, `src/test/java/com/urlshortener/TestUrlShortenerApiApplication.java`
- Modify: `pom.xml` (add springdoc)
- Create: `lombok.config`
- Modify: `src/test/java/com/urlshortener/TestcontainersConfiguration.java`
- Modify: `src/main/resources/application.properties`
- Create: `src/main/resources/db/migration/V1__create_links.sql`
- Create: `src/main/java/com/urlshortener/OpenApiConfig.java`
- Test: `src/test/java/com/urlshortener/OpenApiDocsTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: table `links` with unique constraint `uk_links_slug`; public class `com.urlshortener.TestcontainersConfiguration` (test scope) that every integration test imports with `@Import(TestcontainersConfiguration.class)`; property `app.base-url`; Lombok and springdoc annotations on the classpath; Swagger UI at `/swagger-ui.html` and OpenAPI JSON at `/v3/api-docs`.

- [ ] **Step 1: Check Docker runs**

Run: `docker ps`
Expected: a table header, no "cannot connect" error. Start Docker Desktop if it fails.

- [ ] **Step 2: Generate the project with Spring Initializr**

```bash
curl -s https://start.spring.io/starter.zip \
  -d type=maven-project -d language=java -d bootVersion=4.1.1 -d javaVersion=21 \
  -d groupId=com.urlshortener -d artifactId=url-shortener-api -d name=url-shortener-api \
  -d packageName=com.urlshortener \
  -d dependencies=web,data-jpa,postgresql,flyway,validation,actuator,testcontainers,lombok \
  -o starter.zip
unzip -q starter.zip && rm starter.zip HELP.md
```

Expected: `pom.xml` and `src/` now exist next to `docs/`. The generated `pom.xml` already contains Lombok and a `maven-compiler-plugin` block that registers Lombok as an annotation processor.

- [ ] **Step 3: Add springdoc**

springdoc is not on Initializr. In `pom.xml`, add inside `<dependencies>`, before the `postgresql` dependency:

```xml
		<dependency>
			<groupId>org.springdoc</groupId>
			<artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
			<version>3.1.1</version>
		</dependency>
```

springdoc 3.x is the line for Spring Boot 4. Do not use 2.x.

- [ ] **Step 4: Write the Lombok config**

`lombok.config`:

```text
config.stopBubbling = true
# Copy @Value from fields onto the constructor that @RequiredArgsConstructor generates.
lombok.copyableAnnotations += org.springframework.beans.factory.annotation.Value
```

- [ ] **Step 5: Make the Testcontainers config public and pin Postgres 16**

Tests in sub-packages import this class, so it must be `public`. Replace the whole file:

`src/test/java/com/urlshortener/TestcontainersConfiguration.java`:

```java
package com.urlshortener;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));
	}

}
```

- [ ] **Step 6: Write the application config**

Replace `src/main/resources/application.properties`:

`src/main/resources/application.properties`:

```properties
spring.application.name=url-shortener-api
spring.datasource.url=${DATABASE_URL:jdbc:postgresql://localhost:5432/urlshortener}
spring.datasource.username=${DATABASE_USERNAME:urlshortener}
spring.datasource.password=${DATABASE_PASSWORD:urlshortener}
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.open-in-view=false
spring.mvc.problemdetails.enabled=true
management.endpoints.web.base-path=/
app.base-url=${APP_BASE_URL:http://localhost:8080}
```

`spring.jpa.hibernate.ddl-auto=validate` makes Hibernate compare every entity with its table at startup and refuse to start on a mismatch. Flyway still creates the tables.

`management.endpoints.web.base-path=/` serves health at `/health`. Actuator's handler mapping runs before `/{slug}`, so `health` never reaches the redirect controller.

- [ ] **Step 7: Write the `links` migration**

`src/main/resources/db/migration/V1__create_links.sql`:

```sql
CREATE TABLE links (
    id         UUID PRIMARY KEY,
    slug       VARCHAR(32) NOT NULL CONSTRAINT uk_links_slug UNIQUE,
    target_url TEXT        NOT NULL,
    owner_id   UUID,
    is_custom  BOOLEAN     NOT NULL DEFAULT FALSE,
    active     BOOLEAN     NOT NULL DEFAULT TRUE,
    expires_at TIMESTAMPTZ,
    created_ip VARCHAR(45),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_links_owner_created ON links (owner_id, created_at DESC);
```

`owner_id` has no foreign key yet. Plan 2 adds the `users` table and the FK.

- [ ] **Step 8: Write the failing API docs test**

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
}
```

- [ ] **Step 9: Run the tests to see one fail**

Run: `./mvnw test`
Expected: `servesOpenApiJson` fails with `JSON path "$.info.title" expected:<URL Shortener API> but was:<OpenAPI definition>`. The context test passes, which proves Flyway applies `V1` on real Postgres.

- [ ] **Step 10: Write the OpenAPI config**

`src/main/java/com/urlshortener/OpenApiConfig.java`:

```java
package com.urlshortener;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

// Swagger UI: /swagger-ui.html   OpenAPI JSON: /v3/api-docs
@Configuration
@OpenAPIDefinition(info = @Info(title = "URL Shortener API", version = "v1",
        description = "Short links, redirects, accounts and link management."))
public class OpenApiConfig {
}
```

- [ ] **Step 11: Run the tests to see them pass**

Run: `./mvnw test`
Expected: `Tests run: 3, Failures: 0, Errors: 0`.

- [ ] **Step 12: Commit**

```bash
git add .
git commit -m "chore: scaffold Spring Boot project with links schema and API docs"
```

---

### Task 2: `UrlValidator`

**Files:**
- Create: `src/main/java/com/urlshortener/link/UrlValidator.java`
- Test: `src/test/java/com/urlshortener/link/UrlValidatorTest.java`

**Interfaces:**
- Consumes: property `app.base-url` (Task 1).
- Produces: `@Component public class UrlValidator` with `public UrlValidator(String baseUrl)` and `public void validate(String url)`. `validate` throws `ResponseStatusException` with status `400` and a human-readable reason. Constant `static final int MAX_LENGTH = 2048`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/urlshortener/link/UrlValidatorTest.java`:

```java
package com.urlshortener.link;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;

class UrlValidatorTest {

    private final UrlValidator validator = new UrlValidator("https://sho.rt");

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com",
            "http://example.com/path?q=1#frag",
            "HTTPS://Example.com",
            "https://8.8.8.8/dns",
            "https://[2001:4860:4860::8888]/"
    })
    void acceptsPublicHttpUrls(String url) {
        assertThatCode(() -> validator.validate(url)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "not a url",
            "example.com",
            "javascript:alert(1)",
            "data:text/html,hi",
            "file:///etc/passwd",
            "ftp://example.com",
            "https://sho.rt/abc1234",
            "https://SHO.RT/abc1234",
            "http://localhost:8080",
            "http://api.localhost",
            "http://127.0.0.1",
            "http://10.1.2.3",
            "http://172.16.0.1",
            "http://192.168.1.1",
            "http://169.254.169.254/latest/meta-data",
            "http://0.0.0.0",
            "http://[::1]/",
            "http://[fd00::1]/",
            "http://999.1.1.1"
    })
    void rejectsUnsafeOrInvalidUrls(String url) {
        assertThatThrownBy(() -> validator.validate(url)).isInstanceOf(ResponseStatusException.class);
    }

    @org.junit.jupiter.api.Test
    void rejectsNull() {
        assertThatThrownBy(() -> validator.validate(null)).isInstanceOf(ResponseStatusException.class);
    }

    @org.junit.jupiter.api.Test
    void rejectsUrlsLongerThan2048Characters() {
        String url = "https://example.com/" + "a".repeat(UrlValidator.MAX_LENGTH);
        assertThatThrownBy(() -> validator.validate(url)).isInstanceOf(ResponseStatusException.class);
    }
}
```

- [ ] **Step 2: Run the test to see it fail**

Run: `./mvnw test -Dtest=UrlValidatorTest`
Expected: compilation failure, `cannot find symbol ... class UrlValidator`.

- [ ] **Step 3: Implement**

`src/main/java/com/urlshortener/link/UrlValidator.java`:

```java
package com.urlshortener.link;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class UrlValidator {

    static final int MAX_LENGTH = 2048;
    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    private final String ownHost;

    public UrlValidator(@Value("${app.base-url}") String baseUrl) {
        this.ownHost = URI.create(baseUrl).getHost().toLowerCase(Locale.ROOT);
    }

    public void validate(String url) {
        if (url == null || url.isBlank()) {
            throw invalid("URL is required");
        }
        if (url.length() > MAX_LENGTH) {
            throw invalid("URL is longer than " + MAX_LENGTH + " characters");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw invalid("URL is malformed");
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw invalid("Only http and https URLs are allowed");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw invalid("URL has no host");
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.equals(ownHost)) {
            throw invalid("URL points to this shortener");
        }
        if (host.equals("localhost") || host.endsWith(".localhost") || isPrivateIpLiteral(host)) {
            throw invalid("URL points to a private address");
        }
    }

    // ponytail: checks IP literals only, no DNS lookup. The server never fetches the target,
    // so a hostname that resolves to a private IP only harms the visitor's own network.
    private static boolean isPrivateIpLiteral(String host) {
        boolean ipv6 = host.startsWith("[") && host.endsWith("]");
        if (!ipv6 && !IPV4.matcher(host).matches()) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(ipv6 ? host.substring(1, host.length() - 1) : host);
            byte[] bytes = address.getAddress();
            boolean uniqueLocalV6 = bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
            return address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                    || address.isAnyLocalAddress() || uniqueLocalV6;
        } catch (UnknownHostException e) {
            return true;
        }
    }

    private static ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
```

Why IP literals only: the server never fetches the target, so DNS lookups would add latency and flaky tests for little gain. See spec section 7.

- [ ] **Step 4: Run the test to see it pass**

Run: `./mvnw test -Dtest=UrlValidatorTest`
Expected: `Tests run: 28, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/urlshortener/link/UrlValidator.java src/test/java/com/urlshortener/link/UrlValidatorTest.java
git commit -m "feat: validate target URLs"
```

---

### Task 3: `SlugGenerator`

**Files:**
- Create: `src/main/java/com/urlshortener/link/SlugGenerator.java`
- Test: `src/test/java/com/urlshortener/link/SlugGeneratorTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `@Component public class SlugGenerator` with `public String next()` returning 7 base62 characters.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/urlshortener/link/SlugGeneratorTest.java`:

```java
package com.urlshortener.link;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SlugGeneratorTest {

    private final SlugGenerator generator = new SlugGenerator();

    @Test
    void generatesSevenBase62Characters() {
        assertThat(generator.next()).matches("[0-9A-Za-z]{7}");
    }

    @Test
    void generatesDifferentSlugs() {
        Set<String> slugs = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            slugs.add(generator.next());
        }
        assertThat(slugs).hasSizeGreaterThan(995);
    }
}
```

- [ ] **Step 2: Run the test to see it fail**

Run: `./mvnw test -Dtest=SlugGeneratorTest`
Expected: compilation failure, `cannot find symbol ... class SlugGenerator`.

- [ ] **Step 3: Implement**

`src/main/java/com/urlshortener/link/SlugGenerator.java`:

```java
package com.urlshortener.link;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

@Component
public class SlugGenerator {

    static final int LENGTH = 7;
    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private final SecureRandom random = new SecureRandom();

    public String next() {
        StringBuilder slug = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            slug.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return slug.toString();
    }
}
```

- [ ] **Step 4: Run the test to see it pass**

Run: `./mvnw test -Dtest=SlugGeneratorTest`
Expected: `Tests run: 2, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/urlshortener/link/SlugGenerator.java src/test/java/com/urlshortener/link/SlugGeneratorTest.java
git commit -m "feat: generate random base62 slugs"
```

---

### Task 4: `Link` entity and `LinkService.create` with collision retry

**Files:**
- Create: `src/main/java/com/urlshortener/link/Link.java`
- Create: `src/main/java/com/urlshortener/link/LinkRepository.java`
- Create: `src/main/java/com/urlshortener/link/LinkService.java`
- Test: `src/test/java/com/urlshortener/link/LinkServiceTest.java`

**Interfaces:**
- Consumes: `UrlValidator.validate(String)` (Task 2), `SlugGenerator.next()` (Task 3), table `links` (Task 1).
- Produces:
  - `public class Link` (JPA entity, Lombok `@Getter`) with constructor `Link(String slug, String targetUrl, UUID ownerId, boolean custom, Instant expiresAt, String createdIp)`, getters `getId(): UUID`, `getSlug()`, `getTargetUrl()`, `getOwnerId(): UUID`, `isCustom()`, `isActive()`, `getExpiresAt(): Instant`, `getCreatedIp()`, `getCreatedAt(): Instant`, setters `setTargetUrl(String)`, `setActive(boolean)`, `setExpiresAt(Instant)`.
  - `public interface LinkRepository extends JpaRepository<Link, UUID>` with `Optional<Link> findBySlug(String slug)`.
  - `@Service public class LinkService` with `public Link create(String targetUrl, String createdIp)`. Plan 3 changes this signature.

- [ ] **Step 1: Write the failing test**

The test replaces `SlugGenerator` with a Mockito mock so it can force collisions.

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

        Link link = service.create("https://example.com", "203.0.113.7");

        assertThat(link.getId()).isNotNull();
        assertThat(link.getSlug()).isEqualTo("abc1234");
        assertThat(link.getOwnerId()).isNull();
        assertThat(links.findBySlug("abc1234")).isPresent();
    }

    @Test
    void retriesWhenGeneratedSlugIsTaken() {
        links.saveAndFlush(new Link("taken00", "https://example.com", null, false, null, null));
        when(slugs.next()).thenReturn("taken00", "free000");

        Link link = service.create("https://example.org", null);

        assertThat(link.getSlug()).isEqualTo("free000");
    }

    @Test
    void givesUpAfterThreeCollisions() {
        links.saveAndFlush(new Link("taken00", "https://example.com", null, false, null, null));
        when(slugs.next()).thenReturn("taken00");

        assertThatThrownBy(() -> service.create("https://example.org", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
```

- [ ] **Step 2: Run the test to see it fail**

Run: `./mvnw test -Dtest=LinkServiceTest`
Expected: compilation failure, `cannot find symbol ... class LinkService`.

- [ ] **Step 3: Write the entity**

`src/main/java/com/urlshortener/link/Link.java`:

```java
package com.urlshortener.link;

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
import lombok.Setter;

@Entity
@Table(name = "links")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Link {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false, length = 32)
    private String slug;

    @Column(name = "target_url", nullable = false, columnDefinition = "text")
    @Setter
    private String targetUrl;

    @Column(name = "owner_id", updatable = false)
    private UUID ownerId;

    @Column(name = "is_custom", nullable = false, updatable = false)
    private boolean custom;

    @Column(nullable = false)
    @Setter
    private boolean active = true;

    @Column(name = "expires_at")
    @Setter
    private Instant expiresAt;

    @Column(name = "created_ip", updatable = false, length = 45)
    private String createdIp;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Link(String slug, String targetUrl, UUID ownerId, boolean custom, Instant expiresAt, String createdIp) {
        this.slug = slug;
        this.targetUrl = targetUrl;
        this.ownerId = ownerId;
        this.custom = custom;
        this.expiresAt = expiresAt;
        this.createdIp = createdIp;
    }
}
```

`length` and `columnDefinition` must match `V1__create_links.sql`, or `ddl-auto=validate` stops the app. Lombok generates `isCustom()` and `isActive()` for the `boolean` fields.

- [ ] **Step 4: Write the repository**

`src/main/java/com/urlshortener/link/LinkRepository.java`:

```java
package com.urlshortener.link;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LinkRepository extends JpaRepository<Link, UUID> {

    Optional<Link> findBySlug(String slug);
}
```

- [ ] **Step 5: Write the service**

`src/main/java/com/urlshortener/link/LinkService.java`:

```java
package com.urlshortener.link;

import lombok.RequiredArgsConstructor;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LinkService {

    static final int MAX_SLUG_ATTEMPTS = 3;

    private final LinkRepository links;
    private final SlugGenerator slugs;
    private final UrlValidator urls;

    // Not @Transactional on purpose: each saveAndFlush runs in its own transaction,
    // so a failed insert rolls back alone and the next attempt starts clean.
    public Link create(String targetUrl, String createdIp) {
        urls.validate(targetUrl);
        for (int attempt = 1; ; attempt++) {
            try {
                return links.saveAndFlush(new Link(slugs.next(), targetUrl, null, false, null, createdIp));
            } catch (DataIntegrityViolationException e) {
                if (!isSlugConflict(e) || attempt == MAX_SLUG_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }

    static boolean isSlugConflict(DataIntegrityViolationException e) {
        String message = NestedExceptionUtils.getMostSpecificCause(e).getMessage();
        return message != null && message.contains("uk_links_slug");
    }
}
```

Do not add `@Transactional` to `create`. A failed insert marks a surrounding transaction rollback-only, so a retry inside it would fail too.

- [ ] **Step 6: Run the test to see it pass**

Run: `./mvnw test -Dtest=LinkServiceTest`
Expected: `Tests run: 3, Failures: 0, Errors: 0`.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/urlshortener/link src/test/java/com/urlshortener/link/LinkServiceTest.java
git commit -m "feat: create links with collision-safe random slugs"
```

---

### Task 5: `POST /api/links`

**Files:**
- Create: `src/main/java/com/urlshortener/link/CreateLinkRequest.java`
- Create: `src/main/java/com/urlshortener/link/LinkResponse.java`
- Create: `src/main/java/com/urlshortener/link/LinkController.java`
- Test: `src/test/java/com/urlshortener/link/LinkControllerTest.java`

**Interfaces:**
- Consumes: `LinkService.create(String, String)` (Task 4).
- Produces:
  - `public record CreateLinkRequest(@NotBlank String targetUrl)`.
  - `public record LinkResponse(UUID id, String slug, String shortUrl, String targetUrl, boolean active, Instant expiresAt, Instant createdAt)` with `static LinkResponse from(Link link, String baseUrl)`.
  - `POST /api/links` returns `201` with a `LinkResponse` JSON body.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/urlshortener/link/LinkControllerTest.java`:

```java
package com.urlshortener.link;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
class LinkControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void createsAnonymousLink() throws Exception {
        mvc.perform(post("/api/links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUrl\":\"https://example.com/page\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value(matchesPattern("[0-9A-Za-z]{7}")))
                .andExpect(jsonPath("$.shortUrl").value(matchesPattern("http://localhost:8080/[0-9A-Za-z]{7}")))
                .andExpect(jsonPath("$.targetUrl").value("https://example.com/page"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void rejectsBlankTargetUrlAsProblemJson() throws Exception {
        mvc.perform(post("/api/links").contentType(MediaType.APPLICATION_JSON).content("{\"targetUrl\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void rejectsUnsafeTargetUrlAsProblemJson() throws Exception {
        mvc.perform(post("/api/links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUrl\":\"javascript:alert(1)\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Only http and https URLs are allowed"));
    }
}
```

- [ ] **Step 2: Run the test to see it fail**

Run: `./mvnw test -Dtest=LinkControllerTest`
Expected: `createsAnonymousLink` fails with `Status expected:<201> but was:<404>` (or a compile error if earlier tasks are missing).

- [ ] **Step 3: Write the request record**

`src/main/java/com/urlshortener/link/CreateLinkRequest.java`:

```java
package com.urlshortener.link;

import jakarta.validation.constraints.NotBlank;

public record CreateLinkRequest(@NotBlank String targetUrl) {
}
```

- [ ] **Step 4: Write the response record**

`src/main/java/com/urlshortener/link/LinkResponse.java`:

```java
package com.urlshortener.link;

import java.time.Instant;
import java.util.UUID;

public record LinkResponse(UUID id, String slug, String shortUrl, String targetUrl, boolean active,
        Instant expiresAt, Instant createdAt) {

    static LinkResponse from(Link link, String baseUrl) {
        return new LinkResponse(link.getId(), link.getSlug(), baseUrl + "/" + link.getSlug(), link.getTargetUrl(),
                link.isActive(), link.getExpiresAt(), link.getCreatedAt());
    }
}
```

- [ ] **Step 5: Write the controller**

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
    public LinkResponse create(@Valid @RequestBody CreateLinkRequest body, HttpServletRequest request) {
        return LinkResponse.from(service.create(body.targetUrl(), request.getRemoteAddr()), baseUrl);
    }
}
```

- [ ] **Step 6: Run the test to see it pass**

Run: `./mvnw test -Dtest=LinkControllerTest`
Expected: `Tests run: 3, Failures: 0, Errors: 0`.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/urlshortener/link src/test/java/com/urlshortener/link/LinkControllerTest.java
git commit -m "feat: add POST /api/links"
```

---

### Task 6: Redirect `GET /{slug}`

**Files:**
- Create: `src/main/java/com/urlshortener/link/SlugResolver.java`
- Create: `src/main/java/com/urlshortener/redirect/RedirectController.java`
- Test: `src/test/java/com/urlshortener/redirect/RedirectControllerTest.java`

**Interfaces:**
- Consumes: `LinkRepository.findBySlug(String)` (Task 4).
- Produces: `@Component public class SlugResolver` with `public String resolve(String slug)`. It returns the target URL or throws `ResponseStatusException(404)`. Plan 3 adds a `410` case to the same method.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/urlshortener/redirect/RedirectControllerTest.java`:

```java
package com.urlshortener.redirect;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.urlshortener.TestcontainersConfiguration;
import com.urlshortener.link.Link;
import com.urlshortener.link.LinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RedirectControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    LinkRepository links;

    @BeforeEach
    void cleanDatabase() {
        links.deleteAll();
    }

    @Test
    void redirectsWith302AndNoStore() throws Exception {
        links.saveAndFlush(new Link("abc1234", "https://example.com/page", null, false, null, null));

        mvc.perform(get("/abc1234"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/page"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void returns404ForUnknownSlug() throws Exception {
        mvc.perform(get("/nope123")).andExpect(status().isNotFound());
    }

    @Test
    void healthIsNotTreatedAsSlug() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk());
    }
}
```

- [ ] **Step 2: Run the test to see it fail**

Run: `./mvnw test -Dtest=RedirectControllerTest`
Expected: `redirectsWith302AndNoStore` fails with `Status expected:<302> but was:<404>`.

- [ ] **Step 3: Write the resolver**

`src/main/java/com/urlshortener/link/SlugResolver.java`:

```java
package com.urlshortener.link;

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
        return links.findBySlug(slug)
                .map(Link::getTargetUrl)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Short link not found"));
    }
}
```

- [ ] **Step 4: Write the controller**

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
    public ResponseEntity<Void> redirect(@PathVariable String slug) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(resolver.resolve(slug)))
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
```

- [ ] **Step 5: Run the test to see it pass**

Run: `./mvnw test -Dtest=RedirectControllerTest`
Expected: `Tests run: 3, Failures: 0, Errors: 0`.

- [ ] **Step 6: Run the whole suite**

Run: `./mvnw test`
Expected: `Tests run: 42, Failures: 0, Errors: 0`.

- [ ] **Step 7: Try it by hand**

```bash
./mvnw spring-boot:test-run
# in a second terminal:
curl -s -X POST localhost:8080/api/links -H 'Content-Type: application/json' -d '{"targetUrl":"https://example.com"}'
curl -si localhost:8080/<slug from the response> | head -5
```

Expected: the first call prints JSON with a 7-character `slug`. The second prints `HTTP/1.1 302` and `Location: https://example.com`.

Open http://localhost:8080/swagger-ui.html. Expected: the **Links** and **Redirect** groups, each endpoint with its summary and status codes.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/urlshortener src/test/java/com/urlshortener/redirect
git commit -m "feat: redirect short links with 302 and no-store"
```
