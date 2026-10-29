package com.urlshortener.link.service;

import com.urlshortener.error.ApiException;
import com.urlshortener.error.ErrorType;
import com.urlshortener.link.dto.LinkCursor;
import com.urlshortener.link.entity.Link;
import com.urlshortener.link.repository.LinkRepository;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LinkService {

    static final int MAX_SLUG_ATTEMPTS = 3;

    // Top-level paths the app uses itself (actuator lives at the root).
    static final Set<String> RESERVED_SLUGS = Set.of(
        "api",
        "health",
        "info",
        "actuator",
        "error",
        "swagger-ui",
        "v3",
        "docs",
        "admin"
    );

    private final LinkRepository links;
    private final SlugGenerator slugs;
    private final UrlValidator urls;

    // Not @Transactional on purpose: each saveAndFlush runs in its own transaction,
    // so a failed insert rolls back alone and the next attempt starts clean.
    public Link create(String targetUrl, String customSlug, UUID ownerId, String createdIp) {
        urls.validate(targetUrl);

        if (customSlug != null) {
            return createCustom(targetUrl, customSlug, ownerId, createdIp);
        }

        for (int attempt = 1; ; attempt++) {
            try {
                return links.saveAndFlush(
                    new Link(slugs.next(), targetUrl, ownerId, false, null, createdIp)
                );
            } catch (DataIntegrityViolationException e) {
                if (!isSlugConflict(e) || attempt == MAX_SLUG_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }

    private Link createCustom(String targetUrl, String slug, UUID ownerId, String createdIp) {
        if (RESERVED_SLUGS.contains(slug.toLowerCase(Locale.ROOT))) {
            throw new ApiException(ErrorType.SLUG_TAKEN, "Slug is reserved");
        }

        try {
            return links.saveAndFlush(new Link(slug, targetUrl, ownerId, true, null, createdIp));
        } catch (DataIntegrityViolationException e) {
            if (isSlugConflict(e)) {
                throw new ApiException(ErrorType.SLUG_TAKEN, "Slug is already in use");
            }
            throw e;
        }
    }

    public List<Link> listOwned(UUID ownerId, LinkCursor after, int limit) {
        if (after == null) {
            return links.findByOwnerIdOrderByCreatedAtDescIdDesc(ownerId, Limit.of(limit));
        }

        return links.findOwnedAfter(ownerId, after.createdAt(), after.id(), Limit.of(limit));
    }

    static boolean isSlugConflict(DataIntegrityViolationException e) {
        String message = NestedExceptionUtils.getMostSpecificCause(e).getMessage();
        return message != null && message.contains("uk_links_slug");
    }
}
