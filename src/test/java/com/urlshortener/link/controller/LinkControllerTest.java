package com.urlshortener.link.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.urlshortener.TestcontainersConfiguration;
import com.urlshortener.auth.entity.User;
import com.urlshortener.auth.repository.UserRepository;
import com.urlshortener.link.entity.Link;
import com.urlshortener.link.repository.LinkRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LinkControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    @Autowired
    LinkRepository links;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void createsAnonymousLink() throws Exception {
        mvc.perform(
            post("/api/links")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetUrl\":\"https://example.com/page\"}")
        )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.slug").value(matchesPattern("[0-9A-Za-z]{7}")))
            .andExpect(
                jsonPath("$.shortUrl").value(matchesPattern("http://localhost:8080/[0-9A-Za-z]{7}"))
            )
            .andExpect(jsonPath("$.targetUrl").value("https://example.com/page"))
            .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void rejectsBlankTargetUrlAsProblemJson() throws Exception {
        mvc.perform(
            post("/api/links")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetUrl\":\"\"}")
        )
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").value("urn:problem:validation-failed"))
            .andExpect(jsonPath("$.errors[0].field").value("targetUrl"));
    }

    @Test
    void rejectsUnsafeTargetUrlAsProblemJson() throws Exception {
        mvc.perform(
            post("/api/links")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetUrl\":\"javascript:alert(1)\"}")
        )
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").value("urn:problem:url-invalid"))
            .andExpect(jsonPath("$.detail").value("Only http and https URLs are allowed"));
    }

    @Test
    void createWithTokenSavesTheCallerAsOwner() throws Exception {
        UUID ownerId = newUser();

        String body = mvc
            .perform(
                post("/api/links")
                    .with(loggedInAs(ownerId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"targetUrl\":\"https://example.com/owned\"}")
            )
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

        String slug = body.replaceAll(".*\"slug\":\"([^\"]+)\".*", "$1");
        assertThat(links.findBySlug(slug)).get().extracting(Link::getOwnerId).isEqualTo(ownerId);
    }

    @Test
    void listReturnsOnlyTheCallersLinksNewestFirst() throws Exception {
        UUID ownerId = newUser();
        UUID otherId = newUser();
        saveLink("older01", ownerId, Instant.parse("2026-01-01T00:00:00Z"));
        saveLink("newer01", ownerId, Instant.parse("2026-02-01T00:00:00Z"));
        saveLink("other01", otherId, Instant.parse("2026-03-01T00:00:00Z"));

        mvc.perform(get("/api/links").with(loggedInAs(ownerId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].slug").value("newer01"))
            .andExpect(jsonPath("$.items[1].slug").value("older01"))
            .andExpect(jsonPath("$.items[0].shortUrl").value("http://localhost:8080/newer01"))
            .andExpect(jsonPath("$.items[0].targetUrl").value("https://example.com/newer01"))
            .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void listLoadsMoreWithCursor() throws Exception {
        UUID ownerId = newUser();
        Instant sameTime = Instant.parse("2026-01-02T00:00:00Z");
        saveLink("more001", ownerId, Instant.parse("2026-01-01T00:00:00Z"));
        // Two links share a createdAt, so the cursor must break the tie by id.
        saveLink("more002", ownerId, sameTime);
        saveLink("more003", ownerId, sameTime);

        Set<String> seen = new HashSet<>();
        String cursor = null;
        int batches = 0;
        do {
            var request = get("/api/links").param("limit", "1").with(loggedInAs(ownerId));
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            String body = mvc
                .perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();
            seen.add(JsonPath.read(body, "$.items[0].slug"));
            cursor = JsonPath.read(body, "$.nextCursor");
            batches++;
        } while (cursor != null);

        assertThat(batches).isEqualTo(3);
        assertThat(seen).containsExactlyInAnyOrder("more001", "more002", "more003");
    }

    @Test
    void listRejectsInvalidCursor() throws Exception {
        mvc.perform(get("/api/links").param("cursor", "garbage").with(loggedInAs(newUser())))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:problem:validation-failed"));
    }

    @Test
    void listNeedsALogin() throws Exception {
        mvc.perform(get("/api/links")).andExpect(status().isUnauthorized());
    }

    private UUID newUser() {
        return users
            .saveAndFlush(new User("user-" + UUID.randomUUID() + "@example.com", "hash"))
            .getId();
    }

    private void saveLink(String slug, UUID ownerId, Instant createdAt) {
        links.saveAndFlush(
            new Link(slug, "https://example.com/" + slug, ownerId, false, null, null)
        );
        // created_at is not updatable through JPA, so backdate it with SQL.
        jdbc.update(
            "UPDATE links SET created_at = ? WHERE slug = ?",
            Timestamp.from(createdAt),
            slug
        );
    }

    private static RequestPostProcessor loggedInAs(UUID userId) {
        return jwt().jwt(token -> token.subject(userId.toString()));
    }

    @Test
    void customSlugNeedsLogin() throws Exception {
        mvc.perform(
            post("/api/links")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetUrl\":\"https://example.com\",\"slug\":\"my-link\"}")
        )
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.type").value("urn:problem:login-required"));
    }

    @Test
    void createsCustomLinkForLoggedInUser() throws Exception {
        mvc.perform(
            post("/api/links")
                .with(loggedInAs(newUser()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetUrl\":\"https://example.com\",\"slug\":\"ctrl-custom\"}")
        )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.slug").value("ctrl-custom"))
            .andExpect(jsonPath("$.shortUrl").value("http://localhost:8080/ctrl-custom"));

        // Second use of the same slug conflicts.
        mvc.perform(
            post("/api/links")
                .with(loggedInAs(newUser()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetUrl\":\"https://example.org\",\"slug\":\"ctrl-custom\"}")
        )
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.type").value("urn:problem:slug-taken"));
    }

    @Test
    void rejectsMalformedCustomSlug() throws Exception {
        mvc.perform(
            post("/api/links")
                .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetUrl\":\"https://example.com\",\"slug\":\"a b\"}")
        )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors[0].field").value("slug"));
    }
}
