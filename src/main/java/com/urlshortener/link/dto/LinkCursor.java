package com.urlshortener.link.dto;

import com.urlshortener.error.ApiException;
import com.urlshortener.error.ErrorType;
import com.urlshortener.link.entity.Link;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

// Position after the last link a client has seen. The id breaks ties on equal createdAt.
public record LinkCursor(Instant createdAt, UUID id) {
    public static LinkCursor after(Link link) {
        return new LinkCursor(link.getCreatedAt(), link.getId());
    }

    // Opaque to clients, so the format can change without breaking them.
    public String encode() {
        String raw = createdAt + "," + id;
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static LinkCursor decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split(",", 2);

            return new LinkCursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (RuntimeException e) {
            throw new ApiException(ErrorType.VALIDATION_FAILED, "Cursor is invalid");
        }
    }
}
