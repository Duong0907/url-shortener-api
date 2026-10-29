package com.urlshortener.link.controller;

import com.urlshortener.error.ApiException;
import com.urlshortener.error.ErrorType;
import com.urlshortener.link.dto.CreateLinkRequest;
import com.urlshortener.link.dto.LinkCursor;
import com.urlshortener.link.dto.LinkListResponse;
import com.urlshortener.link.dto.LinkResponse;
import com.urlshortener.link.entity.Link;
import com.urlshortener.link.service.LinkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
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

    static final int MAX_LIST_LIMIT = 100;

    private final LinkService service;

    @Value("${app.base-url}")
    private final String baseUrl;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Shorten a URL")
    @ApiResponse(
        responseCode = "400",
        description = "Invalid request body (urn:problem:validation-failed) " +
            "or unsafe URL (urn:problem:url-invalid)",
        content = @Content(
            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class),
            examples = {
                @ExampleObject(
                    name = "Unsafe URL",
                    value = """
                    {
                      "type": "urn:problem:url-invalid",
                      "title": "Invalid URL",
                      "status": 400,
                      "detail": "Only http and https URLs are allowed"
                    }"""
                ),
                @ExampleObject(
                    name = "Validation failed",
                    value = """
                    {
                      "type": "urn:problem:validation-failed",
                      "title": "Request validation failed",
                      "status": 400,
                      "detail": "One or more fields are invalid",
                      "errors": [
                        { "field": "targetUrl", "message": "must not be blank" }
                      ]
                    }"""
                ),
            }
        )
    )
    @ApiResponse(
        responseCode = "401",
        description = "Custom slug without login (urn:problem:login-required)"
    )
    @ApiResponse(
        responseCode = "409",
        description = "Custom slug is taken or reserved (urn:problem:slug-taken)"
    )
    public LinkResponse create(
        @Valid @RequestBody CreateLinkRequest body,
        @AuthenticationPrincipal Jwt jwt,
        HttpServletRequest request
    ) {
        // Custom slugs need an owner, so anonymous callers cannot squat names.
        if (body.slug() != null && jwt == null) {
            throw new ApiException(ErrorType.LOGIN_REQUIRED, "Login is required for a custom slug");
        }

        // Logged-in callers own the link. Anonymous callers get an unowned link.
        UUID ownerId = jwt == null ? null : UUID.fromString(jwt.getSubject());

        return LinkResponse.from(
            service.create(body.targetUrl(), body.slug(), ownerId, request.getRemoteAddr()),
            baseUrl
        );
    }

    @GetMapping
    @Operation(summary = "List the caller's links, newest first, one batch per call")
    public LinkListResponse listMine(
        @AuthenticationPrincipal Jwt jwt,
        @RequestParam(required = false) String cursor,
        @RequestParam(defaultValue = "20") int limit
    ) {
        int batchSize = Math.clamp(limit, 1, MAX_LIST_LIMIT);
        LinkCursor after = cursor == null ? null : LinkCursor.decode(cursor);

        // Fetch one extra link to know if another batch exists without a count query.
        UUID ownerId = UUID.fromString(jwt.getSubject());
        List<Link> found = service.listOwned(ownerId, after, batchSize + 1);
        boolean hasMore = found.size() > batchSize;
        List<Link> batch = hasMore ? found.subList(0, batchSize) : found;

        return new LinkListResponse(
            batch
                .stream()
                .map(link -> LinkResponse.from(link, baseUrl))
                .toList(),
            hasMore ? LinkCursor.after(batch.getLast()).encode() : null
        );
    }
}
