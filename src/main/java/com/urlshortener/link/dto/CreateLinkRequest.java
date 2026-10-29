package com.urlshortener.link.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateLinkRequest(
    @NotBlank
    @Schema(
        description = "Public http or https URL to shorten",
        example = "https://example.com/some/long/path?q=1"
    )
    String targetUrl,
    // Same pattern as the redirect route, so every stored slug can be opened.
    @Pattern(regexp = "[A-Za-z0-9_-]{3,32}", message = "must be 3-32 letters, digits, - or _")
    @Schema(
        description = "Custom slug. Needs login. Omit to get a random one.",
        example = "my-link",
        nullable = true
    )
    String slug
) {}
