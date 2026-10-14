package com.urlshortener.link.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record CreateLinkRequest(
        @NotBlank @Schema(description = "Public http or https URL to shorten", example = "https://example.com/some/long/path?q=1") String targetUrl) {
}
