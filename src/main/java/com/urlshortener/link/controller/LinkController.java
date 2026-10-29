package com.urlshortener.link.controller;

import com.urlshortener.link.dto.CreateLinkRequest;
import com.urlshortener.link.dto.LinkResponse;
import com.urlshortener.link.service.LinkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
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
    public LinkResponse create(
        @Valid @RequestBody CreateLinkRequest body,
        @AuthenticationPrincipal Jwt jwt,
        HttpServletRequest request
    ) {
        // Logged-in callers own the link. Anonymous callers get an unowned link.
        UUID ownerId = jwt == null ? null : UUID.fromString(jwt.getSubject());

        return LinkResponse.from(
            service.create(body.targetUrl(), ownerId, request.getRemoteAddr()),
            baseUrl
        );
    }

    @GetMapping
    @Operation(summary = "List the caller's links, newest first")
    public PagedModel<LinkResponse> listMine(@AuthenticationPrincipal Jwt jwt, Pageable pageable) {
        // Drop any client sort: the order is fixed to match the owner/created_at index.
        Pageable page = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());

        return new PagedModel<>(
            service
                .listOwned(UUID.fromString(jwt.getSubject()), page)
                .map(link -> LinkResponse.from(link, baseUrl))
        );
    }
}
