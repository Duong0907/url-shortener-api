package com.urlshortener.link.dto;

import com.urlshortener.link.entity.Link;
import java.time.Instant;
import java.util.UUID;

public record LinkResponse(UUID id, String slug, String shortUrl, String targetUrl, boolean active,
        Instant expiresAt, Instant createdAt) {

    public static LinkResponse from(Link link, String baseUrl) {
        return new LinkResponse(link.getId(), link.getSlug(), baseUrl + "/" + link.getSlug(), link.getTargetUrl(),
                link.isActive(), link.getExpiresAt(), link.getCreatedAt());
    }
}
