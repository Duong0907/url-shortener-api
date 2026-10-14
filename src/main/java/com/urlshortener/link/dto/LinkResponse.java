package com.urlshortener.link.dto;

import com.urlshortener.link.entity.Link;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

public record LinkResponse(
    @Schema(example = "3fa85f64-5717-4562-b3fc-2c963f66afa6") UUID id,
    @Schema(description = "Short code in the short URL", example = "aZ3k9Qx") String slug,
    @Schema(
        description = "Full short URL that redirects to the target",
        example = "http://localhost:8080/aZ3k9Qx"
    )
    String shortUrl,
    @Schema(example = "https://example.com/some/long/path?q=1") String targetUrl,
    @Schema(description = "False when the link is disabled", example = "true") boolean active,
    @Schema(
        description = "Null when the link never expires",
        example = "2026-12-31T23:59:59Z",
        nullable = true
    )
    Instant expiresAt,
    @Schema(example = "2026-10-14T04:30:00Z") Instant createdAt
) {
    public static LinkResponse from(Link link, String baseUrl) {
        return new LinkResponse(
            link.getId(),
            link.getSlug(),
            baseUrl + "/" + link.getSlug(),
            link.getTargetUrl(),
            link.isActive(),
            link.getExpiresAt(),
            link.getCreatedAt()
        );
    }
}
